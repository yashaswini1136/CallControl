package com.callcontrol.app.pairing

import com.callcontrol.app.network.DeviceTransport
import com.callcontrol.app.network.TransportListener
import com.callcontrol.app.network.TransportState
import com.callcontrol.app.state.CallControlStateManager
import com.callcontrol.app.state.CallSessionStatus
import com.callcontrol.app.state.ManagedApp
import com.callcontrol.app.state.SessionTransitionResult

/**
 * Result of evaluating and dispatching an incoming [ProtocolMessage].
 */
sealed class MessageDispatchResult {
    data class Handled(val response: ProtocolMessage?) : MessageDispatchResult()
    data class Rejected(val reason: String, val validationResult: MessageValidationResult? = null) : MessageDispatchResult()
    data class Ignored(val reason: String) : MessageDispatchResult()
}

/**
 * Listener interface for state integration events and notifications.
 */
interface PairingIntegrationListener {
    fun onSessionStatusUpdated(status: CallSessionStatus) {}
    fun onPolicyUpdated(app: ManagedApp, locked: Boolean) {}
    fun onRemoteDisconnect(reason: String, permanent: Boolean) {}
    fun onMessageRejected(message: ProtocolMessage, reason: String) {}
}

/**
 * Dedicated integration component connecting authenticated [ProtocolMessage] commands
 * to [CallControlStateManager].
 *
 * Guarantees:
 * 1. Strict Authorization: Commands are only processed after mutual pairing approval
 *    (handshake state CONNECTED), valid active session token, fresh timestamp, and HMAC signature.
 * 2. Role Separation: Only the Controller may issue SessionControlRequest and PolicyUpdateRequest.
 *    Friend Device rejects any attempt to receive commands if configured as Controller, and vice versa.
 * 3. Transport Security Isolation: Raw TCP or transport-layer encryption alone never authorizes commands.
 * 4. State Integrity: Unauthorized, stale, replayed, or out-of-session commands are rejected
 *    without mutating app state.
 * 5. Preference Separation: Remote policy commands only act as temporary session overrides during
 *    an ACTIVE call session; ending a session wipes overrides and restores baseline saved preferences.
 * 6. Lifecycle Safety: Disconnection, revocation, or authorization loss immediately clears temporary
 *    remote overrides and restores the Friend Device to local baseline settings.
 */
class PairingStateIntegrator(
    val localRole: DeviceRole,
    val handshakeEngine: PairingHandshakeEngine,
    val stateManager: CallControlStateManager,
    var transport: DeviceTransport? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val listeners = mutableSetOf<PairingIntegrationListener>()

    var remoteSessionStatus: CallSessionStatus = CallSessionStatus.INACTIVE
        private set

    var lastSessionControlAck: ProtocolMessage.SessionControlAck? = null
        private set

    var lastPolicyUpdateAck: ProtocolMessage.PolicyUpdateAck? = null
        private set

    init {
        transport?.let { bindTransport(it) }
    }

    fun addListener(listener: PairingIntegrationListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: PairingIntegrationListener) {
        listeners.remove(listener)
    }

    /**
     * Binds an underlying [DeviceTransport] to this integrator.
     */
    fun bindTransport(deviceTransport: DeviceTransport) {
        this.transport = deviceTransport
        deviceTransport.setListener(asTransportListener())
    }

    /**
     * Creates a [TransportListener] bridge routing network events into this integrator.
     */
    fun asTransportListener(): TransportListener = object : TransportListener {
        override fun onStateChanged(newState: TransportState, detail: String?) {
            if (newState == TransportState.DISCONNECTED || newState == TransportState.FAILED) {
                handleTransportDisconnected()
            }
        }

        override fun onMessageReceived(message: ProtocolMessage) {
            processIncomingMessage(message)
        }

        override fun onError(throwable: Throwable) {
            handleTransportDisconnected()
        }
    }

    /**
     * Processes an incoming authenticated [ProtocolMessage] synchronously and deterministically.
     * Thread-safe and prevents concurrent state mutations.
     */
    @Synchronized
    fun processIncomingMessage(message: ProtocolMessage): MessageDispatchResult {
        // 1. Handshake-phase messages
        when (message) {
            is ProtocolMessage.PairRequest -> {
                if (localRole != DeviceRole.FRIEND_DEVICE) {
                    val reason = "Unauthorized role: Controller cannot process incoming PairRequest"
                    notifyRejected(message, reason)
                    return MessageDispatchResult.Rejected(reason)
                }
                // PairRequest is handled at pairing initialization
                return MessageDispatchResult.Ignored("PairRequest routed to handshake engine")
            }
            is ProtocolMessage.PairResponse -> {
                if (localRole != DeviceRole.CONTROLLER) {
                    val reason = "Unauthorized role: Friend Device cannot process incoming PairResponse"
                    notifyRejected(message, reason)
                    return MessageDispatchResult.Rejected(reason)
                }
                return MessageDispatchResult.Ignored("PairResponse routed to handshake engine")
            }
            else -> {}
        }

        // 2. Precondition: Handshake must be CONNECTED
        if (handshakeEngine.state != PairingState.CONNECTED) {
            val reason = "Rejected: pairing state is ${handshakeEngine.state}, required CONNECTED"
            notifyRejected(message, reason)
            return MessageDispatchResult.Rejected(reason)
        }

        // 3. Precondition: Cryptographic validation (Timestamp, Session Token, HMAC Signature, Monotonic Replay)
        val currentTime = timeProvider()
        val validation = handshakeEngine.validateIncomingMessage(message, currentTime)
        if (validation !is MessageValidationResult.Valid) {
            val reason = "Cryptographic validation failed: $validation"
            notifyRejected(message, reason)
            return MessageDispatchResult.Rejected(reason, validation)
        }

        // 4. Role-based command dispatching
        return when (message) {
            is ProtocolMessage.SessionControlRequest -> {
                if (localRole != DeviceRole.FRIEND_DEVICE) {
                    val reason = "Unauthorized role: Controller cannot receive SessionControlRequest"
                    notifyRejected(message, reason)
                    MessageDispatchResult.Rejected(reason)
                } else {
                    val ack = handleSessionControlRequest(message)
                    transport?.sendMessage(ack)
                    MessageDispatchResult.Handled(ack)
                }
            }
            is ProtocolMessage.SessionControlAck -> {
                if (localRole != DeviceRole.CONTROLLER) {
                    val reason = "Unauthorized role: Friend Device cannot receive SessionControlAck"
                    notifyRejected(message, reason)
                    MessageDispatchResult.Rejected(reason)
                } else {
                    handleSessionControlAck(message)
                    MessageDispatchResult.Handled(null)
                }
            }
            is ProtocolMessage.PolicyUpdateRequest -> {
                if (localRole != DeviceRole.FRIEND_DEVICE) {
                    val reason = "Unauthorized role: Controller cannot receive PolicyUpdateRequest"
                    notifyRejected(message, reason)
                    MessageDispatchResult.Rejected(reason)
                } else {
                    val ack = handlePolicyUpdateRequest(message)
                    transport?.sendMessage(ack)
                    MessageDispatchResult.Handled(ack)
                }
            }
            is ProtocolMessage.PolicyUpdateAck -> {
                if (localRole != DeviceRole.CONTROLLER) {
                    val reason = "Unauthorized role: Friend Device cannot receive PolicyUpdateAck"
                    notifyRejected(message, reason)
                    MessageDispatchResult.Rejected(reason)
                } else {
                    handlePolicyUpdateAck(message)
                    MessageDispatchResult.Handled(null)
                }
            }
            is ProtocolMessage.DisconnectOrRevoke -> {
                handleRemoteDisconnect(message)
                MessageDispatchResult.Handled(null)
            }
            is ProtocolMessage.Heartbeat -> {
                val ack = handshakeEngine.signMessage { seq ->
                    ProtocolMessage.HeartbeatAck(
                        timestampMs = timeProvider(),
                        sequenceNumber = seq,
                        senderDeviceId = handshakeEngine.localDeviceId,
                        sessionToken = message.sessionToken,
                        acknowledgedSequenceNumber = message.sequenceNumber
                    )
                }
                transport?.sendMessage(ack)
                MessageDispatchResult.Handled(ack)
            }
            is ProtocolMessage.HeartbeatAck -> {
                MessageDispatchResult.Handled(null)
            }
            is ProtocolMessage.PairRequest -> {
                MessageDispatchResult.Ignored("PairRequest already processed")
            }
            is ProtocolMessage.PairResponse -> {
                MessageDispatchResult.Ignored("PairResponse already processed")
            }
        }
    }

    // -------------------------------------------------------------
    // FRIEND DEVICE COMMAND HANDLERS
    // -------------------------------------------------------------

    private fun handleSessionControlRequest(request: ProtocolMessage.SessionControlRequest): ProtocolMessage.SessionControlAck {
        val currentStatus = stateManager.currentState.sessionStatus
        val (resultingStatus, success, errorMessage) = when (request.action) {
            SessionAction.START -> {
                if (currentStatus == CallSessionStatus.ACTIVE) {
                    // Idempotent duplicate: already in requested ACTIVE state
                    Triple("ACTIVE", true, null)
                } else {
                    val transitionResult = stateManager.startSession()
                    when (transitionResult) {
                        is SessionTransitionResult.Success -> Triple("ACTIVE", true, null)
                        is SessionTransitionResult.InvalidTransition -> Triple(currentStatus.name, false, transitionResult.reason)
                    }
                }
            }
            SessionAction.END -> {
                if (currentStatus == CallSessionStatus.INACTIVE) {
                    // Idempotent duplicate: already in requested INACTIVE state
                    Triple("INACTIVE", true, null)
                } else {
                    val transitionResult = stateManager.endSession()
                    when (transitionResult) {
                        is SessionTransitionResult.Success -> Triple("INACTIVE", true, null)
                        is SessionTransitionResult.InvalidTransition -> Triple(currentStatus.name, false, transitionResult.reason)
                    }
                }
            }
        }

        val ack = handshakeEngine.signMessage { seq ->
            ProtocolMessage.SessionControlAck(
                timestampMs = timeProvider(),
                sequenceNumber = seq,
                senderDeviceId = handshakeEngine.localDeviceId,
                sessionToken = request.sessionToken,
                resultingSessionStatus = resultingStatus,
                success = success,
                errorMessage = errorMessage
            )
        } as ProtocolMessage.SessionControlAck

        if (success) {
            notifySessionStatus(stateManager.currentState.sessionStatus)
        }
        return ack
    }

    private fun handlePolicyUpdateRequest(request: ProtocolMessage.PolicyUpdateRequest): ProtocolMessage.PolicyUpdateAck {
        val app = ManagedApp.fromKey(request.appStorageKey)
        if (app == null) {
            // Unsupported app: rejected without state mutation
            return handshakeEngine.signMessage { seq ->
                ProtocolMessage.PolicyUpdateAck(
                    timestampMs = timeProvider(),
                    sequenceNumber = seq,
                    senderDeviceId = handshakeEngine.localDeviceId,
                    sessionToken = request.sessionToken,
                    appStorageKey = request.appStorageKey,
                    appliedLocked = false,
                    success = false,
                    errorMessage = "Unsupported app key: ${request.appStorageKey}"
                )
            } as ProtocolMessage.PolicyUpdateAck
        }

        // Preference separation: Remote policy update is ONLY valid while a call session is ACTIVE
        if (stateManager.currentState.sessionStatus != CallSessionStatus.ACTIVE) {
            val currentEffective = stateManager.currentState.stateFor(app).effectiveLocked
            return handshakeEngine.signMessage { seq ->
                ProtocolMessage.PolicyUpdateAck(
                    timestampMs = timeProvider(),
                    sequenceNumber = seq,
                    senderDeviceId = handshakeEngine.localDeviceId,
                    sessionToken = request.sessionToken,
                    appStorageKey = request.appStorageKey,
                    appliedLocked = currentEffective,
                    success = false,
                    errorMessage = "Cannot update app policy: no active call session"
                )
            } as ProtocolMessage.PolicyUpdateAck
        }

        val applied = stateManager.setSessionAppOverride(app, request.targetLocked)
        val resultingLocked = stateManager.currentState.stateFor(app).effectiveLocked

        val ack = handshakeEngine.signMessage { seq ->
            ProtocolMessage.PolicyUpdateAck(
                timestampMs = timeProvider(),
                sequenceNumber = seq,
                senderDeviceId = handshakeEngine.localDeviceId,
                sessionToken = request.sessionToken,
                appStorageKey = request.appStorageKey,
                appliedLocked = resultingLocked,
                success = applied,
                errorMessage = if (applied) null else "Failed to apply override for ${app.displayName}"
            )
        } as ProtocolMessage.PolicyUpdateAck

        if (applied) {
            notifyPolicyUpdated(app, resultingLocked)
        }
        return ack
    }

    // -------------------------------------------------------------
    // CONTROLLER COMMAND INITIATORS
    // -------------------------------------------------------------

    @Synchronized
    fun requestSessionStart(): ProtocolMessage.SessionControlRequest {
        require(localRole == DeviceRole.CONTROLLER) { "Only Controller can initiate SessionControlRequest" }
        check(handshakeEngine.state == PairingState.CONNECTED) { "Cannot request session start: not paired" }
        val token = checkNotNull(handshakeEngine.activeSessionToken) { "Missing active session token" }

        val request = handshakeEngine.signMessage { seq ->
            ProtocolMessage.SessionControlRequest(
                timestampMs = timeProvider(),
                sequenceNumber = seq,
                senderDeviceId = handshakeEngine.localDeviceId,
                sessionToken = token,
                action = SessionAction.START
            )
        } as ProtocolMessage.SessionControlRequest

        transport?.sendMessage(request)
        return request
    }

    @Synchronized
    fun requestSessionEnd(): ProtocolMessage.SessionControlRequest {
        require(localRole == DeviceRole.CONTROLLER) { "Only Controller can initiate SessionControlRequest" }
        check(handshakeEngine.state == PairingState.CONNECTED) { "Cannot request session end: not paired" }
        val token = checkNotNull(handshakeEngine.activeSessionToken) { "Missing active session token" }

        val request = handshakeEngine.signMessage { seq ->
            ProtocolMessage.SessionControlRequest(
                timestampMs = timeProvider(),
                sequenceNumber = seq,
                senderDeviceId = handshakeEngine.localDeviceId,
                sessionToken = token,
                action = SessionAction.END
            )
        } as ProtocolMessage.SessionControlRequest

        transport?.sendMessage(request)
        return request
    }

    @Synchronized
    fun requestPolicyUpdate(app: ManagedApp, targetLocked: Boolean): ProtocolMessage.PolicyUpdateRequest {
        return requestPolicyUpdate(app.storageKey, targetLocked)
    }

    @Synchronized
    fun requestPolicyUpdate(appStorageKey: String, targetLocked: Boolean): ProtocolMessage.PolicyUpdateRequest {
        require(localRole == DeviceRole.CONTROLLER) { "Only Controller can initiate PolicyUpdateRequest" }
        check(handshakeEngine.state == PairingState.CONNECTED) { "Cannot request policy update: not paired" }
        val token = checkNotNull(handshakeEngine.activeSessionToken) { "Missing active session token" }

        val request = handshakeEngine.signMessage { seq ->
            ProtocolMessage.PolicyUpdateRequest(
                timestampMs = timeProvider(),
                sequenceNumber = seq,
                senderDeviceId = handshakeEngine.localDeviceId,
                sessionToken = token,
                appStorageKey = appStorageKey,
                targetLocked = targetLocked
            )
        } as ProtocolMessage.PolicyUpdateRequest

        transport?.sendMessage(request)
        return request
    }

    @Synchronized
    fun disconnectOrRevoke(reason: String, permanent: Boolean): ProtocolMessage.DisconnectOrRevoke {
        val msg = handshakeEngine.disconnectOrRevoke(reason, permanent, timeProvider())
        transport?.sendMessage(msg)
        transport?.disconnect()

        if (localRole == DeviceRole.FRIEND_DEVICE && stateManager.currentState.sessionStatus == CallSessionStatus.ACTIVE) {
            stateManager.endSession()
        }
        return msg
    }

    // -------------------------------------------------------------
    // CONTROLLER ACK HANDLERS
    // -------------------------------------------------------------

    private fun handleSessionControlAck(ack: ProtocolMessage.SessionControlAck) {
        lastSessionControlAck = ack
        if (ack.success) {
            remoteSessionStatus = if (ack.resultingSessionStatus == "ACTIVE") {
                CallSessionStatus.ACTIVE
            } else {
                CallSessionStatus.INACTIVE
            }
            notifySessionStatus(remoteSessionStatus)
        }
    }

    private fun handlePolicyUpdateAck(ack: ProtocolMessage.PolicyUpdateAck) {
        lastPolicyUpdateAck = ack
        val app = ManagedApp.fromKey(ack.appStorageKey)
        if (app != null && ack.success) {
            notifyPolicyUpdated(app, ack.appliedLocked)
        }
    }

    // -------------------------------------------------------------
    // LIFECYCLE & TEARDOWN
    // -------------------------------------------------------------

    @Synchronized
    fun handleRemoteDisconnect(message: ProtocolMessage.DisconnectOrRevoke) {
        handshakeEngine.handleRemoteDisconnect(message)
        if (localRole == DeviceRole.FRIEND_DEVICE && stateManager.currentState.sessionStatus == CallSessionStatus.ACTIVE) {
            stateManager.endSession()
        }
        notifyRemoteDisconnect(message.reason, message.isPermanentRevocation)
    }

    @Synchronized
    fun handleTransportDisconnected() {
        if (handshakeEngine.state == PairingState.CONNECTED) {
            handshakeEngine.disconnectOrRevoke(
                reason = "Transport connection closed",
                isPermanentRevocation = false,
                currentTimeMs = timeProvider()
            )
        }
        if (localRole == DeviceRole.FRIEND_DEVICE && stateManager.currentState.sessionStatus == CallSessionStatus.ACTIVE) {
            stateManager.endSession()
        }
    }

    // -------------------------------------------------------------
    // LISTENER NOTIFICATIONS
    // -------------------------------------------------------------

    private fun notifySessionStatus(status: CallSessionStatus) {
        for (l in listeners.toList()) {
            l.onSessionStatusUpdated(status)
        }
    }

    private fun notifyPolicyUpdated(app: ManagedApp, locked: Boolean) {
        for (l in listeners.toList()) {
            l.onPolicyUpdated(app, locked)
        }
    }

    private fun notifyRemoteDisconnect(reason: String, permanent: Boolean) {
        for (l in listeners.toList()) {
            l.onRemoteDisconnect(reason, permanent)
        }
    }

    private fun notifyRejected(message: ProtocolMessage, reason: String) {
        for (l in listeners.toList()) {
            l.onMessageRejected(message, reason)
        }
    }
}

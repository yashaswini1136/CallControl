package com.callcontrol.app.pairing

import com.callcontrol.app.network.DeviceTransport
import com.callcontrol.app.network.LanSocketTransport
import com.callcontrol.app.network.TransportListener
import com.callcontrol.app.network.TransportState
import com.callcontrol.app.state.CallControlStateManager
import com.callcontrol.app.state.CallSessionStatus
import com.callcontrol.app.state.ManagedApp
import java.util.UUID

/**
 * Visual status enum representing the 8 discrete stages of the CallControl pairing lifecycle.
 */
enum class PairingUiStatus(
    val badgeText: String,
    val isConnected: Boolean
) {
    /** 1. Idle, not connected or paired */
    NOT_PAIRED("NOT PAIRED", false),

    /** 2. Friend Device bound and listening with active pairing credentials */
    LISTENING("LISTENING", false),

    /** 3. Controller in progress of connecting/sending credentials */
    CONNECTING("CONNECTING", false),

    /** 4. Friend Device reviewing an incoming connection request */
    WAITING_APPROVAL("APPROVAL NEEDED", false),

    /** 5. Authenticated ECDH and mutual verification in progress */
    HANDSHAKE_IN_PROGRESS("AUTHENTICATING", false),

    /** 6. Connected, mutually authenticated, and actively authorized */
    CONNECTED("CONNECTED", true),

    /** 7. Pairing was explicitly rejected or maximum attempt rate limit hit */
    REJECTED("REJECTED", false),

    /** 7b. Single-use pairing code expired (5-minute TTL) */
    EXPIRED("EXPIRED", false),

    /** 8. Previously established connection disconnected */
    DISCONNECTED("DISCONNECTED", false),

    /** 8b. Pairing association permanently revoked by user */
    REVOKED("REVOKED", false)
}

/**
 * Observable UI state model consumed by the dashboard.
 */
data class PairingUiModel(
    val selectedRole: DeviceRole = DeviceRole.FRIEND_DEVICE,
    val status: PairingUiStatus = PairingUiStatus.NOT_PAIRED,
    val statusDetail: String = "Not connected",
    val activePairingCode: String? = null,
    val peerDeviceId: String? = null,
    val peerRole: DeviceRole? = null,
    val remoteSessionStatus: CallSessionStatus = CallSessionStatus.INACTIVE,
    val remoteAppStates: Map<ManagedApp, Boolean> = ManagedApp.entries.associateWith { it.defaultLocked },
    val feedbackMessage: String? = null,
    val isActionInProgress: Boolean = false,
    val listeningPort: Int = DeviceTransport.DEFAULT_PORT
)

/**
 * State coordinator managing the complete pairing lifecycle, role switching,
 * LAN transport integration, and dashboard view synchronization.
 */
class DashboardPairingManager(
    val stateManager: CallControlStateManager,
    var localDeviceId: String = "dev-" + UUID.randomUUID().toString().take(8),
    initialRole: DeviceRole = DeviceRole.FRIEND_DEVICE,
    private val transportFactory: () -> DeviceTransport = { LanSocketTransport() },
    private val uiDispatcher: (Runnable) -> Unit = { r ->
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post(r)
        } catch (_: Exception) {
            r.run() // Fallback for JVM unit tests where android.os.Looper is not stubbed
        }
    },
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    var localRole: DeviceRole = initialRole
        private set

    var handshakeEngine: PairingHandshakeEngine = PairingHandshakeEngine(localDeviceId, localRole)
        private set

    var transport: DeviceTransport? = null
        private set

    var integrator: PairingStateIntegrator = PairingStateIntegrator(
        localRole = localRole,
        handshakeEngine = handshakeEngine,
        stateManager = stateManager,
        timeProvider = timeProvider
    )
        private set

    var currentUiState: PairingUiModel = PairingUiModel(
        selectedRole = localRole,
        status = PairingUiStatus.NOT_PAIRED,
        statusDetail = if (localRole == DeviceRole.FRIEND_DEVICE) {
            "Ready to pair. Tap 'START PAIRING' to generate a 6-digit code."
        } else {
            "Ready to pair. Tap 'CONNECT TO FRIEND' to enter code."
        }
    )
        private set

    private val uiListeners = mutableSetOf<(PairingUiModel) -> Unit>()
    private var pendingPairRequest: ProtocolMessage.PairRequest? = null
    private var pendingControllerCode: String? = null
    private var pendingClientNonce: String? = null

    init {
        setupIntegratorListeners()
    }

    fun addUiListener(listener: (PairingUiModel) -> Unit) {
        uiListeners.add(listener)
        listener(currentUiState)
    }

    fun removeUiListener(listener: (PairingUiModel) -> Unit) {
        uiListeners.remove(listener)
    }

    /**
     * Switches the active device role between Controller and Friend Device.
     * Only permitted when not actively connected.
     */
    @Synchronized
    fun setRole(newRole: DeviceRole): Boolean {
        if (currentUiState.status.isConnected || currentUiState.status == PairingUiStatus.LISTENING ||
            currentUiState.status == PairingUiStatus.CONNECTING || currentUiState.status == PairingUiStatus.WAITING_APPROVAL) {
            return false
        }

        localRole = newRole
        handshakeEngine = PairingHandshakeEngine(localDeviceId, localRole)
        integrator = PairingStateIntegrator(
            localRole = localRole,
            handshakeEngine = handshakeEngine,
            stateManager = stateManager,
            transport = transport,
            timeProvider = timeProvider
        )
        setupIntegratorListeners()

        val detail = if (newRole == DeviceRole.FRIEND_DEVICE) {
            "Friend Device role active. Tap 'START PAIRING' to generate a 6-digit code."
        } else {
            "Controller role active. Tap 'CONNECT TO FRIEND' to enter code."
        }

        publishUiState(
            currentUiState.copy(
                selectedRole = newRole,
                status = PairingUiStatus.NOT_PAIRED,
                statusDetail = detail,
                activePairingCode = null,
                peerDeviceId = null,
                peerRole = null,
                feedbackMessage = null
            )
        )
        return true
    }

    // -------------------------------------------------------------
    // FRIEND DEVICE JOURNEY
    // -------------------------------------------------------------

    /**
     * Friend Device generates an expiring, rate-limited 6-digit pairing code and
     * begins listening on LAN socket transport.
     */
    @Synchronized
    fun startFriendListening(port: Int = DeviceTransport.DEFAULT_PORT): Boolean {
        require(localRole == DeviceRole.FRIEND_DEVICE) { "Only Friend Device can generate pairing codes" }

        cleanupTransport()

        val creds = handshakeEngine.generatePairingCredentials(timeProvider())
        val newTransport = transportFactory()
        transport = newTransport
        integrator.bindTransport(newTransport)

        // Custom listener wrapping integrator to intercept handshake messages
        newTransport.setListener(createFriendTransportListener())

        val started = newTransport.startListening(port)
        if (!started) {
            publishUiState(
                currentUiState.copy(
                    status = PairingUiStatus.NOT_PAIRED,
                    statusDetail = "Failed to start listener on port $port",
                    feedbackMessage = "Failed to bind network socket on port $port."
                )
            )
            return false
        }

        publishUiState(
            currentUiState.copy(
                status = PairingUiStatus.LISTENING,
                statusDetail = "Pairing Code: ${creds.code} • Listening on port $port\nExpires in 5 min • 3 attempts max",
                activePairingCode = creds.code,
                listeningPort = port,
                feedbackMessage = "Pairing code generated. Share this code with your Controller."
            )
        )
        return true
    }

    /**
     * Friend Device cancels active pairing listening and tears down server socket.
     */
    @Synchronized
    fun cancelFriendListening() {
        handshakeEngine.activeCredentials?.cancel()
        cleanupTransport()
        publishUiState(
            currentUiState.copy(
                status = PairingUiStatus.NOT_PAIRED,
                statusDetail = "Pairing canceled. Tap 'START PAIRING' to begin.",
                activePairingCode = null,
                feedbackMessage = "Pairing canceled."
            )
        )
    }

    /**
     * Friend Device user explicitly approves the pending [PairRequest].
     */
    @Synchronized
    fun approvePendingPairing(): Boolean {
        require(localRole == DeviceRole.FRIEND_DEVICE) { "Only Friend Device can approve pairing" }
        val request = pendingPairRequest ?: return false
        val activeTransport = transport ?: return false
        val creds = handshakeEngine.activeCredentials ?: return false

        val serverProof = activeTransport.computeChannelAuthProof(creds.code, creds.saltHex, forClient = false)
        val response = handshakeEngine.approvePairing(
            request = request,
            currentTimeMs = timeProvider(),
            serverChannelAuthProof = serverProof
        )
        activeTransport.sendMessage(response)
        pendingPairRequest = null

        publishUiState(
            currentUiState.copy(
                status = PairingUiStatus.CONNECTED,
                statusDetail = "Connected to ${handshakeEngine.peerDeviceId} (Controller)\nEncrypted LAN • Channel Authenticated",
                peerDeviceId = handshakeEngine.peerDeviceId,
                peerRole = DeviceRole.CONTROLLER,
                activePairingCode = null,
                feedbackMessage = "Pairing approved. Remote control active."
            )
        )
        return true
    }

    /**
     * Friend Device user explicitly rejects the pending [PairRequest].
     */
    @Synchronized
    fun rejectPendingPairing(reason: String = "User declined pairing"): Boolean {
        require(localRole == DeviceRole.FRIEND_DEVICE) { "Only Friend Device can reject pairing" }
        val request = pendingPairRequest ?: return false
        val activeTransport = transport

        val response = handshakeEngine.rejectPairing(request, reason, timeProvider())
        activeTransport?.sendMessage(response)
        pendingPairRequest = null
        cleanupTransport()

        publishUiState(
            currentUiState.copy(
                status = PairingUiStatus.REJECTED,
                statusDetail = "Pairing request rejected: $reason",
                activePairingCode = null,
                feedbackMessage = "Pairing declined."
            )
        )
        return true
    }

    // -------------------------------------------------------------
    // CONTROLLER JOURNEY
    // -------------------------------------------------------------

    /**
     * Controller connects to a Friend Device over LAN and submits the pairing code.
     */
    @Synchronized
    fun initiateControllerConnection(
        host: String = "127.0.0.1",
        port: Int = DeviceTransport.DEFAULT_PORT,
        enteredCode: String
    ): Boolean {
        require(localRole == DeviceRole.CONTROLLER) { "Only Controller can initiate pairing" }
        val trimmedCode = enteredCode.trim()
        if (trimmedCode.length != 6) {
            publishUiState(
                currentUiState.copy(
                    feedbackMessage = "Invalid code: pairing code must be exactly 6 digits."
                )
            )
            return false
        }

        cleanupTransport()

        pendingControllerCode = trimmedCode
        val clientNonce = MessageSecurity.generateNonce()
        pendingClientNonce = clientNonce

        val newTransport = transportFactory()
        transport = newTransport
        integrator.bindTransport(newTransport)

        publishUiState(
            currentUiState.copy(
                status = PairingUiStatus.CONNECTING,
                statusDetail = "Connecting to Friend Device at $host:$port...",
                isActionInProgress = true,
                feedbackMessage = null
            )
        )

        newTransport.setListener(createControllerTransportListener(host, port, trimmedCode, clientNonce))
        val connecting = newTransport.connect(host, port)
        if (!connecting) {
            publishUiState(
                currentUiState.copy(
                    status = PairingUiStatus.NOT_PAIRED,
                    statusDetail = "Failed to connect to $host:$port",
                    isActionInProgress = false,
                    feedbackMessage = "Could not reach Friend Device at $host:$port."
                )
            )
            return false
        }
        return true
    }

    /**
     * Controller requests remote call session START.
     */
    @Synchronized
    fun controllerRequestSessionStart(): Boolean {
        require(localRole == DeviceRole.CONTROLLER) { "Only Controller can request remote session start" }
        if (!currentUiState.status.isConnected) {
            publishUiState(currentUiState.copy(feedbackMessage = "Not connected to Friend Device."))
            return false
        }
        return try {
            integrator.requestSessionStart()
            publishUiState(currentUiState.copy(feedbackMessage = "Session START request sent..."))
            true
        } catch (e: Exception) {
            publishUiState(currentUiState.copy(feedbackMessage = "Failed to send START: ${e.message}"))
            false
        }
    }

    /**
     * Controller requests remote call session END.
     */
    @Synchronized
    fun controllerRequestSessionEnd(): Boolean {
        require(localRole == DeviceRole.CONTROLLER) { "Only Controller can request remote session end" }
        if (!currentUiState.status.isConnected) {
            publishUiState(currentUiState.copy(feedbackMessage = "Not connected to Friend Device."))
            return false
        }
        return try {
            integrator.requestSessionEnd()
            publishUiState(currentUiState.copy(feedbackMessage = "Session END request sent..."))
            true
        } catch (e: Exception) {
            publishUiState(currentUiState.copy(feedbackMessage = "Failed to send END: ${e.message}"))
            false
        }
    }

    /**
     * Controller requests remote app policy toggle (LOCK/ALLOW).
     */
    @Synchronized
    fun controllerRequestPolicyToggle(app: ManagedApp): Boolean {
        require(localRole == DeviceRole.CONTROLLER) { "Only Controller can request remote policy updates" }
        if (!currentUiState.status.isConnected) {
            publishUiState(currentUiState.copy(feedbackMessage = "Not connected to Friend Device."))
            return false
        }
        if (currentUiState.remoteSessionStatus != CallSessionStatus.ACTIVE) {
            publishUiState(currentUiState.copy(feedbackMessage = "Cannot update policy: remote call session is not active."))
            return false
        }

        val currentLocked = currentUiState.remoteAppStates[app] ?: app.defaultLocked
        val targetLocked = !currentLocked
        return try {
            integrator.requestPolicyUpdate(app, targetLocked)
            val actionLabel = if (targetLocked) "LOCK" else "ALLOW"
            publishUiState(currentUiState.copy(feedbackMessage = "Requesting $actionLabel for ${app.displayName}..."))
            true
        } catch (e: Exception) {
            publishUiState(currentUiState.copy(feedbackMessage = "Failed to send policy update: ${e.message}"))
            false
        }
    }

    // -------------------------------------------------------------
    // DISCONNECT & TEARDOWN
    // -------------------------------------------------------------

    /**
     * Disconnects or permanently revokes pairing.
     */
    @Synchronized
    fun disconnectOrRevoke(permanent: Boolean = false) {
        val reason = if (permanent) "User revoked pairing" else "User disconnected"
        try {
            integrator.disconnectOrRevoke(reason, permanent)
        } catch (_: Exception) {}

        cleanupTransport()

        val nextStatus = if (permanent) PairingUiStatus.REVOKED else PairingUiStatus.DISCONNECTED
        val detail = if (permanent) {
            "Pairing revoked. Encryption keys securely zeroed."
        } else {
            "Disconnected from peer. Encryption keys zeroed."
        }

        publishUiState(
            currentUiState.copy(
                status = nextStatus,
                statusDetail = detail,
                activePairingCode = null,
                peerDeviceId = null,
                peerRole = null,
                remoteSessionStatus = CallSessionStatus.INACTIVE,
                feedbackMessage = detail
            )
        )
    }

    /**
     * Resets status to NOT_PAIRED.
     */
    @Synchronized
    fun resetToUnpaired() {
        cleanupTransport()
        val detail = if (localRole == DeviceRole.FRIEND_DEVICE) {
            "Ready to pair. Tap 'START PAIRING' to generate a 6-digit code."
        } else {
            "Ready to pair. Tap 'CONNECT TO FRIEND' to enter code."
        }
        publishUiState(
            currentUiState.copy(
                status = PairingUiStatus.NOT_PAIRED,
                statusDetail = detail,
                activePairingCode = null,
                peerDeviceId = null,
                peerRole = null,
                feedbackMessage = null,
                isActionInProgress = false
            )
        )
    }

    // -------------------------------------------------------------
    // INTERNAL TRANSPORT LISTENERS & ROUTING
    // -------------------------------------------------------------

    private fun createFriendTransportListener(): TransportListener {
        return object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.DISCONNECTED || newState == TransportState.FAILED) {
                    handleTransportDrop()
                }
            }

            override fun onMessageReceived(message: ProtocolMessage) {
                when (message) {
                    is ProtocolMessage.PairRequest -> {
                        handleIncomingPairRequest(message)
                    }
                    else -> {
                        integrator.processIncomingMessage(message)
                    }
                }
            }

            override fun onError(throwable: Throwable) {
                handleTransportDrop()
            }
        }
    }

    private fun handleIncomingPairRequest(request: ProtocolMessage.PairRequest) {
        val activeTransport = transport ?: return
        val eval = handshakeEngine.evaluatePairRequest(request, timeProvider()) { code, salt, proof ->
            activeTransport.verifyChannelAuthProof(code, salt, proof, isClientProof = true)
        }

        when (eval) {
            is CredentialValidationResult.Success -> {
                pendingPairRequest = request
                publishUiState(
                    currentUiState.copy(
                        status = PairingUiStatus.WAITING_APPROVAL,
                        statusDetail = "Pairing request from ${request.senderDeviceId}.\nApprove connection?",
                        peerDeviceId = request.senderDeviceId,
                        feedbackMessage = "Pairing code verified. Please approve or decline."
                    )
                )
            }
            is CredentialValidationResult.InvalidCode -> {
                publishUiState(
                    currentUiState.copy(
                        feedbackMessage = "Invalid pairing code attempt (${eval.attemptsRemaining} remaining)."
                    )
                )
            }
            is CredentialValidationResult.RateLimited -> {
                cleanupTransport()
                publishUiState(
                    currentUiState.copy(
                        status = PairingUiStatus.REJECTED,
                        statusDetail = "Too many failed attempts. Code locked out.",
                        activePairingCode = null,
                        feedbackMessage = "Pairing locked out due to repeated invalid attempts."
                    )
                )
            }
            is CredentialValidationResult.Expired -> {
                cleanupTransport()
                publishUiState(
                    currentUiState.copy(
                        status = PairingUiStatus.EXPIRED,
                        statusDetail = "Pairing code expired.",
                        activePairingCode = null,
                        feedbackMessage = "Pairing code expired."
                    )
                )
            }
            is CredentialValidationResult.AlreadyConsumed -> {
                publishUiState(
                    currentUiState.copy(
                        feedbackMessage = "Pairing credential already consumed."
                    )
                )
            }
        }
    }

    private fun createControllerTransportListener(
        host: String,
        port: Int,
        code: String,
        clientNonce: String
    ): TransportListener {
        return object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                when (newState) {
                    TransportState.CONNECTED -> {
                        // Transport connected and ephemeral ECDH exchange completed!
                        // Now compute channel auth proof and send PairRequest
                        val activeTransport = transport ?: return
                        val clientProof = activeTransport.computeChannelAuthProof(code, saltHex = "", forClient = true)
                        val pairRequest = handshakeEngine.createPairRequest(
                            pairingId = "pair-session-" + UUID.randomUUID().toString().take(8),
                            submittedCode = code,
                            clientNonce = clientNonce,
                            currentTimeMs = timeProvider(),
                            channelAuthProof = clientProof
                        )
                        activeTransport.sendMessage(pairRequest)
                        publishUiState(
                            currentUiState.copy(
                                status = PairingUiStatus.HANDSHAKE_IN_PROGRESS,
                                statusDetail = "Encrypted channel active. Waiting for Friend approval...",
                                isActionInProgress = true
                            )
                        )
                    }
                    TransportState.DISCONNECTED, TransportState.FAILED -> {
                        handleTransportDrop()
                    }
                    else -> {}
                }
            }

            override fun onMessageReceived(message: ProtocolMessage) {
                when (message) {
                    is ProtocolMessage.PairResponse -> {
                        handleControllerPairResponse(message, code, clientNonce)
                    }
                    else -> {
                        integrator.processIncomingMessage(message)
                    }
                }
            }

            override fun onError(throwable: Throwable) {
                handleTransportDrop()
            }
        }
    }

    private fun handleControllerPairResponse(
        response: ProtocolMessage.PairResponse,
        code: String,
        clientNonce: String
    ) {
        val activeTransport = transport
        if (!response.approved) {
            cleanupTransport()
            publishUiState(
                currentUiState.copy(
                    status = PairingUiStatus.REJECTED,
                    statusDetail = "Friend Device declined pairing: ${response.rejectionReason ?: "Rejected"}",
                    isActionInProgress = false,
                    feedbackMessage = "Pairing request was declined by the Friend Device."
                )
            )
            return
        }

        val verified = handshakeEngine.handlePairResponse(
            response = response,
            submittedCode = code,
            clientNonce = clientNonce,
            serverChannelAuthVerifier = { c, s, p ->
                activeTransport?.verifyChannelAuthProof(c, s, p, isClientProof = false) ?: false
            }
        )

        if (verified) {
            publishUiState(
                currentUiState.copy(
                    status = PairingUiStatus.CONNECTED,
                    statusDetail = "Connected to ${response.senderDeviceId} (Friend Device)\nEncrypted LAN • Channel Authenticated",
                    peerDeviceId = response.senderDeviceId,
                    peerRole = DeviceRole.FRIEND_DEVICE,
                    isActionInProgress = false,
                    feedbackMessage = "Connected to Friend Device! You can now control the session."
                )
            )
        } else {
            cleanupTransport()
            publishUiState(
                currentUiState.copy(
                    status = PairingUiStatus.REJECTED,
                    statusDetail = "Peer verification failed (HMAC signature or channel proof mismatch)",
                    isActionInProgress = false,
                    feedbackMessage = "Security verification failed for Friend Device response."
                )
            )
        }
    }

    private fun setupIntegratorListeners() {
        integrator.addListener(object : PairingIntegrationListener {
            override fun onSessionStatusUpdated(status: CallSessionStatus) {
                publishUiState(
                    currentUiState.copy(
                        remoteSessionStatus = status,
                        feedbackMessage = if (status == CallSessionStatus.ACTIVE) {
                            "Remote call session is ACTIVE."
                        } else {
                            "Remote call session is INACTIVE."
                        }
                    )
                )
            }

            override fun onPolicyUpdated(app: ManagedApp, locked: Boolean) {
                val updatedAppStates = currentUiState.remoteAppStates.toMutableMap()
                updatedAppStates[app] = locked
                val stateLabel = if (locked) "LOCKED" else "ALLOWED"
                publishUiState(
                    currentUiState.copy(
                        remoteAppStates = updatedAppStates,
                        feedbackMessage = "${app.displayName} set to $stateLabel on Friend Device."
                    )
                )
            }

            override fun onRemoteDisconnect(reason: String, permanent: Boolean) {
                val nextStatus = if (permanent) PairingUiStatus.REVOKED else PairingUiStatus.DISCONNECTED
                publishUiState(
                    currentUiState.copy(
                        status = nextStatus,
                        statusDetail = "Peer disconnected: $reason",
                        peerDeviceId = null,
                        peerRole = null,
                        remoteSessionStatus = CallSessionStatus.INACTIVE,
                        feedbackMessage = "Peer disconnected ($reason)."
                    )
                )
            }

            override fun onMessageRejected(message: ProtocolMessage, reason: String) {
                publishUiState(
                    currentUiState.copy(
                        feedbackMessage = "Command rejected: $reason"
                    )
                )
            }
        })
    }

    private fun handleTransportDrop() {
        if (currentUiState.status.isConnected) {
            integrator.handleTransportDisconnected()
            publishUiState(
                currentUiState.copy(
                    status = PairingUiStatus.DISCONNECTED,
                    statusDetail = "Connection dropped. Cryptographic keys cleared.",
                    peerDeviceId = null,
                    peerRole = null,
                    remoteSessionStatus = CallSessionStatus.INACTIVE,
                    isActionInProgress = false,
                    feedbackMessage = "LAN connection was closed."
                )
            )
        } else if (currentUiState.status == PairingUiStatus.CONNECTING ||
            currentUiState.status == PairingUiStatus.HANDSHAKE_IN_PROGRESS) {
            publishUiState(
                currentUiState.copy(
                    status = PairingUiStatus.NOT_PAIRED,
                    statusDetail = "Connection failed",
                    isActionInProgress = false,
                    feedbackMessage = "Failed to connect to Friend Device."
                )
            )
        }
    }

    private fun cleanupTransport() {
        try {
            transport?.disconnect()
        } catch (_: Exception) {}
        transport = null
    }

    private fun publishUiState(newState: PairingUiModel) {
        currentUiState = newState
        uiDispatcher(Runnable {
            for (listener in uiListeners.toList()) {
                listener(newState)
            }
        })
    }
}

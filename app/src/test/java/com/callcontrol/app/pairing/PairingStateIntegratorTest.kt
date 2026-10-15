package com.callcontrol.app.pairing

import com.callcontrol.app.network.LoopbackTransport
import com.callcontrol.app.state.CallControlStateManager
import com.callcontrol.app.state.CallSessionStatus
import com.callcontrol.app.state.EnforcementStatus
import com.callcontrol.app.state.InMemoryAppAccessStorage
import com.callcontrol.app.state.ManagedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingStateIntegratorTest {

    private lateinit var friendStorage: InMemoryAppAccessStorage
    private lateinit var friendStateManager: CallControlStateManager
    private lateinit var friendEngine: PairingHandshakeEngine
    private lateinit var friendIntegrator: PairingStateIntegrator

    private lateinit var controllerStorage: InMemoryAppAccessStorage
    private lateinit var controllerStateManager: CallControlStateManager
    private lateinit var controllerEngine: PairingHandshakeEngine
    private lateinit var controllerIntegrator: PairingStateIntegrator

    private var currentTime: Long = 1_000_000L

    @Before
    fun setUp() {
        currentTime = 1_000_000L

        friendStorage = InMemoryAppAccessStorage()
        friendStateManager = CallControlStateManager(
            storage = friendStorage,
            enforcementStatusProvider = { EnforcementStatus.OS_ENFORCED }
        )
        friendEngine = PairingHandshakeEngine(
            localDeviceId = "friend-device-123",
            localRole = DeviceRole.FRIEND_DEVICE
        )
        friendIntegrator = PairingStateIntegrator(
            localRole = DeviceRole.FRIEND_DEVICE,
            handshakeEngine = friendEngine,
            stateManager = friendStateManager,
            timeProvider = { currentTime }
        )

        controllerStorage = InMemoryAppAccessStorage()
        controllerStateManager = CallControlStateManager(
            storage = controllerStorage,
            enforcementStatusProvider = { EnforcementStatus.SIMULATED_POLICY_ONLY }
        )
        controllerEngine = PairingHandshakeEngine(
            localDeviceId = "controller-device-456",
            localRole = DeviceRole.CONTROLLER
        )
        controllerIntegrator = PairingStateIntegrator(
            localRole = DeviceRole.CONTROLLER,
            handshakeEngine = controllerEngine,
            stateManager = controllerStateManager,
            timeProvider = { currentTime }
        )
    }

    private fun completePairingHandshake() {
        val creds = friendEngine.generatePairingCredentials(currentTime)
        val clientNonce = MessageSecurity.generateNonce()
        val pairRequest = controllerEngine.createPairRequest(
            pairingId = creds.pairingId,
            submittedCode = creds.code,
            clientNonce = clientNonce,
            currentTimeMs = currentTime
        )
        val eval = friendEngine.evaluatePairRequest(pairRequest, currentTime)
        assertTrue(eval is CredentialValidationResult.Success)
        val pairResponse = friendEngine.approvePairing(pairRequest, currentTime)
        val completed = controllerEngine.handlePairResponse(pairResponse, creds.code, clientNonce)
        assertTrue(completed)
        assertEquals(PairingState.CONNECTED, friendEngine.state)
        assertEquals(PairingState.CONNECTED, controllerEngine.state)
    }

    @Test
    fun sessionStartAndEnd_throughIntegrationLayer_updatesStateManagerAndReturnsSignedAcks() {
        completePairingHandshake()
        currentTime += 1_000L

        // Initial baseline verification
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)
        assertFalse(friendStateManager.currentState.stateFor(ManagedApp.WHATSAPP).effectiveLocked)

        // 1. Controller requests START
        val startReq = controllerIntegrator.requestSessionStart()
        assertEquals(SessionAction.START, startReq.action)

        // Friend processes START
        val startResult = friendIntegrator.processIncomingMessage(startReq)
        assertTrue(startResult is MessageDispatchResult.Handled)
        val startAck = (startResult as MessageDispatchResult.Handled).response as ProtocolMessage.SessionControlAck
        assertTrue(startAck.success)
        assertEquals("ACTIVE", startAck.resultingSessionStatus)
        assertEquals(CallSessionStatus.ACTIVE, friendStateManager.currentState.sessionStatus)

        // Controller processes START Ack
        controllerIntegrator.processIncomingMessage(startAck)
        assertEquals(CallSessionStatus.ACTIVE, controllerIntegrator.remoteSessionStatus)

        // 2. Controller requests END
        currentTime += 1_000L
        val endReq = controllerIntegrator.requestSessionEnd()
        assertEquals(SessionAction.END, endReq.action)

        // Friend processes END
        val endResult = friendIntegrator.processIncomingMessage(endReq)
        assertTrue(endResult is MessageDispatchResult.Handled)
        val endAck = (endResult as MessageDispatchResult.Handled).response as ProtocolMessage.SessionControlAck
        assertTrue(endAck.success)
        assertEquals("INACTIVE", endAck.resultingSessionStatus)
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)

        // Controller processes END Ack
        controllerIntegrator.processIncomingMessage(endAck)
        assertEquals(CallSessionStatus.INACTIVE, controllerIntegrator.remoteSessionStatus)
    }

    @Test
    fun unauthorizedRoleAndUnpairedCommands_rejectedWithoutStateMutation() {
        // Test A: Command received while UNPAIRED
        assertEquals(PairingState.UNPAIRED, friendEngine.state)
        val fakeMsg = ProtocolMessage.SessionControlRequest(
            timestampMs = currentTime,
            sequenceNumber = 1L,
            senderDeviceId = "attacker",
            signature = "fake-sig",
            sessionToken = "fake-tok",
            action = SessionAction.START
        )
        val unpairedResult = friendIntegrator.processIncomingMessage(fakeMsg)
        assertTrue(unpairedResult is MessageDispatchResult.Rejected)
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)

        // Now pair
        completePairingHandshake()
        currentTime += 1_000L

        // Test B: Controller receives SessionControlRequest (Role Inversion Attack)
        val reqForController = controllerEngine.signMessage { seq ->
            ProtocolMessage.SessionControlRequest(
                timestampMs = currentTime,
                sequenceNumber = seq,
                senderDeviceId = friendEngine.localDeviceId,
                sessionToken = controllerEngine.activeSessionToken!!,
                action = SessionAction.START
            )
        }
        val controllerRejection = controllerIntegrator.processIncomingMessage(reqForController)
        assertTrue(controllerRejection is MessageDispatchResult.Rejected)
        assertTrue((controllerRejection as MessageDispatchResult.Rejected).reason.contains("Unauthorized role"))
        assertEquals(CallSessionStatus.INACTIVE, controllerStateManager.currentState.sessionStatus)

        // Test C: Friend receives SessionControlAck (Role Inversion)
        currentTime += 1_000L
        val ackForFriend = friendEngine.signMessage { seq ->
            ProtocolMessage.SessionControlAck(
                timestampMs = currentTime,
                sequenceNumber = seq,
                senderDeviceId = controllerEngine.localDeviceId,
                sessionToken = friendEngine.activeSessionToken!!,
                resultingSessionStatus = "ACTIVE",
                success = true
            )
        }
        val friendRejection = friendIntegrator.processIncomingMessage(ackForFriend)
        assertTrue(friendRejection is MessageDispatchResult.Rejected)
        assertTrue((friendRejection as MessageDispatchResult.Rejected).reason.contains("Unauthorized role"))
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)
    }

    @Test
    fun invalidSignature_sessionToken_replay_andStaleSession_rejectedWithoutStateMutation() {
        completePairingHandshake()
        currentTime += 1_000L

        // Test A: Invalid HMAC signature
        val validMsg = controllerIntegrator.requestSessionStart()
        val tamperedMsg = validMsg.copy(signature = "tampered-hmac-signature-hex")
        val sigResult = friendIntegrator.processIncomingMessage(tamperedMsg)
        assertTrue(sigResult is MessageDispatchResult.Rejected)
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)

        // Test B: Invalid / Stale Session Token
        val staleTokenMsg = validMsg.copy(sessionToken = "stale-expired-token-1234")
        val tokenResult = friendIntegrator.processIncomingMessage(staleTokenMsg)
        assertTrue(tokenResult is MessageDispatchResult.Rejected)
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)

        // Test C: Monotonic Replay Protection
        // First valid execution
        val realResult = friendIntegrator.processIncomingMessage(validMsg)
        assertTrue(realResult is MessageDispatchResult.Handled)
        assertEquals(CallSessionStatus.ACTIVE, friendStateManager.currentState.sessionStatus)

        // Attacker replays identical validMsg
        val replayResult = friendIntegrator.processIncomingMessage(validMsg)
        assertTrue(replayResult is MessageDispatchResult.Rejected)
        assertTrue((replayResult as MessageDispatchResult.Rejected).reason.contains("validation failed"))

        // Test D: Decreasing sequence number replay
        val oldSeqMsg = controllerEngine.signMessage { _ ->
            ProtocolMessage.PolicyUpdateRequest(
                timestampMs = currentTime,
                sequenceNumber = validMsg.sequenceNumber - 1, // Decreasing
                senderDeviceId = controllerEngine.localDeviceId,
                sessionToken = controllerEngine.activeSessionToken!!,
                appStorageKey = "instagram",
                targetLocked = false
            )
        }
        val oldSeqResult = friendIntegrator.processIncomingMessage(oldSeqMsg)
        assertTrue(oldSeqResult is MessageDispatchResult.Rejected)
    }

    @Test
    fun policyUpdates_forSupportedAndUnsupportedApps() {
        completePairingHandshake()
        currentTime += 1_000L

        // Start session first so policy updates are valid
        val startReq = controllerIntegrator.requestSessionStart()
        friendIntegrator.processIncomingMessage(startReq)
        assertEquals(CallSessionStatus.ACTIVE, friendStateManager.currentState.sessionStatus)

        // Baseline for Instagram was true (locked)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).savedLocked)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)

        // Test A: Supported app (Instagram: unlock during active session)
        currentTime += 1_000L
        val unlockIgReq = controllerIntegrator.requestPolicyUpdate(ManagedApp.INSTAGRAM, targetLocked = false)
        val unlockResult = friendIntegrator.processIncomingMessage(unlockIgReq)
        assertTrue(unlockResult is MessageDispatchResult.Handled)
        val unlockAck = (unlockResult as MessageDispatchResult.Handled).response as ProtocolMessage.PolicyUpdateAck
        assertTrue(unlockAck.success)
        assertFalse(unlockAck.appliedLocked)
        // Effective is now false, but baseline savedLocked remains TRUE!
        assertFalse(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).savedLocked)

        // Test B: Supported app (Documents: lock during active session)
        currentTime += 1_000L
        val lockDocsReq = controllerIntegrator.requestPolicyUpdate(ManagedApp.DOCUMENTS, targetLocked = true)
        val lockDocsResult = friendIntegrator.processIncomingMessage(lockDocsReq)
        assertTrue(lockDocsResult is MessageDispatchResult.Handled)
        val lockDocsAck = (lockDocsResult as MessageDispatchResult.Handled).response as ProtocolMessage.PolicyUpdateAck
        assertTrue(lockDocsAck.success)
        assertTrue(lockDocsAck.appliedLocked)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.DOCUMENTS).effectiveLocked)

        // Test C: Unsupported app key
        currentTime += 1_000L
        val unsupportedReq = controllerIntegrator.requestPolicyUpdate("com.unsupported.malware", targetLocked = false)
        val unsuppResult = friendIntegrator.processIncomingMessage(unsupportedReq)
        assertTrue(unsuppResult is MessageDispatchResult.Handled)
        val unsuppAck = (unsuppResult as MessageDispatchResult.Handled).response as ProtocolMessage.PolicyUpdateAck
        assertFalse(unsuppAck.success)
        assertTrue(unsuppAck.errorMessage!!.contains("Unsupported app key"))
    }

    @Test
    fun policyUpdate_whileSessionIsInactive_rejectedWithoutMutatingBaselinePreferences() {
        completePairingHandshake()
        currentTime += 1_000L

        // Session is INACTIVE
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).savedLocked)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)

        // Remote Controller attempts to unlock Instagram while session is inactive
        val req = controllerIntegrator.requestPolicyUpdate(ManagedApp.INSTAGRAM, targetLocked = false)
        val res = friendIntegrator.processIncomingMessage(req)
        assertTrue(res is MessageDispatchResult.Handled)
        val ack = (res as MessageDispatchResult.Handled).response as ProtocolMessage.PolicyUpdateAck

        assertFalse(ack.success)
        assertTrue(ack.errorMessage!!.contains("no active call session"))
        assertTrue(ack.appliedLocked) // Still locked

        // Verify that saved baseline preferences were NOT mutated
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).savedLocked)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)
    }

    @Test
    fun duplicateCommands_handledIdempotentlyWithConsistentAcks() {
        completePairingHandshake()
        currentTime += 1_000L

        // 1. Send START
        val startReq1 = controllerIntegrator.requestSessionStart()
        val res1 = friendIntegrator.processIncomingMessage(startReq1)
        assertTrue(res1 is MessageDispatchResult.Handled)
        assertEquals(CallSessionStatus.ACTIVE, friendStateManager.currentState.sessionStatus)

        // Send duplicate START (with new valid monotonic sequence number from controller)
        currentTime += 1_000L
        val startReq2 = controllerIntegrator.requestSessionStart()
        val res2 = friendIntegrator.processIncomingMessage(startReq2)
        assertTrue(res2 is MessageDispatchResult.Handled)
        val ack2 = (res2 as MessageDispatchResult.Handled).response as ProtocolMessage.SessionControlAck
        assertTrue(ack2.success)
        assertEquals("ACTIVE", ack2.resultingSessionStatus)
        assertEquals(CallSessionStatus.ACTIVE, friendStateManager.currentState.sessionStatus)

        // 2. Send END
        currentTime += 1_000L
        val endReq1 = controllerIntegrator.requestSessionEnd()
        val endRes1 = friendIntegrator.processIncomingMessage(endReq1)
        assertTrue(endRes1 is MessageDispatchResult.Handled)
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)

        // Send duplicate END
        currentTime += 1_000L
        val endReq2 = controllerIntegrator.requestSessionEnd()
        val endRes2 = friendIntegrator.processIncomingMessage(endReq2)
        assertTrue(endRes2 is MessageDispatchResult.Handled)
        val endAck2 = (endRes2 as MessageDispatchResult.Handled).response as ProtocolMessage.SessionControlAck
        assertTrue(endAck2.success)
        assertEquals("INACTIVE", endAck2.resultingSessionStatus)
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)
    }

    @Test
    fun disconnectAndRevocation_cleanupActiveSessionAndRestoreBaselinePreferences() {
        completePairingHandshake()
        currentTime += 1_000L

        // Start session and apply overrides
        val startReq = controllerIntegrator.requestSessionStart()
        friendIntegrator.processIncomingMessage(startReq)
        currentTime += 1_000L
        val igReq = controllerIntegrator.requestPolicyUpdate(ManagedApp.INSTAGRAM, targetLocked = false)
        friendIntegrator.processIncomingMessage(igReq)

        assertEquals(CallSessionStatus.ACTIVE, friendStateManager.currentState.sessionStatus)
        assertFalse(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).savedLocked)

        // Controller issues Disconnect
        currentTime += 1_000L
        val disconnectMsg = controllerIntegrator.disconnectOrRevoke("User closed session", permanent = false)
        friendIntegrator.processIncomingMessage(disconnectMsg)

        // Friend device session must be ended and baseline restored!
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)
        assertEquals(PairingState.DISCONNECTED, friendEngine.state)
        assertNull(friendEngine.activeSessionToken)
    }

    @Test
    fun transportDisconnection_clearsRemoteSessionAndRestoresBaseline() {
        val (friendTransport, controllerTransport) = LoopbackTransport.createPair()
        friendIntegrator.bindTransport(friendTransport)
        controllerIntegrator.bindTransport(controllerTransport)

        completePairingHandshake()
        currentTime += 1_000L

        // Start session
        val startReq = controllerIntegrator.requestSessionStart()
        friendIntegrator.processIncomingMessage(startReq)
        assertEquals(CallSessionStatus.ACTIVE, friendStateManager.currentState.sessionStatus)

        // Apply session override
        currentTime += 1_000L
        val igReq = controllerIntegrator.requestPolicyUpdate(ManagedApp.INSTAGRAM, targetLocked = false)
        friendIntegrator.processIncomingMessage(igReq)
        assertFalse(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)

        // Physical link drops
        friendIntegrator.handleTransportDisconnected()

        // Active session must be ended and baseline restored!
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)
        assertEquals(PairingState.DISCONNECTED, friendEngine.state)
    }

    @Test
    fun preservationOfExistingLocalDashboardBehavior() {
        completePairingHandshake()

        // 1. Local dashboard starts session
        val startRes = friendStateManager.startSession()
        assertTrue(startRes is com.callcontrol.app.state.SessionTransitionResult.Success)
        assertEquals(CallSessionStatus.ACTIVE, friendStateManager.currentState.sessionStatus)

        // 2. Local toggle while active modifies override only
        val toggledIg = friendStateManager.toggleAppAccess(ManagedApp.INSTAGRAM)
        assertFalse(toggledIg.effectiveLocked) // Was locked, now allowed
        assertTrue(toggledIg.savedLocked)     // Saved baseline preserved as locked

        // 3. Local dashboard ends session
        val endRes = friendStateManager.endSession()
        assertTrue(endRes is com.callcontrol.app.state.SessionTransitionResult.Success)
        assertEquals(CallSessionStatus.INACTIVE, friendStateManager.currentState.sessionStatus)

        // Overrides cleared: Instagram restored to saved locked
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).savedLocked)

        // 4. Local toggle while inactive modifies and persists saved baseline
        val savedToggle = friendStateManager.toggleAppAccess(ManagedApp.INSTAGRAM)
        assertFalse(savedToggle.effectiveLocked)
        assertFalse(savedToggle.savedLocked)
        assertFalse(friendStorage.getSavedLocked(ManagedApp.INSTAGRAM))
    }
}

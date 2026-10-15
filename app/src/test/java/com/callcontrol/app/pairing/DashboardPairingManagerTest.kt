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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DashboardPairingManagerTest {

    private lateinit var friendStorage: InMemoryAppAccessStorage
    private lateinit var friendStateManager: CallControlStateManager
    private lateinit var controllerStorage: InMemoryAppAccessStorage
    private lateinit var controllerStateManager: CallControlStateManager

    private var currentTime: Long = 1_000_000L

    @Before
    fun setUp() {
        currentTime = 1_000_000L

        friendStorage = InMemoryAppAccessStorage()
        friendStateManager = CallControlStateManager(
            storage = friendStorage,
            enforcementStatusProvider = { EnforcementStatus.OS_ENFORCED }
        )

        controllerStorage = InMemoryAppAccessStorage()
        controllerStateManager = CallControlStateManager(
            storage = controllerStorage,
            enforcementStatusProvider = { EnforcementStatus.SIMULATED_POLICY_ONLY }
        )
    }

    private fun waitForCondition(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun initialState_defaultsToFriendDevice_inNotPairedState() {
        val manager = DashboardPairingManager(
            stateManager = friendStateManager,
            localDeviceId = "friend-dev-1",
            initialRole = DeviceRole.FRIEND_DEVICE,
            timeProvider = { currentTime }
        )

        assertEquals(DeviceRole.FRIEND_DEVICE, manager.localRole)
        assertEquals(PairingUiStatus.NOT_PAIRED, manager.currentUiState.status)
        assertEquals(DeviceRole.FRIEND_DEVICE, manager.currentUiState.selectedRole)
        assertFalse(manager.currentUiState.status.isConnected)
        assertNull(manager.currentUiState.activePairingCode)
        assertNull(manager.currentUiState.peerDeviceId)
        assertNull(manager.currentUiState.peerRole)
    }

    @Test
    fun roleSwitching_betweenFriendAndController_succeedsWhenUnpaired() {
        val manager = DashboardPairingManager(
            stateManager = friendStateManager,
            localDeviceId = "friend-dev-1",
            initialRole = DeviceRole.FRIEND_DEVICE,
            timeProvider = { currentTime }
        )

        // Switch to CONTROLLER
        val switchedToController = manager.setRole(DeviceRole.CONTROLLER)
        assertTrue(switchedToController)
        assertEquals(DeviceRole.CONTROLLER, manager.localRole)
        assertEquals(DeviceRole.CONTROLLER, manager.currentUiState.selectedRole)
        assertEquals(PairingUiStatus.NOT_PAIRED, manager.currentUiState.status)

        // Switch back to FRIEND_DEVICE
        val switchedBack = manager.setRole(DeviceRole.FRIEND_DEVICE)
        assertTrue(switchedBack)
        assertEquals(DeviceRole.FRIEND_DEVICE, manager.localRole)
        assertEquals(DeviceRole.FRIEND_DEVICE, manager.currentUiState.selectedRole)
    }

    @Test
    fun roleSwitching_preventedWhenActivelyListeningOrConnected() {
        val (friendTransport, _) = LoopbackTransport.createPair()
        val manager = DashboardPairingManager(
            stateManager = friendStateManager,
            localDeviceId = "friend-dev-1",
            initialRole = DeviceRole.FRIEND_DEVICE,
            transportFactory = { friendTransport },
            timeProvider = { currentTime }
        )

        // Start listening
        val started = manager.startFriendListening(port = 8492)
        assertTrue(started)
        assertEquals(PairingUiStatus.LISTENING, manager.currentUiState.status)

        // Attempt role switch must fail
        val switchAttempt = manager.setRole(DeviceRole.CONTROLLER)
        assertFalse(switchAttempt)
        assertEquals(DeviceRole.FRIEND_DEVICE, manager.localRole)

        // Cancel listening enables role switch again
        manager.cancelFriendListening()
        assertEquals(PairingUiStatus.NOT_PAIRED, manager.currentUiState.status)
        val switchAfterCancel = manager.setRole(DeviceRole.CONTROLLER)
        assertTrue(switchAfterCancel)
        assertEquals(DeviceRole.CONTROLLER, manager.localRole)
    }

    @Test
    fun friendListening_generatesSixDigitCode_andAllowsCancellation() {
        val (friendTransport, _) = LoopbackTransport.createPair()
        val manager = DashboardPairingManager(
            stateManager = friendStateManager,
            localDeviceId = "friend-dev-1",
            initialRole = DeviceRole.FRIEND_DEVICE,
            transportFactory = { friendTransport },
            timeProvider = { currentTime }
        )

        val started = manager.startFriendListening(port = 8492)
        assertTrue(started)
        assertEquals(PairingUiStatus.LISTENING, manager.currentUiState.status)

        val code = manager.currentUiState.activePairingCode
        assertNotNull(code)
        assertEquals(6, code!!.length)
        assertTrue(code.all { it.isDigit() })
        assertEquals(8492, manager.currentUiState.listeningPort)

        // Cancel
        manager.cancelFriendListening()
        assertEquals(PairingUiStatus.NOT_PAIRED, manager.currentUiState.status)
        assertNull(manager.currentUiState.activePairingCode)
    }

    @Test
    fun controllerInitiatesConnection_validatesSixDigitCodeFormat() {
        val manager = DashboardPairingManager(
            stateManager = controllerStateManager,
            localDeviceId = "controller-dev-1",
            initialRole = DeviceRole.CONTROLLER,
            timeProvider = { currentTime }
        )

        // Invalid length (5 digits)
        val shortResult = manager.initiateControllerConnection("127.0.0.1", 8492, "12345")
        assertFalse(shortResult)
        assertEquals(PairingUiStatus.NOT_PAIRED, manager.currentUiState.status)
        assertTrue(manager.currentUiState.feedbackMessage!!.contains("exactly 6 digits"))

        // Invalid length (7 digits)
        val longResult = manager.initiateControllerConnection("127.0.0.1", 8492, "1234567")
        assertFalse(longResult)
        assertEquals(PairingUiStatus.NOT_PAIRED, manager.currentUiState.status)
    }

    @Test
    fun fullPairingLifecycle_connect_approve_andEstablishAuthenticatedSession() {
        val (friendTransport, controllerTransport) = LoopbackTransport.createPair()

        val friendManager = DashboardPairingManager(
            stateManager = friendStateManager,
            localDeviceId = "friend-dev-1",
            initialRole = DeviceRole.FRIEND_DEVICE,
            transportFactory = { friendTransport },
            uiDispatcher = { it.run() },
            timeProvider = { currentTime }
        )

        val controllerManager = DashboardPairingManager(
            stateManager = controllerStateManager,
            localDeviceId = "controller-dev-1",
            initialRole = DeviceRole.CONTROLLER,
            transportFactory = { controllerTransport },
            uiDispatcher = { it.run() },
            timeProvider = { currentTime }
        )

        // 1. Friend starts listening
        friendManager.startFriendListening(8492)
        assertEquals(PairingUiStatus.LISTENING, friendManager.currentUiState.status)
        val code = friendManager.currentUiState.activePairingCode!!

        // 2. Controller enters code and initiates connection
        controllerManager.initiateControllerConnection("127.0.0.1", 8492, code)

        // 3. Friend receives PairRequest and enters WAITING_APPROVAL
        waitForCondition {
            friendManager.currentUiState.status == PairingUiStatus.WAITING_APPROVAL
        }
        assertEquals("controller-dev-1", friendManager.currentUiState.peerDeviceId)

        // 4. Friend explicitly approves
        val approved = friendManager.approvePendingPairing()
        assertTrue(approved)
        assertEquals(PairingUiStatus.CONNECTED, friendManager.currentUiState.status)
        assertEquals(DeviceRole.CONTROLLER, friendManager.currentUiState.peerRole)

        // 5. Controller receives PairResponse and transitions to CONNECTED
        waitForCondition {
            controllerManager.currentUiState.status == PairingUiStatus.CONNECTED
        }
        assertEquals("friend-dev-1", controllerManager.currentUiState.peerDeviceId)
        assertEquals(DeviceRole.FRIEND_DEVICE, controllerManager.currentUiState.peerRole)

        // 6. Controller triggers remote session START
        currentTime += 1_000L
        val startRequested = controllerManager.controllerRequestSessionStart()
        assertTrue(startRequested)

        waitForCondition {
            friendStateManager.currentState.sessionStatus == CallSessionStatus.ACTIVE &&
                    controllerManager.currentUiState.remoteSessionStatus == CallSessionStatus.ACTIVE
        }

        // 7. Controller triggers remote policy toggle for Instagram (from locked to allowed)
        currentTime += 1_000L
        assertTrue(friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)
        val policyRequested = controllerManager.controllerRequestPolicyToggle(ManagedApp.INSTAGRAM)
        assertTrue(policyRequested)

        waitForCondition {
            !friendStateManager.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked &&
                    controllerManager.currentUiState.remoteAppStates[ManagedApp.INSTAGRAM] == false
        }

        // 8. Controller triggers remote session END
        currentTime += 1_000L
        val endRequested = controllerManager.controllerRequestSessionEnd()
        assertTrue(endRequested)

        waitForCondition {
            friendStateManager.currentState.sessionStatus == CallSessionStatus.INACTIVE &&
                    controllerManager.currentUiState.remoteSessionStatus == CallSessionStatus.INACTIVE
        }

        // 9. Friend disconnects
        friendManager.disconnectOrRevoke(permanent = false)
        assertEquals(PairingUiStatus.DISCONNECTED, friendManager.currentUiState.status)

        waitForCondition {
            controllerManager.currentUiState.status == PairingUiStatus.DISCONNECTED
        }
    }

    @Test
    fun friendRejection_transitionsBothPartiesToRejected() {
        val (friendTransport, controllerTransport) = LoopbackTransport.createPair()

        val friendManager = DashboardPairingManager(
            stateManager = friendStateManager,
            localDeviceId = "friend-dev-1",
            initialRole = DeviceRole.FRIEND_DEVICE,
            transportFactory = { friendTransport },
            uiDispatcher = { it.run() },
            timeProvider = { currentTime }
        )

        val controllerManager = DashboardPairingManager(
            stateManager = controllerStateManager,
            localDeviceId = "controller-dev-1",
            initialRole = DeviceRole.CONTROLLER,
            transportFactory = { controllerTransport },
            uiDispatcher = { it.run() },
            timeProvider = { currentTime }
        )

        friendManager.startFriendListening(8492)
        val code = friendManager.currentUiState.activePairingCode!!

        controllerManager.initiateControllerConnection("127.0.0.1", 8492, code)

        waitForCondition {
            friendManager.currentUiState.status == PairingUiStatus.WAITING_APPROVAL
        }

        // Friend explicitly declines
        val rejected = friendManager.rejectPendingPairing("Decline by user")
        assertTrue(rejected)
        assertEquals(PairingUiStatus.REJECTED, friendManager.currentUiState.status)

        waitForCondition {
            controllerManager.currentUiState.status == PairingUiStatus.REJECTED
        }
        assertFalse(controllerManager.currentUiState.status.isConnected)
    }

    @Test
    fun permanentRevocation_transitionsToRevoked_andCleansUpCredentials() {
        val (friendTransport, controllerTransport) = LoopbackTransport.createPair()

        val friendManager = DashboardPairingManager(
            stateManager = friendStateManager,
            localDeviceId = "friend-dev-1",
            initialRole = DeviceRole.FRIEND_DEVICE,
            transportFactory = { friendTransport },
            uiDispatcher = { it.run() },
            timeProvider = { currentTime }
        )

        val controllerManager = DashboardPairingManager(
            stateManager = controllerStateManager,
            localDeviceId = "controller-dev-1",
            initialRole = DeviceRole.CONTROLLER,
            transportFactory = { controllerTransport },
            uiDispatcher = { it.run() },
            timeProvider = { currentTime }
        )

        friendManager.startFriendListening(8492)
        val code = friendManager.currentUiState.activePairingCode!!
        controllerManager.initiateControllerConnection("127.0.0.1", 8492, code)

        waitForCondition {
            friendManager.currentUiState.status == PairingUiStatus.WAITING_APPROVAL
        }
        friendManager.approvePendingPairing()

        waitForCondition {
            friendManager.currentUiState.status == PairingUiStatus.CONNECTED &&
                    controllerManager.currentUiState.status == PairingUiStatus.CONNECTED
        }

        // Controller executes permanent revocation
        controllerManager.disconnectOrRevoke(permanent = true)
        assertEquals(PairingUiStatus.REVOKED, controllerManager.currentUiState.status)

        waitForCondition {
            friendManager.currentUiState.status == PairingUiStatus.REVOKED
        }
        assertEquals(PairingState.REVOKED, friendManager.handshakeEngine.state)
        assertEquals(PairingState.REVOKED, controllerManager.handshakeEngine.state)
    }

    @Test
    fun remoteCommands_rejectedWhenNotConnected() {
        val manager = DashboardPairingManager(
            stateManager = controllerStateManager,
            localDeviceId = "controller-dev-1",
            initialRole = DeviceRole.CONTROLLER,
            timeProvider = { currentTime }
        )

        assertFalse(manager.controllerRequestSessionStart())
        assertFalse(manager.controllerRequestSessionEnd())
        assertFalse(manager.controllerRequestPolicyToggle(ManagedApp.YOUTUBE))
        assertTrue(manager.currentUiState.feedbackMessage!!.contains("Not connected"))
    }
}

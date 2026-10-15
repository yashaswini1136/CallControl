package com.callcontrol.app.network

import com.callcontrol.app.pairing.DeviceRole
import com.callcontrol.app.pairing.ProtocolMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoopbackTransportTest {

    @Test
    fun loopbackPair_connectsAndDeliversMessagesBidirectionally() {
        val (alice, bob) = LoopbackTransport.createPair()

        var aliceState = TransportState.DISCONNECTED
        var bobState = TransportState.DISCONNECTED
        var messageReceivedByBob: ProtocolMessage? = null
        val latch = CountDownLatch(1)

        alice.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                aliceState = newState
            }
            override fun onMessageReceived(message: ProtocolMessage) {}
            override fun onError(throwable: Throwable) {}
        })

        bob.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                bobState = newState
            }
            override fun onMessageReceived(message: ProtocolMessage) {
                messageReceivedByBob = message
                latch.countDown()
            }
            override fun onError(throwable: Throwable) {}
        })

        // Bob starts listening
        bob.startListening(8492)
        assertEquals(TransportState.LISTENING, bob.currentState)

        // Alice connects
        val connected = alice.connect("127.0.0.1", 8492)
        assertTrue(connected)
        assertEquals(TransportState.CONNECTED, alice.currentState)
        assertEquals(TransportState.CONNECTED, bob.currentState)

        // Alice sends message to Bob
        val msg = ProtocolMessage.PairRequest(
            timestampMs = 1000L,
            senderDeviceId = "alice-dev",
            clientRole = DeviceRole.CONTROLLER,
            pairingId = "pair-1",
            submittedCode = "123456",
            clientNonce = "nonce"
        )
        alice.sendMessage(msg)

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(msg.messageId, messageReceivedByBob?.messageId)

        // Disconnecting alice disconnects bob
        alice.disconnect()
        assertEquals(TransportState.DISCONNECTED, alice.currentState)
        assertEquals(TransportState.DISCONNECTED, bob.currentState)
    }
}

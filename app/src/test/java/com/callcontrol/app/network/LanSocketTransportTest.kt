package com.callcontrol.app.network

import com.callcontrol.app.pairing.DeviceRole
import com.callcontrol.app.pairing.ProtocolMessage
import com.callcontrol.app.pairing.SessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LanSocketTransportTest {

    private fun findAvailablePort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    @Test
    fun localTcpTransport_connectsAndTransfersEncryptedMessagesSuccessfully() {
        val port = findAvailablePort()
        val server = LanSocketTransport(enableEncryption = true)
        val client = LanSocketTransport(enableEncryption = true)

        val serverConnectedLatch = CountDownLatch(1)
        val clientConnectedLatch = CountDownLatch(1)
        val serverMsgLatch = CountDownLatch(1)
        val clientMsgLatch = CountDownLatch(1)

        var receivedByServer: ProtocolMessage? = null
        var receivedByClient: ProtocolMessage? = null

        server.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.CONNECTED) serverConnectedLatch.countDown()
            }
            override fun onMessageReceived(message: ProtocolMessage) {
                receivedByServer = message
                serverMsgLatch.countDown()
            }
            override fun onError(throwable: Throwable) {}
        })

        client.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.CONNECTED) clientConnectedLatch.countDown()
            }
            override fun onMessageReceived(message: ProtocolMessage) {
                receivedByClient = message
                clientMsgLatch.countDown()
            }
            override fun onError(throwable: Throwable) {}
        })

        // Server listens on localhost
        server.startListening(port, "127.0.0.1")
        assertEquals(TransportState.LISTENING, server.currentState)

        // Client connects
        client.connect("127.0.0.1", port)

        assertTrue("Server should connect within 5s", serverConnectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Client should connect within 5s", clientConnectedLatch.await(5, TimeUnit.SECONDS))

        assertTrue(server.isConnected)
        assertTrue(client.isConnected)

        // Client -> Server
        val clientMsg = ProtocolMessage.PairRequest(
            messageId = "client-msg-1",
            timestampMs = 1000L,
            sequenceNumber = 0L,
            senderDeviceId = "client-phone",
            clientRole = DeviceRole.CONTROLLER,
            pairingId = "pair-99",
            submittedCode = "482910",
            clientNonce = "client-nonce-xyz"
        )
        client.sendMessage(clientMsg)
        assertTrue("Server should receive client message", serverMsgLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedByServer)
        assertEquals(clientMsg.messageId, receivedByServer!!.messageId)

        // Server -> Client
        val serverMsg = ProtocolMessage.SessionControlAck(
            messageId = "server-msg-1",
            timestampMs = 2000L,
            sequenceNumber = 1L,
            senderDeviceId = "server-phone",
            sessionToken = "tok-abc",
            resultingSessionStatus = "ACTIVE",
            success = true
        )
        server.sendMessage(serverMsg)
        assertTrue("Client should receive server message", clientMsgLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedByClient)
        assertEquals(serverMsg.messageId, receivedByClient!!.messageId)

        // Clean disconnect
        client.disconnect()
        server.disconnect()

        assertFalse(client.isConnected)
        assertFalse(server.isConnected)
    }

    @Test
    fun connect_failsGracefullyWhenServerNotListening() {
        val unusedPort = findAvailablePort()
        val client = LanSocketTransport(enableEncryption = false)
        val failedLatch = CountDownLatch(1)

        client.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.FAILED) failedLatch.countDown()
            }
            override fun onMessageReceived(message: ProtocolMessage) {}
            override fun onError(throwable: Throwable) {}
        })

        client.connect("127.0.0.1", unusedPort, timeoutMs = 1000)
        assertTrue("Should report failure when port is refused", failedLatch.await(3, TimeUnit.SECONDS))
        assertEquals(TransportState.FAILED, client.currentState)
        client.disconnect()
    }
}

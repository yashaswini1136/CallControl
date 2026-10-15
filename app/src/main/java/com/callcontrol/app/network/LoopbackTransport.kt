package com.callcontrol.app.network

import com.callcontrol.app.pairing.ProtocolMessage
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Pure in-memory [DeviceTransport] for deterministic, lightning-fast unit tests.
 *
 * Guarantees:
 * - Does not bind to any network sockets or require network permissions.
 * - Allows simulating packet delivery, delays, and disconnects reliably in test suites.
 */
class LoopbackTransport private constructor(
    private val executor: Executor = Executors.newSingleThreadExecutor()
) : DeviceTransport {

    @Volatile
    override var currentState: TransportState = TransportState.DISCONNECTED
        private set

    private var listener: TransportListener? = null
    private var peer: LoopbackTransport? = null

    @Synchronized
    override fun setListener(listener: TransportListener?) {
        this.listener = listener
    }

    @Synchronized
    override fun startListening(port: Int, bindAddress: String): Boolean {
        currentState = TransportState.LISTENING
        listener?.onStateChanged(TransportState.LISTENING, "Loopback listening on port $port")
        return true
    }

    @Synchronized
    override fun connect(host: String, port: Int, timeoutMs: Int): Boolean {
        currentState = TransportState.CONNECTING
        listener?.onStateChanged(TransportState.CONNECTING, "Loopback connecting...")

        val targetPeer = peer
        if (targetPeer != null && targetPeer.currentState == TransportState.LISTENING) {
            currentState = TransportState.CONNECTED
            targetPeer.currentState = TransportState.CONNECTED
            listener?.onStateChanged(TransportState.CONNECTED, "Loopback connected to peer")
            targetPeer.listener?.onStateChanged(TransportState.CONNECTED, "Loopback accepted peer")
            return true
        } else {
            currentState = TransportState.FAILED
            listener?.onStateChanged(TransportState.FAILED, "Loopback peer not listening")
            return false
        }
    }

    @Synchronized
    override fun sendMessage(message: ProtocolMessage): Boolean {
        if (!isConnected) return false
        val targetPeer = peer ?: return false

        executor.execute {
            targetPeer.listener?.onMessageReceived(message)
        }
        return true
    }

    @Synchronized
    override fun disconnect() {
        if (currentState != TransportState.DISCONNECTED) {
            currentState = TransportState.DISCONNECTED
            listener?.onStateChanged(TransportState.DISCONNECTED, "Loopback disconnected")

            val targetPeer = peer
            if (targetPeer != null && targetPeer.currentState != TransportState.DISCONNECTED) {
                targetPeer.currentState = TransportState.DISCONNECTED
                executor.execute {
                    targetPeer.listener?.onStateChanged(TransportState.DISCONNECTED, "Loopback disconnected")
                }
            }
        }
    }

    override fun getChannelBindingToken(): String? = "loopback-token"

    override fun computeChannelAuthProof(pairingCode: String, saltHex: String, forClient: Boolean): String? {
        val role = if (forClient) "CLIENT" else "SERVER"
        return "loopback-proof-$role-$pairingCode-$saltHex"
    }

    override fun verifyChannelAuthProof(
        pairingCode: String,
        saltHex: String,
        proofHex: String?,
        isClientProof: Boolean
    ): Boolean {
        if (proofHex.isNullOrBlank()) return false
        val expected = computeChannelAuthProof(pairingCode, saltHex, forClient = isClientProof)
        return proofHex == expected || (isClientProof && proofHex == computeChannelAuthProof(pairingCode, "", forClient = true))
    }

    companion object {
        /**
         * Creates a connected pair of in-memory transports for testing.
         */
        fun createPair(): Pair<LoopbackTransport, LoopbackTransport> {
            val a = LoopbackTransport()
            val b = LoopbackTransport()
            a.peer = b
            b.peer = a
            return Pair(a, b)
        }
    }
}

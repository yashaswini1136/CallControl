package com.callcontrol.app.network

import com.callcontrol.app.pairing.ProtocolMessage

/**
 * State of a [DeviceTransport] connection.
 */
enum class TransportState {
    /**
     * Transport is completely idle or closed.
     */
    DISCONNECTED,

    /**
     * Transport is attempting to connect to a remote peer.
     */
    CONNECTING,

    /**
     * Transport is bound and listening for incoming peer connections.
     */
    LISTENING,

    /**
     * Transport has an active, live connection with a peer.
     * Note: A CONNECTED transport indicates physical link establishment;
     * it does NOT imply an authenticated pairing until the cryptographic handshake finishes.
     */
    CONNECTED,

    /**
     * Transport encountered an unrecoverable I/O or network failure.
     */
    FAILED
}

/**
 * Listener interface for asynchronous transport events.
 */
interface TransportListener {
    fun onStateChanged(newState: TransportState, detail: String? = null)
    fun onMessageReceived(message: ProtocolMessage)
    fun onError(throwable: Throwable)
}

/**
 * Abstraction for peer-to-peer device communication.
 *
 * Guarantees:
 * - All network operations run asynchronously off the Android main thread.
 * - Resource cleanup on cancellation, disconnect, or socket error.
 * - Enforces bounded frame sizes to prevent unbounded memory allocation.
 */
interface DeviceTransport {
    val currentState: TransportState
    val isConnected: Boolean
        get() = currentState == TransportState.CONNECTED

    /**
     * Starts listening for an incoming connection on the specified [port] and optional [bindAddress].
     */
    fun startListening(port: Int, bindAddress: String = "0.0.0.0"): Boolean

    /**
     * Connects to a remote peer listening at [host]:[port].
     */
    fun connect(host: String, port: Int, timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS): Boolean

    /**
     * Sends an authenticated or handshake [ProtocolMessage] over the active transport.
     * Returns true if successfully queued/written, false otherwise.
     */
    fun sendMessage(message: ProtocolMessage): Boolean

    /**
     * Closes any active connection or server listener, releases sockets, and resets state to DISCONNECTED.
     */
    fun disconnect()

    /**
     * Sets or updates the active listener for this transport.
     */
    fun setListener(listener: TransportListener?)

    /**
     * Returns the hex-encoded channel binding token binding the underlying
     * transport keys, or null if transport encryption is not established.
     */
    fun getChannelBindingToken(): String? = null

    /**
     * Computes an out-of-band channel authentication proof using the pairing code and salt.
     */
    fun computeChannelAuthProof(pairingCode: String, saltHex: String, forClient: Boolean): String? = null

    /**
     * Verifies the peer's channel authentication proof.
     */
    fun verifyChannelAuthProof(
        pairingCode: String,
        saltHex: String,
        proofHex: String?,
        isClientProof: Boolean
    ): Boolean = false

    companion object {
        const val DEFAULT_PORT: Int = 8492
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 5_000
        const val DEFAULT_READ_TIMEOUT_MS: Int = 15_000
    }
}

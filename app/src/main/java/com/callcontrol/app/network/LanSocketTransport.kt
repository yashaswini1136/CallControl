package com.callcontrol.app.network

import com.callcontrol.app.pairing.ProtocolMessage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asynchronous, authenticated TCP transport for peer-to-peer Wi-Fi LAN communication.
 *
 * Architecture & Safeguards:
 * - Network I/O is dispatched completely off the Android main thread via background worker executors.
 * - Enforces length-prefixed framing and strict 64 KB memory bounds via [MessageFramer].
 * - Uses explicit connect (5s) and read timeouts (15s) to detect stalled/dead connections.
 * - Single-peer concurrency limit: rejects simultaneous connection attempts when already connected.
 * - Transport-layer security: automatically performs ephemeral ECDH key exchange to encrypt
 *   all subsequent frames with AES-256-GCM.
 * - Comprehensive resource release on error or disconnect.
 */
class LanSocketTransport(
    private val enableEncryption: Boolean = true
) : DeviceTransport {

    @Volatile
    override var currentState: TransportState = TransportState.DISCONNECTED
        private set

    private var listener: TransportListener? = null

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null

    private var inputStream: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null

    private var executor: ExecutorService? = null
    private val isRunning = AtomicBoolean(false)

    private var transportEncryption: TransportEncryption? = null

    @Synchronized
    override fun setListener(listener: TransportListener?) {
        this.listener = listener
    }

    @Synchronized
    override fun startListening(port: Int, bindAddress: String): Boolean {
        disconnect()

        val server = try {
            val address = InetAddress.getByName(bindAddress)
            ServerSocket(port, 50, address)
        } catch (e: Exception) {
            updateState(TransportState.FAILED, "Server failed to bind: ${e.message}")
            notifyError(e)
            return false
        }

        serverSocket = server
        val newExecutor = Executors.newCachedThreadPool()
        executor = newExecutor
        isRunning.set(true)
        updateState(TransportState.LISTENING, "Listening on $bindAddress:$port")

        newExecutor.execute {
            try {
                while (isRunning.get() && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        synchronized(this@LanSocketTransport) {
                            if (activeSocket != null && !activeSocket!!.isClosed) {
                                // Already connected to a peer; reject concurrent connection
                                try { clientSocket.close() } catch (_: Exception) {}
                                return@synchronized
                            }
                            activeSocket = clientSocket
                        }
                        handleIncomingConnection(clientSocket)
                    } catch (e: IOException) {
                        if (isRunning.get() && !server.isClosed) {
                            notifyError(e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    notifyError(e)
                }
            }
        }
        return true
    }

    @Synchronized
    override fun connect(host: String, port: Int, timeoutMs: Int): Boolean {
        disconnect()

        val newExecutor = Executors.newCachedThreadPool()
        executor = newExecutor
        isRunning.set(true)
        updateState(TransportState.CONNECTING, "Connecting to $host:$port...")

        newExecutor.execute {
            val socket = Socket()
            try {
                socket.soTimeout = DeviceTransport.DEFAULT_READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(host, port), timeoutMs)

                synchronized(this@LanSocketTransport) {
                    if (!isRunning.get()) {
                        socket.close()
                        return@execute
                    }
                    activeSocket = socket
                }
                setupSocketIo(socket, isInitiator = true)
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                updateState(TransportState.FAILED, "Connection failed: ${e.message}")
                notifyError(e)
            }
        }
        return true
    }

    @Synchronized
    override fun sendMessage(message: ProtocolMessage): Boolean {
        if (!isConnected) return false
        val out = outputStream ?: return false
        val enc = transportEncryption

        executor?.execute {
            try {
                val serializedBytes = ProtocolMessageJsonSerializer.serialize(message)
                val wirePayload = if (enableEncryption && enc != null && enc.isEstablished) {
                    enc.encrypt(serializedBytes)
                } else {
                    serializedBytes
                }
                synchronized(out) {
                    MessageFramer.writeFrame(out, wirePayload)
                }
            } catch (e: Exception) {
                notifyError(e)
                disconnect()
            }
        } ?: return false

        return true
    }

    @Synchronized
    override fun disconnect() {
        isRunning.set(false)

        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            inputStream?.close()
        } catch (_: Exception) {}
        inputStream = null

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null

        transportEncryption?.zeroize()
        transportEncryption = null

        executor?.let { exec ->
            exec.shutdownNow()
            try {
                exec.awaitTermination(200, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
        }
        executor = null

        if (currentState != TransportState.DISCONNECTED) {
            updateState(TransportState.DISCONNECTED, "Transport closed")
        }
    }

    override fun getChannelBindingToken(): String? {
        return transportEncryption?.channelBindingToken
    }

    override fun computeChannelAuthProof(pairingCode: String, saltHex: String, forClient: Boolean): String? {
        return transportEncryption?.computeChannelAuthProof(pairingCode, saltHex, forClient)
    }

    override fun verifyChannelAuthProof(
        pairingCode: String,
        saltHex: String,
        proofHex: String?,
        isClientProof: Boolean
    ): Boolean {
        return transportEncryption?.verifyChannelAuthProof(pairingCode, saltHex, proofHex, isClientProof) ?: false
    }

    private fun handleIncomingConnection(socket: Socket) {
        setupSocketIo(socket, isInitiator = false)
    }

    private fun setupSocketIo(socket: Socket, isInitiator: Boolean) {
        try {
            socket.soTimeout = DeviceTransport.DEFAULT_READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = BufferedOutputStream(socket.getOutputStream(), 8192)

            synchronized(this) {
                inputStream = input
                outputStream = output
            }

            if (enableEncryption) {
                val enc = TransportEncryption(isInitiator = isInitiator)
                transportEncryption = enc

                if (isInitiator) {
                    // Client sends public key, then reads server public key
                    MessageFramer.writeFrame(output, enc.localPublicKeyEncoded)
                    val serverKeyBytes = MessageFramer.readFrame(input)
                        ?: throw EOFException("Server closed connection during key exchange")
                    enc.completeKeyExchange(serverKeyBytes)
                } else {
                    // Server reads client public key, sends server public key
                    val clientKeyBytes = MessageFramer.readFrame(input)
                        ?: throw EOFException("Client closed connection during key exchange")
                    MessageFramer.writeFrame(output, enc.localPublicKeyEncoded)
                    enc.completeKeyExchange(clientKeyBytes)
                }
            }

            updateState(TransportState.CONNECTED, "Connected to ${socket.remoteSocketAddress}")

            // Start reading incoming frames
            runReadLoop(input)
        } catch (e: Exception) {
            if (isRunning.get()) {
                notifyError(e)
                disconnect()
            }
        }
    }

    private fun runReadLoop(input: BufferedInputStream) {
        val enc = transportEncryption

        while (isRunning.get()) {
            try {
                val frameBytes = MessageFramer.readFrame(input)
                if (frameBytes == null) {
                    // Peer disconnected gracefully
                    break
                }

                val decryptedBytes = if (enableEncryption && enc != null && enc.isEstablished) {
                    enc.decrypt(frameBytes)
                } else {
                    frameBytes
                }

                val message = ProtocolMessageJsonSerializer.deserialize(decryptedBytes)
                listener?.onMessageReceived(message)
            } catch (_: SocketTimeoutException) {
                // Read timeout fired; socket still alive, continue listening
                continue
            } catch (e: EOFException) {
                // Peer closed stream
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    notifyError(e)
                    break
                }
            }
        }

        if (isRunning.get()) {
            disconnect()
        }
    }

    private fun updateState(newState: TransportState, detail: String?) {
        currentState = newState
        listener?.onStateChanged(newState, detail)
    }

    private fun notifyError(throwable: Throwable) {
        listener?.onError(throwable)
    }
}

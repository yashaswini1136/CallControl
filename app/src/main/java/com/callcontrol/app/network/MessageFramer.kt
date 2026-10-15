package com.callcontrol.app.network

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

class FrameException(message: String) : Exception(message)

/**
 * Robust, bounded length-prefixed message framer for TCP streams.
 *
 * Frame Format:
 * [4 bytes big-endian length N] [N bytes payload]
 *
 * Security:
 * - Rejects frame lengths <= 0 or > [MAX_FRAME_SIZE] (64 KB) prior to buffer allocation,
 *   preventing remote OutOfMemory and denial-of-service exploits.
 * - Handles partial socket reads cleanly by looping until the requested byte count is filled.
 * - Detects clean EOF at frame boundaries without throwing an error.
 */
object MessageFramer {
    const val HEADER_SIZE_BYTES: Int = 4
    const val MIN_FRAME_SIZE: Int = 2
    const val MAX_FRAME_SIZE: Int = 64 * 1024 // 64 KB

    /**
     * Writes a length-prefixed frame to [outputStream] and flushes the stream.
     */
    @Synchronized
    fun writeFrame(outputStream: OutputStream, payloadBytes: ByteArray) {
        val length = payloadBytes.size
        if (length < MIN_FRAME_SIZE || length > MAX_FRAME_SIZE) {
            throw FrameException("Payload size $length out of valid bounds ($MIN_FRAME_SIZE..$MAX_FRAME_SIZE)")
        }

        // 4 bytes big-endian integer length
        outputStream.write((length ushr 24) and 0xFF)
        outputStream.write((length ushr 16) and 0xFF)
        outputStream.write((length ushr 8) and 0xFF)
        outputStream.write(length and 0xFF)

        outputStream.write(payloadBytes)
        outputStream.flush()
    }

    /**
     * Reads a length-prefixed frame from [inputStream].
     * Returns null on clean EOF (connection closed between frames).
     * Throws [FrameException] if the length header is invalid or oversized.
     * Throws [EOFException] if connection closes mid-frame.
     */
    fun readFrame(inputStream: InputStream): ByteArray? {
        val header = ByteArray(HEADER_SIZE_BYTES)
        var bytesRead = 0

        // Read 4-byte header
        while (bytesRead < HEADER_SIZE_BYTES) {
            val count = inputStream.read(header, bytesRead, HEADER_SIZE_BYTES - bytesRead)
            if (count == -1) {
                if (bytesRead == 0) {
                    return null // Clean EOF at frame boundary
                } else {
                    throw EOFException("Connection closed while reading frame header (read $bytesRead of $HEADER_SIZE_BYTES)")
                }
            }
            bytesRead += count
        }

        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)

        if (length < MIN_FRAME_SIZE || length > MAX_FRAME_SIZE) {
            throw FrameException("Invalid frame length: $length. Bounds: $MIN_FRAME_SIZE..$MAX_FRAME_SIZE bytes")
        }

        // Bounded allocation
        val payload = ByteArray(length)
        var payloadRead = 0
        while (payloadRead < length) {
            val count = inputStream.read(payload, payloadRead, length - payloadRead)
            if (count == -1) {
                throw EOFException("Connection closed while reading frame payload (read $payloadRead of $length)")
            }
            payloadRead += count
        }

        return payload
    }
}

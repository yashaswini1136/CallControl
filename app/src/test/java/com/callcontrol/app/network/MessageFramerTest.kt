package com.callcontrol.app.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

class MessageFramerTest {

    @Test
    fun writeAndReadFrame_succeedsForValidPayload() {
        val payload = "Hello, CallControl secure framing!".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()

        MessageFramer.writeFrame(out, payload)
        val frameBytes = out.toByteArray()

        assertEquals(payload.size + 4, frameBytes.size)

        val input = ByteArrayInputStream(frameBytes)
        val decoded = MessageFramer.readFrame(input)

        assertArrayEquals(payload, decoded)
    }

    @Test
    fun readFrame_handlesPartialReadsSeamlessly() {
        val payload = "Testing partial stream chunking".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        MessageFramer.writeFrame(out, payload)
        val fullData = out.toByteArray()

        // InputStream that delivers only 1 byte at a time
        val chunkedStream = object : InputStream() {
            var index = 0
            override fun read(): Int {
                return if (index < fullData.size) {
                    fullData[index++].toInt() and 0xFF
                } else {
                    -1
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= fullData.size) return -1
                // Deliver at most 1 byte per call
                val byte = read()
                b[off] = byte.toByte()
                return 1
            }
        }

        val decoded = MessageFramer.readFrame(chunkedStream)
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun readFrame_returnsNullOnCleanEof() {
        val emptyInput = ByteArrayInputStream(ByteArray(0))
        val result = MessageFramer.readFrame(emptyInput)
        assertNull(result)
    }

    @Test
    fun readFrame_throwsEofExceptionOnTruncatedPayload() {
        val out = ByteArrayOutputStream()
        // Header says 100 bytes, but we only supply 10 bytes
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(100)
        out.write(ByteArray(10))

        val input = ByteArrayInputStream(out.toByteArray())
        try {
            MessageFramer.readFrame(input)
            fail("Expected EOFException on truncated stream")
        } catch (_: EOFException) {
            // Success
        }
    }

    @Test
    fun readFrame_rejectsOversizedLengthHeaderBeforeAllocation() {
        val out = ByteArrayOutputStream()
        val oversized = MessageFramer.MAX_FRAME_SIZE + 1
        out.write((oversized ushr 24) and 0xFF)
        out.write((oversized ushr 16) and 0xFF)
        out.write((oversized ushr 8) and 0xFF)
        out.write(oversized and 0xFF)

        val input = ByteArrayInputStream(out.toByteArray())
        try {
            MessageFramer.readFrame(input)
            fail("Expected FrameException on oversized frame")
        } catch (e: FrameException) {
            assertTrue(e.message!!.contains("Invalid frame length"))
        }
    }

    @Test
    fun writeFrame_rejectsOversizedPayload() {
        val hugePayload = ByteArray(MessageFramer.MAX_FRAME_SIZE + 1)
        val out = ByteArrayOutputStream()
        try {
            MessageFramer.writeFrame(out, hugePayload)
            fail("Expected FrameException on write of oversized payload")
        } catch (e: FrameException) {
            assertTrue(e.message!!.contains("out of valid bounds"))
        }
    }
}

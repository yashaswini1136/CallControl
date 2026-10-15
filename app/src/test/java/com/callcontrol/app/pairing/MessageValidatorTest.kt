package com.callcontrol.app.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageValidatorTest {

    private val secret = MessageSecurity.deriveSharedSecret("654321", "saltsalt", "cNonce", "sNonce")
    private val sessionToken = "session-token-xyz"
    private val validator = MessageValidator(maxClockDriftMs = 60_000L)

    @Test
    fun validMessage_passesValidation() {
        val now = 100_000L
        val unsigned = ProtocolMessage.PolicyUpdateRequest(
            messageId = "msg-101",
            timestampMs = now,
            sequenceNumber = 1L,
            senderDeviceId = "controller-dev",
            sessionToken = sessionToken,
            appStorageKey = "instagram",
            targetLocked = true
        )
        val signed = unsigned.copy(
            signature = MessageSecurity.sign(unsigned.canonicalData(), secret)
        )

        val result = validator.validateMessage(
            message = signed,
            sharedSecret = secret,
            expectedSessionToken = sessionToken,
            currentTimeMs = now
        )
        assertTrue(result is MessageValidationResult.Valid)
    }

    @Test
    fun expiredTimestamp_isRejected() {
        val messageTime = 100_000L
        val currentTime = 200_000L // 100s later, exceeds 60s max drift

        val unsigned = ProtocolMessage.Heartbeat(
            messageId = "msg-102",
            timestampMs = messageTime,
            sequenceNumber = 2L,
            senderDeviceId = "controller-dev",
            sessionToken = sessionToken
        )
        val signed = unsigned.copy(
            signature = MessageSecurity.sign(unsigned.canonicalData(), secret)
        )

        val result = validator.validateMessage(
            message = signed,
            sharedSecret = secret,
            expectedSessionToken = sessionToken,
            currentTimeMs = currentTime
        )
        assertTrue(result is MessageValidationResult.ExpiredTimestamp)
    }

    @Test
    fun duplicateMessageId_isRejectedAsReplay() {
        val now = 100_000L
        val unsigned1 = ProtocolMessage.Heartbeat(
            messageId = "duplicate-id-1",
            timestampMs = now,
            sequenceNumber = 1L,
            senderDeviceId = "controller-dev",
            sessionToken = sessionToken
        )
        val signed1 = unsigned1.copy(
            signature = MessageSecurity.sign(unsigned1.canonicalData(), secret)
        )

        val firstPass = validator.validateMessage(
            message = signed1,
            sharedSecret = secret,
            expectedSessionToken = sessionToken,
            currentTimeMs = now
        )
        assertTrue(firstPass is MessageValidationResult.Valid)

        // Resending exact same message ID must be detected as replay
        val duplicateAttempt = validator.validateMessage(
            message = signed1,
            sharedSecret = secret,
            expectedSessionToken = sessionToken,
            currentTimeMs = now + 500L
        )
        assertTrue(duplicateAttempt is MessageValidationResult.ReplayDetected)
    }

    @Test
    fun outOfOrderOrDuplicateSequenceNumber_isRejectedAsReplay() {
        val now = 100_000L
        val v = MessageValidator(maxClockDriftMs = 60_000L)

        // Message with sequenceNumber = 5
        val msg5 = ProtocolMessage.PolicyUpdateRequest(
            messageId = "msg-5",
            timestampMs = now,
            sequenceNumber = 5L,
            senderDeviceId = "controller-dev",
            sessionToken = sessionToken,
            appStorageKey = "youtube",
            targetLocked = true
        ).let { it.copy(signature = MessageSecurity.sign(it.canonicalData(), secret)) }

        val res5 = v.validateMessage(msg5, secret, sessionToken, now)
        assertTrue(res5 is MessageValidationResult.Valid)

        // Subsequent message with lower sequenceNumber = 4 must be rejected
        val msg4 = ProtocolMessage.PolicyUpdateRequest(
            messageId = "msg-4",
            timestampMs = now + 10L,
            sequenceNumber = 4L,
            senderDeviceId = "controller-dev",
            sessionToken = sessionToken,
            appStorageKey = "youtube",
            targetLocked = true
        ).let { it.copy(signature = MessageSecurity.sign(it.canonicalData(), secret)) }

        val res4 = v.validateMessage(msg4, secret, sessionToken, now + 10L)
        assertTrue(res4 is MessageValidationResult.ReplayDetected)

        // Duplicate sequenceNumber = 5 with new messageId must also be rejected
        val msg5Dup = ProtocolMessage.PolicyUpdateRequest(
            messageId = "msg-5-new",
            timestampMs = now + 20L,
            sequenceNumber = 5L,
            senderDeviceId = "controller-dev",
            sessionToken = sessionToken,
            appStorageKey = "youtube",
            targetLocked = true
        ).let { it.copy(signature = MessageSecurity.sign(it.canonicalData(), secret)) }

        val res5Dup = v.validateMessage(msg5Dup, secret, sessionToken, now + 20L)
        assertTrue(res5Dup is MessageValidationResult.ReplayDetected)
    }

    @Test
    fun invalidSignature_isRejected() {
        val now = 100_000L
        val unsigned = ProtocolMessage.SessionControlRequest(
            messageId = "msg-201",
            timestampMs = now,
            sequenceNumber = 1L,
            senderDeviceId = "controller-dev",
            sessionToken = sessionToken,
            action = SessionAction.START
        )
        // Sign with a completely different secret
        val wrongSecret = MessageSecurity.deriveSharedSecret("000000", "saltsalt", "cNonce", "sNonce")
        val badSigned = unsigned.copy(
            signature = MessageSecurity.sign(unsigned.canonicalData(), wrongSecret)
        )

        val result = validator.validateMessage(
            message = badSigned,
            sharedSecret = secret,
            expectedSessionToken = sessionToken,
            currentTimeMs = now
        )
        assertTrue(result is MessageValidationResult.InvalidSignature)
    }

    @Test
    fun sessionTokenMismatch_isRejected() {
        val now = 100_000L
        val unsigned = ProtocolMessage.Heartbeat(
            messageId = "msg-301",
            timestampMs = now,
            sequenceNumber = 1L,
            senderDeviceId = "controller-dev",
            sessionToken = "unauthorized-token"
        )
        val signed = unsigned.copy(
            signature = MessageSecurity.sign(unsigned.canonicalData(), secret)
        )

        val result = validator.validateMessage(
            message = signed,
            sharedSecret = secret,
            expectedSessionToken = "expected-valid-token",
            currentTimeMs = now
        )
        assertTrue(result is MessageValidationResult.UnauthorizedSessionToken)
    }
}

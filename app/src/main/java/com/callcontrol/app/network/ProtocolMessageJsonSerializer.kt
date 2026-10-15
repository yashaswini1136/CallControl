package com.callcontrol.app.network

import com.callcontrol.app.pairing.DeviceRole
import com.callcontrol.app.pairing.ProtocolMessage
import com.callcontrol.app.pairing.SessionAction

/**
 * Pure Kotlin, zero-dependency JSON serializer and deserializer for [ProtocolMessage] wire transmission.
 *
 * Emits and parses valid JSON format.
 * Pure Kotlin implementation ensures identical behavior across Android devices and JVM unit tests
 * without relying on Android framework stubs.
 */
object ProtocolMessageJsonSerializer {

    private const val KEY_TYPE = "type"
    private const val KEY_MESSAGE_ID = "messageId"
    private const val KEY_TIMESTAMP_MS = "timestampMs"
    private const val KEY_SEQUENCE_NUMBER = "sequenceNumber"
    private const val KEY_SENDER_DEVICE_ID = "senderDeviceId"
    private const val KEY_SIGNATURE = "signature"

    fun serialize(message: ProtocolMessage): ByteArray {
        val pairs = mutableListOf<Pair<String, String>>()
        pairs.add(KEY_TYPE to quote(message.messageType()))
        pairs.add(KEY_MESSAGE_ID to quote(message.messageId))
        pairs.add(KEY_TIMESTAMP_MS to message.timestampMs.toString())
        pairs.add(KEY_SEQUENCE_NUMBER to message.sequenceNumber.toString())
        pairs.add(KEY_SENDER_DEVICE_ID to quote(message.senderDeviceId))
        if (message.signature != null) {
            pairs.add(KEY_SIGNATURE to quote(message.signature!!))
        }

        when (message) {
            is ProtocolMessage.PairRequest -> {
                pairs.add("clientRole" to quote(message.clientRole.name))
                pairs.add("pairingId" to quote(message.pairingId))
                pairs.add("submittedCode" to quote(message.submittedCode))
                pairs.add("clientNonce" to quote(message.clientNonce))
                if (message.channelAuthProof != null) pairs.add("channelAuthProof" to quote(message.channelAuthProof))
            }
            is ProtocolMessage.PairResponse -> {
                pairs.add("approved" to message.approved.toString())
                pairs.add("saltHex" to quote(message.saltHex))
                pairs.add("serverNonce" to quote(message.serverNonce))
                if (message.sessionToken != null) pairs.add("sessionToken" to quote(message.sessionToken))
                if (message.rejectionReason != null) pairs.add("rejectionReason" to quote(message.rejectionReason))
                if (message.serverChannelAuthProof != null) pairs.add("serverChannelAuthProof" to quote(message.serverChannelAuthProof))
            }
            is ProtocolMessage.Heartbeat -> {
                pairs.add("sessionToken" to quote(message.sessionToken))
            }
            is ProtocolMessage.HeartbeatAck -> {
                pairs.add("sessionToken" to quote(message.sessionToken))
                pairs.add("acknowledgedSequenceNumber" to message.acknowledgedSequenceNumber.toString())
            }
            is ProtocolMessage.SessionControlRequest -> {
                pairs.add("sessionToken" to quote(message.sessionToken))
                pairs.add("action" to quote(message.action.name))
            }
            is ProtocolMessage.SessionControlAck -> {
                pairs.add("sessionToken" to quote(message.sessionToken))
                pairs.add("resultingSessionStatus" to quote(message.resultingSessionStatus))
                pairs.add("success" to message.success.toString())
                if (message.errorMessage != null) pairs.add("errorMessage" to quote(message.errorMessage))
            }
            is ProtocolMessage.PolicyUpdateRequest -> {
                pairs.add("sessionToken" to quote(message.sessionToken))
                pairs.add("appStorageKey" to quote(message.appStorageKey))
                pairs.add("targetLocked" to message.targetLocked.toString())
            }
            is ProtocolMessage.PolicyUpdateAck -> {
                pairs.add("sessionToken" to quote(message.sessionToken))
                pairs.add("appStorageKey" to quote(message.appStorageKey))
                pairs.add("appliedLocked" to message.appliedLocked.toString())
                pairs.add("success" to message.success.toString())
                if (message.errorMessage != null) pairs.add("errorMessage" to quote(message.errorMessage))
            }
            is ProtocolMessage.DisconnectOrRevoke -> {
                if (message.sessionToken != null) pairs.add("sessionToken" to quote(message.sessionToken))
                pairs.add("isPermanentRevocation" to message.isPermanentRevocation.toString())
                pairs.add("reason" to quote(message.reason))
            }
        }

        val jsonStr = pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":$v"
        }
        return jsonStr.toByteArray(Charsets.UTF_8)
    }

    fun deserialize(bytes: ByteArray): ProtocolMessage {
        val jsonStr = String(bytes, Charsets.UTF_8)
        val map = SimpleJsonParser.parse(jsonStr)

        val type = map[KEY_TYPE] ?: ""
        val messageId = map[KEY_MESSAGE_ID] ?: ""
        val timestampMs = map[KEY_TIMESTAMP_MS]?.toLongOrNull() ?: 0L
        val sequenceNumber = map[KEY_SEQUENCE_NUMBER]?.toLongOrNull() ?: 0L
        val senderDeviceId = map[KEY_SENDER_DEVICE_ID] ?: ""
        val signature = map[KEY_SIGNATURE]

        if (type.isBlank() || messageId.isBlank() || senderDeviceId.isBlank()) {
            throw FrameException("Missing core protocol message header fields (type, messageId, or senderDeviceId)")
        }

        return when (type) {
            "PAIR_REQUEST" -> ProtocolMessage.PairRequest(
                messageId = messageId,
                timestampMs = timestampMs,
                sequenceNumber = sequenceNumber,
                senderDeviceId = senderDeviceId,
                signature = signature,
                clientRole = DeviceRole.valueOf(map["clientRole"] ?: DeviceRole.CONTROLLER.name),
                pairingId = map["pairingId"] ?: throw FrameException("Missing pairingId"),
                submittedCode = map["submittedCode"] ?: throw FrameException("Missing submittedCode"),
                clientNonce = map["clientNonce"] ?: throw FrameException("Missing clientNonce"),
                channelAuthProof = map["channelAuthProof"]
            )
            "PAIR_RESPONSE" -> ProtocolMessage.PairResponse(
                messageId = messageId,
                timestampMs = timestampMs,
                sequenceNumber = sequenceNumber,
                senderDeviceId = senderDeviceId,
                signature = signature,
                approved = map["approved"] == "true",
                saltHex = map["saltHex"] ?: "",
                serverNonce = map["serverNonce"] ?: "",
                sessionToken = map["sessionToken"],
                rejectionReason = map["rejectionReason"],
                serverChannelAuthProof = map["serverChannelAuthProof"]
            )
            "HEARTBEAT" -> ProtocolMessage.Heartbeat(
                messageId = messageId,
                timestampMs = timestampMs,
                sequenceNumber = sequenceNumber,
                senderDeviceId = senderDeviceId,
                signature = signature,
                sessionToken = map["sessionToken"] ?: throw FrameException("Missing sessionToken")
            )
            "HEARTBEAT_ACK" -> ProtocolMessage.HeartbeatAck(
                messageId = messageId,
                timestampMs = timestampMs,
                sequenceNumber = sequenceNumber,
                senderDeviceId = senderDeviceId,
                signature = signature,
                sessionToken = map["sessionToken"] ?: throw FrameException("Missing sessionToken"),
                acknowledgedSequenceNumber = map["acknowledgedSequenceNumber"]?.toLongOrNull() ?: 0L
            )
            "SESSION_CONTROL_REQUEST" -> ProtocolMessage.SessionControlRequest(
                messageId = messageId,
                timestampMs = timestampMs,
                sequenceNumber = sequenceNumber,
                senderDeviceId = senderDeviceId,
                signature = signature,
                sessionToken = map["sessionToken"] ?: throw FrameException("Missing sessionToken"),
                action = SessionAction.valueOf(map["action"] ?: throw FrameException("Missing action"))
            )
            "SESSION_CONTROL_ACK" -> ProtocolMessage.SessionControlAck(
                messageId = messageId,
                timestampMs = timestampMs,
                sequenceNumber = sequenceNumber,
                senderDeviceId = senderDeviceId,
                signature = signature,
                sessionToken = map["sessionToken"] ?: throw FrameException("Missing sessionToken"),
                resultingSessionStatus = map["resultingSessionStatus"] ?: "",
                success = map["success"] == "true",
                errorMessage = map["errorMessage"]
            )
            "POLICY_UPDATE_REQUEST" -> ProtocolMessage.PolicyUpdateRequest(
                messageId = messageId,
                timestampMs = timestampMs,
                sequenceNumber = sequenceNumber,
                senderDeviceId = senderDeviceId,
                signature = signature,
                sessionToken = map["sessionToken"] ?: throw FrameException("Missing sessionToken"),
                appStorageKey = map["appStorageKey"] ?: throw FrameException("Missing appStorageKey"),
                targetLocked = map["targetLocked"] == "true"
            )
            "POLICY_UPDATE_ACK" -> ProtocolMessage.PolicyUpdateAck(
                messageId = messageId,
                timestampMs = timestampMs,
                sequenceNumber = sequenceNumber,
                senderDeviceId = senderDeviceId,
                signature = signature,
                sessionToken = map["sessionToken"] ?: throw FrameException("Missing sessionToken"),
                appStorageKey = map["appStorageKey"] ?: throw FrameException("Missing appStorageKey"),
                appliedLocked = map["appliedLocked"] == "true",
                success = map["success"] == "true",
                errorMessage = map["errorMessage"]
            )
            "DISCONNECT_OR_REVOKE" -> ProtocolMessage.DisconnectOrRevoke(
                messageId = messageId,
                timestampMs = timestampMs,
                sequenceNumber = sequenceNumber,
                senderDeviceId = senderDeviceId,
                signature = signature,
                sessionToken = map["sessionToken"],
                isPermanentRevocation = map["isPermanentRevocation"] == "true",
                reason = map["reason"] ?: ""
            )
            else -> throw FrameException("Unrecognized protocol message type: '$type'")
        }
    }

    private fun quote(s: String): String {
        return buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
    }

    internal object SimpleJsonParser {
        fun parse(jsonStr: String): Map<String, String> {
            val trimmed = jsonStr.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                throw FrameException("Malformed JSON: Must start with '{' and end with '}'")
            }
            val result = mutableMapOf<String, String>()
            var i = 1
            val len = trimmed.length - 1

            fun skipWhitespace() {
                while (i < len && trimmed[i].isWhitespace()) i++
            }

            fun parseString(): String {
                if (i >= len || trimmed[i] != '"') throw FrameException("Expected '\"' at position $i")
                i++ // skip opening quote
                val sb = StringBuilder()
                var escaped = false
                while (i < len) {
                    val c = trimmed[i++]
                    if (escaped) {
                        when (c) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> sb.append(c)
                        }
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        return sb.toString()
                    } else {
                        sb.append(c)
                    }
                }
                throw FrameException("Unterminated string in JSON")
            }

            fun parseValue(): String? {
                skipWhitespace()
                if (i >= len) throw FrameException("Unexpected end of JSON while parsing value")
                return if (trimmed[i] == '"') {
                    parseString()
                } else {
                    val start = i
                    while (i < len && trimmed[i] != ',' && trimmed[i] != '}' && !trimmed[i].isWhitespace()) {
                        i++
                    }
                    val raw = trimmed.substring(start, i)
                    if (raw == "null") null else raw
                }
            }

            while (i < len) {
                skipWhitespace()
                if (i >= len) break
                val key = parseString()
                skipWhitespace()
                if (i >= len || trimmed[i] != ':') throw FrameException("Expected ':' after key '$key'")
                i++ // skip ':'
                val value = parseValue()
                if (value != null) {
                    result[key] = value
                }
                skipWhitespace()
                if (i < len) {
                    if (trimmed[i] == ',') {
                        i++ // skip ','
                    } else if (trimmed[i] == '}') {
                        break
                    } else {
                        throw FrameException("Expected ',' or '}' at position $i")
                    }
                }
            }
            return result
        }
    }
}

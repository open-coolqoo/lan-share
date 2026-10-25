package com.lanshare

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

@Serializable
data class FileOffer(
    val fileId: String,
    val transferId: String,
    val name: String,
    val size: Long,
    val mime: String,
    val chunkSize: Int,
    val totalChunks: Int,
)

sealed interface TransferControlMessage {
    val protocolVersion: Int
    val messageId: String
    val type: String

    // Chat messages carry no transfer identity: they are not tied to a file transfer.
    @Serializable
    data class TextMessage(
        override val protocolVersion: Int = 1,
        override val messageId: String,
        override val type: String = "text_message",
        val text: String,
    ) : TransferControlMessage {
        companion object {
            fun create(text: String): TextMessage = TextMessage(
                messageId = nextMessageId(),
                text = text,
            )
        }
    }

    @Serializable
    data class MessageRecallMessage(
        override val protocolVersion: Int = 1,
        override val messageId: String,
        override val type: String = "message_recall",
        val target: String,
        val targetId: String,
    ) : TransferControlMessage {
        companion object {
            fun create(target: RecallTarget, targetId: String): MessageRecallMessage = MessageRecallMessage(
                messageId = nextMessageId(),
                target = target.wireValue,
                targetId = targetId,
            )
        }
    }

    @Serializable
    data class FileOfferMessage(
        override val protocolVersion: Int = 1,
        override val messageId: String,
        override val transferId: String,
        override val fileId: String,
        override val type: String = "file_offer",
        val offer: FileOffer,
    ) : FileTransferMessage {
        companion object {
            fun create(offer: FileOffer): FileOfferMessage = FileOfferMessage(
                messageId = nextMessageId(),
                transferId = offer.transferId,
                fileId = offer.fileId,
                offer = offer,
            )
        }
    }

    @Serializable
    data class FileAcceptMessage(
        override val protocolVersion: Int = 1,
        override val messageId: String,
        override val transferId: String,
        override val fileId: String,
        override val type: String = "file_accept",
    ) : FileTransferMessage {
        companion object {
            fun create(offer: FileOffer): FileAcceptMessage = FileAcceptMessage(
                messageId = nextMessageId(),
                transferId = offer.transferId,
                fileId = offer.fileId,
            )
        }
    }

    @Serializable
    data class FileRejectMessage(
        override val protocolVersion: Int = 1,
        override val messageId: String,
        override val transferId: String,
        override val fileId: String,
        override val type: String = "file_reject",
        val reason: String,
    ) : FileTransferMessage

    @Serializable
    data class ChunkMetaMessage(
        override val protocolVersion: Int = 1,
        override val messageId: String,
        override val transferId: String,
        override val fileId: String,
        override val type: String = "chunk_meta",
        val index: Int,
        val offset: Long,
        val size: Int,
        val sha256: String? = null,
    ) : FileTransferMessage

    @Serializable
    data class FileCompleteMessage(
        override val protocolVersion: Int = 1,
        override val messageId: String,
        override val transferId: String,
        override val fileId: String,
        override val type: String = "file_complete",
        val bytesSent: Long,
    ) : FileTransferMessage

    @Serializable
    data class TransferErrorMessage(
        override val protocolVersion: Int = 1,
        override val messageId: String,
        override val transferId: String,
        override val fileId: String,
        override val type: String = "transfer_error",
        val reason: String,
    ) : FileTransferMessage

    @Serializable
    data class TransferCancelMessage(
        override val protocolVersion: Int = 1,
        override val messageId: String,
        override val transferId: String,
        override val fileId: String,
        override val type: String = "transfer_cancel",
        val reason: String? = null,
    ) : FileTransferMessage
}

/** Control messages that belong to one file transfer and therefore carry its identity. */
sealed interface FileTransferMessage : TransferControlMessage {
    val transferId: String
    val fileId: String
}

/** Target of a [TransferControlMessage.MessageRecallMessage]; the wire values match the web client. */
enum class RecallTarget(val wireValue: String) {
    TEXT("text"),
    FILE("file"),
    ;

    companion object {
        fun fromWire(value: String?): RecallTarget? = entries.firstOrNull { it.wireValue == value }
    }
}

const val MAX_TEXT_BYTES = 64 * 1024
private const val MAX_RECALL_TARGET_ID_LENGTH = 128
private val SHA256_HEX_PATTERN = Regex("^[0-9a-f]{64}$")

fun utf8ByteLength(text: String): Int = text.toByteArray(Charsets.UTF_8).size

fun isTextTooLarge(text: String): Boolean = utf8ByteLength(text) > MAX_TEXT_BYTES

fun isSha256Hex(value: String): Boolean = SHA256_HEX_PATTERN.matches(value)

fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }

fun createTextMessage(text: String): TransferControlMessage.TextMessage =
    TransferControlMessage.TextMessage.create(text)

fun createRecallMessage(target: RecallTarget, targetId: String): TransferControlMessage.MessageRecallMessage =
    TransferControlMessage.MessageRecallMessage.create(target, targetId)

object TransferJson {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Top-level string fields only; used to read a line the typed decoder refused. */
    internal fun fields(value: String): Map<String, String> =
        json.parseToJsonElement(value).jsonObject.mapNotNull { (key, element) -> (element as? JsonPrimitive)?.contentOrNull?.let { key to it } }
            .toMap()

    fun encode(message: TransferControlMessage): String = when (message) {
        is TransferControlMessage.FileOfferMessage -> json.encodeToString(TransferControlMessage.FileOfferMessage.serializer(), message)
        is TransferControlMessage.FileAcceptMessage -> json.encodeToString(TransferControlMessage.FileAcceptMessage.serializer(), message)
        is TransferControlMessage.FileRejectMessage -> json.encodeToString(TransferControlMessage.FileRejectMessage.serializer(), message)
        is TransferControlMessage.ChunkMetaMessage -> json.encodeToString(TransferControlMessage.ChunkMetaMessage.serializer(), message)
        is TransferControlMessage.FileCompleteMessage -> json.encodeToString(TransferControlMessage.FileCompleteMessage.serializer(), message)
        is TransferControlMessage.TransferErrorMessage -> json.encodeToString(TransferControlMessage.TransferErrorMessage.serializer(), message)
        is TransferControlMessage.TransferCancelMessage -> json.encodeToString(TransferControlMessage.TransferCancelMessage.serializer(), message)
        is TransferControlMessage.TextMessage -> json.encodeToString(TransferControlMessage.TextMessage.serializer(), message)
        is TransferControlMessage.MessageRecallMessage -> json.encodeToString(TransferControlMessage.MessageRecallMessage.serializer(), message)
    }

    /**
     * Parses a control frame, mirroring the web client's `parseControlMessage`.
     *
     * Returns null for an unknown type: the peer may already speak a newer optional message type,
     * and one unknown line must not kill a file transfer. Invalid input for a known type, or an
     * unsupported protocol version, throws.
     */
    fun decode(value: String): TransferControlMessage? {
        val obj = json.parseToJsonElement(value).jsonObject
        if (obj.int("protocolVersion") != 1) throw IllegalArgumentException("Unsupported transfer protocol version.")
        val type = obj.string("type")
        return when (type) {
            "text_message" -> {
                requireString(obj, "messageId")
                val text = obj["text"]?.jsonPrimitive?.contentOrNull
                if (text == null || text.isEmpty() || isTextTooLarge(text)) {
                    throw IllegalArgumentException(INVALID_TEXT_MESSAGE_REASON)
                }
                json.decodeFromString(TransferControlMessage.TextMessage.serializer(), value)
            }
            "message_recall" -> {
                requireString(obj, "messageId")
                if (RecallTarget.fromWire(obj["target"]?.jsonPrimitive?.contentOrNull) == null) {
                    throw IllegalArgumentException("Invalid recall target.")
                }
                val targetId = obj["targetId"]?.jsonPrimitive?.contentOrNull
                if (targetId == null || targetId.isEmpty() || targetId.length > MAX_RECALL_TARGET_ID_LENGTH) {
                    throw IllegalArgumentException("Invalid recall target identity.")
                }
                json.decodeFromString(TransferControlMessage.MessageRecallMessage.serializer(), value)
            }
            "chunk_meta" -> {
                val sha256 = obj.stringOrNull("sha256")
                if (sha256 != null && !isSha256Hex(sha256)) throw IllegalArgumentException("Invalid chunk checksum.")
                json.decodeFromString(TransferControlMessage.ChunkMetaMessage.serializer(), value)
            }
            "file_offer" -> json.decodeFromString(TransferControlMessage.FileOfferMessage.serializer(), value)
            "file_accept" -> json.decodeFromString(TransferControlMessage.FileAcceptMessage.serializer(), value)
            "file_reject" -> json.decodeFromString(TransferControlMessage.FileRejectMessage.serializer(), value)
            "file_complete" -> json.decodeFromString(TransferControlMessage.FileCompleteMessage.serializer(), value)
            "transfer_error" -> json.decodeFromString(TransferControlMessage.TransferErrorMessage.serializer(), value)
            "transfer_cancel" -> json.decodeFromString(TransferControlMessage.TransferCancelMessage.serializer(), value)
            else -> null
        }
    }
}

const val DEFAULT_TRANSFER_CHUNK_SIZE = 262_144
const val DEFAULT_TRANSFER_MAX_FILE_SIZE = 2_147_483_648L

fun defaultTransferLimits(): TransferLimits = TransferLimits(
    maxFileSize = DEFAULT_TRANSFER_MAX_FILE_SIZE,
    maxActiveTransfers = 1,
    chunkSize = DEFAULT_TRANSFER_CHUNK_SIZE,
)

fun validateFileOffer(offer: FileOffer, limits: TransferLimits = defaultTransferLimits()): String? {
    if (offer.fileId.isBlank() || offer.transferId.isBlank()) return "Missing transfer identity."
    if (offer.size < 0L) return "Negative file size."
    if (offer.size > limits.maxFileSize) return "File exceeds the connection limit."
    if (offer.chunkSize <= 0 || offer.chunkSize > limits.chunkSize) return "Invalid chunk size."
    val expectedChunks = if (offer.size == 0L) 1 else {
        (offer.size / offer.chunkSize) + if (offer.size % offer.chunkSize == 0L) 0 else 1
    }
    if (expectedChunks > Int.MAX_VALUE || offer.totalChunks.toLong() != expectedChunks) {
        return "File chunk metadata is inconsistent."
    }
    if (offer.totalChunks <= 0) return "Invalid total chunk count."
    return null
}

fun createFileOffer(name: String, size: Long, mime: String = "application/octet-stream", chunkSize: Int = DEFAULT_TRANSFER_CHUNK_SIZE): FileOffer {
    require(size >= 0) { "File size cannot be negative." }
    require(chunkSize > 0) { "Chunk size must be positive." }
    val safeName = sanitizeFileName(name)
    val totalChunks = if (size == 0L) 1 else ((size / chunkSize) + if (size % chunkSize == 0L) 0 else 1)
    require(totalChunks <= Int.MAX_VALUE) { "File has too many chunks." }
    return FileOffer(
        fileId = "file_${UUID.randomUUID()}",
        transferId = "transfer_${UUID.randomUUID()}",
        name = safeName,
        size = size,
        mime = mime,
        chunkSize = chunkSize,
        totalChunks = totalChunks.toInt(),
    )
}

fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("""\.\.+"""), ".").trim()
    return cleaned.ifEmpty { "unnamed-file" }
}

fun chunkMeta(
    offer: FileOffer,
    index: Int,
    offset: Long,
    size: Int,
    sha256: String? = null,
): TransferControlMessage.ChunkMetaMessage =
    TransferControlMessage.ChunkMetaMessage(
        messageId = nextMessageId(),
        transferId = offer.transferId,
        fileId = offer.fileId,
        index = index,
        offset = offset,
        size = size,
        sha256 = sha256,
    )

fun fileComplete(offer: FileOffer, bytesSent: Long): TransferControlMessage.FileCompleteMessage =
    TransferControlMessage.FileCompleteMessage(
        messageId = nextMessageId(),
        transferId = offer.transferId,
        fileId = offer.fileId,
        bytesSent = bytesSent,
    )

private fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Missing $name.")

private fun JsonObject.int(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Missing $name.")

private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun requireString(obj: JsonObject, name: String) {
    if (obj.stringOrNull(name) == null) throw IllegalArgumentException("Missing $name.")
}

private fun nextMessageId(): String = "msg_${UUID.randomUUID()}"

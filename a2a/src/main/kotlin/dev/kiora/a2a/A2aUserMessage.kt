package dev.kiora.a2a

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A small file sent inline with an A2A 1.x message. The Android host reads its own content URI. */
class A2aInlineFile(
    val filename: String,
    val mediaType: String,
    bytes: ByteArray,
) {
    val bytes: ByteArray = bytes.copyOf()

    init {
        require(filename.isNotBlank() && '/' !in filename && '\\' !in filename) { "Invalid filename" }
        require(mediaType.isNotBlank()) { "mediaType is required" }
        require(bytes.isNotEmpty() && bytes.size <= MAX_INLINE_BYTES) {
            "Inline file must be between 1 byte and 10 MiB"
        }
    }

    companion object {
        const val MAX_INLINE_BYTES = 10 * 1024 * 1024
    }
}

data class A2aUserMessage(
    val text: String,
    val files: List<A2aInlineFile> = emptyList(),
    val data: JsonObject? = null,
) {
    init {
        require(text.isNotBlank() || files.isNotEmpty() || data != null) {
            "A2A message must contain text, a file or structured data"
        }
        require(files.sumOf { it.bytes.size.toLong() } <= A2aInlineFile.MAX_INLINE_BYTES) {
            "Total inline files exceed 10 MiB"
        }
    }
}

internal fun A2aUserMessage.toParts(): JsonArray = JsonArray(buildList {
    if (text.isNotBlank()) add(JsonObject(mapOf("text" to JsonPrimitive(text))))
    data?.let { add(JsonObject(mapOf(
        "data" to it,
        "mediaType" to JsonPrimitive("application/json"),
    ))) }
    files.forEach { file ->
        add(JsonObject(mapOf(
            "raw" to JsonPrimitive(Base64.getEncoder().encodeToString(file.bytes)),
            "filename" to JsonPrimitive(file.filename),
            "mediaType" to JsonPrimitive(file.mediaType),
        )))
    }
})

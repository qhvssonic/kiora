package dev.kiora.android.attachments

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.kiora.a2a.A2aInlineFile
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads a user-granted content URI into the bounded A2A inline-file representation. */
object AndroidA2aAttachmentReader {
    suspend fun read(resolver: ContentResolver, uri: Uri): A2aInlineFile = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Select a file through Android's document or photo picker" }
        val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf(String::isNotBlank) ?: "attachment"
        val mediaType = resolver.getType(uri)?.takeIf(String::isNotBlank) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= A2aInlineFile.MAX_INLINE_BYTES) { "File exceeds the 10 MiB A2A inline limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } ?: error("Cannot open selected file")
        A2aInlineFile(filename, mediaType, bytes)
    }
}

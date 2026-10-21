package dev.kiora.android.storage

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.kiora.core.AgentTool
import dev.kiora.core.ToolDefinition
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Android SAF tools. Every location is resolved from a host-owned, persisted URI grant. */
class AndroidStorageTools(
    context: Context,
    private val folderGrants: () -> List<Uri>,
    private val photoGrants: () -> List<Uri>,
) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    data class AuthorizedFile(val uri: Uri, val name: String, val mimeType: String)

    fun all(): List<AgentTool> = listOf(
        tool("list_authorized_folders", "List folders explicitly granted by the user. Use the returned folder ID with other authorized file tools.",
            schema()) { folders().map { entry(it) }.asJson() },
        tool("list_authorized_photos", "List photos explicitly selected and persistently authorized by the user. This does not browse the entire gallery.",
            schema()) { photos().map { entry(it) }.asJson() },
        tool("list_authorized_files", "List one directory inside an authorized folder. Give its folder ID and an optional relative path. At most 100 entries are returned.",
            schema("folderId" to "Authorized folder ID", "path" to "Relative directory path; omit for root", required = listOf("folderId"))) { args ->
            val folder = folder(args.string("folderId"))
            val directory = resolve(folder, args.optional("path"))
            require(directory.isDirectory) { "Path is not a directory" }
            val entries = children(folder.uri, directory).take(101)
            buildJsonObject {
                put("items", JsonArray(entries.take(100).map { entry(it) }))
                put("truncated", JsonPrimitive(entries.size > 100))
            }.toString()
        },
        tool("search_authorized_files", "Search names in user-authorized folders and photos. Returns at most 50 matches and scans at most 1000 entries.",
            schema("query" to "Filename fragment", required = listOf("query"))) { args -> search(args.string("query")) },
        tool("inspect_authorized_file", "Get name, MIME type, size and image dimensions where available. Use photoId for a selected photo, or folderId and path for a folder file.",
            targetSchema()) { args -> entry(target(args), inspectImage = true).toString() },
        tool("read_authorized_text", "Read a small UTF-8 text file inside an authorized folder. Images and binary files cannot be read as text. Output is limited to 32 KiB.",
            schema("folderId" to "Authorized folder ID", "path" to "Relative file path", required = listOf("folderId", "path"))) { args ->
            val file = target(args)
            require(!file.isDirectory) { "Path is a directory" }
            require(file.mimeType.startsWith("text/") || listOf("txt", "json", "md", "csv", "xml", "log", "kt")
                .any { file.name.endsWith(".$it", true) }) { "File is not recognized as text" }
            val bytes = resolver.openInputStream(file.uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "Text file exceeds 1 MiB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("Cannot open file")
            val content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            if (content.length > 32_000) content.take(32_000) + "\n[Truncated]" else content
        },
        tool("copy_authorized_file", "Copy a selected photo or file into an authorized destination folder. Never overwrites an existing name. Maximum 100 MiB.",
            transferSchema()) { args -> copyOrMove(args, false) },
        tool("move_authorized_file", "Move a selected photo or file into an authorized destination folder, deleting the source only after a complete copy. Maximum 100 MiB.",
            transferSchema()) { args -> copyOrMove(args, true) },
        tool("rename_authorized_file", "Rename one writable file in an authorized folder or a writable selected photo. Does not overwrite another file.",
            targetSchema("newName" to "New filename", requiredExtra = listOf("newName"))) { args -> rename(args) },
        tool("delete_authorized_file", "Delete exactly one writable file or photo. Directories cannot be deleted by this tool.",
            targetSchema()) { args -> delete(args) },
    )

    /** Resolve an ID/path pair for a host action such as an explicitly approved A2A transfer. */
    fun authorizedFile(locationId: String, path: String = ""): AuthorizedFile {
        val file = target(locationId, path)
        require(!file.isDirectory) { "A file is required" }
        return AuthorizedFile(file.uri, file.name, file.mimeType)
    }

    private data class Item(
        val uri: Uri, val name: String, val mimeType: String, val size: Long?,
        val modified: Long?, val flags: Int, val id: String, val path: String = "",
        val tree: Uri? = null, val writableGrant: Boolean = false,
    ) {
        val isDirectory get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun grants(): Map<String, Pair<Boolean, Boolean>> = resolver.persistedUriPermissions.associate {
        it.uri.toString() to (it.isReadPermission to it.isWritePermission)
    }

    private fun folders(): List<Item> {
        val granted = grants()
        return folderGrants().distinct().mapNotNull { tree ->
            val permission = granted[tree.toString()] ?: return@mapNotNull null
            if (!permission.first || !DocumentsContract.isTreeUri(tree)) return@mapNotNull null
            runCatching {
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                metadata(root, "folder_${fingerprint(tree)}", "", tree, permission.second)
            }.getOrNull()?.takeIf { it.isDirectory }
        }
    }

    private fun photos(): List<Item> {
        val granted = grants()
        return photoGrants().distinct().mapNotNull { uri ->
            val permission = granted[uri.toString()] ?: return@mapNotNull null
            if (!permission.first || !DocumentsContract.isDocumentUri(app, uri)) return@mapNotNull null
            runCatching { metadata(uri, "photo_${fingerprint(uri)}", "", null, permission.second) }
                .getOrNull()?.takeIf { it.mimeType.startsWith("image/") }
        }
    }

    private fun folder(id: String): Item = folders().firstOrNull { it.id == id }
        ?: error("Folder is not authorized")

    private fun target(args: JsonObject): Item = target(
        args.optional("photoId").ifBlank { args.optional("folderId") }, args.optional("path"),
    )

    private fun target(id: String, path: String): Item {
        photos().firstOrNull { it.id == id }?.let {
            require(path.isBlank()) { "Photo ID does not accept a path" }
            return it
        }
        return resolve(folder(id), path)
    }

    private fun resolve(folder: Item, path: String): Item {
        if (path.isBlank()) return folder
        require(!path.startsWith('/') && !path.contains('\\')) { "Use a relative slash-separated path" }
        val parts = path.split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." }) { "Invalid relative path" }
        var current = folder
        for (part in parts) {
            require(current.isDirectory) { "Parent is not a directory" }
            current = children(folder.uri, current).firstOrNull { it.name == part }
                ?: error("File not found: $part")
        }
        return current
    }

    private fun children(tree: Uri, directory: Item): List<Item> {
        val parentId = DocumentsContract.getDocumentId(directory.uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val result = mutableListOf<Item>()
        resolver.query(childrenUri, COLUMNS, null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && result.size < 1000) {
                val documentId = cursor.getString(0)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                val name = cursor.getString(1) ?: continue
                val path = listOf(directory.path, name).filter { it.isNotEmpty() }.joinToString("/")
                result += Item(uri, name, cursor.getString(2) ?: "application/octet-stream",
                    cursor.longOrNull(3), cursor.longOrNull(4), cursor.getInt(5),
                    directory.id, path, tree, directory.writableGrant)
            }
        } ?: error("Cannot list authorized folder")
        return result
    }

    private fun metadata(uri: Uri, id: String, path: String, tree: Uri?, writable: Boolean): Item {
        resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return Item(uri, cursor.getString(1) ?: "unnamed",
                cursor.getString(2) ?: "application/octet-stream", cursor.longOrNull(3),
                cursor.longOrNull(4), cursor.getInt(5), id, path, tree, writable)
        }
        error("Authorized item is unavailable")
    }

    private fun entry(item: Item, inspectImage: Boolean = false): JsonObject = buildJsonObject {
        put("id", item.id)
        put("name", item.name)
        put("path", item.path)
        put("mimeType", item.mimeType)
        item.size?.let { put("sizeBytes", it) }
        item.modified?.let { put("modifiedMillis", it) }
        put("writable", item.writableGrant)
        if (inspectImage && item.mimeType.startsWith("image/")) runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth > 0 && options.outHeight > 0) {
                put("width", options.outWidth)
                put("height", options.outHeight)
            }
        }
    }

    private fun search(query: String): String {
        require(query.length in 1..200) { "Query must be 1–200 characters" }
        val matches = mutableListOf<JsonObject>()
        var scanned = 0
        photos().forEach { if (it.name.contains(query, true) && matches.size < 50) matches += entry(it) }
        folders().forEach { folder ->
            val queue = ArrayDeque<Item>()
            queue.add(folder)
            while (queue.isNotEmpty() && scanned < 1000 && matches.size < 50) {
                val directory = queue.removeFirst()
                for (child in children(folder.uri, directory)) {
                    if (++scanned > 1000) break
                    if (child.name.contains(query, true) && matches.size < 50) matches += entry(child)
                    if (child.isDirectory) queue.add(child)
                }
            }
        }
        return buildJsonObject {
            put("items", JsonArray(matches))
            put("scanned", scanned)
            put("truncated", scanned >= 1000 || matches.size >= 50)
        }.toString()
    }

    private fun copyOrMove(args: JsonObject, move: Boolean): String {
        val source = target(args.optional("photoId").ifBlank { args.optional("sourceFolderId") }, args.optional("sourcePath"))
        require(!source.isDirectory) { "Source must be a file" }
        val destinationFolder = folder(args.string("destinationFolderId"))
        require(destinationFolder.writableGrant) { "Destination folder has no write grant" }
        val directory = resolve(destinationFolder, args.optional("destinationPath"))
        require(directory.isDirectory) { "Destination is not a directory" }
        val name = args.optional("newName").ifBlank { source.name }.also(::validName)
        require(children(destinationFolder.uri, directory).none { it.name == name }) { "Destination name already exists" }
        if (move) require(source.writableGrant) { "Source has no write grant; copy it instead" }
        if (move) require(source.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) {
            "Provider cannot delete source; copy it instead"
        }
        val created = DocumentsContract.createDocument(resolver, directory.uri, source.mimeType, name)
            ?: error("Cannot create destination")
        try {
            resolver.openInputStream(source.uri)?.use { input ->
                resolver.openOutputStream(created, "w")?.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= 100L * 1024 * 1024) { "File exceeds 100 MiB copy limit" }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                } ?: error("Cannot write destination")
            } ?: error("Cannot read source")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }
        if (move) {
            check(DocumentsContract.deleteDocument(resolver, source.uri)) {
                "Copy succeeded but source could not be deleted; both files remain"
            }
        }
        return "${if (move) "Moved" else "Copied"} ${source.name} to ${destinationFolder.name}/${directory.path}/${name}"
    }

    private fun rename(args: JsonObject): String {
        val item = target(args)
        require(!item.isDirectory && item.writableGrant) { "A writable file is required" }
        require(item.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) { "Provider does not support rename" }
        val name = args.string("newName").also(::validName)
        item.tree?.let { tree ->
            val parentPath = item.path.substringBeforeLast('/', "")
            val parent = resolve(folder(item.id), parentPath)
            require(children(tree, parent).none { it.name == name }) { "Destination name already exists" }
        }
        val renamed = DocumentsContract.renameDocument(resolver, item.uri, name) ?: error("Rename failed")
        return "Renamed ${item.name} to $name (${renamed})"
    }

    private fun delete(args: JsonObject): String {
        val item = target(args)
        require(!item.isDirectory && item.writableGrant) { "A writable file is required" }
        require(item.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) { "Provider does not support delete" }
        check(DocumentsContract.deleteDocument(resolver, item.uri)) { "Delete failed" }
        return "Deleted ${item.name}"
    }

    private fun tool(name: String, description: String, parameters: JsonObject,
                     action: (JsonObject) -> String): AgentTool = object : AgentTool {
        override val definition = ToolDefinition(name, description, parameters)
        override suspend fun execute(arguments: JsonObject): String = withContext(Dispatchers.IO) { action(arguments) }
    }

    private fun schema(vararg fields: Pair<String, String>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(fields.associate { (name, description) ->
            name to buildJsonObject { put("type", "string"); put("description", description) }
        }))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }

    private fun targetSchema(vararg extra: Pair<String, String>, requiredExtra: List<String> = emptyList()) =
        JsonObject(schema(
            "folderId" to "Authorized folder ID when addressing a folder file",
            "path" to "Relative file path inside the folder",
            "photoId" to "Authorized photo ID when addressing a selected photo",
            *extra, required = requiredExtra,
        ) + ("oneOf" to JsonArray(listOf(
            requiredFields("photoId"), requiredFields("folderId", "path"),
        ))))

    private fun transferSchema() = JsonObject(schema(
        "sourceFolderId" to "Source folder ID, unless photoId is used",
        "sourcePath" to "Relative source file path inside the folder",
        "photoId" to "Selected photo ID, instead of sourceFolderId and sourcePath",
        "destinationFolderId" to "Writable destination folder ID",
        "destinationPath" to "Relative destination directory path; omit for root",
        "newName" to "Optional destination filename",
        required = listOf("destinationFolderId"),
    ) + ("oneOf" to JsonArray(listOf(
        requiredFields("photoId"), requiredFields("sourceFolderId", "sourcePath"),
    ))))

    private fun requiredFields(vararg names: String) = buildJsonObject {
        put("required", JsonArray(names.map(::JsonPrimitive)))
    }

    private fun JsonObject.string(key: String): String = optional(key).also { require(it.isNotBlank()) { "$key is required" } }
    private fun JsonObject.optional(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty().trim()
    private fun List<JsonObject>.asJson(): String = JsonArray(this).toString()
    private fun android.database.Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)
    private fun validName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) {
            "Invalid filename"
        }
    }
    private fun fingerprint(uri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}

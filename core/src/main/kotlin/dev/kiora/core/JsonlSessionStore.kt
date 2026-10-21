package dev.kiora.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Simple single-process, append-only store. An Android app can replace it with Room. */
class JsonlSessionStore(private val directory: File) : SessionStore, SessionSummaryStore,
    SessionUsageStore, SessionModelInvocationStore, SessionInputQueueStore {
    private val lock = Any()
    private val json = Json
    private val validId = Regex("[A-Za-z0-9_-]+")

    override suspend fun load(sessionId: String): SessionTree? = withContext(Dispatchers.IO) { synchronized(lock) {
        val file = sessionFile(sessionId)
        if (!file.exists()) return@synchronized null
        val content = file.readText(Charsets.UTF_8)
        val completeContent = content.substringBeforeLast('\n', missingDelimiterValue = "")
        val lines = completeContent.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "Empty session file" }
        val header = json.parseToJsonElement(lines.first()).jsonObject
        require(header.string("type") == "session" && header.string("id") == sessionId) {
            "Invalid session header"
        }
        val entries = mutableListOf<SessionEntry>()
        val summaries = mutableListOf<SessionSummary>()
        val usages = mutableListOf<SessionUsage>()
        val modelInvocations = mutableListOf<SessionModelInvocation>()
        val queuedInputs = mutableListOf<QueuedUserInput>()
        val consumedQueueIds = mutableSetOf<String>()
        var leafId: String? = null
        for (line in lines.drop(1)) {
            val record = json.parseToJsonElement(line).jsonObject
            when (record.string("type")) {
                "message" -> {
                    val entry = SessionEntry(
                        id = record.string("id"),
                        parentId = record.optionalString("parentId"),
                        timestampEpochMillis = record.getValue("timestamp").jsonPrimitive.long,
                        message = decodeMessage(record.getValue("message").jsonObject),
                    )
                    entries += entry
                    leafId = entry.id
                    record.optionalString("sourceQueueId")?.let(consumedQueueIds::add)
                }
                "cursor" -> leafId = record.optionalString("leafId")
                "summary" -> summaries += SessionSummary(
                    id = record.string("id"),
                    throughEntryId = record.string("throughEntryId"),
                    timestampEpochMillis = record.getValue("timestamp").jsonPrimitive.long,
                    text = record.string("text"),
                )
                "usage" -> {
                    val counts = record.getValue("usage").jsonObject
                    usages += SessionUsage(
                        id = record.string("id"),
                        assistantEntryId = record.string("assistantEntryId"),
                        timestampEpochMillis = record.getValue("timestamp").jsonPrimitive.long,
                        usage = ModelUsage(
                            inputTokens = counts.optionalInt("inputTokens"),
                            outputTokens = counts.optionalInt("outputTokens"),
                            totalTokens = counts.optionalInt("totalTokens"),
                            cachedInputTokens = counts.optionalInt("cachedInputTokens"),
                        ),
                    )
                }
                "model_invocation" -> modelInvocations += SessionModelInvocation(
                    id = record.string("id"),
                    assistantEntryId = record.string("assistantEntryId"),
                    timestampEpochMillis = record.getValue("timestamp").jsonPrimitive.long,
                    model = ModelIdentity(record.string("modelId"), record.optionalString("providerId")),
                )
                "queued_input" -> queuedInputs += QueuedUserInput(
                    id = record.string("id"),
                    kind = QueuedInputKind.valueOf(record.string("kind")),
                    text = record.string("text"),
                    targetLeafId = record.optionalString("targetLeafId"),
                    timestampEpochMillis = record.getValue("timestamp").jsonPrimitive.long,
                )
                else -> error("Unknown session record")
            }
        }
        SessionTree.restore(sessionId, entries, leafId, summaries, usages, modelInvocations,
            queuedInputs, consumedQueueIds)
    } }

    override suspend fun create(sessionId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            directory.mkdirs()
            val file = sessionFile(sessionId)
            require(file.createNewFile()) { "Session already exists" }
            file.writeText(
                JsonObject(mapOf(
                    "type" to JsonPrimitive("session"),
                    "version" to JsonPrimitive(1),
                    "id" to JsonPrimitive(sessionId),
                )).toString() + "\n",
                Charsets.UTF_8,
            )
        }
    }

    override suspend fun append(sessionId: String, entry: SessionEntry) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            appendRecord(sessionId, entryRecord(entry))
        }
    }

    override suspend fun enqueueInput(sessionId: String, input: QueuedUserInput) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            appendRecord(sessionId, JsonObject(mapOf(
                "type" to JsonPrimitive("queued_input"),
                "id" to JsonPrimitive(input.id),
                "kind" to JsonPrimitive(input.kind.name),
                "text" to JsonPrimitive(input.text),
                "targetLeafId" to (input.targetLeafId?.let(::JsonPrimitive) ?: JsonNull),
                "timestamp" to JsonPrimitive(input.timestampEpochMillis),
            )))
        }
    }

    override suspend fun appendQueuedMessage(sessionId: String, entry: SessionEntry, queueId: String) =
        withContext(Dispatchers.IO) {
            require(entry.message is AgentMessage.User) { "Queued input must become a user message" }
            synchronized(lock) { appendRecord(sessionId, entryRecord(entry, queueId)) }
        }

    private fun entryRecord(entry: SessionEntry, sourceQueueId: String? = null): JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("message"),
        "id" to JsonPrimitive(entry.id),
        "parentId" to (entry.parentId?.let(::JsonPrimitive) ?: JsonNull),
        "timestamp" to JsonPrimitive(entry.timestampEpochMillis),
        "message" to encodeMessage(entry.message),
    ) + listOfNotNull(sourceQueueId?.let { "sourceQueueId" to JsonPrimitive(it) }))

    override suspend fun moveLeaf(sessionId: String, entryId: String?) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            appendRecord(sessionId, JsonObject(mapOf(
                "type" to JsonPrimitive("cursor"),
                "leafId" to (entryId?.let(::JsonPrimitive) ?: JsonNull),
            )))
        }
    }

    override suspend fun appendSummary(sessionId: String, summary: SessionSummary) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            appendRecord(sessionId, JsonObject(mapOf(
                "type" to JsonPrimitive("summary"),
                "id" to JsonPrimitive(summary.id),
                "throughEntryId" to JsonPrimitive(summary.throughEntryId),
                "timestamp" to JsonPrimitive(summary.timestampEpochMillis),
                "text" to JsonPrimitive(summary.text),
            )))
        }
    }

    override suspend fun appendUsage(sessionId: String, usage: SessionUsage) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val counts = usage.usage
            appendRecord(sessionId, JsonObject(mapOf(
                "type" to JsonPrimitive("usage"),
                "id" to JsonPrimitive(usage.id),
                "assistantEntryId" to JsonPrimitive(usage.assistantEntryId),
                "timestamp" to JsonPrimitive(usage.timestampEpochMillis),
                "usage" to JsonObject(listOfNotNull(
                    counts.inputTokens?.let { "inputTokens" to JsonPrimitive(it) },
                    counts.outputTokens?.let { "outputTokens" to JsonPrimitive(it) },
                    counts.totalTokens?.let { "totalTokens" to JsonPrimitive(it) },
                    counts.cachedInputTokens?.let { "cachedInputTokens" to JsonPrimitive(it) },
                ).toMap()),
            )))
        }
    }

    override suspend fun appendModelInvocation(sessionId: String, invocation: SessionModelInvocation) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                appendRecord(sessionId, JsonObject(mapOf(
                    "type" to JsonPrimitive("model_invocation"),
                    "id" to JsonPrimitive(invocation.id),
                    "assistantEntryId" to JsonPrimitive(invocation.assistantEntryId),
                    "timestamp" to JsonPrimitive(invocation.timestampEpochMillis),
                    "modelId" to JsonPrimitive(invocation.model.modelId),
                    "providerId" to (invocation.model.providerId?.let(::JsonPrimitive) ?: JsonNull),
                )))
            }
        }

    private fun appendRecord(sessionId: String, record: JsonObject) {
        val file = sessionFile(sessionId)
        require(file.exists()) { "Session not found" }
        // A killed mobile process may leave a partial final line. Keep only complete records.
        java.io.RandomAccessFile(file, "rw").use { handle ->
            if (handle.length() > 0) {
                handle.seek(handle.length() - 1)
                if (handle.read() != '\n'.code) {
                    var cursor = handle.length() - 1
                    while (cursor >= 0) {
                        handle.seek(cursor)
                        if (handle.read() == '\n'.code) break
                        cursor--
                    }
                    handle.setLength(cursor + 1)
                }
            }
        }
        file.appendText(record.toString() + "\n", Charsets.UTF_8)
    }

    private fun sessionFile(sessionId: String): File {
        require(validId.matches(sessionId)) { "Invalid session ID" }
        return File(directory, "$sessionId.jsonl")
    }

    private fun encodeMessage(message: AgentMessage): JsonObject = when (message) {
        is AgentMessage.User -> JsonObject(mapOf(
            "role" to JsonPrimitive("user"),
            "text" to JsonPrimitive(message.text),
            "images" to JsonArray(message.images.map { image -> JsonObject(mapOf(
                "mimeType" to JsonPrimitive(image.mimeType),
                "data" to JsonPrimitive(image.data),
            )) }),
        ))
        is AgentMessage.Assistant -> JsonObject(mapOf(
            "role" to JsonPrimitive("assistant"),
            "text" to JsonPrimitive(message.text),
            "toolCalls" to JsonArray(message.toolCalls.map { call ->
                JsonObject(mapOf(
                    "id" to JsonPrimitive(call.id),
                    "name" to JsonPrimitive(call.name),
                    "arguments" to call.arguments,
                ) + listOfNotNull(call.argumentsError?.let { "argumentsError" to JsonPrimitive(it) }))
            }),
        ))
        is AgentMessage.ContextSummary -> JsonObject(mapOf(
            "role" to JsonPrimitive("summary"),
            "text" to JsonPrimitive(message.text),
        ))
        is AgentMessage.ToolResult -> JsonObject(mapOf(
            "role" to JsonPrimitive("tool"),
            "callId" to JsonPrimitive(message.callId),
            "toolName" to JsonPrimitive(message.toolName),
            "text" to JsonPrimitive(message.text),
            "isError" to JsonPrimitive(message.isError),
            "images" to JsonArray(message.images.map { image -> JsonObject(mapOf(
                "mimeType" to JsonPrimitive(image.mimeType),
                "data" to JsonPrimitive(image.data),
            )) }),
        ) + listOfNotNull(
            message.failureKind?.let { "failureKind" to JsonPrimitive(it.name) },
            message.structuredContent?.let { "structuredContent" to it },
            message.attention?.let { "attention" to JsonPrimitive(it.name) },
        ))
    }

    private fun decodeMessage(record: JsonObject): AgentMessage = when (record.string("role")) {
        "user" -> AgentMessage.User(record.string("text"),
            record["images"]?.jsonArray?.map { image ->
                val item = image.jsonObject
                ToolImage(item.getValue("mimeType").jsonPrimitive.content,
                    item.getValue("data").jsonPrimitive.content)
            }.orEmpty())
        "assistant" -> AgentMessage.Assistant(
            record.string("text"),
            record.getValue("toolCalls").jsonArray.map { value ->
                val call = value.jsonObject
                ToolCall(call.string("id"), call.string("name"), call.getValue("arguments").jsonObject,
                    call["argumentsError"]?.jsonPrimitive?.content)
            },
        )
        "summary" -> AgentMessage.ContextSummary(record.string("text"))
        "tool" -> AgentMessage.ToolResult(
            record.string("callId"),
            record.string("toolName"),
            record.string("text"),
            record.getValue("isError").jsonPrimitive.boolean,
            record["images"]?.jsonArray?.map { image ->
                val item = image.jsonObject
                ToolImage(item.getValue("mimeType").jsonPrimitive.content,
                    item.getValue("data").jsonPrimitive.content)
            }.orEmpty(),
            record["failureKind"]?.jsonPrimitive?.content?.let { name ->
                ToolFailureKind.entries.firstOrNull { it.name == name }
            },
            record["structuredContent"],
            record["attention"]?.jsonPrimitive?.content?.let { name ->
                ToolAttention.entries.firstOrNull { it.name == name }
            },
        )
        else -> error("Unknown message role")
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.optionalString(key: String): String? = get(key)?.let { value ->
        if (value == JsonNull) null else value.jsonPrimitive.content
    }

    private fun JsonObject.optionalInt(key: String): Int? = get(key)?.let { value ->
        if (value == JsonNull) null else value.jsonPrimitive.int
    }
}

package dev.kiora.core

data class SessionEntry(
    val id: String,
    val parentId: String?,
    val timestampEpochMillis: Long,
    val message: AgentMessage,
)

/** A durable summary of the active path through one completed message. */
data class SessionSummary(
    val id: String,
    val throughEntryId: String,
    val timestampEpochMillis: Long,
    val text: String,
)

data class SessionUsage(
    val id: String,
    val assistantEntryId: String,
    val timestampEpochMillis: Long,
    val usage: ModelUsage,
)

data class SessionModelInvocation(
    val id: String,
    val assistantEntryId: String,
    val timestampEpochMillis: Long,
    val model: ModelIdentity,
)

enum class QueuedInputKind { STEERING, FOLLOW_UP }

data class QueuedUserInput(
    val id: String,
    val kind: QueuedInputKind,
    val text: String,
    val targetLeafId: String?,
    val timestampEpochMillis: Long,
)

/** Append-only conversation tree. The active leaf selects the model-visible branch. */
class SessionTree private constructor(
    val id: String,
    entries: List<SessionEntry>,
    leafId: String?,
    summaries: List<SessionSummary>,
    usages: List<SessionUsage>,
    modelInvocations: List<SessionModelInvocation>,
    queuedInputs: List<QueuedUserInput>,
    consumedQueueIds: Set<String>,
) {
    private val entries = entries.toMutableList()
    private val byId = entries.associateByTo(linkedMapOf()) { it.id }
    private val summaries = summaries.toMutableList()
    private val usages = usages.toMutableList()
    private val modelInvocations = modelInvocations.toMutableList()
    private val queuedInputs = queuedInputs.toMutableList()
    private val consumedQueueIds = consumedQueueIds.toMutableSet()
    var leafId: String? = leafId
        private set

    init {
        require(id.isNotBlank())
        require(byId.size == entries.size) { "Duplicate session entry ID" }
        val seen = mutableSetOf<String>()
        for (entry in entries) {
            require(entry.id.isNotBlank())
            require(entry.parentId == null || entry.parentId in seen) { "Parent must precede child" }
            seen += entry.id
        }
        require(leafId == null || leafId in byId) { "Unknown active leaf" }
        require(summaries.map { it.id }.toSet().size == summaries.size) { "Duplicate summary ID" }
        require(summaries.all { it.throughEntryId in byId && it.text.isNotBlank() }) {
            "Summary must reference a message and contain text"
        }
        require(usages.map { it.id }.toSet().size == usages.size) { "Duplicate usage ID" }
        require(usages.all { byId[it.assistantEntryId]?.message is AgentMessage.Assistant }) {
            "Usage must reference an assistant message"
        }
        require(modelInvocations.map { it.id }.toSet().size == modelInvocations.size)
        require(modelInvocations.all { byId[it.assistantEntryId]?.message is AgentMessage.Assistant }) {
            "Model invocation must reference an assistant message"
        }
        require(queuedInputs.map { it.id }.toSet().size == queuedInputs.size) { "Duplicate queued input ID" }
        require(queuedInputs.all { it.text.isNotBlank() && (it.targetLeafId == null || it.targetLeafId in byId) }) {
            "Queued input must have text and a valid branch anchor"
        }
        require(consumedQueueIds.all { id -> queuedInputs.any { it.id == id } }) {
            "Consumed queue ID has no queued input"
        }
    }

    fun allEntries(): List<SessionEntry> = entries.toList()
    fun allSummaries(): List<SessionSummary> = summaries.toList()
    fun allUsages(): List<SessionUsage> = usages.toList()
    fun allModelInvocations(): List<SessionModelInvocation> = modelInvocations.toList()
    fun activeModelInvocations(): List<SessionModelInvocation> {
        val activeIds = activeEntries().map { it.id }.toSet()
        return modelInvocations.filter { it.assistantEntryId in activeIds }
    }
    fun activeUsages(): List<SessionUsage> {
        val activeIds = activeEntries().map { it.id }.toSet()
        return usages.filter { it.assistantEntryId in activeIds }
    }
    fun allQueuedInputs(): List<QueuedUserInput> = queuedInputs.toList()
    fun consumedQueuedInputIds(): Set<String> = consumedQueueIds.toSet()
    fun pendingQueuedInputs(): List<QueuedUserInput> {
        val pathIds = activeEntries().map { it.id }.toSet()
        return queuedInputs.filter { it.id !in consumedQueueIds &&
            (it.targetLeafId == null || it.targetLeafId in pathIds) }
    }

    private fun pathTo(entryId: String?): List<SessionEntry> {
        val path = mutableListOf<SessionEntry>()
        var current = entryId
        while (current != null) {
            val entry = checkNotNull(byId[current])
            path += entry
            current = entry.parentId
        }
        return path.asReversed()
    }

    fun activeEntries(): List<SessionEntry> = pathTo(leafId)

    fun activeMessages(): List<AgentMessage> = activeEntries().map { it.message }

    /** The deepest summary on this branch replaces only the prefix it covers. */
    fun modelMessagesThrough(entryId: String? = leafId): List<AgentMessage> {
        val path = pathTo(entryId)
        val positions = path.mapIndexed { index, entry -> entry.id to index }.toMap()
        val summary = summaries.asReversed().mapNotNull { item ->
            positions[item.throughEntryId]?.let { index -> index to item }
        }.maxByOrNull { it.first }
        return if (summary == null) path.map { it.message }
        else listOf(AgentMessage.ContextSummary(summary.second.text)) +
            path.drop(summary.first + 1).map { it.message }
    }

    fun activeSummary(): SessionSummary? {
        val path = activeEntries()
        val positions = path.mapIndexed { index, entry -> entry.id to index }.toMap()
        return summaries.asReversed().mapNotNull { item ->
            positions[item.throughEntryId]?.let { index -> index to item }
        }.maxByOrNull { it.first }?.second
    }

    fun addSummary(summary: SessionSummary) {
        require(summary.id.isNotBlank() && summaries.none { it.id == summary.id }) { "Duplicate or empty summary ID" }
        require(summary.throughEntryId in byId) { "Unknown summary boundary" }
        require(summary.text.isNotBlank()) { "Empty summary" }
        summaries += summary
    }

    fun addUsage(record: SessionUsage) {
        require(record.id.isNotBlank() && usages.none { it.id == record.id }) { "Duplicate or empty usage ID" }
        require(byId[record.assistantEntryId]?.message is AgentMessage.Assistant) {
            "Usage must reference an assistant message"
        }
        usages += record
    }

    fun addModelInvocation(record: SessionModelInvocation) {
        require(record.id.isNotBlank() && modelInvocations.none { it.id == record.id }) { "Duplicate or empty invocation ID" }
        require(byId[record.assistantEntryId]?.message is AgentMessage.Assistant) {
            "Model invocation must reference an assistant message"
        }
        modelInvocations += record
    }

    fun addQueuedInput(input: QueuedUserInput) {
        require(input.id.isNotBlank() && queuedInputs.none { it.id == input.id }) { "Duplicate or empty queue ID" }
        require(input.text.isNotBlank()) { "Empty queued input" }
        require(input.targetLeafId == null || input.targetLeafId in byId) { "Unknown queue branch anchor" }
        queuedInputs += input
    }

    fun markQueuedInputConsumed(queueId: String) {
        require(queuedInputs.any { it.id == queueId }) { "Unknown queued input" }
        require(consumedQueueIds.add(queueId)) { "Queued input was already consumed" }
    }

    fun append(message: AgentMessage, entryId: String, timestampEpochMillis: Long): SessionEntry {
        require(entryId.isNotBlank() && entryId !in byId) { "Duplicate or empty entry ID" }
        val entry = SessionEntry(entryId, leafId, timestampEpochMillis, message)
        entries += entry
        byId[entryId] = entry
        leafId = entryId
        return entry
    }

    fun branchTo(entryId: String?) {
        require(entryId == null || entryId in byId) { "Unknown branch point" }
        leafId = entryId
    }

    companion object {
        fun create(id: String): SessionTree = SessionTree(
            id, emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList(), emptySet())

        fun restore(id: String, entries: List<SessionEntry>, leafId: String?): SessionTree =
            SessionTree(id, entries, leafId, emptyList(), emptyList(), emptyList(), emptyList(), emptySet())

        fun restore(id: String, entries: List<SessionEntry>, leafId: String?, summaries: List<SessionSummary>): SessionTree =
            SessionTree(id, entries, leafId, summaries, emptyList(), emptyList(), emptyList(), emptySet())

        fun restore(id: String, entries: List<SessionEntry>, leafId: String?, summaries: List<SessionSummary>,
                    usages: List<SessionUsage>): SessionTree =
            SessionTree(id, entries, leafId, summaries, usages, emptyList(), emptyList(), emptySet())

        fun restore(id: String, entries: List<SessionEntry>, leafId: String?, summaries: List<SessionSummary>,
                    usages: List<SessionUsage>, queuedInputs: List<QueuedUserInput>,
                    consumedQueueIds: Set<String>): SessionTree =
            SessionTree(id, entries, leafId, summaries, usages, emptyList(), queuedInputs, consumedQueueIds)

        fun restore(id: String, entries: List<SessionEntry>, leafId: String?, summaries: List<SessionSummary>,
                    usages: List<SessionUsage>, modelInvocations: List<SessionModelInvocation>,
                    queuedInputs: List<QueuedUserInput>, consumedQueueIds: Set<String>): SessionTree =
            SessionTree(id, entries, leafId, summaries, usages, modelInvocations, queuedInputs, consumedQueueIds)
    }
}

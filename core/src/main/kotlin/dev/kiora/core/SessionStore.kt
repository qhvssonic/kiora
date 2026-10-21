package dev.kiora.core

interface SessionStore {
    suspend fun load(sessionId: String): SessionTree?
    suspend fun create(sessionId: String)
    suspend fun append(sessionId: String, entry: SessionEntry)
    suspend fun moveLeaf(sessionId: String, entryId: String?)
}

/** Optional durable summary capability for stores that can preserve branch metadata. */
interface SessionSummaryStore {
    suspend fun appendSummary(sessionId: String, summary: SessionSummary)
}

/** Optional metadata capability; stores without it can still serve model responses. */
interface SessionUsageStore {
    suspend fun appendUsage(sessionId: String, usage: SessionUsage)
}

/** Optional record of the physical model used for each response. */
interface SessionModelInvocationStore {
    suspend fun appendModelInvocation(sessionId: String, invocation: SessionModelInvocation)
}

/** Queued input is consumed atomically with the user message that applies it. */
interface SessionInputQueueStore {
    suspend fun enqueueInput(sessionId: String, input: QueuedUserInput)
    suspend fun appendQueuedMessage(sessionId: String, entry: SessionEntry, queueId: String)
}

/** Useful for tests and ephemeral tasks. Use a durable store for app restarts. */
class InMemorySessionStore : SessionStore, SessionSummaryStore, SessionUsageStore,
    SessionModelInvocationStore, SessionInputQueueStore {
    private val sessions = mutableMapOf<String, SessionTree>()

    override suspend fun load(sessionId: String): SessionTree? = synchronized(sessions) {
        sessions[sessionId]?.let {
            SessionTree.restore(it.id, it.allEntries(), it.leafId, it.allSummaries(), it.allUsages(),
                it.allModelInvocations(),
                it.allQueuedInputs(), it.consumedQueuedInputIds())
        }
    }

    override suspend fun create(sessionId: String) {
        synchronized(sessions) {
            require(sessionId !in sessions) { "Session already exists" }
            sessions[sessionId] = SessionTree.create(sessionId)
        }
    }

    override suspend fun append(sessionId: String, entry: SessionEntry) {
        synchronized(sessions) {
            val session = checkNotNull(sessions[sessionId]) { "Session not found" }
            require(entry.parentId == session.leafId) { "Stale session branch" }
            session.append(entry.message, entry.id, entry.timestampEpochMillis)
        }
    }

    override suspend fun moveLeaf(sessionId: String, entryId: String?) {
        synchronized(sessions) {
            checkNotNull(sessions[sessionId]) { "Session not found" }.branchTo(entryId)
        }
    }

    override suspend fun appendSummary(sessionId: String, summary: SessionSummary) {
        synchronized(sessions) {
            checkNotNull(sessions[sessionId]) { "Session not found" }.addSummary(summary)
        }
    }

    override suspend fun appendUsage(sessionId: String, usage: SessionUsage) {
        synchronized(sessions) {
            checkNotNull(sessions[sessionId]) { "Session not found" }.addUsage(usage)
        }
    }

    override suspend fun appendModelInvocation(sessionId: String, invocation: SessionModelInvocation) {
        synchronized(sessions) {
            checkNotNull(sessions[sessionId]) { "Session not found" }.addModelInvocation(invocation)
        }
    }

    override suspend fun enqueueInput(sessionId: String, input: QueuedUserInput) {
        synchronized(sessions) {
            checkNotNull(sessions[sessionId]) { "Session not found" }.addQueuedInput(input)
        }
    }

    override suspend fun appendQueuedMessage(sessionId: String, entry: SessionEntry, queueId: String) {
        synchronized(sessions) {
            val session = checkNotNull(sessions[sessionId]) { "Session not found" }
            require(entry.parentId == session.leafId) { "Stale session branch" }
            require(entry.message is AgentMessage.User) { "Queued input must become a user message" }
            require(session.pendingQueuedInputs().any { it.id == queueId }) { "Queued input is not pending here" }
            session.append(entry.message, entry.id, entry.timestampEpochMillis)
            session.markQueuedInputConsumed(queueId)
        }
    }
}

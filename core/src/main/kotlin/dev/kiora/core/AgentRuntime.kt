package dev.kiora.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One runtime can serve many sessions; calls for the same session are serialized. */
class AgentRuntime(
    private val model: ModelPort,
    tools: List<AgentTool>,
    private val store: SessionStore,
    private val authorizer: ToolAuthorizer,
    private val limits: RunLimits = RunLimits(),
    private val events: EventSink = EventSink { },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val contextTransformer: ModelContextTransformer = ModelContextTransformer { it },
    private val activeTools: ActiveToolSelector = ActiveToolSelector { _, available -> available },
    private val maxToolArgumentBytes: Int = 16 * 1024,
    private val contextWindowTokens: Int? = null,
    private val reserveOutputTokens: Int = 768,
    private val extraInputTokens: suspend () -> Int = { 0 },
    private val autoCompaction: AutoCompaction? = null,
    private val autoRunFollowUps: Boolean = true,
) {
    private val toolsByName = tools.associateBy { it.definition.name }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val queueLocks = ConcurrentHashMap<String, Mutex>()
    private val activeInputQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<QueuedUserInput>>()
    private val activeDiscardedQueues = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var observedContextWindowTokens = contextWindowTokens

    init {
        require(toolsByName.size == tools.size) { "Duplicate tool name" }
        require(maxToolArgumentBytes > 0) { "maxToolArgumentBytes must be positive" }
    }

    suspend fun run(sessionId: String, userText: String): RunResult = run(sessionId, userText, emptyList())

    suspend fun run(sessionId: String, userText: String, images: List<ToolImage>): RunResult =
        runInternal(sessionId, userText, images, null)

    /** Start a new run from one durable queued item, consuming it with the initial user message. */
    suspend fun runQueued(sessionId: String, queueId: String,
                          modelText: String? = null): RunResult {
        val session = checkNotNull(store.load(sessionId)) { "Session not found" }
        val input = checkNotNull(session.pendingQueuedInputs().firstOrNull { it.id == queueId }) {
            "Queued input is not pending here"
        }
        return runInternal(sessionId, modelText ?: input.text, emptyList(), queueId, input.text)
    }

    private suspend fun runInternal(sessionId: String, userText: String, images: List<ToolImage>,
                                    initialQueueId: String?, queuedSourceText: String? = null): RunResult =
        lockFor(sessionId).withLock {
        require(userText.isNotBlank())
        val liveInputs = ConcurrentLinkedQueue<QueuedUserInput>()
        val discardedInputs = ConcurrentHashMap.newKeySet<String>()
        activeInputQueues[sessionId] = liveInputs
        activeDiscardedQueues[sessionId] = discardedInputs
        try {
        val session = store.load(sessionId) ?: SessionTree.create(sessionId).also { store.create(sessionId) }
        if (initialQueueId != null) require(session.pendingQueuedInputs().any {
            it.id == initialQueueId && it.text == queuedSourceText
        }) { "Queued input is not pending here" }
        events.emit(AgentEvent.RunStarted(sessionId))

        suspend fun append(message: AgentMessage, sourceQueueId: String? = null): SessionEntry {
            val entry = session.append(message, newId(), now())
            if (sourceQueueId == null) store.append(sessionId, entry)
            else checkNotNull(store as? SessionInputQueueStore).appendQueuedMessage(sessionId, entry, sourceQueueId)
            events.emit(AgentEvent.MessageAdded(entry))
            return entry
        }

        suspend fun applyQueued(kind: QueuedInputKind, maxCount: Int): Boolean {
            if (store !is SessionInputQueueStore) return false
            return queueLockFor(sessionId).withLock {
                val pathIds = session.activeEntries().map { it.id }.toSet()
                val consumed = session.consumedQueuedInputIds() + discardedInputs
                val pending = (session.allQueuedInputs() + liveInputs.toList()).distinctBy { it.id }
                    .filter { it.kind == kind && it.id !in consumed &&
                        (it.targetLeafId == null || it.targetLeafId in pathIds) }.take(maxCount)
                pending.forEach { input ->
                    append(AgentMessage.User(input.text), input.id)
                    if (session.allQueuedInputs().none { it.id == input.id }) session.addQueuedInput(input)
                    session.markQueuedInputConsumed(input.id)
                }
                pending.isNotEmpty()
            }
        }

        val activeMessages = session.activeMessages()
        val lastNonResult = activeMessages.indexOfLast { it !is AgentMessage.ToolResult }
        val interruptedAnswer = activeMessages.getOrNull(lastNonResult) as? AgentMessage.Assistant
        if (interruptedAnswer != null && interruptedAnswer.toolCalls.isNotEmpty()) {
            val missing = interruptedAnswer.toolCalls.toMutableList()
            activeMessages.drop(lastNonResult + 1).forEach { message ->
                val result = message as AgentMessage.ToolResult
                val index = missing.indexOfFirst { it.id == result.callId && it.name == result.toolName }
                if (index >= 0) missing.removeAt(index)
            }
            for (call in missing) {
                events.emit(AgentEvent.ToolRequested(call))
                val result = AgentMessage.ToolResult(
                    call.id, call.name,
                    "Previous run ended before tool result was recorded; execution status is unknown", true,
                    failureKind = ToolFailureKind.INTERRUPTED,
                )
                append(result)
                events.emit(AgentEvent.ToolCompleted(result))
            }
        }

        val pendingToolCalls = mutableListOf<ToolCall>()
        var firstPendingRequested = false
        suspend fun completePending(reason: String) = withContext(NonCancellable) {
            while (pendingToolCalls.isNotEmpty()) {
                val call = pendingToolCalls.first()
                if (!firstPendingRequested) events.emit(AgentEvent.ToolRequested(call))
                val result = AgentMessage.ToolResult(call.id, call.name, reason, true,
                    failureKind = ToolFailureKind.INTERRUPTED)
                append(result)
                pendingToolCalls.removeAt(0)
                firstPendingRequested = false
                events.emit(AgentEvent.ToolCompleted(result))
            }
        }

        if (initialQueueId == null) append(AgentMessage.User(userText, images))
        else queueLockFor(sessionId).withLock {
            val latest = checkNotNull(store.load(sessionId)) { "Session not found" }
            require(latest.pendingQueuedInputs().any { it.id == initialQueueId && it.text == queuedSourceText }) {
                "Queued input is not pending here"
            }
            append(AgentMessage.User(userText, images), initialQueueId)
            session.markQueuedInputConsumed(initialQueueId)
        }
        val result = try {
            withTimeout(limits.timeoutMillis) {
                var toolCalls = 0
                repeat(limits.maxModelTurns) { turnIndex ->
                    val selectedNames = activeTools.select(sessionId, toolsByName.keys.toSet()).toSet()
                    val selectedTools = toolsByName.filterKeys { it in selectedNames }
                    val definitions = selectedTools.values.map { tool -> tool.definition }
                    var transformed = contextTransformer.transform(session.modelMessagesThrough())
                    val extraTokens = extraInputTokens()
                    autoCompaction?.let { policy ->
                        val estimated = transformed.sumOf(ContextWindowLimiter::estimate) +
                            definitions.sumOf(ContextWindowLimiter::estimate) +
                            extraTokens + reserveOutputTokens
                        if (estimated >= policy.triggerTokens) {
                            try {
                                if (compactSession(sessionId, session, policy.keepRecentTurns,
                                        policy.summarizer) != null)
                                    transformed = contextTransformer.transform(session.modelMessagesThrough())
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Summary generation is best effort; the window limiter remains the fallback.
                            }
                        }
                    }
                    suspend fun respond(window: Int?): ModelResponse {
                        val visible = if (window == null) transformed else ContextWindowLimiter.fit(
                            transformed, definitions, window,
                            reserveOutputTokens.coerceAtMost(window / 3), extraTokens,
                        )
                        val onDelta: suspend (String) -> Unit = { delta ->
                            events.emit(AgentEvent.AssistantTextDelta(sessionId, delta))
                        }
                        return if (model is UsageReportingModelPort)
                            model.respondStreamingWithUsage(visible, definitions, onDelta)
                        else ModelResponse(model.respondStreaming(visible, definitions, onDelta))
                    }
                    val response = try {
                        respond(observedContextWindowTokens)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val oldWindow = observedContextWindowTokens
                        if (oldWindow == null || !ContextWindowLimiter.isOverflow(error)) throw error
                        val reported = ContextWindowLimiter.reportedLimit(error)
                        val smaller = minOf(oldWindow * 3 / 4, (reported ?: oldWindow) - 128)
                        if (smaller <= 0) throw error
                        observedContextWindowTokens = smaller
                        respond(smaller)
                    }
                    val answer = response.assistant
                    val answerEntry = append(answer)
                    response.model?.let { modelIdentity ->
                        val record = SessionModelInvocation(newId(), answerEntry.id, now(), modelIdentity)
                        (store as? SessionModelInvocationStore)?.appendModelInvocation(sessionId, record)
                        events.emit(AgentEvent.ModelInvocationRecorded(sessionId, record))
                    }
                    response.usage?.let { usage ->
                        val record = SessionUsage(newId(), answerEntry.id, now(), usage)
                        (store as? SessionUsageStore)?.appendUsage(sessionId, record)
                        events.emit(AgentEvent.ModelUsageReported(sessionId, record))
                    }
                    if (answer.toolCalls.isEmpty()) {
                        if (turnIndex < limits.maxModelTurns - 1) {
                            if (applyQueued(QueuedInputKind.STEERING, Int.MAX_VALUE)) return@repeat
                            if (autoRunFollowUps && applyQueued(QueuedInputKind.FOLLOW_UP, 1)) return@repeat
                        }
                        return@withTimeout RunResult.Completed(answer.text, sessionId)
                    }
                    pendingToolCalls.addAll(answer.toolCalls)
                    var toolLimitReached = false
                    var attentionResult: AgentMessage.ToolResult? = null
                    for (call in answer.toolCalls) {
                        if (toolCalls >= limits.maxToolCalls) {
                            toolLimitReached = true
                            val result = AgentMessage.ToolResult(call.id, call.name, "Tool-call limit reached", true,
                                failureKind = ToolFailureKind.LIMIT_REACHED)
                            events.emit(AgentEvent.ToolRequested(call))
                            firstPendingRequested = true
                            append(result)
                            pendingToolCalls.removeAt(0)
                            firstPendingRequested = false
                            events.emit(AgentEvent.ToolCompleted(result))
                            continue
                        }
                        toolCalls++
                        events.emit(AgentEvent.ToolRequested(call))
                        firstPendingRequested = true
                        val tool = selectedTools[call.name]
                        val argumentError = tool?.let {
                            ToolArgumentValidator.validate(it.definition.parameters, call.arguments,
                                maxToolArgumentBytes)
                        }
                        val preflightError = if (tool is ToolArgumentPrevalidator && argumentError == null) {
                            try {
                                tool.validateArguments(call.arguments)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                error.message ?: error.javaClass.simpleName
                            }
                        } else null
                        val result = when {
                            tool == null -> AgentMessage.ToolResult(call.id, call.name, "Unknown or inactive tool", true,
                                failureKind = ToolFailureKind.UNKNOWN_OR_INACTIVE_TOOL)
                            call.argumentsError != null -> AgentMessage.ToolResult(
                                call.id, call.name, call.argumentsError, true,
                                failureKind = ToolFailureKind.INVALID_ARGUMENTS,
                            )
                            argumentError != null -> AgentMessage.ToolResult(call.id, call.name, argumentError, true,
                                failureKind = ToolFailureKind.INVALID_ARGUMENTS)
                            preflightError != null -> AgentMessage.ToolResult(call.id, call.name, preflightError, true,
                                failureKind = ToolFailureKind.INVALID_ARGUMENTS)
                            else -> {
                                try {
                                    when (val decision = authorizer.authorize(
                                        ToolAuthorizationRequest(sessionId, tool.definition, call)
                                    )) {
                                        is ToolAuthorization.Denied -> AgentMessage.ToolResult(
                                            call.id, call.name, decision.reason, true,
                                            failureKind = ToolFailureKind.AUTHORIZATION_DENIED,
                                        )
                                        ToolAuthorization.Allowed -> try {
                                            val output = if (tool is ProgressReportingAgentTool) {
                                                val acceptingProgress = AtomicBoolean(true)
                                                try {
                                                    tool.executeResult(call.arguments, ToolProgressSink { message ->
                                                        if (acceptingProgress.get()) {
                                                            events.emit(AgentEvent.ToolProgress(sessionId, call, message))
                                                        }
                                                    })
                                                } finally {
                                                    acceptingProgress.set(false)
                                                }
                                            } else tool.executeResult(call.arguments)
                                            if (!output.isError) {
                                                tool.definition.outputSchema?.let { schema ->
                                                    ToolArgumentValidator.validateResult(schema, output.structuredContent)?.let {
                                                        error(it)
                                                    }
                                                }
                                            }
                                            AgentMessage.ToolResult(call.id, call.name, output.text, output.isError,
                                                output.images, null, output.structuredContent, output.attention)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            AgentMessage.ToolResult(
                                                call.id, call.name, error.message ?: error.javaClass.simpleName, true,
                                                failureKind = ToolFailureKind.EXECUTION_FAILED,
                                            )
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    AgentMessage.ToolResult(
                                        call.id, call.name,
                                        "Tool authorization failed: ${error.message ?: error.javaClass.simpleName}", true,
                                        failureKind = ToolFailureKind.AUTHORIZATION_FAILED,
                                    )
                                }
                            }
                        }
                        append(result)
                        pendingToolCalls.removeAt(0)
                        firstPendingRequested = false
                        events.emit(AgentEvent.ToolCompleted(result))
                        if (result.attention != null && attentionResult == null) attentionResult = result
                    }
                    attentionResult?.let { return@withTimeout RunResult.NeedsAttention(sessionId, it) }
                    if (toolLimitReached) return@withTimeout RunResult.LimitReached("Tool-call limit reached", sessionId)
                    if (turnIndex < limits.maxModelTurns - 1)
                        applyQueued(QueuedInputKind.STEERING, Int.MAX_VALUE)
                }
                RunResult.LimitReached("Model-turn limit reached", sessionId)
            }
        } catch (_: TimeoutCancellationException) {
            completePending("Time limit reached before tool result was recorded; execution status is unknown")
            RunResult.LimitReached("Time limit reached", sessionId)
        } catch (cancelled: CancellationException) {
            completePending("Run cancelled before tool result was recorded; execution status is unknown")
            throw cancelled
        } catch (error: Exception) {
            RunResult.Failed(error.message ?: error.javaClass.simpleName, sessionId)
        }
        events.emit(AgentEvent.RunFinished(result))
        result
        } finally {
            activeInputQueues.remove(sessionId, liveInputs)
            activeDiscardedQueues.remove(sessionId, discardedInputs)
        }
    }

    suspend fun branch(sessionId: String, entryId: String?) = lockFor(sessionId).withLock {
        val session = checkNotNull(store.load(sessionId)) { "Session not found" }
        session.branchTo(entryId)
        store.moveLeaf(sessionId, entryId)
    }

    /** Safe to call while run() holds the session lock; applied at the next turn boundary. */
    suspend fun queueSteering(sessionId: String, text: String): QueuedUserInput =
        enqueueInput(sessionId, QueuedInputKind.STEERING, text)

    /** Runs after the current answer finishes; only one follow-up is applied per model turn. */
    suspend fun queueFollowUp(sessionId: String, text: String): QueuedUserInput =
        enqueueInput(sessionId, QueuedInputKind.FOLLOW_UP, text)

    /** Remove an item that has not yet entered the session; safe while run() is active. */
    suspend fun discardQueuedInput(sessionId: String, queueId: String) {
        val queueStore = store as? SessionInputQueueStore
            ?: error("This session store does not support queued inputs")
        queueLockFor(sessionId).withLock {
            val session = checkNotNull(store.load(sessionId)) { "Session not found" }
            require(session.pendingQueuedInputs().any { it.id == queueId }) {
                "Queued input is not pending here"
            }
            queueStore.discardQueuedInput(sessionId, queueId)
            activeDiscardedQueues[sessionId]?.add(queueId)
            activeInputQueues[sessionId]?.removeIf { it.id == queueId }
        }
    }

    private suspend fun enqueueInput(sessionId: String, kind: QueuedInputKind, text: String): QueuedUserInput {
        require(text.isNotBlank()) { "Empty queued input" }
        require(text.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "Queued input exceeds 16 KiB" }
        val queueStore = store as? SessionInputQueueStore
            ?: error("This session store does not support queued inputs")
        return queueLockFor(sessionId).withLock {
            val session = checkNotNull(store.load(sessionId)) { "Session not found" }
            val input = QueuedUserInput(newId(), kind, text, session.leafId, now())
            queueStore.enqueueInput(sessionId, input)
            activeInputQueues[sessionId]?.add(input)
            input
        }
    }

    /** Checkpoint completed older turns while retaining their original session entries. */
    suspend fun compact(
        sessionId: String,
        keepRecentTurns: Int = 2,
        summarizer: SessionSummarizer,
    ): SessionSummary? = lockFor(sessionId).withLock {
        val session = checkNotNull(store.load(sessionId)) { "Session not found" }
        compactSession(sessionId, session, keepRecentTurns, summarizer)
    }

    private suspend fun compactSession(sessionId: String, session: SessionTree,
        keepRecentTurns: Int, summarizer: SessionSummarizer): SessionSummary? {
        require(keepRecentTurns >= 1)
        val summaryStore = store as? SessionSummaryStore
            ?: error("This session store does not support durable summaries")
        val path = session.activeEntries()
        val userStarts = path.indices.filter { path[it].message is AgentMessage.User }
        if (userStarts.size <= keepRecentTurns) return null
        val boundaryIndex = userStarts[userStarts.size - keepRecentTurns] - 1
        val boundary = path.getOrNull(boundaryIndex) ?: return null
        val answer = boundary.message as? AgentMessage.Assistant ?: return null
        if (answer.toolCalls.isNotEmpty()) return null
        val previousBoundary = session.activeSummary()?.throughEntryId
        if (previousBoundary != null && path.indexOfFirst { it.id == previousBoundary } >= boundaryIndex)
            return null
        val text = summarizer.summarize(session.modelMessagesThrough(boundary.id)).trim()
        require(text.isNotBlank()) { "Empty session summary" }
        require(text.toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "Session summary exceeds 32 KiB" }
        val summary = SessionSummary(newId(), boundary.id, now(), text)
        summaryStore.appendSummary(sessionId, summary)
        session.addSummary(summary)
        return summary
    }

    suspend fun session(sessionId: String): SessionTree? = lockFor(sessionId).withLock {
        store.load(sessionId)
    }

    private fun lockFor(sessionId: String): Mutex = locks.computeIfAbsent(sessionId) { Mutex() }
    private fun queueLockFor(sessionId: String): Mutex = queueLocks.computeIfAbsent(sessionId) { Mutex() }
}

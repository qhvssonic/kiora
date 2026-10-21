package dev.kiora.core

import kotlin.math.ceil

/** Fits model-visible history to a known window without changing the durable session. */
object ContextWindowLimiter {
    fun fit(
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        contextWindowTokens: Int,
        reserveOutputTokens: Int,
        extraInputTokens: Int = 0,
    ): List<AgentMessage> {
        require(contextWindowTokens > 0)
        val inputBudget = contextWindowTokens - reserveOutputTokens - extraInputTokens -
            96 - tools.sumOf(::estimate)
        require(inputBudget > 0) { "模型上下文窗口不足以容纳系统提示和工具定义" }
        if (messages.isEmpty()) return messages

        val turns = messages.indices.filter { messages[it] is AgentMessage.User }
        if (turns.isEmpty()) return messages
        val currentStart = turns.last()
        var current = messages.drop(currentStart).toMutableList()
        var currentTokens = current.sumOf(::estimate)

        // Tool output can be much larger than the request that produced it. Keep its beginning
        // and a visible truncation marker, while retaining the original in the session tree.
        if (currentTokens > inputBudget) {
            for (index in current.indices) {
                val result = current[index] as? AgentMessage.ToolResult ?: continue
                val otherTokens = currentTokens - estimate(result)
                val allowed = inputBudget - otherTokens
                if (allowed <= 16) continue
                if (estimate(result) > allowed) {
                    val marker = "\n[工具结果已按上下文窗口截断]"
                    val contentBudget = allowed - 8 - estimateText(result.toolName) - estimateText(marker)
                    if (contentBudget <= 0) continue
                    var low = 0
                    var high = result.text.length
                    while (low < high) {
                        val middle = (low + high + 1) / 2
                        if (estimateText(result.text.take(middle)) <= contentBudget) low = middle
                        else high = middle - 1
                    }
                    current[index] = result.copy(text = result.text.take(low) + marker)
                    currentTokens = current.sumOf(::estimate)
                }
                if (currentTokens <= inputBudget) break
            }
        }
        require(currentTokens <= inputBudget) { "本轮输入超过模型上下文窗口，请缩短消息或减少工具输出" }

        val summary = (messages.firstOrNull() as? AgentMessage.ContextSummary)?.let { checkpoint ->
            val available = minOf(inputBudget - currentTokens, inputBudget / 3)
            if (available < 24) null
            else if (estimate(checkpoint) <= available) checkpoint
            else {
                val marker = "…[摘要已截断]"
                var low = 0
                var high = checkpoint.text.length
                while (low < high) {
                    val middle = (low + high + 1) / 2
                    if (estimate(AgentMessage.ContextSummary(checkpoint.text.take(middle) + marker)) <= available)
                        low = middle else high = middle - 1
                }
                AgentMessage.ContextSummary(checkpoint.text.take(low) + marker)
                    .takeIf { estimate(it) <= available }
            }
        }
        val historyBudget = inputBudget - (summary?.let(::estimate) ?: 0)

        var start = currentStart
        for (previous in turns.dropLast(1).asReversed()) {
            val candidate = messages.subList(previous, start)
            val candidateTokens = candidate.sumOf(::estimate)
            if (candidateTokens + currentTokens > historyBudget) break
            current.addAll(0, candidate)
            currentTokens += candidateTokens
            start = previous
        }
        if (summary != null) current.add(0, summary)
        if (start > if (summary != null) 1 else 0) {
            val marker = AgentMessage.User("[较早的对话已按模型上下文窗口省略]")
            if (currentTokens + (summary?.let(::estimate) ?: 0) + estimate(marker) <= inputBudget)
                current.add(if (summary != null) 1 else 0, marker)
        }
        return current
    }

    /** A conservative mixed ASCII/CJK fallback when the model API does not report usage. */
    fun estimateText(text: String): Int {
        var ascii = 0
        var other = 0
        text.forEach { character -> if (character.code < 128) ascii++ else other++ }
        return ceil(ascii / 3.5).toInt() + other
    }

    fun estimate(message: AgentMessage): Int = 8 + when (message) {
        is AgentMessage.User -> estimateText(message.text) + message.images.size * 1_024
        is AgentMessage.ContextSummary -> estimateText(message.text)
        is AgentMessage.Assistant -> estimateText(message.text) + message.toolCalls.sumOf {
            estimateText(it.name) + estimateText(it.arguments.toString()) + 8
        }
        is AgentMessage.ToolResult -> estimateText(message.toolName) + estimateText(message.text) +
            message.images.size * 1_024
    }

    fun estimate(tool: ToolDefinition): Int = 12 + estimateText(tool.name) +
        estimateText(tool.description) + estimateText(tool.parameters.toString())

    /** Reported limits take precedence over a stale model catalog entry. */
    fun reportedLimit(error: Throwable): Int? = Regex(
        "maximum number of tokens allowed:\\s*\\d+\\s*>=\\s*(\\d+)", RegexOption.IGNORE_CASE,
    ).find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    fun isOverflow(error: Throwable): Boolean = reportedLimit(error) != null ||
        listOf("context_length_exceeded", "input token ids are too long", "maximum context length")
            .any { error.message.orEmpty().contains(it, ignoreCase = true) }
}

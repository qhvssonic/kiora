package dev.kiora.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContextWindowLimiterTest {
    @Test
    fun `keeps the newest turn and leaves stored history intact`() {
        val history = listOf(
            AgentMessage.User("旧问题".repeat(350)),
            AgentMessage.Assistant("旧回答".repeat(350)),
            AgentMessage.User("新问题"),
        )
        val visible = ContextWindowLimiter.fit(history, emptyList(), 650, 150)
        assertTrue(visible.last() == history.last())
        assertTrue(visible.none { it is AgentMessage.Assistant })
        assertEquals(3, history.size)
    }

    @Test
    fun `caps a large tool result in the model view`() {
        val original = AgentMessage.ToolResult("call", "read", "内容".repeat(3000))
        val visible = ContextWindowLimiter.fit(
            listOf(AgentMessage.User("读取文件"), AgentMessage.Assistant(""), original),
            emptyList(), 900, 200,
        )
        val result = assertIs<AgentMessage.ToolResult>(visible.last())
        assertTrue(result.text.length < original.text.length)
        assertTrue(result.text.contains("截断"))
        assertEquals(6000, original.text.length)
    }

    @Test
    fun `keeps a summary checkpoint ahead of the current user turn`() {
        val visible = ContextWindowLimiter.fit(listOf(
            AgentMessage.ContextSummary("Earlier decisions and constraints"),
            AgentMessage.User("continue"),
        ), emptyList(), 500, 100)
        assertIs<AgentMessage.ContextSummary>(visible.first())
        assertEquals(AgentMessage.User("continue"), visible.last())
    }

    @Test
    fun `large summary is trimmed for a smaller mobile model window`() {
        val original = AgentMessage.ContextSummary("旧约束".repeat(500))
        val visible = ContextWindowLimiter.fit(listOf(original, AgentMessage.User("continue")),
            emptyList(), 450, 100)
        val summary = assertIs<AgentMessage.ContextSummary>(visible.first())
        assertTrue(summary.text.length < original.text.length)
        assertTrue(summary.text.contains("摘要已截断"))
    }

    @Test
    fun `reported model overflow shrinks the next attempt`() = runBlocking {
        var attempts = 0
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                attempts++
                if (attempts == 1) error("Input token ids are too long. Exceeding the maximum number of tokens allowed: 4202 >= 4096")
                return AgentMessage.Assistant("好了")
            }
        }
        val runtime = AgentRuntime(model, emptyList(), InMemorySessionStore(),
            { ToolAuthorization.Allowed }, contextWindowTokens = 8_192)
        assertEquals("好了", assertIs<RunResult.Completed>(runtime.run("s", "继续")).text)
        assertEquals(2, attempts)
    }
}

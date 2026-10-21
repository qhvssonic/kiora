package dev.kiora.sample

import dev.kiora.a2a.A2aDelegateTool
import dev.kiora.a2a.A2aPeer
import dev.kiora.a2a.RemoteTask
import dev.kiora.a2a.RemoteTaskState
import dev.kiora.core.AgentMessage
import dev.kiora.core.AgentRuntime
import dev.kiora.core.InMemorySessionStore
import dev.kiora.core.ModelPort
import dev.kiora.core.ToolAuthorization
import dev.kiora.core.ToolCall
import dev.kiora.core.ToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private class DemoDesktopPeer : A2aPeer {
    override suspend fun send(instruction: String): RemoteTask =
        RemoteTask("desktop-task-1", RemoteTaskState.COMPLETED, "电脑端已处理：$instruction")

    override suspend fun continueTask(taskId: String, instruction: String): RemoteTask =
        RemoteTask(taskId, RemoteTaskState.COMPLETED, instruction)

    override suspend fun getTask(taskId: String): RemoteTask = error("Demo task completes immediately")
    override suspend fun cancelTask(taskId: String) = Unit
}

fun main() = runBlocking {
    val model = object : ModelPort {
        override suspend fun respond(
            messages: List<AgentMessage>,
            tools: List<ToolDefinition>,
        ): AgentMessage.Assistant {
            val last = messages.last()
            return if (last is AgentMessage.ToolResult) {
                AgentMessage.Assistant("手机端收到结果：${last.text}")
            } else {
                AgentMessage.Assistant(
                    "我把这项工作委托给电脑端。",
                    listOf(ToolCall(
                        "call-1",
                        "delegate_to_desktop",
                        JsonObject(mapOf("instruction" to JsonPrimitive((last as AgentMessage.User).text))),
                    )),
                )
            }
        }
    }
    val runtime = AgentRuntime(
        model = model,
        tools = listOf(A2aDelegateTool(DemoDesktopPeer())),
        store = InMemorySessionStore(),
        authorizer = { ToolAuthorization.Allowed },
    )
    println(runtime.run("demo", "请电脑端整理报告"))
}

package dev.kiora.demo

import dev.kiora.core.AgentMessage
import dev.kiora.core.ModelPort
import dev.kiora.core.ToolCall
import dev.kiora.core.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Deterministic offline model for trying the UI without a model account. */
class DemoModelPort : ModelPort {
    override suspend fun respond(
        messages: List<AgentMessage>, tools: List<ToolDefinition>,
    ): AgentMessage.Assistant = when (val last = messages.last()) {
        is AgentMessage.ToolResult -> AgentMessage.Assistant(
            if (last.isError) "工具执行失败：${last.text}" else "执行结果：${last.text}",
        )
        is AgentMessage.User -> when {
            ("电脑" in last.text || "desktop" in last.text.lowercase()) &&
                tools.any { it.name == "delegate_to_desktop" || it.name.startsWith("a2a_") } ->
                AgentMessage.Assistant("", listOf(ToolCall(
                    "demo-desktop-${System.currentTimeMillis()}",
                    tools.firstOrNull { it.name == "delegate_to_desktop" }?.name
                        ?: tools.first { it.name.startsWith("a2a_") }.name,
                    JsonObject(mapOf("instruction" to JsonPrimitive(last.text))),
                )))
            ("设备" in last.text || "device" in last.text.lowercase()) &&
                tools.any { it.name == "device_info" } ->
                AgentMessage.Assistant("", listOf(ToolCall(
                    "demo-device-${System.currentTimeMillis()}", "device_info", JsonObject(emptyMap()),
                )))
            else -> AgentMessage.Assistant(
                "这是离线模拟回复。可以发送“查看设备信息”，或配置电脑端 Agent Card 后发送“请电脑处理这个任务”。",
            )
        }
        is AgentMessage.Assistant -> AgentMessage.Assistant("请继续输入任务。")
        is AgentMessage.ContextSummary -> AgentMessage.Assistant("请继续输入任务。")
    }
}

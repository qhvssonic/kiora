package dev.kiora.demo

import android.os.Build
import dev.kiora.core.AgentTool
import dev.kiora.core.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DeviceInfoTool : AgentTool {
    override val definition = ToolDefinition(
        name = "device_info",
        description = "Read the phone manufacturer, model, and Android version after user approval.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(emptyMap()),
        )),
    )

    override suspend fun execute(arguments: JsonObject): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
}

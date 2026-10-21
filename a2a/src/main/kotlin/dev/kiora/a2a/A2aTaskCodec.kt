package dev.kiora.a2a

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object A2aTaskCodec {
    fun decode(response: JsonObject, directMessageId: () -> String): RemoteTask {
        val direct = response["message"]?.jsonObject
        if (direct != null) return RemoteTask(directMessageId(), RemoteTaskState.COMPLETED,
            textParts(direct), direct["contextId"]?.jsonPrimitive?.content)
        val task = (response["task"] ?: response).jsonObject
        val id = task.getValue("id").jsonPrimitive.content
        val status = task.getValue("status").jsonObject
        val state = when (status.getValue("state").jsonPrimitive.content) {
            "TASK_STATE_SUBMITTED" -> RemoteTaskState.SUBMITTED
            "TASK_STATE_WORKING" -> RemoteTaskState.WORKING
            "TASK_STATE_INPUT_REQUIRED" -> RemoteTaskState.INPUT_REQUIRED
            "TASK_STATE_AUTH_REQUIRED" -> RemoteTaskState.AUTH_REQUIRED
            "TASK_STATE_COMPLETED" -> RemoteTaskState.COMPLETED
            "TASK_STATE_FAILED" -> RemoteTaskState.FAILED
            "TASK_STATE_CANCELED" -> RemoteTaskState.CANCELED
            "TASK_STATE_REJECTED" -> RemoteTaskState.REJECTED
            else -> error("Unsupported A2A task state")
        }
        val artifacts = task["artifacts"]?.let {
            if (it == JsonNull) emptyList() else it.jsonArray.mapNotNull { artifact -> textParts(artifact.jsonObject) }
        }.orEmpty()
        val output = artifacts.joinToString("\n").ifBlank {
            status["message"]?.jsonObject?.let(::textParts).orEmpty()
        }
        return RemoteTask(id, state, output.ifBlank { null }, task["contextId"]?.jsonPrimitive?.content)
    }

    private fun textParts(container: JsonObject): String? = container["parts"]?.jsonArray
        ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
        ?.joinToString("\n")
}

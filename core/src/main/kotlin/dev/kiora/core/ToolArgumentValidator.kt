package dev.kiora.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Validate the JSON Schema features used by Kiora tools before approval or execution. */
internal object ToolArgumentValidator {
    fun validate(schema: JsonObject, arguments: JsonObject, maxBytes: Int = 16 * 1024): String? {
        if (arguments.toString().toByteArray(Charsets.UTF_8).size > maxBytes)
            return "Tool arguments exceed $maxBytes bytes"
        return check(schema, arguments, "arguments")
    }

    fun validateResult(schema: JsonObject, result: JsonElement?): String? =
        if (result == null) "Tool structured result is missing" else check(schema, result, "result")

    private fun check(schema: JsonObject, value: JsonElement, path: String): String? {
        val type = (schema["type"] as? JsonPrimitive)?.content
        val matchesType = when (type) {
            null -> true
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
            "number" -> value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null
            "boolean" -> value is JsonPrimitive && !value.isString && value.content in setOf("true", "false")
            "null" -> value == JsonNull
            else -> return "$path has an unsupported schema type: $type"
        }
        if (!matchesType) return "$path must be $type"

        val choices = schema["enum"] as? JsonArray
        if (choices != null && value !in choices) return "$path is not an allowed value"

        val alternatives = schema["oneOf"] as? JsonArray
        if (alternatives != null) {
            val matches = alternatives.count { option ->
                option is JsonObject && check(option, value, path) == null
            }
            if (matches != 1) return "$path must match exactly one allowed shape"
        }

        if (value is JsonObject) {
            val required = schema["required"] as? JsonArray
            required?.forEach { field ->
                val name = (field as? JsonPrimitive)?.content ?: return "$path has an invalid required field"
                if (name !in value) return "$path.$name is required"
            }
            val properties = schema["properties"] as? JsonObject
            value.forEach { (name, child) ->
                val childSchema = properties?.get(name) as? JsonObject
                if (childSchema != null) {
                    check(childSchema, child, "$path.$name")?.let { return it }
                } else if (schema["additionalProperties"] == JsonPrimitive(false)) {
                    return "$path.$name is not allowed"
                }
            }
        }
        if (value is JsonArray) {
            val itemSchema = schema["items"] as? JsonObject
            if (itemSchema != null) value.forEachIndexed { index, child ->
                check(itemSchema, child, "$path[$index]")?.let { return it }
            }
        }
        return null
    }
}

package dev.kiora.android.sandbox

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import dev.kiora.core.AgentTool
import dev.kiora.core.ToolDefinition
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Runs model-supplied JavaScript in Jetpack JavaScriptEngine's isolated process. */
class IsolatedJavaScriptTool(
    context: Context,
    private val timeoutMillis: Long = 10_000,
    private val maxCodeBytes: Int = 16 * 1024,
    private val maxHeapBytes: Long = 32L * 1024 * 1024,
) : AgentTool {
    private val appContext = context.applicationContext

    init {
        require(timeoutMillis > 0 && maxCodeBytes > 0 && maxHeapBytes > 0)
    }

    override val definition = ToolDefinition(
        name = "run_javascript",
        description = "Evaluate standalone synchronous JavaScript in an isolated process. The last expression is returned as text. No device files, Android APIs, or network bridge are provided. This requires host permission.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "code" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("code"))),
        )),
    )

    override suspend fun execute(arguments: JsonObject): String {
        val code = arguments["code"]?.jsonPrimitive?.content ?: error("code is required")
        require(code.toByteArray(Charsets.UTF_8).size <= maxCodeBytes) { "Code exceeds sandbox limit" }
        check(JavaScriptSandbox.isSupported()) { "JavaScript sandbox is unavailable on this device" }
        // JavaScriptEngine permits one connected sandbox per app; serialize creation and teardown.
        return withTimeout(timeoutMillis) {
            processLock.withLock {
                val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(appContext).await()
                try {
                    val settings = IsolateStartupParameters()
                    if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                        settings.maxHeapSizeBytes = maxHeapBytes
                    }
                    val isolate = sandbox.createIsolate(settings)
                    try {
                        // The API returns an empty result for non-string JS values; stringify inside the isolate.
                        isolate.evaluateJavaScriptAsync("String((0, eval)(${JsonPrimitive(code)}))").await()
                    } finally {
                        isolate.close()
                    }
                } finally {
                    sandbox.close()
                }
            }
        }
    }

    private companion object { val processLock = Mutex() }
}

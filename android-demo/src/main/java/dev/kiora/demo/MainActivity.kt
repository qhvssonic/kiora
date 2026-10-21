package dev.kiora.demo

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kiora.a2a.A2aDelegateTool
import dev.kiora.a2a.A2aPeerConnector
import dev.kiora.android.sandbox.IsolatedJavaScriptTool
import dev.kiora.core.AgentEvent
import dev.kiora.core.AgentMessage
import dev.kiora.core.AgentRuntime
import dev.kiora.core.JsonlSessionStore
import dev.kiora.core.ModelPort
import dev.kiora.core.RunResult
import dev.kiora.core.ToolAuthorization
import dev.kiora.core.ToolCall
import dev.kiora.model.ChatCompletionModelPort
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("kiora_demo", MODE_PRIVATE) }
    private val sessionStore by lazy { JsonlSessionStore(filesDir.resolve("sessions")) }
    private lateinit var sessionId: String
    private var runtime: AgentRuntime? = null

    private lateinit var settingsPanel: View
    private lateinit var mockMode: CheckBox
    private lateinit var codeSandboxEnabled: CheckBox
    private lateinit var modelEndpoint: EditText
    private lateinit var modelName: EditText
    private lateinit var modelKey: EditText
    private lateinit var agentCardUrl: EditText
    private lateinit var a2aToken: EditText
    private lateinit var transcript: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var status: TextView
    private lateinit var sendButton: Button
    private lateinit var connectButton: Button
    private var remoteSkillNames: List<String> = emptyList()
    private var showingStream = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = prefs.getString("session_id", null) ?: newSessionId()
        buildUi()
        restoreSettings()
        scope.launch { showSavedConversation() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFF5F7FC.toInt())
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "Kiora"
            textSize = 26f
            setTextColor(0xFF172449.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "手机端智能体 · 本机工具 · 电脑端 A2A"
            textSize = 13f
            setTextColor(0xFF5B6783.toInt())
        })

        val topActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(topActions)
        topActions.addView(Button(this).apply {
            text = "配置"
            setOnClickListener {
                settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        topActions.addView(Button(this).apply {
            text = "新会话"
            setOnClickListener {
                sessionId = newSessionId()
                transcript.text = ""
                status.text = "已创建新会话"
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        val configScroll = ScrollView(this)
        settingsPanel = configScroll
        root.addView(configScroll, LinearLayout.LayoutParams(-1, dp(300)))
        val config = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        configScroll.addView(config)
        mockMode = CheckBox(this).apply { text = "离线模拟模型（无需密钥）"; isChecked = true }
        config.addView(mockMode)
        codeSandboxEnabled = CheckBox(this).apply { text = "启用独立进程 JavaScript 工具（每次调用仍需确认）" }
        config.addView(codeSandboxEnabled)
        modelEndpoint = input(config, "模型地址（站点地址会自动补全 /v1/chat/completions）", "https://example.com")
        modelName = input(config, "模型名称", "例如服务商提供的模型 ID")
        modelKey = input(config, "模型 API Key（仅本次运行保留）", "可留空用于本地模型", true)
        agentCardUrl = input(config, "电脑端 Agent Card 地址（可选）", "https://…/.well-known/agent-card.json")
        a2aToken = input(config, "电脑端 Bearer Token（可选，仅本次运行保留）", "Token", true)
        connectButton = Button(this).apply {
            text = "应用配置并连接"
            setOnClickListener { scope.launch { connectAndReport() } }
        }
        config.addView(connectButton)

        status = TextView(this).apply {
            text = "离线模拟模式可直接发送消息"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(0xFF3156C9.toInt())
        }
        root.addView(status)
        transcriptScroll = ScrollView(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        root.addView(transcriptScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        transcript = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF172449.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        transcriptScroll.addView(transcript)

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(composer)
        messageInput = EditText(this).apply {
            hint = "输入任务…"
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        composer.addView(messageInput, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = Button(this).apply {
            text = "发送"
            setOnClickListener { scope.launch { send() } }
        }
        composer.addView(sendButton)
    }

    private fun input(parent: LinearLayout, label: String, hintText: String, secret: Boolean = false): EditText {
        parent.addView(TextView(this).apply { text = label; textSize = 13f })
        return EditText(this).also { field ->
            field.hint = hintText
            field.setSingleLine(true)
            field.textSize = 15f
            if (secret) field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            parent.addView(field, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun restoreSettings() {
        mockMode.isChecked = prefs.getBoolean("mock", true)
        modelEndpoint.setText(prefs.getString("model_endpoint", ""))
        modelName.setText(prefs.getString("model_name", ""))
        agentCardUrl.setText(prefs.getString("card_url", ""))
    }

    private suspend fun connectAndReport(): Boolean {
        connectButton.isEnabled = false
        status.text = "正在连接…"
        return try {
            connect()
            val modelStatus = if (mockMode.isChecked) "离线模型已就绪" else "模型已配置"
            status.text = if (remoteSkillNames.isEmpty()) modelStatus else
                "$modelStatus · 电脑端能力：${remoteSkillNames.joinToString("、") }"
            settingsPanel.visibility = View.GONE
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            status.text = "连接失败：${error.message}"
            false
        } finally {
            connectButton.isEnabled = true
        }
    }

    private suspend fun connect() {
        val mock = mockMode.isChecked
        val endpoint = modelEndpoint.text.toString().trim()
        val modelId = modelName.text.toString().trim()
        val key = modelKey.text.toString().trim()
        val cardUrl = agentCardUrl.text.toString().trim()
        val token = a2aToken.text.toString().trim()
        if (!mock) require(endpoint.isNotBlank() && modelId.isNotBlank()) { "请填写模型地址与名称" }
        prefs.edit()
            .putBoolean("mock", mock)
            .putString("model_endpoint", endpoint)
            .putString("model_name", modelId)
            .putString("card_url", cardUrl)
            .apply()

        val model: ModelPort = if (mock) DemoModelPort() else ChatCompletionModelPort(
            endpoint = endpoint,
            model = modelId,
            apiKey = { key.ifBlank { null } },
        )
        val tools = mutableListOf<dev.kiora.core.AgentTool>(DeviceInfoTool())
        if (codeSandboxEnabled.isChecked) tools += IsolatedJavaScriptTool(this)
        remoteSkillNames = emptyList()
        if (cardUrl.isNotBlank()) {
            val connection = A2aPeerConnector.discoverConnection(
                cardUrl,
                bearerToken = { token.ifBlank { null } },
                rewriteLoopbackHost = true,
            )
            remoteSkillNames = connection.skills.map { it.name }
            if (connection.skills.isEmpty()) tools += A2aDelegateTool(connection.peer)
            else tools += connection.skillTools()
        }
        runtime = AgentRuntime(
            model = model,
            tools = tools,
            store = sessionStore,
            authorizer = { request -> confirmTool(request.call) },
            events = { event -> withContext(Dispatchers.Main) { showEvent(event) } },
        )
    }

    private suspend fun send() {
        val input = messageInput.text.toString().trim()
        if (input.isBlank()) return
        if (runtime == null && !connectAndReport()) return
        val agent = runtime ?: return
        messageInput.text.clear()
        sendButton.isEnabled = false
        status.text = "正在执行…"
        try {
            when (val result = agent.run(sessionId, input)) {
                is RunResult.Completed -> status.text = "已完成"
                is RunResult.NeedsAttention -> status.text = "需要处理：${result.toolResult.text}"
                is RunResult.Failed -> status.text = "执行失败：${result.reason}"
                is RunResult.LimitReached -> status.text = "已停止：${result.reason}"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            status.text = "执行失败：${error.message}"
        } finally {
            sendButton.isEnabled = true
        }
    }

    private suspend fun showSavedConversation() {
        val history = sessionStore.load(sessionId)?.activeMessages().orEmpty()
        history.forEach { message ->
            when (message) {
                is AgentMessage.User -> appendChat("你", message.text)
                is AgentMessage.Assistant -> if (message.text.isNotBlank()) appendChat("Kiora", message.text)
                is AgentMessage.ToolResult -> appendChat("工具", message.text)
                is AgentMessage.ContextSummary -> Unit
            }
        }
    }

    private fun showEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageAdded -> when (val message = event.entry.message) {
                is AgentMessage.User -> appendChat("你", message.text)
                is AgentMessage.Assistant -> {
                    if (showingStream) {
                        transcript.append("\n\n")
                        showingStream = false
                    } else if (message.text.isNotBlank()) appendChat("Kiora", message.text)
                }
                is AgentMessage.ToolResult -> appendChat("工具", message.text)
                is AgentMessage.ContextSummary -> Unit
            }
            is AgentEvent.AssistantTextDelta -> {
                if (!showingStream) {
                    transcript.append("Kiora：")
                    showingStream = true
                }
                transcript.append(event.delta)
                transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
            }
            is AgentEvent.ToolRequested -> status.text = "请求工具：${event.call.name}"
            is AgentEvent.ToolProgress -> status.text = "${event.call.name}：${event.message}"
            is AgentEvent.ToolCompleted -> status.text = "工具已返回：${event.result.toolName}"
            is AgentEvent.RunFinished -> if (showingStream) {
                transcript.append("\n\n")
                showingStream = false
            }
            is AgentEvent.RunStarted -> Unit
            is AgentEvent.ModelUsageReported -> Unit
            is AgentEvent.ModelInvocationRecorded -> Unit
        }
    }

    private fun appendChat(speaker: String, text: String) {
        transcript.append("$speaker：$text\n\n")
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private suspend fun confirmTool(call: ToolCall): ToolAuthorization = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this)
            .setTitle("允许工具执行？")
            .setMessage("工具：${call.name}\n参数：${call.arguments}")
            .setPositiveButton("允许") { _, _ ->
                if (continuation.isActive) continuation.resume(ToolAuthorization.Allowed)
            }
            .setNegativeButton("拒绝") { _, _ ->
                if (continuation.isActive) continuation.resume(ToolAuthorization.Denied("用户拒绝"))
            }
            .setOnCancelListener {
                if (continuation.isActive) continuation.resume(ToolAuthorization.Denied("用户取消"))
            }
            .create()
        continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
        dialog.show()
    }

    private fun newSessionId(): String = "chat_${UUID.randomUUID()}".also {
        prefs.edit().putString("session_id", it).apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

# 模型、流式事件与代码沙箱

## 多个 Chat Completions 兼容端点

`CompatibleModelProviders` 让一个 `AgentRuntime` 在多组兼容端点之间切换。配置只保存端点和模型 ID；API Key 由宿主回调提供，不写入 SDK 配置或会话文件。

```kotlin
val providers = CompatibleModelProviders(
    profiles = listOf(
        CompatibleProvider("cloud", "云端", "https://example.com", "model-a"),
        CompatibleProvider("local", "本地", "http://192.168.1.5:8000/v1/chat/completions", "model-b", supportsStreaming = false),
    ),
    apiKeyFor = { id -> credentialStore.keyFor(id) },
)
providers.select("cloud")
val runtime = AgentRuntime(model = providers, tools = tools, store = store,
    authorizer = authorizer)
// 在下一次模型请求前切换；会话树仍由同一个 runtime 管理。
providers.select("local")
```

站点根地址会自动补全为 `/v1/chat/completions`，`https://example.com/v1` 会补全为 `/v1/chat/completions`；已经带有端点路径的地址保持原样，适合 DeepSeek 等使用不同路径的服务。此规则同时适用于 `CompatibleProvider` 和直接创建的 `ChatCompletionModelPort`。

这只覆盖 Chat Completions 兼容协议。端点若不支持 `stream: true`，将该 profile 设为 `supportsStreaming = false`，适配器会使用普通响应。需要 Anthropic/Gemini 等原生协议时，应单独实现 `ModelPort`。

## 流式事件

兼容端点支持 SSE 时，`ChatCompletionModelPort` 一边解析文本和分片工具调用，一边发出 `AgentEvent.AssistantTextDelta`。`AgentRuntime` 只在完整响应到达后写入一次最终 Assistant 消息，所以 UI 可以实时展示，持久会话不会保存碎片。流异常或缺少 `[DONE]` 时本轮返回失败；已显示的临时文本由宿主 UI 处理。事件可能从 I/O 线程回调，Android UI 要切换到主线程。

如果兼容端点以 `finish_reason: length` 结束且返回了工具调用，适配器会将所有调用标为参数错误，即使其参数片段恰好仍是合法 JSON。运行时会给模型写入错误工具结果，不会请求授权或执行这些调用。

```kotlin
events = { event ->
    if (event is AgentEvent.AssistantTextDelta) {
        withContext(Dispatchers.Main) { transcript.append(event.delta) }
    }
}
```

## 工具授权与代码沙箱

Kiora core 使用 `ActiveToolSelector` 选择本轮可用工具；工具调用再经过参数校验与 `ToolAuthorizer`。参数默认最多 16 KiB。每次有效调用都会把包含 `sessionId`、工具定义和调用参数的 `ToolAuthorizationRequest` 交给宿主；宿主可自动允许、拒绝或暂停等待用户确认。core 不提供按工具声明能力检查的伪沙箱；实际文件和设备访问仍由工具实现、宿主应用和 Android 系统权限约束。

授权回调抛出普通异常时，运行时会把失败写成该调用的错误工具结果，不会执行工具。运行超时或外部取消时，已写入会话但尚未完成的工具调用也会补写错误结果；外部取消仍向宿主传播。若进程在工具结果落盘前退出，下次运行同一会话时会为末尾缺失的结果补写中断错误，再追加新的用户消息。中断错误明确标记执行状态未知，因为工具可能已产生副作用，但结果尚未记录。

错误工具结果仍通过 `isError` 告诉模型调用失败；`ToolResult.failureKind` 是宿主可读取的结构化分类：`UNKNOWN_OR_INACTIVE_TOOL`、`INVALID_ARGUMENTS`、`AUTHORIZATION_DENIED`、`AUTHORIZATION_FAILED`、`EXECUTION_FAILED`、`LIMIT_REACHED`、`INTERRUPTED`。旧会话中没有此字段的错误仍可读取，分类为 `null`。宿主可据此展示原因或统计失败类型，但 `INTERRUPTED` 只说明结果未可靠记录，不能推断工具副作用没有发生。

长时间运行的工具可选择实现 `ProgressReportingAgentTool`，在 `executeResult(arguments, progress)` 中调用 `progress.report(message)`。运行时会发出 `AgentEvent.ToolProgress(sessionId, call, message)`；这些更新不会加入模型上下文或会话存储，工具结束后回调不再产生进度事件。默认 A2A 委托和 skill 工具会在远端任务从 submitted 变为 working 等状态变化时报告进度；如果宿主为 skill 提供自定义 `messageSender`，其发送流程仍由宿主自行上报。工具的最终结果仍由 `ToolCompleted` 和会话记录承载。

可选的 `android-code-sandbox` 提供真正的独立进程执行。`IsolatedJavaScriptTool(context)` 通过 AndroidX JavaScriptEngine 运行 JavaScript；SDK 不向脚本提供文件、网络或 Android API 桥。仅在宿主显式加入工具并通过 `ToolAuthorizer` 后才执行。代码输入上限 16 KiB，默认执行超时 10 秒；设备支持时设置 32 MiB 的 isolate 堆上限。底层不支持此功能的设备会返回工具错误。它不是 Kotlin、Shell 或任意二进制程序沙箱，也不替代 Android 系统权限或用户授权。

```kotlin
val codeTool = IsolatedJavaScriptTool(applicationContext)
val runtime = AgentRuntime(
    model = providers, tools = listOf(codeTool), store = store,
    authorizer = authorizer,
)
```

AndroidX 依据：[JavaScriptEngine 指南](https://developer.android.com/develop/ui/views/layout/webapps/jsengine)、[JavaScriptSandbox API](https://developer.android.com/reference/androidx/javascriptengine/JavaScriptSandbox)。

# 会话摘要、用量与运行中指令

这些能力属于 Kiora SDK 的会话与运行时接口。Android 宿主决定何时生成摘要、如何展示用量和如何接收用户的运行中指令；`kiora-companion` 是其中一个宿主，并非这些接口的定义处。

## 持久摘要

`AgentRuntime.compact(sessionId, keepRecentTurns = 2, summarizer = …)` 选择当前分支上已完成的较早轮次，请宿主提供的 `SessionSummarizer` 生成文本，再通过 `SessionSummaryStore` 追加摘要。返回 `null` 表示没有新的完整前缀可摘要。`InMemorySessionStore` 与 `JsonlSessionStore` 已实现该存储接口；自定义存储需要实现它才能使用 `compact()`。

```kotlin
runtime.compact("chat_1", keepRecentTurns = 2) { earlierContext ->
    summaryModel.summarize(earlierContext) // 宿主实现；返回不超过 32 KiB 的摘要文本
}
```

摘要锚定到一个已完成的 assistant 消息。`SessionTree.activeMessages()` 和 `allEntries()` 保留全部原始消息；`modelMessagesThrough()` 只在模型视图中用当前分支最深的摘要替换它覆盖的前缀。切回摘要锚点之前的分支时不会带上该摘要。下一次摘要的输入包含上一份摘要和新增的原始消息，便于递进压缩。Chat Completions 适配器将摘要作为 system 上下文发送，LiteRT-LM 将其加入 system instruction。若模型窗口仍不足，`ContextWindowLimiter` 会优先保留当前用户轮次，并按可用预算截短摘要。

SDK 不自行选择摘要模型，默认也不自动生成摘要；宿主可根据实际上下文窗口、用量和设备成本决定触发时机。摘要来自模型时，宿主应检查其内容是否足以保留未完成任务和用户约束。

需要自动摘要的宿主可向 `AgentRuntime` 传入 `AutoCompaction(triggerTokens, summarizer, keepRecentTurns)`。运行时在每次模型请求前估算消息、工具定义和输出预留量，达到宿主设定的预算后，尝试摘要已完成的较早轮次。摘要持久化失败时继续执行，并在配置了上下文窗口时用输入裁剪兜底。`triggerTokens` 是宿主资源预算，不代表模型架构的最大上下文长度。

## 模型用量

可选的 `UsageReportingModelPort` 返回 `ModelResponse(assistant, usage, model)`。运行时把有报告值的 `ModelUsage` 关联到对应 assistant 节点，写入支持 `SessionUsageStore` 的会话存储，并发出 `AgentEvent.ModelUsageReported`。模型身份即使没有用量也可独立记录：支持 `SessionModelInvocationStore` 的存储会保存 `SessionModelInvocation`，宿主可从 `SessionTree.activeModelInvocations()` 查看当前分支，并收到 `AgentEvent.ModelInvocationRecorded`。两类记录都不会作为聊天消息发给模型。

`model-chat` 读取 Chat Completions 响应中的 `usage`；流式响应若服务端发送用量块，也会记录。只有已知兼容端点支持时才需要设置 `requestStreamingUsage = true`，这会请求 `stream_options.include_usage`；默认关闭以兼容不接受该字段的服务。LiteRT-LM 会记录本地模型文件名作为模型身份，但不提供精确 token 用量；运行时的上下文窗口估算仍是估算。

`CompatibleModelProviders` 会透传用量，并把调用时选定的 profile ID 与服务端实际返回的模型 ID 关联；服务端未返回模型 ID 时使用请求模型 ID。MNN 本地适配器标记 `mnn-local`。其他模型适配器可按需返回 `ModelIdentity`；没有身份信息时 SDK 不猜测。

## 结构化工具结果与等待状态

工具可在 `ToolOutput` 中返回 `structuredContent`、`isError` 和 `attention`，同时保持 `text` 作为模型可读结果。可选的 `ToolDefinition.outputSchema` 由运行时在执行后校验；不匹配会成为工具执行错误。结构化结果保存在 `AgentMessage.ToolResult` 中供宿主读取，不发送给模型。旧工具仍可只返回文本。

当工具返回 `ToolAttention.INPUT_REQUIRED` 或 `AUTH_REQUIRED` 时，运行时先保存当前批次全部工具结果，再以 `RunResult.NeedsAttention` 结束本次运行。A2A 工具把远端 task ID、状态和输出放入结构化结果，宿主可用 task ID 发起后续用户输入。普通完成结果仍继续进入下一次模型调用。宿主决定怎样展示请求及取得用户输入；SDK 不自行弹窗。

需要自行处理远端传输的宿主可在 `A2aAgentConnection.skillTools(resultSender = …)` 中返回 `ToolOutput`，沿用 SDK 的结构化结果与等待状态；仅返回文本的旧 `messageSender` 参数仍可使用。

## 运行中指令队列

会话存在后，宿主可以在 `run()` 尚未结束时调用 `runtime.queueSteering(sessionId, text)` 或 `runtime.queueFollowUp(sessionId, text)`；两者都不等待该会话的运行锁。文本上限为 16 KiB。存储需实现 `SessionInputQueueStore`，SDK 自带的内存和 JSONL 存储均支持。

运行中即时交付应使用正在执行该会话的同一个 `AgentRuntime` 实例；其他实例写入的队列仍会持久化，并在该分支下一次运行时读取。JSONL 存储面向单进程使用。

- **Steering**：在当前工具批次结束后，或无工具的当前回答结束后，优先作为用户消息进入下一次模型调用；同一边界的多条 steering 按入队顺序加入。
- **Follow-up**：在当前回答完成后，每次取一条作为下一轮用户消息。若有 steering，先处理 steering。
- **额度与恢复**：没有剩余模型轮次时，指令仍保持待处理。队列条目锚定入队时的会话分支；切到不包含锚点的分支时不会消费。JSONL 把消费来源 ID 写在用户消息同一条记录中，进程中断后可识别已消费项，避免重复追加。若指令在运行结束前未被处理，它会留到下一次该分支运行的轮次边界。

队列处理会消耗同一次 `run()` 的模型轮次、工具调用和总时长额度。宿主可以通过 `SessionTree.pendingQueuedInputs()` 查看当前分支仍待处理的指令，并按界面需要显示或提醒用户。

宿主需要让用户选中某条待处理指令立即继续时，可先取消当前 `run()` 协程，再调用 `runtime.runQueued(sessionId, queueId)`。SDK 会在新一轮开始时把这条指令与用户消息原子消费，避免作为 follow-up 重复执行。`runtime.discardQueuedInput(sessionId, queueId)` 可移除尚未进入会话的指令；这两个操作只接受当前分支仍待处理的 ID。取消本地运行不保证已执行的工具副作用或远端 A2A 任务被撤销。

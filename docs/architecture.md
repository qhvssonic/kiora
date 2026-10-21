# Kiora 架构

Kiora 将手机端智能体拆成五个可替换的边界：

```mermaid
flowchart LR
    App[Android 宿主应用] --> Runtime[AgentRuntime]
    Runtime --> Model[ModelPort]
    Runtime --> Tools[AgentTool]
    Runtime --> Store[SessionStore]
    Runtime --> Policy[ToolAuthorizer]
    Runtime --> Context[ModelContextTransformer]
    Tools --> A2A[A2A Peer]
    A2A --> Desktop[电脑端 Agent]
```

一次运行的顺序是：写入用户消息 → 投影当前分支的摘要与原始消息 → 转换模型上下文 → 请求模型 → 授权并执行工具 → 写入工具结果 → 再次请求模型。原始消息追加到会话树；`ModelContextTransformer` 只改变发给模型的视图，不删除原始记录。模型可以使用多个工具，但每次运行受轮数、调用次数和总时间限制。用量、摘要与排队指令作为独立元数据持久化。

宿主已知模型实际上下文窗口时，可向 `AgentRuntime` 传入 `contextWindowTokens`。运行时按消息文本、工具定义和额外模态开销估算输入，预留回复空间，优先保留最近完整轮次；单轮工具结果过长时仅截断模型视图，会话树仍保存完整结果。模型若报告上下文超限，运行时根据报出的上限缩小窗口并重试一次。未知窗口不做猜测，由宿主提供模型目录值或用户配置；估算是保守回退，不能替代模型原生 token 用量。

## 与 pi 的对应关系

| pi 中的设计 | Kiora 当前实现 | 移动端取舍 |
| --- | --- | --- |
| 小型 agent loop | `core/AgentRuntime` | 不绑定 Android 生命周期；宿主负责启动和取消协程 |
| 模型适配层 | `ModelPort`、`model-chat` | 可换云端或本地模型 |
| 工具与调用事件 | `AgentTool`、`AgentEvent` | 本轮工具选择、参数校验和 `ToolAuthorizer` 授权；工具错误有结构化分类，文本与可选工具进度以事件上报 |
| 分支会话 | `SessionTree`、`SessionStore` | JSONL 是原型实现，大规模数据可换 Room；摘要和排队指令跟随分支 |
| 上下文转换 | `ModelContextTransformer`、`SessionSummarizer` | 可按设备内存和模型上下文窗口裁剪或持久压缩历史 |
| 外部能力 | `a2a` 模块 | 从 Agent Card 发现能力，并以 A2A 消息执行 |

Kiora 不把 pi 的终端工具、文件系统权限或 CLI 默认行为直接搬到手机。Android 宿主应通过系统文件选择器获得用户明确授予的文件 URI，通过 `ContentResolver` 读取，并在传输前显示目标和文件信息。

### 值得继续借鉴的能力

以下按 SDK 对移动端的实际收益排序；`kiora-companion` 是 SDK 宿主，可在 SDK 接口成熟后再接入界面。

| 优先级 | pi 的实现 | Kiora 现状与建议 |
| --- | --- | --- |
| 已接入 | 工具执行期间通过 `tool_execution_update` 报告中间结果 | `ProgressReportingAgentTool` 可上报临时进度，运行时发出 `AgentEvent.ToolProgress`；默认 A2A 委托与 skill 工具报告远端状态变化。宿主自行决定是否显示，最终结果仍单独持久化。 |
| 已接入 | 会话树上的 compaction 条目和分支摘要 | `SessionSummaryStore` 追加摘要记录，模型视图使用当前分支最深摘要与其后的原始消息；`compact()` 由宿主触发并提供摘要函数。 |
| 已接入 | 模型返回 token usage，供上下文管理和成本统计 | `UsageReportingModelPort` 可返回用量，记录在 assistant 节点旁；无精确数据的端侧模型继续使用估算。 |
| 已接入 | 在轮次边界读取 steering 与 follow-up 队列 | SDK 支持持久排队、分支锚定和与用户消息原子消费；运行中的排队调用不等待会话运行锁。 |
| 低 | 针对独立工具配置顺序或并行执行 | SDK 当前顺序执行。只在有明确只读、无交互授权的工具场景下考虑可选并行；修改类工具保持顺序。 |
| 低 | 工具的 `structuredContent` 和输出 Schema | SDK 当前为文本和图片。待宿主需要卡片、表格等机器可读结果时，再增加与模型可见文本分离的结构化结果。 |

已补齐的安全细节：参考 pi 对 `length` 结束原因的处理，`model-chat` 会拒绝执行被截断响应中的工具调用；core 在参数校验前处理该标记，并把失败写成 `INVALID_ARGUMENTS` 工具结果。

## 当前边界

- `core` 和 `a2a` 不依赖 Android UI；宿主可以自由使用 Activity、Compose 或其他界面。
- `android-attachments` 是可选的 Android 桥接模块，只负责读取系统选择器授权的内容 URI。
- A2A 发现与文本调用已在真实手机链路验证。内联文件编码有自动化测试，仍需与目标电脑端的文件处理能力做端到端验证。
- 当前模型适配器支持 Chat Completions 兼容接口的 SSE 文本、函数调用和图片工具结果；LiteRT-LM 也可在工具读取图片后将图片送入本机视觉模型。其他多模态输入仍需扩展，A2A 的 SSE 仍未实现。
- `android-code-sandbox` 只执行独立进程中的 JavaScript；是否可用取决于设备的 WebView 沙箱支持。其他语言的任意代码执行未实现。
- 电脑端 Agent Card 中的 `skill` 是能力描述，A2A 协议调用仍通过 `SendMessage`，并非独立的远程函数。

三项会话能力的接口与恢复规则见 [会话生命周期](session-lifecycle.md)。`kiora-companion` 可按产品界面需要接入工具进度、用量和队列。远端文件下载仍需根据实际服务端响应补齐。

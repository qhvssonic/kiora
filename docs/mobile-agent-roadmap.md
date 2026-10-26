# Kiora 的移动端差异化方向（讨论记录）

更新于 2026-10-26。本文件记录实际能力、设计候选和验证顺序。Kiora 指 SDK；`kiora-companion` 是基于 SDK 的 Android 宿主应用，`pi` 是桌面端参考实现。离线 TTS 已接入并完成 V2509A 上的精度、线程和播放连续性验证；更广泛设备、音质和内存指标仍待测量。

## 判断依据

Kiora 已有模型、工具、授权、会话树、摘要、用量、运行中指令、A2A 和端侧生成模型等基础能力。后续应优先解决手机特有的问题：进程可能被回收，权限和媒体 URI 有作用范围，网络与电量会变化，设备同时提供相机、麦克风、扬声器和个人相册。不要仅为追赶 pi 而复制终端工具或桌面插件行为。

下面的 Android 功能应放在可选模块；`core` 保持纯 Kotlin。系统权限申请、前台通知、数据选择界面和播放控件由宿主负责。工具调用仍走 `ActiveToolSelector`、参数校验、`ToolAuthorizer`、执行的现有流程；不恢复已移除的 `ToolSandbox`、`CapabilitySandbox` 或 `AgentTool.requiredCapabilities`。

## 优先探索：端侧语音交互

首版已实现独立的 `speech` 转写接口、可选 `speech-sherpa-onnx` Android 适配器，以及 Companion 的按键录音与可编辑转写。识别只返回最终文本；临时字幕与 VAD 尚未实现。见 [端侧语音识别](speech-recognition.md)。

已接入 **sherpa-onnx 1.13.8 + MeloTTS 中文英混合模型 + Android AudioTrack**，复用现有 sherpa/ONNX Runtime。Companion 默认推荐 FP32、CPU 4 线程，保留 int8 导入支持；支持手动点击图标朗读回复和停止，不向智能体注册 `speak_text`。目标手机长回复播放记录为零缓冲断流；现有按键说话 → SenseVoice 最终转写 → 用户校正 → `AgentRuntime` 可与朗读组成半双工轮次，完整语音交互与多设备指标仍需验收。选型证据、模型资产、接口与验收项见 [离线语音合成](speech-synthesis.md)。真正流式 ASR、VAD 和全双工交互另行评估。

SDK 已有 `SpeechRecognitionPort`、`SpeechSynthesisPort`、`MeloTtsSynthesizer` 与独立的 `SpeakTextTool`。音频焦点、蓝牙路由、来电中断、麦克风权限和播放 UI 属于宿主集成。模型回复、工具进度与朗读状态分别呈现；停止音频输出不等于原生推理或远端任务已经取消。

系统端侧 ASR/TTS 保留为未来可选适配器。`SpeechRecognizer.createOnDeviceSpeechRecognizer()` 必须先检查 `isOnDeviceRecognitionAvailable()`，系统 TTS 必须检查 `Voice.isNetworkConnectionRequired()` 与声音资产是否准备好；普通 `SpeechRecognizer` 和未核验的系统 voice 不能作为离线保证，也不能在模型缺失时静默作为联网回退。

先在真实设备验证中文和中英混说、断网、长句、耳机切换、打断响应、延迟与耗电，再决定默认适配器。ASR/TTS 的原始音频和转写是否保存，应由宿主显式配置。

### ASR 首版选型

用户已有 sherpa-onnx + SenseVoice Small int8 的端侧项目经验。建议把这组合作为首版按键说话的验证基线：Android 宿主在获得麦克风权限后录制短语音，使用 VAD 或用户松开按钮确定片段边界，SenseVoice 产出最终文本，用户确认后才提交给 `AgentRuntime`。临时转写不应作为多条用户消息写入会话。现有 sherpa-onnx 的 SenseVoice 适配属于非流式识别；“模拟流式”是对片段反复识别，不等于真正的增量模型。

如果产品要求说话时持续显示稳定的临时字幕，再评估 sherpa-onnx 双遍方案：小型流式 Zipformer 给第一遍反馈，SenseVoice 给片段最终结果。它增加模型体积、内存与集成成本，应由实测收益决定。系统端侧 `SpeechRecognizer` 可作为无需另带模型的备选，但只能在设备报告可用时启用；绝不静默切换到可能联网的普通识别服务。

同机对比至少记录中文字符错误率、中英混说、数字与专有名词、句尾到最终转写的 P50/P95 延迟、误切句、冷启动时间、峰值内存、APK/模型下载体积、发热和耗电。SenseVoiceSmall 模型权重与 sherpa-onnx 代码的许可证分别核验，第三方 int8 转换包也要核对自身条款。模型文件宜与 SDK AAR 分开分发，并记录版本与校验值。

### 后续候选：方言识别

目前没有确定的目标方言，也没有 Kiora 方言录音样本，因此先不替换现有 SenseVoice。候选 [Fun-ASR-Nano-2512](https://huggingface.co/FunAudioLLM/Fun-ASR-Nano-2512) 官方模型卡列出吴、粤、闽、客家、赣、湘、晋七类方言及 26 种地域口音，参数规模 800M；它与支持 31 种语言的 MLT-Nano 是不同模型。上游宣称的覆盖范围不是本项目的识别准确率。先收集用户实际方言、说话场景和经同意录制的短样本，再对照现有 SenseVoice 测 CER/WER、数字和地名误识、端到端延迟与峰值内存。

现用 sherpa-onnx 1.13.8 AAR 已有 Fun-ASR-Nano 配置类型，可先做独立的 `SpeechRecognitionPort` 适配器验证，无需在首轮再引入另一套推理运行库。[sherpa 转换包](https://huggingface.co/csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30/tree/main)的 int8 ONNX 资产合计约 1 GB，模型与聊天模型、TTS 同机驻留的内存风险需要目标手机实测；Android 端是否可用、最低 RAM、速度和许可证组合需随固定模型版本逐项复核。仅在目标方言明显优于 SenseVoice 且资源预算可接受时，才把它做成按需导入的可选识别后端。

## 已接入：EmbeddingGemma 2 相册检索

具体的模型格式、Android 接口、SDK 边界和验证项见 [EmbeddingGemma 2 接入研究](embeddinggemma2-integration.md)。

Google 于 2026-10-06 发布 EmbeddingGemma 2。它把文本、图片、音频和视频映射到共享的 768 维向量空间；官方资料展示了手机上的端侧图片嵌入与跨模态检索。它适合先做“用自然语言找照片”“找相似照片”“按主题聚合”，不等同于 ASR、TTS，也不应直接驱动自动删除或整理原图。模型在目标 Android 设备上的格式、量化、速度、内存和持续索引耗电仍需实测，不能把文档里的示例设备数据当作所有手机的保证。

当前 SDK 与 Companion 的边界：

1. SDK 已有纯 Kotlin `EmbeddingPort` 与向量比较，以及可选 `embedding-mediapipe` 文字/图片适配器；通用检索与媒体索引模块尚未实现。
2. Companion 自行管理相册授权、MediaStore 访问、手动分批索引和私有 SQLite 数据库，每批最多 100 张。`search_album_photos` 返回最多三张排序候选，原图读取继续核验授权。当前索引固定 768 维，全量读取向量后评分排序，不能直接宣称支持大规模通用知识库。

索引至少记录媒体 ID、内容版本或修改时间、嵌入模型版本、向量维度和授权来源。新增、删除、URI 权限撤销、模型升级和索引中断都需要可恢复处理。大规模首建索引应由宿主在适当的电量与后台任务条件下调度。先用一批经用户授权的照片验证中文自然语言检索、相似图、误命中率及索引成本；不要一开始要求全相册权限。

## 后续候选：本地资料与文档任务

| 方向 | 已有基础 | 新增边界与最小验证 |
| --- | --- | --- |
| 带引用的本地资料检索 | `embedQuery()`、`embedDocument()`、扫描文本、SQLite | 可替换检索接口、按版本分块、证据引用和授权失效检查；Agent 可搜索并读取证据，答案可回到原页。先复用已有 440M 模型，不默认增加第二份权重。 |
| 文档字段提取与校验 | 单页 OCR 行框、Companion 多页草稿、本地聊天模型、结构化工具结果 | 模型生成带来源的字段候选，程序检查 Schema、日期、数值关系和引用；人工校对后导出 JSON/CSV。不能把 OCR 行排序当成复杂表格恢复。 |
| 条码与现场记录 | Companion 已有 ZXing、相机和 OCR | 用受控工具读取设备/物料标识并核对用户提供的清单；位置与传感器只按需采样并附时间、精度和来源。 |

`ToolOutput.structuredContent`、输出 Schema 校验和 `ToolAttention` 已在 core 实现，以上方向应直接复用，详见 [架构](architecture.md)。Companion 仍有根据任务关键词判断纯图片问题并隐藏工具的逻辑；扩展文档和视觉组合任务时，应改用能力、授权和明确用户选择控制工具可见性，避免继续增加关键词规则。

## 其他移动端候选能力

| 优先级 | SDK 方向 | 最小可验证行为 |
| --- | --- | --- |
| P0 | 可恢复运行与待处理任务 | core 已将缺失结果的历史工具调用标记为 `INTERRUPTED`，不自动重放；继续增加持久任务账本、幂等和副作用对账，收到远端 task ID 后立即保存。 |
| P0 | 每轮配置与资源快照 | 在已有 configure/send 互斥和本轮附件绑定基础上，覆盖异步辅助推理与播放任务；切换会话后丢弃旧回调，校验资源版本与授权有效期。 |
| P0 | 数据来源与外发记录 | 为照片、文件和音频记录来源、授权范围、目的地和失效状态；授权决定仍由宿主实现。 |
| P1 | 资源预算与推理生命周期 | 测聊天模型驻留时的 TTS/ASR/OCR/Embedding 峰值，限制并发与队列；只能在已验证可安全重建的边界释放聊天会话，离线模式不自动转云端。 |
| P1 | 与其他 Agent 的任务接力 | 对端可为服务器、其他设备或业务系统；补齐返回 JSON/文件产物、长任务恢复与明确远端取消。现有解析只提取文本，上传成功不代表手机已取得远端文件。 |
| P2 | 按需采集的设备上下文 | 相机、位置、传感器等以一次性、带时间和来源的输入进入会话；避免默认常驻采集。 |
| P2 | 诊断与回放 | 在不泄露原始个人媒体的前提下记录模型选择、工具事件、授权、重试和中断，构造进程回收与断网测试。 |

## 推荐验证顺序

1. **离线 TTS 验收**：SDK 合成与 Agent 工具适配、Companion 主动朗读/停止已实现；下一步在目标手机验证离线、音质、延迟、音频焦点与同机内存。首轮不新增 VAD、常开监听和全双工。
2. **本地资料检索**：复用 EG2，增加资源版本与证据引用；用扫描资料和文本问答验证检索质量、拒答与授权失效。
3. **文档结构化处理**：在已有扫描草稿上增加字段提取、确定性校验、人工复核和导出。
4. **方言 ASR 决策**：确定真实方言与场景后比较 Fun-ASR-Nano 和 SenseVoice，只有达到准确率与资源预算门槛才实现可选适配器。
5. 各阶段按需补齐资源预算、任务持久化和外发策略。后续联网协作保持 A2A 对端中立。

本地处理与外发分别控制：在线 `ModelPort` 可能收到本地工具返回的文本和图片；A2A 是另一个出口。工具执行授权不自动代表允许发送至任意模型或 Agent，派生摘要和语音产物也应继承来源策略。

参考资料：[EmbeddingGemma 2 发布说明](https://blog.google/innovation-and-ai/technology/developers-tools/embeddinggemma-2/)、[Google 模型说明](https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2)、[Google AI Edge 手机端检索示例](https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/)、[Android 端侧语音识别接口](https://developer.android.com/reference/android/speech/SpeechRecognizer)、[Android TTS Voice 接口](https://developer.android.com/reference/android/speech/tts/Voice)、[sherpa-onnx Android 示例](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/README.md)、[sherpa-onnx 双遍识别](https://k2-fsa.github.io/sherpa/onnx/android/apk-2pass-cn.html)、[SenseVoiceSmall 模型卡](https://huggingface.co/FunAudioLLM/SenseVoiceSmall)、[Android 后台任务指南](https://developer.android.com/develop/background-work/background-tasks)。

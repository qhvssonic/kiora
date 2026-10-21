# Kiora 的移动端差异化方向（讨论记录）

更新于 2026-10-20。本文件记录设计候选与首版验证进度。Kiora 指 SDK；`kiora-companion` 是基于 SDK 的 Android 宿主应用，`pi` 是桌面端参考实现。

## 判断依据

Kiora 已有模型、工具、授权、会话树、摘要、用量、运行中指令、A2A 和端侧生成模型等基础能力。后续应优先解决手机特有的问题：进程可能被回收，权限和媒体 URI 有作用范围，网络与电量会变化，设备同时提供相机、麦克风、扬声器和个人相册。不要仅为追赶 pi 而复制终端工具或桌面插件行为。

下面的 Android 功能应放在可选模块；`core` 保持纯 Kotlin。系统权限申请、前台通知、数据选择界面和播放控件由宿主负责。工具调用仍走 `ActiveToolSelector`、参数校验、`ToolAuthorizer`、执行的现有流程；不恢复已移除的 `ToolSandbox`、`CapabilitySandbox` 或 `AgentTool.requiredCapabilities`。

## 优先探索：端侧语音交互

首版已实现独立的 `speech` 转写接口、可选 `speech-sherpa-onnx` Android 适配器，以及 Companion 的按键录音与可编辑转写。它只返回最终文本；临时字幕、VAD 与 TTS 尚未实现。见 [端侧语音识别](speech-recognition.md)。

目标是一条完整、可打断的语音轮次：按键说话 → 端侧 ASR 的临时与最终转写 → 用户可校正文本 → `AgentRuntime` → 流式 TTS 播报。首版先做按键说话，不做常开监听或后台唤醒。模型回复、工具进度与朗读状态应分别呈现；用户打断播报时，停止音频输出，但不能把未完成的工具调用误记为已取消。

SDK 可先定义 `SpeechRecognitionPort` 和 `SpeechSynthesisPort`，覆盖语言、离线可用性、临时结果、取消、错误和音频流状态。Android 适配器可在系统提供端侧识别服务时调用 `SpeechRecognizer.createOnDeviceSpeechRecognizer()`；必须先检查 `isOnDeviceRecognitionAvailable()`。普通 `SpeechRecognizer` 可能使用远端服务，不能仅凭 API 名称宣称离线。TTS 也应检查所选 `Voice.isNetworkConnectionRequired()`；系统无离线语音时应明确告知用户，并允许其他端侧引擎适配。音频焦点、蓝牙路由、来电中断、麦克风权限和播放 UI 属于宿主集成。

先在真实设备验证中文和中英混说、断网、长句、耳机切换、打断响应、延迟与耗电，再决定默认适配器。ASR/TTS 的原始音频和转写是否保存，应由宿主显式配置。

### ASR 首版选型

用户已有 sherpa-onnx + SenseVoice Small int8 的端侧项目经验。建议把这组合作为首版按键说话的验证基线：Android 宿主在获得麦克风权限后录制短语音，使用 VAD 或用户松开按钮确定片段边界，SenseVoice 产出最终文本，用户确认后才提交给 `AgentRuntime`。临时转写不应作为多条用户消息写入会话。现有 sherpa-onnx 的 SenseVoice 适配属于非流式识别；“模拟流式”是对片段反复识别，不等于真正的增量模型。

如果产品要求说话时持续显示稳定的临时字幕，再评估 sherpa-onnx 双遍方案：小型流式 Zipformer 给第一遍反馈，SenseVoice 给片段最终结果。它增加模型体积、内存与集成成本，应由实测收益决定。系统端侧 `SpeechRecognizer` 可作为无需另带模型的备选，但只能在设备报告可用时启用；绝不静默切换到可能联网的普通识别服务。

同机对比至少记录中文字符错误率、中英混说、数字与专有名词、句尾到最终转写的 P50/P95 延迟、误切句、冷启动时间、峰值内存、APK/模型下载体积、发热和耗电。SenseVoiceSmall 模型权重与 sherpa-onnx 代码的许可证分别核验，第三方 int8 转换包也要核对自身条款。模型文件宜与 SDK AAR 分开分发，并记录版本与校验值。

## 优先探索：EmbeddingGemma 2 相册检索

Google 于 2026-10-06 发布 EmbeddingGemma 2。它把文本、图片、音频和视频映射到共享的 768 维向量空间；官方资料展示了手机上的端侧图片嵌入与跨模态检索。它适合先做“用自然语言找照片”“找相似照片”“按主题聚合”，不等同于 ASR、TTS，也不应直接驱动自动删除或整理原图。模型在目标 Android 设备上的格式、量化、速度、内存和持续索引耗电仍需实测，不能把文档里的示例设备数据当作所有手机的保证。

建议 SDK 分两层：

1. 纯 Kotlin 的 `EmbeddingPort` 与向量检索接口，只处理可替换的模型、向量、查询和索引元数据。
2. 可选 Android 媒体索引模块，经宿主授予的 MediaStore 或照片选择器 URI 读取媒体，建立可增量更新的私有索引；向智能体返回不透明媒体 ID、缩略图和可解释的匹配信息，原图读取仍由宿主核验授权。

索引至少记录媒体 ID、内容版本或修改时间、嵌入模型版本、向量维度和授权来源。新增、删除、URI 权限撤销、模型升级和索引中断都需要可恢复处理。大规模首建索引应由宿主在适当的电量与后台任务条件下调度。先用一批经用户授权的照片验证中文自然语言检索、相似图、误命中率及索引成本；不要一开始要求全相册权限。

## 其他移动端候选能力

| 优先级 | SDK 方向 | 最小可验证行为 |
| --- | --- | --- |
| P0 | 可恢复运行与待处理任务 | 进程重启后恢复任务状态；对有副作用的工具不盲目重复执行，要求工具提供幂等或对账方式。 |
| P0 | 每轮配置快照 | 将实际模型、工具清单和会话绑定到一次运行；切换模型期间阻止旧运行实例接收新输入。 |
| P0 | 数据来源与外发记录 | 为照片、文件和音频记录来源、授权范围、目的地和失效状态；授权决定仍由宿主实现。 |
| P1 | 资源感知的模型选择 | 在轮次边界依据离线状态、电量、温度、内存、隐私偏好和延迟选择端侧或云端模型，并记录实际选择。 |
| P1 | 手机与电脑的任务接力 | 补齐 A2A 长任务的进度、取消、恢复与用户补充信息；手机可作为拍摄、选件和确认入口。 |
| P2 | 按需采集的设备上下文 | 相机、位置、传感器等以一次性、带时间和来源的输入进入会话；避免默认常驻采集。 |
| P2 | 诊断与回放 | 在不泄露原始个人媒体的前提下记录模型选择、工具事件、授权、重试和中断，构造进程回收与断网测试。 |

## 推荐验证顺序

1. 语音：完成端侧 ASR → 一轮 AgentRuntime → 端侧 TTS 的原型，先测真实手机的可用性和延迟。
2. 相册：完成经授权媒体的小规模增量索引与中文文字找图原型，先测检索质量、内存和耗电。
3. 将两项原型中稳定的边界固化为可替换 SDK 接口，再让 Companion 接入交互界面。
4. 同步推进可恢复运行和每轮配置快照，它们是语音长任务与相册索引的可靠性基础。

参考资料：[EmbeddingGemma 2 发布说明](https://blog.google/innovation-and-ai/technology/developers-tools/embeddinggemma-2/)、[Google 模型说明](https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2)、[Google AI Edge 手机端检索示例](https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/)、[Android 端侧语音识别接口](https://developer.android.com/reference/android/speech/SpeechRecognizer)、[Android TTS Voice 接口](https://developer.android.com/reference/android/speech/tts/Voice)、[sherpa-onnx Android 示例](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/README.md)、[sherpa-onnx 双遍识别](https://k2-fsa.github.io/sherpa/onnx/android/apk-2pass-cn.html)、[SenseVoiceSmall 模型卡](https://huggingface.co/FunAudioLLM/SenseVoiceSmall)、[Android 后台任务指南](https://developer.android.com/develop/background-work/background-tasks)。

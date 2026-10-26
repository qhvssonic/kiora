# 离线语音合成选型与实施边界

更新于 2026-10-26。状态：SDK 和 Companion 的离线朗读已在 V2509A / Android 16 安装验证，修复 JNI 回调崩溃；已完成合成/播放并行、线程配置和模型精度的单设备对照，长回复播放记录为零缓冲断流。已通过的冒烟结果与待完成的音质、性能验收分别记录于下文，不把桌面测试作为手机性能结论。

## 首轮技术选择

采用 **sherpa-onnx 1.13.8 + `vits-melo-tts-zh_en` + Android AudioTrack**。V2509A 实测后推荐 `model.onnx`（FP32）与 4 个 CPU 线程，兼容保留 int8 导入。先实现中文为主、支持词典内英文的单说话人离线朗读，再组合现有 SenseVoice 按键录音形成半双工语音轮次。

| 层 | 选择与理由 |
| --- | --- |
| 推理运行库 | 复用现有 `com.k2fsa.sherpa.onnx:sherpa-onnx-android:1.13.8`，本地 AAR 已包含 `OfflineTts` 和相关配置类。新增适配器无需另带一套 ONNX Runtime；已完成目标手机冒烟验证。 |
| 合成模型 | sherpa 维护者转换的 `vits-melo-tts-zh_en/model.onnx`；FP32 在目标手机显著快于 int8。中文与词典内英文、单说话人，适合助手回复和文档朗读。每次只加载一个精度的权重。 |
| 后端 | CPU 基线；线程数可配置并做小范围对照，不预设增加线程一定更快。首版不依赖 GPU/NPU。 |
| 音频 | sherpa 回调提供浮点 PCM，Android 播放层按实际采样率使用流式 AudioTrack；不得把 ASR 的 16 kHz 要求套在 TTS 上。模型文档标称 44.1 kHz，实际以引擎返回值为准。 |
| 文本处理 | 复用模型包和 sherpa 的文本前端、词典及 FST 规则。宿主只做有界文本分段和展示文本提取，不维护针对示例的读音替换或关键词任务路由。 |
| 模型准备 | 宿主通过系统目录选择器显式导入到私有目录，逐文件计算 SHA-256 并原子切换当前模型。仓库脚本固定上游修订及各文件 SHA-256；推理期间不自动下载模型，也不回退到云端服务。 |

首版不新增 Python 运行时，不转换为 MNN/LiteRT 模型。系统 TTS 可在将来实现为可选适配器，但不同设备的中文离线声音、资产安装与网络行为不一致，不能作为统一基线。更多声音或更高自然度需要用同一批文本与内存指标另行比较后替换模型。

## 已确认事实与待验证项

- Android 路径：上游有 Android TTS 示例；固定版本 Kotlin API 提供 `OfflineTts.generateWithCallback()` 和 `release()`。回调是原生合成产生音频后的交付点，不保证每个 token 或固定毫秒数产生一块音频。
- 模型体积：2026-10-26 文件页列出 int8 权重约 53.5 MB、FP32 约 170 MB、`lexicon.txt` 约 6.84 MB。仓库总计约 245 MB 包含不同精度权重与其他资产，不能作为最小安装包大小。
- 离线资产：当前实现要求 `model.onnx` 或 `model.int8.onnx`，以及 `lexicon.txt`、`tokens.txt`、`phone.fst`、`date.fst`、`number.fst`、`new_heteronym.fst` 和 `LICENSE`，固定修订为 `a0d5c6a264c0ef92d70d8661d8cc502d79627cd6`。int8 八个文件共 60,591,049 字节（约 57.8 MiB），FP32 八个文件共 177,503,169 字节（约 169.3 MiB），均不含旧版 `dict` 目录。两种精度已完成 Android 1.13.8 合成测试；若所选目录同时包含两种权重，导入器明确优先 FP32，只复制所选权重和公共资产。
- 语言边界：上游明确指出英文词只有在 `lexicon.txt` 中存在时才能发音。设备型号、缩写、未登录词、多音字、日期和金额必须进入测试集；单说话人不提供声线选择、情绪控制或声音克隆。
- 许可：sherpa-onnx 为 Apache-2.0，ONNX Runtime 为 MIT；MeloTTS 原中文模型卡标 MIT，所选转换包附 MyShell MIT 许可。分发时保留这些许可并核验实际携带的文本前端资源声明。
- 维护：核查时 sherpa-onnx 仍在维护；MeloTTS 默认分支最近提交为 2024-12-24 的 README 更新。运行库更新不等于模型权重更新，采用此模型是首轮验证选择。
- 硬件：当前 Kiora Android 适配器 minSdk 为 26；同包 MNN 仅提供 arm64，首轮产品验证以现有 arm64 手机为基线。已取得少量目标手机合成计时，见下文；最低 RAM、峰值 native/图形内存、长时间耗电和 P95 仍待测量。

## SDK 与宿主边界（已接入）

`speech` 提供纯 Kotlin `SpeechSynthesisPort`、`SpeechSynthesisRequest`、`SpeechAudioChunk`、`SpeechSynthesisResult` 与有界文本分段；可选 `speech-sherpa-onnx` 提供 `MeloTtsSynthesizer`，`speech-tools` 提供 `SpeakTextTool`。ASR、TTS 可独立创建和释放；共享运行库不代表共享模型实例或内存。

| 边界 | 职责 |
| --- | --- |
| `SpeechSynthesisPort` | 接收有界文本与速度参数，异步交付有界单声道 PCM 分块；声明格式、模型身份、失败、取消与 `close()` 语义，不接收 Android Context、Activity 或 View。 |
| sherpa 合成适配器 | 模型路径和完整性验证、文本前端配置、后台原生调用、回调桥接、并发限制及释放。限制单次输入和原生分句，记录上游完整返回音频与回调可能同时占用内存。 |
| Android 播放适配器 | Companion 的 `OfflineSpeechPlayer` 使用 AudioTrack float PCM、音频焦点、请求序号和有界播放缓冲；切换会话、录音或 Activity 停止时停止。音频导出尚未实现。 |
| Companion | SAF 模型目录导入、设置入口、助手回复朗读按钮、停止控件和播放状态；默认由用户主动朗读，暂不自动播报。 |
| Agent 工具适配层 | `SpeakTextTool` 接受最长 4000 字符的实际文本，经现有参数预校验与 `ToolAuthorizer` 后由宿主合成和播放；只返回播放状态与时长，PCM 不写入模型上下文或会话树。 |

PCM 的采样率、单声道约束、数值范围和数组所有权必须写入契约。异步队列需要限定积压音频时长，禁止把全部回答无限累积在内存中；JNI 回调中的数组若继续异步使用，必须先确认所有权或复制。回调不能直接执行 Android UI 操作。

Android 回调使用显式 `SherpaTtsCallback` 类：sherpa-onnx 1.13.8 JNI 按 `invoke([F)Ljava/lang/Integer;` 查找方法，不能直接传入只暴露擦除签名的 invokedynamic lambda。后者已在 V2509A / Android 16 上触发 `NoSuchMethodError`，随后发生 JNI `SIGABRT`，无法靠外层 Kotlin `try/catch` 恢复。适配模块携带 consumer ProGuard 保留规则，并通过反射回归测试校验精确签名及继续/停止返回值。

### 准备模型和接入

在 SDK 仓库运行 `python scripts/prepare_melotts.py <输出目录> --precision fp32`。默认不加参数仍准备 int8，以保持旧脚本行为。脚本只下载上述八个文件，核对固定修订中的字节数和 SHA-256，并写出 `manifest.json`。把**解压后的目录**复制到 Android 系统文件选择器可访问的位置，在 Companion「设置 → 离线朗读 → MeloTTS 中文朗读模型」选择该目录。App 再复制到自身私有存储，校验完整性并记录当前版本；模型不进入 APK。工作区已准备的示例目录为 `../artifacts/vits-melo-tts-zh_en-int8/`，只用于本地验证。

宿主直接使用 SDK 时，引入 `speech` 与可选 `speech-sherpa-onnx`，在后台调用 `MeloTtsSynthesizer(modelDirectory, modelId, numThreads = 4, modelFileName = "model.onnx").synthesize(SpeechSynthesisRequest(text)) { chunk -> … }`，最后调用 `close()`；播放由宿主实现。需要 Agent 可调用时另引入 `speech-tools`，将宿主的合成加播放函数注入 `SpeakTextTool`，再交给已有工具选择和授权流程。工具仅处理已解析的实际文本，不负责猜测文件名指向哪个资源。

SDK 在一个推理实例中依次生成各段；宿主不能把同步音频回调阻塞到整句播放完毕，否则下一句生成只能在上一句临近结束时启动，造成缓冲耗尽。Companion 0.1.94 使用独立生产与消费协程：把回调音频切成不超过 100 ms 的帧，队列最多 80 帧（8 秒；44.1 kHz float PCM 约 1.35 MiB，不含当前回调与原生推理缓冲），先预备约 1 秒再播放；不足 1 秒的短句在合成完成后直接播放。AudioTrack 启动前先填充设备缓冲。队列满时才对推理回调施加背压，停止或播放失败会取消队列、唤醒阻塞回调，并等待原生生成退出后释放资源。若设备持续合成速度慢于播放速度，有限预缓冲仍不能保证全程无停顿，需另行测量 RTF。

从 Companion 0.1.96 配套 SDK 开始，`SpeechPhrasing` 按标点和段落划分朗读边界：逗号/顿号 150 ms，分号/冒号 240 ms，句末/换行 320 ms，空行段落 650 ms；末段不额外补静音。适配器检测每段原始 PCM 的连续低幅度尾部（绝对幅值 ≤0.003），只补足目标静音，不裁剪模型音频。静音作为 PCM 进入既有有界队列，不阻塞等待真实时间；小数、千位分隔符和数字时间中的分隔符保持完整，纯符号片段不单独送入模型。以上为默认停顿目标，并非不同文本均有相同主观节奏，仍需试听调整。Companion 0.1.97 的朗读入口和停止操作使用灰色线条图标，去掉按钮文字、色块和播放区卡片背景；图标约 22 dp，透明点击区域为 48 × 48 dp，保留无障碍说明和长按提示。

Companion 每次朗读单独创建、结束后释放 TTS 实例，以降低与聊天模型并存时间；因此当前没有跨请求的暖态缓存。先验证现有聊天模型驻留时的峰值，不为节省内存而直接关闭等待工具结果的聊天会话。

0.1.96 / versionCode 97 在同一 V2509A 上复测上述带段落的长回复：输出 3,402,907 个 44.1 kHz 采样点（约 77.16 秒），首次加载到播放约 3.47 秒，总耗时约 80.64 秒，`AudioTrack.underrunCount = 0`，界面显示「离线朗读完成」。朗读与停止图标已通过真机截图核对；SDK 的 5 项停顿回归测试及 Companion 的 45 项 JVM 测试通过。该结果验证新增停顿没有引入缓冲断流，主观节奏仍以用户试听为准。

## 取消、状态与 Agent 衔接

- 停止朗读时立即停止并清空播放队列，同时请求合成取消；UI 先反映音频已停止，原生资源只在计算安全退出后释放。协程 Job 结束不能作为原生推理已结束的依据。
- 新一段朗读、切换会话或切换模型必须绑定请求 ID，丢弃旧请求的迟到音频；不得在释放后继续使用原生指针。原生回调的停止返回值和停止粒度须以固定版本实现验证。
- 当前结果区分播放完成、用户停止和失败；合成单独完成但尚未播完由内部播放器维护。用户停止工具朗读返回工具失败，且不会自动取消 AgentRuntime 或远端 A2A 任务。
- Agent 请求朗读时，工具负责受控执行和结果；普通回复由用户主动点「朗读」，避免同一回复同时走工具和自动播放而重复朗读。
- 第一版先朗读完整回复或明确选择的文档片段。后续再对已提交的文本句子分段播放；不能把不完整的工具调用、临时输出或待用户授权内容直接当作最终播报。
- 通过纯 Kotlin 工具包装实现 Agent 可调用能力，Android 播放实现由宿主注入。文件资源绑定、权限和来源核验由程序处理；模型只提供任务和参数。
- 将来导出的语音与其源文本应遵循同一外发策略，A2A 发送仍需目标能力与上传授权。当前只播放、不导出。TTS 本身离线不代表在线聊天模型不会收到原始输入或工具文本。

## Companion 最小演示与验收

### 2026-10-26 精度与线程对照

设备 V2509A / Android 16，sherpa-onnx 1.13.8，共享其 ONNX Runtime 1.28.2，CPU 后端。相同 55 字左右的中文测试文本，不播放音频、回调只计数，模型加载完成后的第二次调用如下（单次样本，不是 P95）：

| 权重 | 线程 | 合成耗时 | 音频时长 | RTF |
| --- | ---: | ---: | ---: | ---: |
| int8 | 2 | 15.162 s | 9.462 s | 1.60 |
| int8 | 4 | 14.704 s | 9.462 s | 1.55 |
| int8 | 6 | 14.962 s | 9.462 s | 1.58 |
| FP32 | 2 | 5.651 s | 9.392 s | 0.60 |
| FP32 | 4 | 3.696 s | 9.392 s | 0.39 |
| FP32 | 6 | 3.347 s | 9.392 s | 0.36 |

FP32 的 4 线程暖态首回调约 287 ms，包含首次加载的首回调约 2.93 s；这不是扬声器首次可听时刻。选择 FP32 / 4 线程为 Companion 默认运行配置，6 线程在此样本收益较小；SDK 仍允许宿主选择线程数。int8 的首次无界面测试受到系统后台调度影响，异常等待数据已排除；后续测量通过测试的 shell 身份将应用置于前台。未控制温度和功耗，不能外推到其他手机。

0.1.95 / versionCode 96 已在同一手机通过正常 SAF 流程导入固定 FP32 包。实际助手长回复生成 2,814,020 个 44.1 kHz 采样点（约 63.81 秒音频），首次加载到 AudioTrack 启动约 3.64 秒，完整操作约 67.45 秒；最后一次写入后读取 `AudioTrack.underrunCount = 0`，界面显示「离线朗读完成」。旧版同一回复的日志有 32 次缓冲耗尽重启警告。完成后重播、播放中停止及停止后再次朗读均通过，45 项 Companion JVM 测试通过。这里验证了队列连续性，尚未人工试听音质，也不代表冷启动 P95 ≤2 秒已达标。

可复现入口：`MeloTtsPerformanceTest` 需显式传入 `-e ttsBenchmark true`；用 `-e ttsFp32 true` 时，把已校验 `model.onnx` 放入应用 `cache/tts-fp32-benchmark/`，测试会从当前导入目录复制公共资产，且不替换当前模型。测试应手动安装测试 APK 后通过 `adb shell am instrument` 定向运行，避免整套测试清理应用数据。

对照本地 `mommy-story-on-device` 实现：它使用 ZipVoice-Distill / sherpa-onnx、4 个线程和持久化 Worker，并在 LLM 输出完整句子时预合成 WAV，播放器独立取段；因此点击时可立即使用已准备的音频。Kiora 借鉴生产/消费并行，不照搬其全量 WAV 累积，也不在未经请求时自动合成所有智能体输出。当前仍每次释放 TTS，后台预热与有界缓存是后续独立优化项。

Companion 0.1.98 起仅保留已有助手回复的手动朗读图标，不依赖 `speech-tools`，不向 Agent 注册 `speak_text`；可停止、可观察错误。SDK 的可选工具适配层仍可供其他宿主按需使用。0.1.100 起正常播放完成后自动收起状态栏，不显示完成提示；同一手机短回复验证已完成播放且无提示残留。接下来在手机串联现有按键录音与一轮本地 AgentRuntime。暂不增加 VAD、持续字幕、后台唤醒或全双工通话。

已完成 SDK `:speech:test`、`:speech-tools:test`、`:speech-sherpa-onnx:assembleRelease`，以及 Companion `:app:testDebugUnitTest`、`:app:assembleDebug`。固定模型目录在 Windows 的 sherpa-onnx 1.13.2 上生成 44.1 kHz、142,138 采样点的短句 WAV，耗时约 6.2 秒（含约 1.7 秒加载）；这是**Windows 另一运行库版本的一次冒烟结果**，不能作为 Android 1.13.8 或目标手机性能数据。初次实现阶段未安装手机；后续已将 Companion 0.1.93（versionCode 94）覆盖安装到 V2509A / Android 16，并完成朗读 JNI 回调修复的真机验证，见下文。

2026-10-26，V2509A / Android 16、sherpa-onnx 1.13.8、Companion 0.1.93：在 Gemma 4 E2B 已加载的会话中点击已有长回复「朗读」，观察到 44.1 kHz AudioTrack 启动及「正在离线朗读」；点击停止后显示「朗读已停止」，再次朗读并等待到「离线朗读完成」，进程保持存活且无新增 JNI 崩溃。新增的回调签名测试和 Companion 41 项 JVM 测试通过。这只是播放流程冒烟验证，未人工试听音质、未测定首音/停止延迟与峰值内存，也未完成下列完整验收；日志出现句间 AudioTrack underrun，连续播放平滑性仍需专项验证。

1. **正确性与音质**：100 段文本覆盖中文、数字、日期、金额、单位、中英混合、缩写和型号；人工核对漏读、误读及量化退化，必要时与 FP32 对照。关键数值及单位必须正确，不以整体自然度掩盖关键内容误读。
2. **离线**：资产准备后在飞行模式完成朗读及 ASR → 本地 AgentRuntime → TTS；缺资产、模型不兼容与不可播放均返回明确错误，不触发在线服务。
3. **速度目标**：暖态首段音频 P95 ≤2 秒，合成实时率 RTF ≤1（合成墙钟时间/音频时长）。分别报告冷加载、首段可用音频、首次可听输出和完整合成耗时，不把朗读时长算成推理耗时。
4. **停止目标**：停止后 250 ms 内无声音输出；另外测量原生生成实际停止时间及释放等待时间，禁止把播放停止标为原生即时中断。
5. **稳定性**：30 轮 ASR → Agent → TTS，无崩溃、串会话、重复播放或持续内存增长；同时记录聊天模型驻留时的 Java/native PSS、RSS 和可获取的图形内存。峰值内存预算根据目标设备与基线实测确定，不以权重体积代替。
6. **生命周期**：音频焦点丢失、来电、耳机/蓝牙切换、Activity 重建、取消后立即再次朗读、模型替换均保持状态一致。
7. **工具路径**：相同合成能力可由 `SpeakTextTool` 调用；无效文本在执行前拒绝，拒绝授权时不合成；工具只返回播放结果，不返回 PCM 或任意本地 URI。

## 官方与原始资料

- [sherpa-onnx 1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)
- [固定版本 Kotlin TTS API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/Tts.kt)
- [Android TTS 示例](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/android/SherpaOnnxTts)
- [MeloTTS 中文模型及英文词典边界](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html)
- [MeloTTS 原中文模型卡](https://huggingface.co/myshell-ai/MeloTTS-Chinese)
- [sherpa 转换包文件清单](https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/tree/main)
- [转换包许可](https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/blob/main/LICENSE)
- [MeloTTS 维护记录](https://github.com/myshell-ai/MeloTTS/commits/main/)

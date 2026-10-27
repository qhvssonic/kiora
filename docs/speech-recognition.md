# 端侧语音识别

Kiora SDK 将 ASR 与智能体运行时分开。`speech` 是纯 Kotlin 接口；宿主录制短音频并调用 `SpeechRecognitionPort.transcribe(Pcm16Audio)`，得到 `SpeechTranscript`。转写结果由宿主交给用户核对，再作为普通消息发给 `AgentRuntime`。ASR 不属于工具调用，不经过 `ToolAuthorizer`。

离线 TTS 首版已复用同一 sherpa-onnx 运行库，并由 Companion 接入播放。已实现接口、模型准备和待真机验收项见 [离线语音合成](speech-synthesis.md)。

`speech-sherpa-onnx` 是可选 Android 适配器，复用 sherpa-onnx 1.13.8，提供 SenseVoice Small int8 非流式识别与 Paraformer 中英双语 int8 流式识别。输入须为 16 kHz、单声道、16 位 PCM。适配器不请求麦克风权限，也不录音、保存音频、自动下载模型或发送网络请求。宿主负责权限、录音时长、取消、模型文件及用户确认。SenseVoice 的 `SpeechTranscript` 保留语言、情绪和事件标签；流式 Paraformer 只返回文本片段修订，不提供这些标签。

## 依赖与模型

官方 Android AAR 位于 [sherpa-onnx 1.13.8 release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)。当前 Maven Central 不提供对应 AAR；运行以下脚本将官方 AAR 校验 SHA-256 后放入本地 Maven 目录。构建时提供 `sherpaLocalRepo` 才会包含这个可选模块：

```powershell
.\scripts\install-sherpa-onnx.ps1
.\gradlew.bat :speech:test :speech:publishToMavenLocal :speech-sherpa-onnx:assembleRelease :speech-sherpa-onnx:publishToMavenLocal -PsherpaLocalRepo=..\artifacts\maven --offline
```

宿主还须配置该本地仓库，并引用 `dev.kiora:kiora-speech-sherpa-onnx:0.3.0-SNAPSHOT`。发布 SDK 到公共仓库前，需为 sherpa-onnx AAR 确定可供宿主解析的依赖仓库或重新打包方案。

SenseVoice 模型权重不进入 SDK AAR 或 Companion APK。使用与 sherpa-onnx 兼容的同一模型包中的 `model.int8.onnx` 和 `tokens.txt`，可参考 [官方 SenseVoice 导出说明](https://k2-fsa.github.io/sherpa/onnx/sense-voice/export.html)。模型及转换权重的使用条款需按来源分别核对；sherpa-onnx 代码许可证不能代替模型权重许可证。

## 权重版本与格式

2026-10-20 核对用户指定的 [ModelScope `iic/SenseVoiceSmall`](https://www.modelscope.cn/models/iic/SenseVoiceSmall/files)：仓库显示 2026-06-20 更新，但这次提交只改了 README 中的 GGUF 运行时说明。`model.pt` 最后一次提交为 2024-06-26，SHA-256 为 `833ca2dcfdf8ec91bd4f31cfac36d6124e0c459074d5e909aec9cabe6204a3ea`；[Hugging Face 官方文件](https://huggingface.co/FunAudioLLM/SenseVoiceSmall/blob/main/model.pt)显示相同 SHA-256。[sherpa-onnx 的预训练模型说明](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)指出，当前使用的 2024-07-17 ONNX 包由 `iic/SenseVoiceSmall` 导出。因此，重新把这个 `model.pt` 导出或量化为 ONNX 不会带来新训练权重。

[官方 SenseVoiceSmall-GGUF](https://huggingface.co/FunAudioLLM/SenseVoiceSmall-GGUF) 是同一 SenseVoiceSmall 的 GGUF 格式，配套 [FunASR llama.cpp 运行时](https://github.com/modelscope/FunASR/tree/main/runtime/llama.cpp)。当前 `speech-sherpa-onnx` 只能加载 sherpa-onnx 的 ONNX 导出，不能直接读取 GGUF；若将来要用 GGUF，应新增独立的 Android 原生运行时适配器，并在目标手机上验证内存、速度和输出。格式转换或运行时更新不应标为“2026 新版模型”。若采用真正不同的后续 ASR 权重，应先确认模型结构、导出方式、语言覆盖与许可，再决定复用本适配器或新增适配器。

## 使用边界

- `SpeechRecognitionPort` 保持整段最终转写；流式识别使用独立的 `StreamingSpeechRecognitionPort`，不改变 `AgentRuntime`。没有常开监听或自动发送。
- `Pcm16Audio` 不拷贝传入数组；调用期间宿主不应修改它。宿主应限制录音长度和并发数量，避免无限制积累 PCM。
- `SenseVoiceRecognizer` 通过 `close()` 释放本地识别器；同一实例应顺序使用。模型加载和解码可耗时，宿主应在后台协程调用。当前同步 `recognizer.decode()` 没有协程取消直接打断原生推理的实现；取消等待不等于原生解码已经结束，释放和复用必须等待其安全退出。
- 首轮设备验证应覆盖中文、中英混说、断网、取消、30 秒边界、冷启动时间、内存、延迟和音频路由切换。

2026-10-20 已在 V2509A 上以官方 2024-07-17 int8 模型和中文 `zh.wav` 做过一次真实离线转写，输出“开饭时间早上9点至下午5点。”。模型文件 SHA-256：`model.int8.onnx` 为 `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`，`tokens.txt` 为 `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`。这只验证了短中文样本；录音质量、不同手机的延迟与耗电仍需实测。

## 流式 Paraformer（2026-10-27）

`speech` 新增 `StreamingSpeechRecognitionPort`、`StreamingSpeechSession`、`SpeechRecognitionUpdate` 与 `StreamingTranscript`。宿主按顺序输入 PCM 块，接收带 `segmentId`、`revision`、`isFinal` 的文本。临时结果替换当前片段，最终结果封存该片段；不要把每次回调追加为一句话或写入会话。`StreamingTranscript` 负责去重、修订和拼接，不依赖提示词或关键词。

`speech-sherpa-onnx` 的 `StreamingParaformerRecognizer` 使用 sherpa 的 `OnlineRecognizer`，CPU 默认 2 线程，单次输入块不超过一秒。端点处按官方 Android 示例补 0.8 秒右侧静音上下文，再确认片段；停止时调用 `finish()` 排空尾部。`close()` 取消且不生成文本，释放 stream 与 recognizer；本地解码不能在算子中途强行中断，取消会在块之间检查并等待安全释放。每次 `openSession()` 加载独立模型，宿主须限制并发并在后台关闭；Companion 通过跨 Activity 的 ASR 互斥锁实现。

```kotlin
// 在后台协程执行。pcmChunks 来自宿主管理的有界麦克风队列。
val transcript = StreamingTranscript()
val session = StreamingParaformerRecognizer(modelDirectory).openSession()
try {
    for (pcm in pcmChunks) {
        session.accept(pcm).forEach { update ->
            val text = transcript.apply(update)
            // 切到 UI 线程替换显示 text；不要自动发送给 Agent。
        }
    }
    session.finish().forEach(transcript::apply)
} finally {
    withContext(NonCancellable + Dispatchers.IO) { session.close() }
}
```

### 模型准备与来源

固定导出包：[csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/tree/8e40c43232a1c5c66c82111efc5820d3accca11b)，修订 `8e40c43232a1c5c66c82111efc5820d3accca11b`。模型卡标注 Apache-2.0，并链接至 [FunASR 原始在线 Paraformer 模型](https://www.modelscope.cn/models/damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8404-online/summary)。sherpa-onnx 运行库代码为 Apache-2.0；下载脚本保留上游模型卡和文件清单。该模型与计划中的 Fun-ASR-Nano 是不同模型，不能据此宣称方言方案已经完成。

```powershell
python scripts/prepare_streaming_paraformer.py ..\artifacts\streaming-paraformer-zh-en-int8
```

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `encoder.int8.onnx` | 165462184 | `81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a` |
| `decoder.int8.onnx` | 71664561 | `f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f` |
| `tokens.txt` | 75756 | `59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6` |

三个文件约 237.2 MB（226.2 MiB），不是内存预算。无需增加运行库或原生 `.so`，权重不进入 AAR/APK；模型准备后识别路径不依赖网络。Android 最低版本沿用适配模块的 API 26；其他设备的最低 RAM、长时间热降频和耗电尚待验证。

### Companion 行为与边界

设置中导入上述完整目录，再选“Paraformer · 实时转写”。日常仍点击原来的麦克风按钮开始/停止，无额外字幕开关。SenseVoice 原有资产与选择保留，不自动切换模型、不做双遍识别。

- 录音最长 30 秒。每块 100 ms，Paraformer 路径队列最多 30 块（三秒，排队 PCM 约 96 KB，另有当前块、系统录音缓存和模型状态），SenseVoice 路径保持最多 10 块。识别跟不上时明确报错，不丢音频或无限扩大队列。SenseVoice 路径仍只缓存有上限的整段录音。
- Companion 0.1.105 起，点击后并行打开麦克风与加载 Paraformer，先收音再等待识别器，短暂缓冲保留开头音频；模型不会为了加速而常驻。识别准备/录音/收尾期间锁定输入框编辑；停止后可编辑、发送。已有输入文字保留，临时文本只修订新增部分。
- 开始录音停止 TTS；切后台、切换会话和销毁界面时停止麦克风、取消识别。旧回调不能覆盖新会话。取消/错误时保留已显示的文本，供人工检查。
- ASR 不注册为智能体工具，不调用在线模型、A2A 或外部转写服务。用户发送文字后，文字仍按当前聊天提供商的既有发送规则处理。
- 当前是前台语音输入的实时文本，不是跨应用悬浮字幕、系统音频字幕或无限时长会议转写。模型不提供字词时间戳，未新增独立 VAD、自动标点或说话人分离。

参考：[官方流式 Paraformer 模型说明](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-paraformer/paraformer-models.html)、[sherpa-onnx 1.13.8 Android 端点补齐示例](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt)。

### V2509A 真机记录（2026-10-27）

Companion 0.1.104，Android 16，CPU 2 线程，固定 int8 包；前台 Gemma 4 E2B 显示就绪后测量。使用上游 `test_wavs/0.wav` 与 `1.wav`，约 15.15 秒，以 100 ms 块按录音节奏输入，两段之间额外补静音。以下是一次样本记录，不是 P50/P95 或准确率评测：

| 指标 | 本机样本结果 |
| --- | ---: |
| ASR 模型加载 | 1135 ms |
| 第一批非空临时文字 | 首次送音起 1347 ms（已送入 1300 ms 音频） |
| 临时文本更新 / 端点确认 | 19 / 2 |
| 语音块累计解码耗时 | 1282 ms / 15153 ms 音频（不含额外静音处理） |
| 最后 `finish()` 耗时 | 32 ms（此前已输入尾部静音，不代表自然说话句尾延迟） |
| 测试前进程 PSS | 571030 KiB，约 558 MiB |
| 每秒采样所得最大进程 PSS | 1099124 KiB，约 1073 MiB |
| 相对测试前的 PSS 增量 | 528094 KiB，约 516 MiB |
| 关闭 ASR 后立即读取的进程 PSS | 714423 KiB，约 698 MiB |

PSS 是整个应用进程的测量值，含已就绪的聊天模型、Java/原生内存与共享页分摊；每秒采样可能遗漏瞬时峰值。关闭会话释放识别器和 stream，但进程内存不会立即回到基线，分配器缓存与其他工作仍会占用内存。不能将模型 237 MB 的磁盘体积描述成运行 RAM，也不能将一次增量当作所有设备保证。首次测量受 OEM 后台限速干扰，加载时间失真，已排除；回归测试现在主动打开前台并等待聊天模型就绪。

已通过的设备回归：非空 partial 在停止前出现、端点 reset 后继续第二段、静音无文本、提前停止排空尾部、重复 finish/close、关闭后重新加载。另在关闭 Wi-Fi（测试手机无 SIM）的条件下通过实际麦克风停止、切后台后任务结束/麦克风停止/ASR 锁释放、快速取消准备及输入框重新可编辑；测试结束恢复 Wi-Fi。公开样本中仍有中英混说误识，不据此承诺字词准确率或方言覆盖。更大样本的 CER/WER、连续 30 秒边界、蓝牙/来电、长时间发热和其他手机仍待验证。

设备测试为显式启用项：安装主 APK 与 androidTest APK（不要使用会清除宿主数据的安装流程），导入模型，并将公开 WAV 放入应用 `cache/streaming-asr-validation/`。使用 `StreamingVoiceTest` 的 `streamingVoice=true` 跑样本，或 `streamingVoiceUi=true` 单独跑麦克风生命周期。性能测试需保持手机解锁且聊天模型可就绪。

### 0.1.105 点击响应优化

原先 `openSession()` 完成后才打开麦克风，约 1.14 秒的模型加载会直接阻塞开始收音。现在 Companion 的 `StreamingVoicePipeline` 并行启动模型加载与麦克风，使用最多三秒（约 96 KB）的有界 PCM 队列保留开头音频，模型就绪后按原顺序消费。没有增加模型常驻或另一份识别器；录音结束仍释放会话。若加载/推理跟不上且缓冲满，会停止并报错，不静默丢弃开头。

2026-10-27，V2509A 的 0.1.105 冷 ASR 会话测得点击至 `AudioRecord.startRecording()` 成功并进入录音状态约 **74 ms**（UI 轮询采样；单次结果，不是 P95）。这不是首字延迟：模型加载与模型本身的语音上下文窗口仍会影响出字时间。麦克风停止、后台退出与快速取消回归通过。新增四项 JVM 管线测试覆盖加载前录音及停止后顺序排空、取消时等待加载退出并关闭、加载失败取消录音，以及采集溢出关闭模型；Companion 56 项 JVM 测试通过。

### 0.1.106 显示层更新

实时转写不再对每批结果调用 `EditText.setText()` 重建整段文本。Companion 的 `VoiceDraftRenderer` 保留 Editable 与不变前缀，只更新变化部分；追加内容按 ICU 完整字符簇展开，默认系统动画倍率下最多约 120 ms，旧快照不排队。纠错、撤回与最终结果立即应用，最终文本不等待动画才变得可编辑。录音期间隐藏光标，结束恢复；取消、切换会话或销毁界面移除动画回调，防止旧帧覆盖后续编辑。仅调整宿主显示，不修改 SDK ASR 输出、模型分块或识别质量。

V2509A 的两个显示回归测试已通过：Editable 保持、重复快照、即时纠错/最终落定、最终结果之后人工编辑不被覆盖，以及 emoji 字符簇完整性/取消后旧帧失效。

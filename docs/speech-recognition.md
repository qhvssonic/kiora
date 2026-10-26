# 端侧语音识别

Kiora SDK 将 ASR 与智能体运行时分开。`speech` 是纯 Kotlin 接口；宿主录制短音频并调用 `SpeechRecognitionPort.transcribe(Pcm16Audio)`，得到 `SpeechTranscript`。转写结果由宿主交给用户核对，再作为普通消息发给 `AgentRuntime`。ASR 不属于工具调用，不经过 `ToolAuthorizer`。

离线 TTS 首版已复用同一 sherpa-onnx 运行库，并由 Companion 接入播放。已实现接口、模型准备和待真机验收项见 [离线语音合成](speech-synthesis.md)。

`speech-sherpa-onnx` 是可选 Android 适配器，使用 sherpa-onnx 1.13.8 与 SenseVoice Small int8 做非流式识别。输入须为 16 kHz、单声道、16 位 PCM。适配器不请求麦克风权限，也不录音、保存音频、自动下载模型或发送网络请求。宿主负责权限、录音时长、取消、模型文件及用户确认。当前 `SpeechTranscript` 保留识别器返回的语言、情绪和事件标签，但聊天只发送用户确认的文本。

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

- 当前为一段短录音产生一次最终转写，没有临时字幕、VAD 或常开监听。接口以后可扩展流式事件，而不改变 `AgentRuntime`。
- `Pcm16Audio` 不拷贝传入数组；调用期间宿主不应修改它。宿主应限制录音长度和并发数量，避免无限制积累 PCM。
- `SenseVoiceRecognizer` 通过 `close()` 释放本地识别器；同一实例应顺序使用。模型加载和解码可耗时，宿主应在后台协程调用。当前同步 `recognizer.decode()` 没有协程取消直接打断原生推理的实现；取消等待不等于原生解码已经结束，释放和复用必须等待其安全退出。
- 首轮设备验证应覆盖中文、中英混说、断网、取消、30 秒边界、冷启动时间、内存、延迟和音频路由切换。

2026-10-20 已在 V2509A 上以官方 2024-07-17 int8 模型和中文 `zh.wav` 做过一次真实离线转写，输出“开饭时间早上9点至下午5点。”。模型文件 SHA-256：`model.int8.onnx` 为 `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`，`tokens.txt` 为 `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`。这只验证了短中文样本；录音质量、不同手机的延迟与耗电仍需实测。

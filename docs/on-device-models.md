# Kiora 端侧模型

Kiora 的 AgentRuntime 只依赖 `ModelPort`。端侧模型与云模型可以替换使用；A2A 工具、授权、沙箱和会话存储保持同一条运行链路。

| 后端 | Android 上如何运行 | 模型文件 | 当前边界 |
| --- | --- | --- | --- |
| LiteRT-LM | 在宿主应用进程里加载 SDK 原生库 | `.litertlm` | 支持文本、流式增量、手动工具调用；宿主可通过 `imageProvider` 传入单张图片，音频输入尚未接入 |
| MNN | 在宿主应用进程里加载 MNN 3.6.1 原生库 | 包含 `config.json`、权重和 tokenizer 的模型目录 | 文本、视觉模型图片输入、流式增量、取消和基于文本协议的工具调用；当前仅 arm64-v8a |

## LiteRT-LM

添加 `dev.kiora:kiora-model-litert:0.3.0-SNAPSHOT`。宿主必须通过 Android 系统文件选择器获得模型，再复制到应用可长期访问的目录；`LiteRtModelPort` 只接受现有 `.litertlm` 文件。不要将模型放进 APK。

```kotlin
val model = LiteRtModelPort(
    modelFile = File(filesDir, "models/gemma-4-E2B-it.litertlm"),
    backend = LiteRtBackend.CPU,
    cacheDirectory = File(cacheDir, "litert"),
)
model.prepare() // 在协程中调用；初始化会移到后台线程
val runtime = AgentRuntime(
    model = model,
    tools = tools,
    store = sessionStore,
    authorizer = authorizer,
    limits = RunLimits(timeoutMillis = 300_000),
)
// 宿主不再使用模型时，在协程中调用 model.close()
```

GPU 后端需要在应用 `<application>` 中声明可选的 `libOpenCL.so` 与 `libvndksupport.so` 原生库。模型和设备若不支持 GPU，请改用 CPU。LiteRT-LM 的工具自动执行已关闭：模型请求工具后，Kiora 的沙箱与授权器先检查，随后才执行工具。首次加载大型模型可能耗时并占用数 GB 内存。

Gemma 4 E2B 的 LiteRT 模型可从 [LiteRT Community 模型库](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)获取。具体文件格式、设备要求和后端支持应以该模型包及 [LiteRT-LM 官方 Android 文档](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md)为准。

## MNN 原生模型

添加 `dev.kiora:kiora-model-mnn:0.3.0-SNAPSHOT`。这是 Android AAR，包含 MNN 3.6.1 官方发布包的 arm64-v8a 原生库和 Kiora JNI 桥接。宿主需把**整个模型目录**复制到应用可长期读取的位置，并指向目录中的 `config.json`。模型不进入 APK：

```kotlin
val model = MnnModelPort(
    configFile = File(filesDir, "mnn-models/my-model/config.json"),
)
model.prepare() // 在协程中调用；模型加载在后台线程进行
val runtime = AgentRuntime(model, tools, sessionStore, authorizer,
    limits = RunLimits(timeoutMillis = 300_000))
// 宿主不再使用模型时，在协程中调用 model.close()
```

`MnnModelPort` 会检查模型目录的 `llm_config.json` 是否声明 `is_visual=true`，以及是否存在 `visual.mnn` 和 `visual.mnn.weight`。符合条件时，`AgentMessage.User` 和 `ToolResult` 的图片会缩放到最长边 1024 像素，暂存为应用私有缓存文件，通过 MNN 的 `<img>` 视觉输入路径交给模型；推理结束后删除临时图片。其他模型继续按文本使用。目标模型为 [MNN/Qwen3.5-4B-MNN](https://modelscope.cn/models/MNN/Qwen3.5-4B-MNN)；必须下载完整模型目录，不能只复制 `llm.mnn.weight`。视觉能力仍需用该模型在 Android 真机验证，目前没有音频输入。

MNN 的原生接口返回生成文本。Kiora 在 system 提示中给出工具定义，并只把符合 `kiora_tool_call` JSON 格式、且名称属于本轮可用工具的输出转成工具请求；参数校验、授权与执行仍由 `AgentRuntime` 完成。**工具选择取决于模型遵循提示词的能力，不能等同于 LiteRT-LM 的原生工具调用接口。**取消会请求停止逐 token 生成；若正在进行无法中断的原生加载或预填充，释放会等待原生调用结束。模型后端由其 `config.json` 控制，Kiora 暂未提供独立的 MNN CPU/GPU 开关。二进制来源和版本见 [`model-mnn/THIRD_PARTY.md`](../model-mnn/THIRD_PARTY.md)。

此模块替代了旧的 `MnnChatModelPort` 本机 HTTP 接入；已有宿主需要改用 `MnnModelPort` 并导入模型目录。

# Kiora 端侧模型

Kiora 的 AgentRuntime 只依赖 `ModelPort`。端侧模型与云模型可以替换使用；A2A 工具、授权、沙箱和会话存储保持同一条运行链路。

| 后端 | Android 上如何运行 | 模型文件 | 当前边界 |
| --- | --- | --- | --- |
| LiteRT-LM | 在宿主应用进程里加载 SDK 原生库 | `.litertlm` | 支持文本、流式增量、手动工具调用；宿主可通过 `imageProvider` 传入单张图片，音频输入尚未接入 |
| MNN Chat | 独立的 MNN Chat 应用运行模型，Kiora 经手机回环地址调用其 API | 由 MNN Chat 管理 | 依赖该应用开启 API 服务；函数工具能否调用取决于该 API 与所选模型的支持情况 |

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

## MNN Chat 本机 API

添加 `dev.kiora:kiora-model-mnn:0.3.0-SNAPSHOT`。先在 MNN Chat 中下载或导入模型并开启它的 API 服务，再创建模型端口：

```kotlin
val model = MnnChatModelPort(
    model = "MNN Chat 中显示的模型 ID",
    endpoint = "http://127.0.0.1:8080/v1/chat/completions",
    apiToken = { tokenOrNull },
)
```

这是经本机 HTTP 调用 MNN Chat 的适配器，未把 MNN 原生推理库打进 Kiora SDK。MNN Chat 的端口、鉴权和模型 ID 若有变化，请按其应用显示的地址配置。默认关闭流式请求；确认 MNN Chat API 支持 SSE 后，可设置 `streamingEnabled = true`。Android 宿主还需按其构建类型允许对回环地址的 HTTP 请求。参见 [MNN Chat 官方项目](https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat)。

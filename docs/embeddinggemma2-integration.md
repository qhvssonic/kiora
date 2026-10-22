# EmbeddingGemma 2 接入研究

更新于 2026-10-21。目标先服务于 Android 端的自然语言找照片与相似照片；Kiora SDK 提供可替换的嵌入能力，Companion 负责相册授权、索引调度和交互。SDK 已新增 `embedding` 与可选的 `embedding-mediapipe` 模块。官方 440M 文字＋视觉模型已在手机上完成文字和图片推理烟测；Companion 已实现首版相册索引与搜索，0.1.70 独立预览版由用户在手机上测试并反馈功能正常。0.1.71 已增加 Agent 只读相册搜索工具并覆盖安装正式版；正式版尚需重新导入模型、建立索引并验证 Agent 工具调用。

## 已核对的现状

- EmbeddingGemma 2 是嵌入模型，不生成聊天回复。它把文字、图片、视频和音频映射到共享的 768 维空间；支持截取到 512、256 或 128 维，截取后需要重新做 L2 归一化。它不应接入现有的 `ModelPort.respond()` 聊天循环。
- Google 的 LiteRT 社区提供 `.litertlm` 格式的文字版 270M、文字＋视觉版 440M、完整多模态版 740M。用于相册文字找图的首选是 **文字＋视觉 440M**；其模型卡标注下载体积约 388 MB。该数字不是 Companion APK 大小，也不代表所有设备的内存占用。
- Android 的 MediaPipe `UniversalEmbedder` 可通过 `com.google.mediapipe:tasks-retrieval` 加载模型，分别调用 `embedText()` 与 `embedImage()`。`SemanticRetriever` 还提供 SQLite 向量存储示例。官方 Android 指南采用 `latest.release`；Kiora 固定已经在手机验证的版本。
- 本模块使用 `tasks-retrieval:1.1.1`，并显式指定 LiteRT-LM `0.18.1`。MediaPipe 1.1.1 的 POM 仍声明 `0.17.0-alpha1`，但这个版本缺少运行时需要的 `ActivationDataType`，真机会报 `NoClassDefFoundError`；`0.17.1` 也缺少该类。现有 `model-litert` 原先按 `0.12.0` 编译，同包运行时因 `Backend.CPU` 的签名变化报 `NoSuchMethodError`。两个 SDK Android 模块现已统一声明 `0.18.1`，并在独立测试包中分别通过 EmbeddingGemma 2 与 Gemma 4 E2B 的真机推理回归。Companion 已使用这一组 SDK 产物完成构建，但尚未在原应用中验证运行。
- Google AI Edge Gallery 展示了在手机上建立本地媒体向量索引、用文字或示例图片检索的流程。其性能数据来自指定测试设备，不能直接作为 Companion 目标手机的保证。

## 建议的 SDK 边界

1. 在纯 Kotlin 模块定义独立的 `EmbeddingPort`，包含文字嵌入、图片嵌入、模型身份和向量维度；调用返回有限长度的数值向量，并明确取消与资源释放语义。先不要让生成式 `ModelPort` 承担嵌入任务。
2. 增加可选的 Android 适配器模块，封装 MediaPipe `UniversalEmbedder`。模型由宿主导入到应用私有目录，适配器接收路径与输入数据；不要把权重打进 SDK AAR。先支持文字和静态图片，CPU 路径作为基线，再测 GPU。
3. 检索层按模型 ID、模型文件版本、向量维度和归一化规则隔离索引。相似度用余弦相似度；仅比较同一模型和同一维度产生的向量。向量索引可先采用 SQLite，不能只存向量：还需保存宿主提供的不透明媒体 ID、内容版本或修改时间、授权来源和可访问状态。
4. Companion 在用户授权的媒体范围内读取缩略图或适当缩放后的图片，分批建立索引。模型只负责算向量；MediaStore 查询、系统权限、后台调度、原图访问与删除确认由宿主负责。权限撤销、文件变化、模型升级和中途停止后须能修复或重建相应索引。
5. 首个体验限定为小规模照片集：文字找图和以图找相似图。检索工具向智能体返回媒体 ID、分数与可解释的简要信息；取得原图、分享或修改仍需再次核验授权。

## 当前 SDK 用法

宿主下载并核验文字＋视觉 440M 的 `.litertlm` 文件，然后将其存放于应用私有目录。`modelId` 必须包含权重和预处理版本；模型文件变化时不能继续复用旧索引。模型未打入 SDK 或 APK。

本次设备烟测使用的官方文件为 `embeddinggemma-2-text-vision-440m.litertlm`，大小 387,710,976 字节，SHA-256 为 `92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0`。模型存于仓库外的 `../artifacts/`，不会进入 Git 或 SDK AAR。

```kotlin
val port = EmbeddingGemma2Port(context, modelFile, modelId = "embeddinggemma2-440m:<文件版本>")
port.prepare()
val query = port.embedQuery("海边的日落")
val photo = port.embedImage(imageBytes)
val score = query.cosineSimilarity(photo)
port.close()
```

`embedQuery` 默认使用官方搜索查询前缀；图片不加文字前缀。`embedDocument` 使用文档前缀。图片字节最多 32 MiB，适配器解码时将长边采样至约 1024 像素并按 EXIF 方向旋转；宿主也可传已校正的 `Bitmap`。当前支持 CPU/GPU；GPU 可用性与性能需要设备实测。原始输出为 768 维，也可选 512、256、128 维；截取后重新做 L2 归一化。不要混用不同模型 ID 或维度的向量。

## 验证记录与下一步

- V2509A 手机上，独立 SDK 测试包用上述权重完成中文查询、文档文字和测试图片的 768 维嵌入。一次烟测记录：加载 1506 ms、查询 234 ms、512×512 测试图片 1762 ms，文字与图片余弦相似度 0.745。该数字只说明推理链路已运行，不能代表相册检索质量或稳定性能。
- `:embedding:test`、`:embedding-mediapipe:assembleRelease` 通过；临时把 `model-litert` 与 `embedding-mediapipe` 同时加入 `android-demo`，APK 构建通过。独立测试包里 Gemma 4 E2B 文字回复与 EmbeddingGemma 2 文字/图片向量均通过。一次嵌入测试被手机系统以 `USER REQUESTED / FORCE STOP` 终止，随后重试通过；需在 Companion 接入时观察后台稳定性。
- 适配器额外对非 ARGB_8888 的系统缩略图做格式转换，RGB_565 输入在独立真机测试中通过。Companion 0.1.70 已构建并以独立包名安装预览版；正式版没有覆盖。
- Companion 首版实现手动分批索引、文字找图及从结果找相似照片；每批最多 100 张，按 Android 当前授权可见范围扫描，并在搜索前剔除不可见照片。索引保存模型文件 SHA-256、预处理版本、照片 ID、修改时间、大小和 768 维向量，不保存原图。`app:assembleDebug` 构建通过；仍需测同一进程内交替使用两模型的内存、索引 100 张照片的时间和电量，以及误命中样例。
- 用户已在手机上测试首版相册功能并反馈正常；这是功能性反馈，尚无索引耗时、耗电或检索准确率的量化结果。
- 用同一批照片验证“雨天的街道”等中文查询和以图找图；查询与索引使用相同模型、维度和归一化设置。比较 768 与 256 维的排序质量和索引体积；不要预设 128 维适合跨模态图片检索。
- 验证应用重启、媒体删除、仅授予部分照片、授权撤销及模型文件替换后的行为，再扩大索引规模。

## 参考资料

- [Google EmbeddingGemma 2 模型说明](https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2)
- [LiteRT 文字＋视觉 440M 模型卡](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm)
- [MediaPipe Universal Embedder Android 指南](https://developers.google.com/edge/mediapipe/solutions/retrieval/universal_embedder/android)
- [MediaPipe Semantic Retriever Android 指南](https://developers.google.com/edge/mediapipe/solutions/retrieval/semantic_retriever/android)
- [Google AI Edge Gallery 手机端检索说明](https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/)

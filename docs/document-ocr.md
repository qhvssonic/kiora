# 端侧文档 OCR

Kiora SDK 将 OCR 分为两个可选模块：

- `document-ocr` 是纯 Kotlin 契约。`DocumentOcrPort.recognize(imageBytes)` 返回原文和逐行文本、边界框、可用时的置信度。
- `document-ocr-mlkit` 是 Android 实现，使用打包进应用的 ML Kit 中文文字识别模型。调用不依赖首次联网下载；图片在本机识别。宿主仍需负责拍照、文件读取、结果校对和保存。
- `document-ocr-paddle` 是独立的可选 Android 适配器，封装官方 PaddleOCR Android SDK + PP-OCRv6 small。Kiora SDK 默认构建不引入 ONNX Runtime 和 OpenCV；Companion 0.1.39 的扫描页只使用 Paddle，ML Kit 适配器仍作为可选 SDK 模块保留。

```kotlin
MlKitChineseOcr().use { ocr ->
    val page = ocr.recognize(photoBytes)
    showEditableText(page.text)
}
```

两个适配器处理相机 EXIF 的 90°、180°、270° 旋转，并将最长边超过 4096 像素的图片按 2 的幂次降采样，避免高像素照片直接解码造成过高内存占用。镜像方向尚未处理。`OcrLine.bounds` 位于识别时所用的解码图坐标系内；如需把边界框画回原图，宿主应同时记录采样比例和方向。`OcrPage.timing` 记录各引擎报告的耗时，Paddle 还提供检测和识别分项；加载模型的耗时由 `PaddleDocumentOcr.coldLoadTimeMs` 给出。不同引擎的内部计时范围并不一致，性能对比请在调用外统一计时。`confidence` 可能缺失，不能仅靠它自动断定内容正确。对表格、手写、复杂排版和模糊照片，应保留原图与人工核对步骤。

两个引擎现已接入相同的 SDK 接口。后续仍需在同一批实拍中文文档上比较字符错误、漏行、耗时、峰值内存和安装包大小，再决定 Companion 是否默认内置 Paddle。视觉模型可在 OCR 之后核对疑难片段，但不能擅自用推测内容替换扫描原文。

Paddle 适配器默认把检测模型输入长边限制到 1536 像素，可通过 `PaddleDocumentOcr.create(context, maxDetectionSide = ...)` 调整。它仍从最多 4096 像素的解码原图裁切文字供识别。两张 3072×4080 的实拍照片使用官方默认 4000 像素检测上限时，Android 日志均记录检测阶段约 144 MB 的数组分配触发 `OutOfMemoryError`。1536 像素上限使同类检测张量约为 20 MB；在 V2509A 的 Companion 0.1.35 中，两张原照片的只读复测均成功，各识别出 17 行。尚未人工评估这些行的准确率。

Companion 同时包含 SenseVoice 时，两个上游 AAR 的 `libonnxruntime.so` 需要不同的符号版本。Companion 通过 [本地打包脚本](../../kiora-companion/scripts/install-onnxruntime-coexist.py) 给 OCR 的运行库改名并更新 ELF 链接；语音库保留原名。0.1.36 已在同一手机测试进程中依次运行实拍 Paddle OCR 和 SenseVoice；0.1.38 移除 Companion 的 ML Kit 依赖后仍保留这项兼容处理。此处理属于宿主打包层，SDK 的独立 Paddle 适配器仍使用官方 ONNX Runtime 依赖。

2026-10-20 的初始基线验证：V2509A（Android 16）在飞行模式下通过了 SDK 的印刷中文识别测试；Companion 导入一张四行合成中文测试页，识别出四行，其中“编号”和“检查”各有一字误识。此样张只验证手机链路，不能代表实拍文档准确率。随后两张真实照片触发上述 Paddle 内存错误，修复后均能完成识别；尚未对实拍文档的识别准确率评分。

同机两行合成印刷图像的 5 次重复测试（首次单列，单位 ms）。下表统一从调用 `recognize` 前计时到返回，包含图片解码；Paddle 模型加载时间另列：

| 实现 | 首次识别 | 后 5 次中位数 | 识别文本 |
| --- | ---: | ---: | --- |
| ML Kit 中文 | 108 | 56 | `设备編号A102`、`检査日期 2026-10-20` |
| PP-OCRv6 small | 529；模型加载另需 320 | 384 | `设备编号 A102`、`检查日期 2026-10-20` |

这是单机、单张合成图像的链路测试，样本量不足以做准确率结论。测试 APK 含所有 ABI，ML Kit 为约 47.5 MB，Paddle 为约 320.7 MB；两者不是正式产品 APK 的大小。Paddle 模型所在官方 AAR 约 26.2 MB，OpenCV 官方 AAR 约 117.7 MB，ONNX Runtime AAR 约 27.9 MB，后续应测量 arm64 单 ABI 构建。

## 可选 PP-OCRv6 small 适配器

适配器对应 [PaddleOCR 官方 Android 示例](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/cross_platform/android_deployment.md) 的源码提交 `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`。先按官方文档获取 [检测模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_det_onnx_infer.tar) 与 [识别模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_rec_onnx_infer.tar)，把 `inference.onnx` 放到官方 `ppocr-sdk/src/main/assets/models/det/` 和 `rec/`，把识别模型的 `inference.yml` 也放到 `rec/`。构建官方 `:ppocr-sdk:assembleRelease` 后，在 Kiora 目录运行：

```powershell
.\scripts\install-paddle-ocr.ps1 -Aar <官方 ppocr-sdk-release.aar 的绝对路径>
.\gradlew.bat :document-ocr-paddle:connectedDebugAndroidTest -PpaddleLocalRepo=..\artifacts\maven
```

`install-paddle-ocr.ps1` 检查 AAR 内三个模型文件，并安装到本地 Maven 仓库的 `com.paddle.ocr:ppocr-sdk-android:0.1.0-dab3fe3`。这是 Kiora 为本地构建指定的坐标，不是 PaddlePaddle 发布的 Maven 坐标。它的 POM 使用 OpenCV 官方 `org.opencv:opencv:4.12.0`；官方 Android 示例依赖的 `com.quickbirdstudios:opencv:4.5.3` 在 V2509A Android 16 上无法加载本机库。官方代码另有[识别预处理 BGR/RGB 顺序的待确认问题](https://github.com/PaddlePaddle/PaddleOCR/issues/18380)，实拍对照前要验证该问题是否影响目标文档。

OCR 是图像到原文的步骤；结构化整理、语音指令和 A2A 上传属于宿主/智能体流程。当前 SDK 不提供专用文档工作流或电脑端入库服务。

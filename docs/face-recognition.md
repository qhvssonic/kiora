# 本地人脸注册与检索

`face` 是纯 Kotlin/JVM 契约与注册、匹配逻辑；`face-onnx` 是可选 Android 适配器。
不依赖聊天模型、Android UI、相机、网络或 AgentRuntime；不会自动注册智能体工具。
Companion 0.1.103 提供用户主动的人脸注册、实时扫描 1:N 查询和人脸相册查找。

## 模型和运行库

首版固定使用 InsightFace 官方 **buffalo_sc**：SCRFD-500MF 检测、MobileFaceNet 特征。
下载：https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_sc.zip
解压后需要两份文件（约 16.14 MB，十进制，不代表运行内存）：

| 文件 | SHA-256 |
| --- | --- |
| `det_500m.onnx` | `5e4447f50245bbd7966bd6c0fa52938c61474a04ec7def48753668a9d8b4ea3a` |
| `w600k_mbf.onnx` | `9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f` |

准备脚本：`scripts/prepare-insightface.ps1 -OutputDirectory <仓库外模型目录>`。
模型不进入 Git、AAR 或 APK。Companion 通过系统目录选择器复制并校验固定权重，
成功创建两个推理会话后才原子切换目录；导入失败保留上一个模型。

InsightFace 代码采用 MIT；官方预训练权重仅供非商业研究，下载方式不改变许可。
Kiora 的开源许可不覆盖权重使用权。参见 [官方声明](https://github.com/deepinsight/insightface/tree/master/python-package#license)。
模型接口允许后续适配其他权重；当前 Companion 导入器仅接受上表版本。

`face-onnx` 只以 `compileOnly` 引用 ORT Android 1.21.1 API，**宿主必须提供运行库**。
Companion 复用已有 `1.28.0-kiora-ort1.28.2`，与 OCR/sherpa 共用运行库，不加入第二份 ORT `.so`。
CPU、2 线程作为基线；未宣称 NNAPI/GPU 性能。最低 Android API 26。

## 接口与算法

- `FaceImage`：宿主完成权限检查、EXIF 旋转，提供正向、未镜像 ARGB 像素；最大 4096×4096。
- `FacePort.analyze`：返回人脸框、五点关键点、512 维向量、初步质量问题。
- `InsightFacePort`：SCRFD 输入 640×640，RGB `(pixel-127.5)/128`，右/下补黑；
  解码三个步长、双 anchor、NMS；五点最小二乘相似变换到 ArcFace 112×112 模板；
  MobileFaceNet 输入 RGB `(pixel-127.5)/127.5`，输出 L2 归一化向量。
- `FaceRegistry`：生成稳定人员 UUID，显式注册、添加样本、删除、1:N 查询；
  单人最多 12 个样本，拒绝质量不合格、模型或维度不符的特征。
- `FaceRegistryStore`：宿主注入存储；原子替换人员样本、删除全部版本样本。
- `FaceMatcher`：每个人取最接近的样本，按不同人员计算第一/第二候选间距；
  即使只要求一个候选也检查第二人的歧义；结果为 MATCH、UNKNOWN 或 AMBIGUOUS。

```kotlin
val engine = InsightFacePort(modelDirectory, modelIdentity)
try {
    val observations = engine.analyze(uprightArgbImage)
    require(observations.size == 1)
    val registry = FaceRegistry(hostStore, engine.modelId)
    val person = registry.enroll("本人", observations)
    val result = registry.search(observations.single().embedding,
        FaceMatchPolicy(minimumSimilarity = 0.5, minimumMargin = 0.08))
} finally {
    withContext(NonCancellable) { engine.close() }
}
```

示例阈值仅供功能验证，**不是校准后的身份认证标准**。阈值必须以实际人员规模、
陌生人、光照、角度、年龄变化和误匹配目标校准；余弦相似度不是身份概率。
当前质量规则只粗筛人脸尺寸、检测分数、眼间距和裁剪图模糊度，不是活体检测。
不包含防照片/屏幕翻拍能力，不能直接用于门禁、支付或登录身份验证。

算法实现参考 InsightFace 的 [SCRFD](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/model_zoo/scrfd.py)、
[ArcFace ONNX](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/model_zoo/arcface_onnx.py)、
[五点对齐模板](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/utils/face_align.py)。
Kiora 使用 Kotlin/Android 图像处理，无需引入 OpenCV 原生库。

## Companion 流程与数据边界

侧栏「本地人脸 · 注册与查找」或设置「本地人脸」进入：

1. 导入解压后的 `buffalo_sc` **整个目录**。
2. 注册人员：填写名称，前置相机拍摄或选单人照片。再次点击该人员可以添加样本。
   相似已有人员时提示人工核对，程序不自动合并身份。
3. 1:N：实时扫描或选图；扫描时自动确认清晰、稳定的单张人脸，多人入镜不触发查询；选图包含多张脸时先选择目标。
4. 相册：授予完整或部分照片访问权，更新人脸索引；再扫描人脸、选择参考照片或选择已注册人员查找。
   返回候选照片、命中框和相似度，支持继续显示及打开照片。

注册特征存在应用私有 SQLite `databases/faces.db`（标准安装路径为
`/data/user/0/dev.kiora.companion/databases/faces.db`），保存人员、模型版本、样本向量与照片索引。
原图不作为注册资产保存；注册临时照片处理后删除。模型另存于私有 `files/face-models/`。
扫描查询直接分析 NV21 预览帧（优先不超过 1280×720，无适配尺寸时尝试相机最小尺寸），旋转为正向且不镜像，
不调用拍照、不编码 JPEG、不落盘。约每 350 ms 允许一帧，最多一帧排队或推理，繁忙时丢弃后续预览帧。
程序要求单张合格人脸居中且占据足够面积，连续至少 3 帧、跨度至少 700 ms，
相邻位置重叠及与起始帧特征相似度达标后只返回一次模型 ID 和特征向量，由查询页面执行检索。
多人、失焦、离开画面、换人或长时间中断会重新累计；这些条件用于自动选帧，不提供活体证明。
切换摄像头前等待旧推理引擎关闭，后台/退出取消帧处理并释放相机；注册保持显式采集。
没有默认人脸 Agent 工具、自动上传、云识别、A2A 传输或聊天记录写入。
如果宿主另行封装工具，权限/资源绑定和外发授权仍必须由宿主执行。

相册按照片 ID、修改时间、大小、模型版本增量处理；一张照片可存多个人脸。
无清晰人脸的照片也标记已处理，读图失败则留待下次重试。
每张照片的替换是事务；停止/销毁页面时取消任务，完成的照片可以续用。
查询前重新扫描当前授权范围，删除已撤权、已删除或已变化照片的旧特征。
删除人员不等于删除相册人脸索引；界面分别提供删除人员、清空索引、删除全部数据。

首次索引需保持页面运行；首版不提供系统后台任务。每次操作使用独立、串行的引擎，
关闭等待正在运行的原生调用完成，ORT 全局环境由运行库管理。
小脸、侧脸、遮挡和解码后的低分辨率会导致漏检，不能承诺找回所有相关照片。

## 验证

```powershell
.\gradlew.bat :face:test :face:publishToMavenLocal :face-onnx:publishToMavenLocal --offline
```

单元测试覆盖陌生人拒配、不同人员歧义、多模板聚合、模型隔离、五点对齐、注册持久接口和删除。
Companion 的 `FaceIntegrationTest` 必须显式开启，默认跳过。三个参数分别启用：

| 参数 | 验证内容与数据范围 |
| --- | --- |
| `faceRegression=true` | 公开样例推理、空图、人员注册/重开/删除、多脸索引、媒体变化清理、取消与关闭；使用独立临时数据库 |
| `faceUiRegression=true` | 公开多人照片返回后必须显式选择目标脸；进入真实查询页面，但不添加测试人员 |
| `faceScanRegression=true` | NV21 颜色/旋转、三帧稳定判断、三种相机入口及特征返回后查询；进入真实查询页面，但不注册或拍摄人员 |

界面测试会读取当前注册库，页面恢复时也会执行现有照片授权清理逻辑；建议使用专门的测试安装。
先通过 UI 导入模型，再在应用私有 `cache/face-validation/` 准备以下文件（不打进 APK）：

```text
buffalo_sc/det_500m.onnx
buffalo_sc/w600k_mbf.onnx
t1.jpg
preview-upright.png
preview-512x384.nv21
reference-vectors.bin     # 可选，独立 OpenCV/ORT 参考特征
```

`t1.jpg` 使用 [InsightFace 公开样例](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/data/images/t1.jpg)。
预览样例从该图裁剪单张清晰居中的人脸，缩放为 384×512 PNG，人脸宽度约占图宽 46%；
将 PNG 逆时针旋转 90° 为 512×384，再按 BT.601 limited-range 转换为 NV21（Y 平面 + 交错 VU）。
测试会顺时针旋转 90° 还原并与 PNG 特征比较，不需要真实相机数据。

覆盖安装主 APK 与测试 APK、准备好文件后，先打开主应用，再执行：

```powershell
adb shell am start -W -n dev.kiora.companion/.MainActivity
adb shell am instrument -w -e faceRegression true -e faceUiRegression true -e faceScanRegression true -e class dev.kiora.companion.FaceIntegrationTest dev.kiora.companion.test/androidx.test.runner.AndroidJUnitRunner
```

2026-10-26，Companion 0.1.103 / V2509A / Android 16 / CPU 2 线程：6 项 SDK 单元测试、
52 项 Companion JVM 测试和上述 3 项真机回归通过。JVM 新增 7 项覆盖 NV21 VU 顺序/旋转、
截断帧拒绝、稳定时长、换人、移动、多人/低质量/断帧后重置。
公开 `t1.jpg`（1280×886、6 张脸）本轮回归模型加载 113 ms、检测及全部特征提取 182 ms；
Android 与独立 OpenCV/ORT 参考向量余弦相似度为 0.9957～0.9995。
三个预览样例帧转换及推理分别约 89 / 61 / 59 ms，与原 PNG 特征余弦相似度约 0.99856。
这些数字是一次样例运行记录，不是相机实时性能统计、分位数或人群准确率。
同一设备实际前置预览已完成自动人员查询和自动相册查找（返回 2 张候选照片），
两次查询结束后均由系统相机状态确认释放。这是功能烟测，不是身份准确率验收。
完整相册规模、真实人员误匹配率、峰值内存及与聊天/语音模型高负载并行仍需专项验证。

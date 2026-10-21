# 通过 A2A 发送手机文件和照片

A2A 1.0 的 `Part` 可以包含文本、内联原始字节、URL 或结构化数据。Kiora 当前实现使用 `raw`、`filename`、`mediaType` 发送小文件；`raw` 在 JSON 中按 Base64 编码。电脑端必须支持接收和处理这种 Part。普通手机 `content://` URI 只对授予权限的本机应用有效，不能直接发给电脑端当作下载链接。

```kotlin
val connection = A2aPeerConnector.discoverConnection(agentCardUrl, bearerToken = { token })
val photo = A2aInlineFile(
    filename = "photo.jpg",
    mediaType = "image/jpeg",
    bytes = photoBytes,
)
val task = connection.peer.send(A2aUserMessage(
    text = "请识别这张照片并整理关键信息",
    files = listOf(photo),
))
```

Android 应用应使用系统照片选择器或 `ACTION_OPEN_DOCUMENT` 获取 URI。`android-attachments` 模块的 `AndroidA2aAttachmentReader.read(contentResolver, uri)` 会在后台读取内容、限制大小并保留文件名与 MIME 类型。发送前仍需显示文件名和目标 Agent，让用户确认。当前请求总附件大小上限为 10 MiB；较大的照片可先在应用中缩放或压缩。发送只读取系统选择器授权的文件，不需要申请全盘存储权限。

宿主若提供拍照、扫描等场景动作，应只改变附加内容与默认提示词，不应由场景筛掉已配置的内置工具。例如用户同时要求把扫描文字整理成 Markdown 文件时，模型仍需看到工作区 `write`。`ActiveToolSelector` 可依据宿主配置、实际授权和远端工具发现情况筛选；写入是否允许继续由宿主的 `ToolAuthorizer` 判定。

当前 SDK 提供 Android URI 读取、A2A 附件编码与发送；文件选择界面、长任务进度和远端返回文件的保存属于下一阶段应用功能。服务端若只接受文本，可能拒绝带 `raw` 的请求，此时需先扩展电脑端 Agent 的输入处理。

从 Agent Card 生成的 `A2aSkillTool` 也能发送文件和表单。连接的 skill `inputModes` 必须声明相应文件 MIME 类型，宿主通过 `connection.skillTools(fileLoader = { ids -> … })` 将经过核验的文件 ID 转成 `A2aInlineFile`；工具参数才会出现 `files`。宿主可另传 `fileIdValidator = { ids -> … }`，只解析和核验 ID，不读取文件字节。Kiora 的 `ToolArgumentPrevalidator` 在 `ToolAuthorizer` 之前运行；无效 ID 作为 `INVALID_ARGUMENTS` 返回模型，不弹出上传授权。文件字节仍在授权后由 `fileLoader` 读取。声明 `application/json` 的 skill 可接收 `form` 对象，并作为 `data` Part 发送。未声明这些输入类型的 skill 只发送文本。无需为“URI 转字节”另设模型工具。A2A 标准未规定表单字段 Schema，字段定义需由电脑端的 skill 描述或双方约定补充。

宿主可发现多个 Agent Card 并分别调用 `skillTools(toolNamePrefix = "a2a_<连接 ID>")`。给每个连接提供稳定且不同的前缀，可避免不同 Agent 使用相同 skill ID 时发生工具名冲突。宿主应把连接 ID、名称和凭据与已发现的 peer 配对保存；待继续的任务也应记住目标连接，不能把 task ID 发给另一台 Agent。

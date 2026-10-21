# Android 授权存储工具

`android-storage-tools` 将 Android Storage Access Framework（SAF）授予应用的目录和图片 URI 映射为 Kiora `AgentTool`。模型只收到可调用工具的定义与工具返回的文件名、相对路径和不透明 ID，不接收应用私有文件路径，也不能通过工具参数提交任意 `content://` URI。

宿主应用负责让用户通过 `ACTION_OPEN_DOCUMENT_TREE` 授权目录、通过 `ACTION_OPEN_DOCUMENT` 选择照片，并调用 `takePersistableUriPermission()` 保存系统授权。之后提供两个 URI 清单：

```kotlin
val storageTools = AndroidStorageTools(
    context,
    folderGrants = { savedFolderUris() },
    photoGrants = { savedPhotoUris() },
)
val tools: List<AgentTool> = storageTools.all()
```

组件每次执行时都再次核对系统持久授权。撤销授权后，旧 ID 不再可解析。目录内路径只能是从授权根开始的相对路径；每个路径片段必须是实际子项，`..`、绝对路径和任意 URI 均不接受。

| 工具 | 用途 |
| --- | --- |
| `list_authorized_folders` / `list_authorized_photos` | 列出用户已授权的目录与照片 |
| `list_authorized_files` / `search_authorized_files` | 浏览目录和按名称搜索 |
| `inspect_authorized_file` / `read_authorized_text` | 查看元数据、图片尺寸或读取小型 UTF-8 文本 |
| `copy_authorized_file` / `move_authorized_file` | 复制或移动到可写的授权目录，不覆盖已有文件 |
| `rename_authorized_file` / `delete_authorized_file` | 修改或删除一个可写文件；不允许删目录 |

复制与移动最多 100 MiB；读取文本最多 1 MiB，返回内容最多 32 KiB；浏览与搜索也有结果上限。照片选择授权只覆盖被选中的图片，不代表整个相册。图片本体不会自动送入文本模型；宿主可以结合多模态模型或另行提供经授权的远端处理工具。操作还要通过宿主的 `ToolAuthorizer`，Android 文档提供方也可能拒绝写入、移动或删除。

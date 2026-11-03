# 参与 Kiora 开发

[English](CONTRIBUTING.md) | **简体中文**

Kiora 仍处于实验阶段。欢迎提交可复现的问题、测试用例和小范围改进；较大的 API 变更建议先在 Issue 说明使用场景。

先用 JDK 17 运行模拟示例，无需手机、Android SDK、模型权重或 API Key；首次 Gradle 构建需要联网获取依赖：

```powershell
./gradlew.bat :sample:run -PskipAndroid=true
./gradlew.bat test -PskipAndroid=true
```

Linux/macOS 将 `./gradlew.bat` 换为 `bash ./gradlew`。Android 集成验证另需 Android SDK Platform 35。在项目根目录运行：

```powershell
.\gradlew.bat test :android-attachments:assembleDebug :android-demo:assembleDebug
```

提交时请勿包含 `local.properties`、API Key、A2A Token、签名密钥或构建产物。对网络适配器的改动请使用假的传输实现测试协议请求，避免真实凭据和付费模型调用。

适合首次参与的工作包括：澄清模型导入步骤、修复文档链接、翻译小段指南、提供取消/会话/A2A 的最小复现，以及提交成功或失败的设备组合记录。真实模型性能请注明设备、后端、输入与测量方法，不以模拟测试代替真机结论。

核心保持与 Android UI 解耦，平台与重型依赖留在适配层；资源 ID、权限、生命周期和执行状态由程序负责。不要提交模型权重；反馈中请移除私人媒体与会话内容。

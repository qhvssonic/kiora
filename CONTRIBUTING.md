# 参与 Kiora 开发

Kiora 仍处于实验阶段。欢迎提交可复现的问题、测试用例和小范围改进；较大的 API 变更建议先在 Issue 说明使用场景。

本地验证需要 JDK 17 和 Android SDK Platform 35。在项目根目录运行：

```powershell
.\gradlew.bat test :android-attachments:assembleDebug :android-demo:assembleDebug
```

提交时请勿包含 `local.properties`、API Key、A2A Token、签名密钥或构建产物。对网络适配器的改动请使用假的传输实现测试协议请求，避免真实凭据和付费模型调用。

# Contributing to Kiora

**English** | [简体中文](CONTRIBUTING.zh-CN.md)

Kiora is experimental. Reproducible reports, small fixes, adapter documentation and translations are welcome. Discuss substantial API changes in an issue before implementing them.

## Start without a phone or model

With JDK 17, run the mock-model/A2A sample from this repository:

```powershell
./gradlew.bat :sample:run -PskipAndroid=true
```

No Android SDK, model weights or API key is required for this path. Gradle needs internet access for the initial build dependencies. On Linux/macOS use `bash ./gradlew` instead of `./gradlew.bat`.

## Check a change

For pure Kotlin/JVM modules:

```powershell
./gradlew.bat test -PskipAndroid=true
```

For the default Android integration, also prepare Android SDK Platform 35:

```powershell
./gradlew.bat test :android-attachments:assembleDebug :android-demo:assembleDebug
```

Optional speech/OCR native modules need separate preparation; see the [integration guide](docs/sdk-guide.en.md). Real model/device checks are separate from mock protocol tests. Record the exact model, backend, device and measuring procedure when reporting results.

## Useful first contributions

- Fix an unclear model-import step or broken documentation link.
- Translate a focused guide section while retaining permissions and capability limits.
- Provide a small reproducer for tool arguments, cancellation, session recovery or A2A protocol handling.
- Share a successful or failed device/model setup through the issue forms.
- Describe a concrete user task before proposing a new model integration.

Keep the core independent of Android UI. Put platform work and heavy inference dependencies in adapters. Models understand and plan; host code owns resource IDs, grants, lifecycle and execution status.

Do not commit `local.properties`, API keys, A2A tokens, signing keys, model weights or build output. Redact private media and conversation data from reports. Network adapter tests should use fake transports, rather than real credentials, paid model calls or remote business operations.

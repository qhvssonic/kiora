pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("org.jetbrains.kotlin.")) {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
            if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}

rootProject.name = "kiora"

include(":core", ":a2a", ":model-chat", ":speech", ":speech-tools", ":document-ocr", ":embedding", ":sample")
if (!providers.gradleProperty("skipAndroid").map(String::toBoolean).getOrElse(false)) {
    include(":android-attachments", ":android-storage-tools", ":android-code-sandbox", ":android-demo", ":model-litert", ":model-mnn", ":document-ocr-mlkit", ":embedding-mediapipe")
    if (providers.gradleProperty("sherpaLocalRepo").isPresent) include(":speech-sherpa-onnx")
    if (providers.gradleProperty("paddleLocalRepo").isPresent) include(":document-ocr-paddle")
}

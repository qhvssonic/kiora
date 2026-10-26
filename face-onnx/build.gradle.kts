plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}
android {
    namespace = "dev.kiora.face.onnx"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    publishing { singleVariant("release") { withSourcesJar() } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":face"))
    // Host supplies ONE compatible ORT Android runtime, shared with OCR/speech.
    compileOnly("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
afterEvaluate {
    publishing { publications { create<MavenPublication>("maven") {
        from(components["release"])
        artifactId = "kiora-face-onnx"
    } } }
}

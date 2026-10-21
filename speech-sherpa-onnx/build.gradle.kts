plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "dev.kiora.speech.sherpa"
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
    api(project(":speech"))
    api("com.k2fsa.sherpa.onnx:sherpa-onnx-android:1.13.8")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["release"])
                artifactId = "kiora-speech-sherpa-onnx"
            }
        }
    }
}

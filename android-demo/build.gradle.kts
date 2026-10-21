plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.kiora.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kiora.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":a2a"))
    implementation(project(":model-chat"))
    implementation(project(":android-code-sandbox"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

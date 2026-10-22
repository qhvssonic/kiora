plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "dev.kiora.model.litert"
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
    api(project(":core"))
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("com.google.code.gson:gson:2.13.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["release"])
                artifactId = "kiora-model-litert"
            }
        }
    }
}

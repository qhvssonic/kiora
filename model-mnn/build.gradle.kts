plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "dev.kiora.model.mnn"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        ndk.abiFilters += "arm64-v8a"
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["release"])
                artifactId = "kiora-model-mnn"
            }
        }
    }
}

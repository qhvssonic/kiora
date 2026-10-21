plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("android") version "2.3.10" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
}

allprojects {
    group = "dev.kiora"
    version = "0.3.0-SNAPSHOT"
}

subprojects {
    repositories {
        providers.gradleProperty("sherpaLocalRepo").orNull?.let { localRepo ->
            maven { url = uri(localRepo) }
        }
        providers.gradleProperty("paddleLocalRepo").orNull?.let { localRepo ->
            maven { url = rootProject.uri(localRepo) }
        }
        google()
        mavenCentral()
    }
}

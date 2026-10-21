plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":a2a"))
}

application {
    mainClass.set("dev.kiora.sample.MainKt")
}

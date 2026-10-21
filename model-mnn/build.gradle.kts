plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

dependencies {
    api(project(":core"))
    implementation(project(":model-chat"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kiora-model-mnn"
        }
    }
}

plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kiora-document-ocr"
        }
    }
}

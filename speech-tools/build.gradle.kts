plugins {
    kotlin("jvm")
    `maven-publish`
}
kotlin { jvmToolchain(17) }
java { withSourcesJar() }
dependencies {
    api(project(":core"))
    api(project(":speech"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.3.10")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}
tasks.test { useJUnitPlatform() }
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kiora-speech-tools"
        }
    }
}

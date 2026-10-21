plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kiora-core"
        }
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.4.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.3.10")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

tasks.test {
    useJUnitPlatform()
}

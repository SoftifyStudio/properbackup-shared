plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation("net.openhft:zero-allocation-hashing:0.16")
                implementation("com.google.code.gson:gson:2.10.1")
                implementation("org.yaml:snakeyaml:2.2")
                implementation("org.apache.commons:commons-compress:1.26.1")
                implementation("org.xerial:sqlite-jdbc:3.45.3.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.2")
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

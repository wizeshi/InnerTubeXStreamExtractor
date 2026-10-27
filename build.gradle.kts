plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.wizeshi.innertubex"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.MetrolistGroup.innertubex")
            includeGroup("com.github.MetrolistGroup.quackjs")
            includeGroup("com.github.MetrolistGroup")
        }
    }
}

dependencies {
    implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.11.2")
    implementation("io.ktor:ktor-server-core-jvm:3.6.0")
    implementation("io.ktor:ktor-server-cio-jvm:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.6.0")
    implementation("io.ktor:ktor-server-cors-jvm:3.6.0")
    implementation("io.ktor:ktor-client-core-jvm:3.6.0")
    implementation("io.ktor:ktor-client-cio-jvm:3.6.0")
    implementation("io.ktor:ktor-client-okhttp-jvm:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

tasks.shadowJar {
    archiveBaseName.set("innertubex")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "dev.wizeshi.innertubex.MainKt"
    }
    mergeServiceFiles()
}

plugins {
    kotlin("jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "engineering.cute"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

repositories {
    mavenCentral()
}

intellij {
    version.set("2024.2")
    type.set("CL")
    plugins.set(listOf("com.intellij.clion"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
        }
    }

    withType<org.gradle.api.tasks.compile.JavaCompile> {
        options.release.set(17)
    }

    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("")
    }

    buildSearchableOptions {
        enabled = false
    }
}

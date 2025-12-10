import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.7.0"
}

group = "engineering.cute"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))

    intellijPlatform {
        clion("2025.3")
        bundledPlugin("com.intellij.clion")
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
}

tasks {
    withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
        options.release.set(17)
    }
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform") version "2.5.0"
    // Compose compiler plugin from version catalog
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

group = "org.jetbrains.hexana"

repositories {
    intellijPlatform {
        defaultRepositories()
        marketplace()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2025.2.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugins("com.intellij.mcpServer")
        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }

    implementation(project(":encdec"))
    implementation(project(":wasm"))
    implementation(project(":ui"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")

    // JetBrains Compose for Desktop (AWT/Swing interop via ComposePanel)
    implementation(compose.desktop.currentOs)

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    projectName = "hexana"

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }

        changeNotes = """
            Nothings
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    // Enable remote debugger attach for runIde and debug against intellij sources.
    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>().configureEach {
        //jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5007")

    }
}

kotlin {
    jvmToolchain(21)
}

// Exclude standard kotlin coroutines from runtime / testRuntime since IDEA is using its own implementation
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-swing")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
}
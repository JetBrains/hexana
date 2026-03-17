import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("org.jetbrains.changelog") version "2.4.0"
    // Compose compiler plugin from version catalog
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

group = "org.jetbrains.hexana"

val baseVersion = project.property("pluginVersion") as String
val hexanaBuild = project.property("hexanaBuild")
version = when (hexanaBuild) {
    "release" -> {
        baseVersion
    }
    "nightly" -> {
        val buildNumber = project.findProperty("hexanaNightlyBuildNumber") as String
        "$baseVersion.$buildNumber-nightly"
    }
    else -> {
        "$baseVersion-SNAPSHOT"
    }
}

if (hexanaBuild == "nightly" || hexanaBuild == "release") {
    // set TeamCity build number to the published plugin version
    println("##teamcity[buildNumber '${version}']")
}

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
        intellijIdea("2025.3") {
            type = IntelliJPlatformType.IntellijIdea
            useInstaller = false
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)


        bundledPlugins("com.intellij.mcpServer")

        // TODO: whenever composeUI() will be updated, we can safely remove direct dependencies to dekstop runtime
        // see https://chat.example.invalid/archives/CHANNEL/p1763485226889799
        composeUI()
    }

    implementation(project(":encdec"))
    implementation(project(":wasmParser"))
    implementation(project(":binaryProvider"))
    implementation(project(":graalwasm:wasmprinter"))
    implementation(project(":graalwasm:common"))
    implementation(project(":graalwasm:wat2wasm"))
    implementation(kotlin("reflect"))
    implementation(libs.kotlinx.serialization.json)
    implementation("org.graalvm.polyglot:polyglot:25.0.0")
    implementation("org.graalvm.polyglot:wasm:25.0.0")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")

    // see https://chat.example.invalid/archives/CHANNEL/p1763485226889799
    compileOnly(compose.desktop.currentOs)
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.graalvm.polyglot:wasm:25.0.0")
    testRuntimeOnly("org.graalvm.polyglot:polyglot:25.0.0")

    implementation("com.google.code.gson:gson:2.13.1")
}

changelog {
    version = baseVersion
    headerParserRegex = """(\d+\.\d+)""".toRegex()
}

intellijPlatform {
    projectName = "hexana"

    publishing {
        token = providers.gradleProperty("publishToken")
        if (hexanaBuild == "nightly") {
            channels = listOf("nightly")
        }
    }

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<Test> {
        jvmArgs("-Dpolyglotimpl.DisableMultiReleaseCheck=true")
        classpath = classpath.filter { it.extension != "pom" }
    }
    // Enable remote debugger attach for runIde and debug against intellij sources.
    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>().configureEach {
        //jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5007")
    }

    patchPluginXml {
        changeNotes = provider {
            val item = when (hexanaBuild) {
                "release" -> changelog.get(version as String)
                else -> changelog.getUnreleased()
            }
            changelog.renderItem(
                item
                    .withHeader(false)
                    .withEmptySections(false),
                Changelog.OutputType.HTML
            )
        }
    }

    register<RunIdeTask>("buildEventsScheme") {
        group = "verification"
        description = "Generates Hexana FUS scheme artifact using the built-in IDE headless command"

        val outputFile = layout.buildDirectory.file("fus/scheme.txt")
        applySandboxFrom(project.tasks.named<PrepareSandboxTask>("prepareSandbox"))
        splitMode.set(false)
        splitModeTarget.set(SplitModeAware.SplitModeTarget.BACKEND)
        outputs.file(outputFile)
        environment("FUS_DESCRIPTION_REGISTRATION_ENABLED", "true")
        environment("FUS_COLLECTOR_FILENAME_ENABLED", "true")
        args(
            "buildEventsScheme",
            "--pluginId=org.jetbrains.hexana",
            "--outputFile=${outputFile.get().asFile.absolutePath}",
            "--errorsFile=${layout.buildDirectory.file("fus/scheme.errors.txt").get().asFile.absolutePath}",
            "--pluginsFile=${layout.buildDirectory.file("fus/plugins.txt").get().asFile.absolutePath}",
        )
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

sourceSets {
    main {
        java {
            srcDir("src/main/java")
            srcDir("src/main/gen")
        }
    }
}

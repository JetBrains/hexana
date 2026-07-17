import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    application
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana.cli"
version = providers.gradleProperty("hexanaToolsVersion").get()

val sourceSets = extensions.getByType<SourceSetContainer>()
val mainSourceSet = sourceSets.named("main").get()
val testSourceSet = sourceSets.named("test").get()
val integrationTestSourceSet = sourceSets.create("integrationTest") {
    java.srcDir("src/integrationTest/kotlin")
    compileClasspath += mainSourceSet.output + testSourceSet.runtimeClasspath
    runtimeClasspath += output + compileClasspath
}

configurations.named(integrationTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.named("testImplementation").get())
}

configurations.named(integrationTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.named("testRuntimeOnly").get())
}

val generatedVersionSourceDir = layout.buildDirectory.dir("generated/sources/hexanaCliVersion/main/kotlin")
val generateHexanaCliScriptVersion by tasks.registering {
    val versionValue = version.toString()
    inputs.property("hexanaCliVersion", versionValue)
    outputs.dir(generatedVersionSourceDir)

    doLast {
        val escapedVersion = versionValue.replace("\\", "\\\\").replace("\"", "\\\"")
        val outputDir = generatedVersionSourceDir.get().asFile.resolve("org/jetbrains/hexana/cli/hexanacliscript")
        outputDir.mkdirs()
        outputDir.resolve("HexanaCliScriptVersion.kt").writeText(
            """
            package org.jetbrains.hexana.cli.hexanacliscript

            internal const val HEXANA_CLI_SCRIPT_VERSION: String = "$escapedVersion"
            """.trimIndent() + "\n"
        )
    }
}

dependencies {
    implementation(project(":cli:product"))
    implementation(project(":cli:scripting"))
    implementation(project(":mcp:core"))
    implementation(project(":mcp:scripting"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)

    add(integrationTestSourceSet.implementationConfigurationName, libs.gson)
    add(integrationTestSourceSet.implementationConfigurationName, libs.junit4)
    add(integrationTestSourceSet.implementationConfigurationName, libs.kotlin.test)
}

application {
    applicationName = "hexana-cli-script"
    mainClass = "org.jetbrains.hexana.cli.hexanacliscript.MainKt"
    applicationDefaultJvmArgs = listOf("-Dhexana.version=$version")
}

graalvmNative {
    testSupport.set(false)
    toolchainDetection.set(true)

    binaries {
        named("main") {
            imageName.set("hexana-cli-script")
            mainClass.set(application.mainClass)
            fallback.set(false)
            buildArgs.addAll(
                "-Dhexana.version=$version",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=kotlin.DeprecationLevel",
            )
        }
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs black-box integration tests against the installed hexana-cli-script JVM application."
    group = "verification"
    dependsOn(tasks.named("installDist"))
    dependsOn(":cli:hexana-cli:installDist")
    shouldRunAfter(tasks.named("test"))
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    testLogging {
        showStandardStreams = true
        events("failed", "passed", "skipped")
    }
    systemProperty("hexana.cli.script.integration.installDir", layout.buildDirectory.dir("install/hexana-cli-script").get().asFile.absolutePath)
    systemProperty("hexana.cli.integration.installDir", project(":cli:hexana-cli").layout.buildDirectory.dir("install/hexana-cli").get().asFile.absolutePath)
}

val verifyHexanaCliScriptBoundary = tasks.register("verifyHexanaCliScriptBoundary") {
    description = "Fails if hexana-cli-script carries standard analysis, server transport, Ktor, MCP SDK, Clikt, or wasm-printer runtime."
    group = "verification"
    dependsOn(tasks.named("installDist"))

    doLast {
        val forbiddenProjectPaths = setOf(
            ":cli:core",
            ":graalwasm:wasmprinter",
            ":mcp:packaging",
            ":mcp:server",
            ":mcp:tools-generator",
            ":mcp:vscode-server",
        )
        val forbiddenGroupPrefixes = listOf(
            "com.github.ajalt.clikt",
            "io.ktor",
            "io.modelcontextprotocol",
        )
        val forbiddenPackagePrefixes = listOf(
            "com.github.ajalt.clikt",
            "io.ktor",
            "io.modelcontextprotocol",
            "org.jetbrains.hexana.cli.core",
            "org.jetbrains.hexana.graalwasm.wasmprinter",
            "org.jetbrains.hexana.mcp.server",
        )
        val forbiddenLibPrefixes = listOf(
            "cli-core",
            "wasmprinter",
            "server-",
            "kotlin-sdk",
            "ktor-",
            "clikt",
            "colormath",
            "jansi",
            "kotlin-logging",
            "logback",
            "mordant",
            "wasm-",
            "wasm-language",
        )
        val checkedConfigurationNames = setOf("implementation", "compileOnly", "runtimeOnly")

        fun hasForbiddenPrefix(value: String, prefixes: List<String>): Boolean =
            prefixes.any { prefix -> value == prefix || value.startsWith("$prefix.") }

        val declarativeViolations = checkedConfigurationNames.flatMap { configurationName ->
            configurations.findByName(configurationName)?.dependencies.orEmpty().mapNotNull { dependency ->
                when (dependency) {
                    is ProjectDependency -> {
                        val path = dependency.path
                        if (path in forbiddenProjectPaths) "$configurationName -> project($path)" else null
                    }

                    is ExternalModuleDependency -> {
                        val group = dependency.group
                        if (hasForbiddenPrefix(group, forbiddenGroupPrefixes)) "$configurationName -> $group:${dependency.name}" else null
                    }

                    else -> null
                }
            }
        }

        val runtimeClasspath = configurations.named("runtimeClasspath").get()
        val resolvedViolations = runtimeClasspath.incoming.resolutionResult.allComponents.mapNotNull { component ->
            when (val id = component.id) {
                is ProjectComponentIdentifier -> {
                    val path = id.projectPath
                    if (path in forbiddenProjectPaths) "runtimeClasspath resolves project($path)" else null
                }

                is ModuleComponentIdentifier -> {
                    val group = id.group
                    if (hasForbiddenPrefix(group, forbiddenGroupPrefixes)) {
                        "runtimeClasspath resolves $group:${id.module}:${id.version}"
                    } else {
                        null
                    }
                }

                else -> null
            }
        }.distinct()

        val importViolations = layout.projectDirectory.dir("src/main").asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { sourceFile ->
                sourceFile.readLines().asSequence().mapIndexedNotNull { index, rawLine ->
                    val line = rawLine.trim()
                    val referencedPackage = when {
                        line.startsWith("import ") -> line.removePrefix("import ").substringBefore(" as ").removeSuffix(".*")
                        line.startsWith("package ") -> line.removePrefix("package ")
                        else -> return@mapIndexedNotNull null
                    }
                    if (hasForbiddenPrefix(referencedPackage, forbiddenPackagePrefixes)) {
                        "${sourceFile.relativeTo(projectDir)}:${index + 1} -> $line"
                    } else {
                        null
                    }
                }
            }
            .toList()

        val libDir = layout.buildDirectory.dir("install/hexana-cli-script/lib").get().asFile
        val layoutViolations = libDir.listFiles().orEmpty()
            .map { it.name }
            .filter { jarName -> forbiddenLibPrefixes.any { prefix -> jarName == "$prefix.jar" || jarName.startsWith(prefix) } }
            .sorted()

        val violations = buildList {
            if (declarativeViolations.isNotEmpty()) {
                add("Forbidden declarative dependencies:")
                declarativeViolations.forEach { add("  - $it") }
            }
            if (resolvedViolations.isNotEmpty()) {
                add("Forbidden resolved dependencies:")
                resolvedViolations.forEach { add("  - $it") }
            }
            if (importViolations.isNotEmpty()) {
                add("Forbidden package imports:")
                importViolations.forEach { add("  - $it") }
            }
            if (layoutViolations.isNotEmpty()) {
                add("Forbidden installed jars:")
                layoutViolations.forEach { add("  - $it") }
            }
        }

        check(violations.isEmpty()) {
            "hexana-cli-script must stay free of standard analysis, server transport, Ktor, MCP SDK, Clikt, and wasm-printer runtime.\n" +
                violations.joinToString(separator = "\n")
        }
    }
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generateHexanaCliScriptVersion)
    }
    jvmToolchain(21)
}

tasks.named("check") {
    dependsOn(verifyHexanaCliScriptBoundary)
}

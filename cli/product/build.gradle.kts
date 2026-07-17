import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana.cli"

base {
    archivesName.set("cli-product")
}

dependencies {
    api(project(":cli:metadata"))
    api(project(":cli:runtime"))
    api(project(":mcp:core"))
    api(libs.kotlinx.serialization.json)
    implementation(project(":analytics"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}

val verifyCliProductBoundary = tasks.register("verifyCliProductBoundary") {
    description = "Fails if cli:product depends on command runtimes, MCP server transports, Ktor, MCP SDK, or server packages."
    group = "verification"

    doLast {
        val allowedProjectPaths = setOf(
            ":analytics",
            ":cli:metadata",
            ":cli:runtime",
            ":mcp:core",
        )
        val forbiddenProjectPaths = setOf(
            ":cli:core",
            ":cli:scripting",
            ":graalwasm:wasmprinter",
            ":idea-plugin",
            ":mcp:packaging",
            ":mcp:server",
            ":mcp:scripting",
            ":mcp:tools-generator",
            ":mcp:vscode-server",
        )
        val forbiddenGroupPrefixes = listOf(
            "com.github.ajalt.clikt",
            "com.jetbrains.intellij",
            "io.ktor",
            "io.modelcontextprotocol",
        )
        val forbiddenPackagePrefixes = listOf(
            "com.github.ajalt.clikt",
            "com.intellij",
            "com.jetbrains.intellij",
            "io.ktor",
            "io.modelcontextprotocol",
            "org.jetbrains.hexana.cli.core",
            "org.jetbrains.hexana.cli.scripting",
            "org.jetbrains.hexana.mcp.server",
            "org.jetbrains.hexana.mcp.scripting",
            "org.jetbrains.hexana.mcp.tooling",
        )
        val checkedConfigurationNames = setOf(
            "api",
            "implementation",
            "compileOnly",
            "runtimeOnly",
            "testImplementation",
            "testCompileOnly",
            "testRuntimeOnly",
        )
        val checkedResolvedConfigurationNames = setOf(
            "compileClasspath",
            "runtimeClasspath",
            "testCompileClasspath",
            "testRuntimeClasspath",
        )

        fun hasForbiddenPrefix(value: String, prefixes: List<String>): Boolean =
            prefixes.any { prefix -> value == prefix || value.startsWith("$prefix.") }

        val declarativeViolations = checkedConfigurationNames.flatMap { configurationName ->
            configurations.findByName(configurationName)?.dependencies.orEmpty().mapNotNull { dependency ->
                when (dependency) {
                    is ProjectDependency -> {
                        val path = dependency.path
                        if (path !in allowedProjectPaths) {
                            "$configurationName -> project($path) (allowed project dependencies: ${allowedProjectPaths.joinToString()})"
                        } else {
                            null
                        }
                    }

                    is ExternalModuleDependency -> {
                        val group = dependency.group
                        if (hasForbiddenPrefix(group, forbiddenGroupPrefixes)) "$configurationName -> $group:${dependency.name}" else null
                    }

                    else -> null
                }
            }
        }

        val resolvedViolations = checkedResolvedConfigurationNames.flatMap { configurationName ->
            val configuration = configurations.findByName(configurationName)
            if (configuration == null || !configuration.isCanBeResolved) {
                emptyList()
            } else {
                configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
                    when (val id = component.id) {
                        is ProjectComponentIdentifier -> {
                            val path = id.projectPath
                            if (path in forbiddenProjectPaths) "$configurationName resolves project($path)" else null
                        }

                        is ModuleComponentIdentifier -> {
                            val group = id.group
                            if (hasForbiddenPrefix(group, forbiddenGroupPrefixes)) "$configurationName resolves $group:${id.module}:${id.version}" else null
                        }

                        else -> null
                    }
                }
            }
        }.distinct()

        val importViolations = layout.projectDirectory.dir("src").asFile
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
        }

        check(violations.isEmpty()) {
            "cli:product must stay free of command runtimes, mcp:server, server packages, Ktor, MCP SDK, and Clikt.\n" +
                violations.joinToString(separator = "\n")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyCliProductBoundary)
}

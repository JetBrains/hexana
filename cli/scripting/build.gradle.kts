import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana.cli"

base {
    archivesName.set("cli-scripting")
}

dependencies {
    implementation(project(":cli:runtime"))
    implementation(project(":mcp:core"))
    implementation(project(":mcp:scripting"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}

val verifyCliScriptingBoundary = tasks.register("verifyCliScriptingBoundary") {
    description = "Fails if cli:scripting declares dependencies outside the neutral script CLI boundary."
    group = "verification"

    doLast {
        val forbiddenProjectPaths = setOf(
            ":analytics",
            ":cli:core",
            ":idea-plugin",
            ":mcp:server",
            ":mcp:tools-generator",
        )
        val forbiddenGroups = setOf(
            "com.github.ajalt.clikt",
            "com.jetbrains.intellij",
            "com.jetbrains.intellij.idea",
            "com.jetbrains.intellij.platform",
            "io.ktor",
            "io.modelcontextprotocol",
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

        val violations = checkedConfigurationNames.flatMap { configurationName ->
            configurations.findByName(configurationName)?.dependencies.orEmpty().mapNotNull { dependency ->
                when (dependency) {
                    is ProjectDependency -> {
                        val path = dependency.path
                        if (path in forbiddenProjectPaths) "$configurationName -> project($path)" else null
                    }

                    is ExternalModuleDependency -> {
                        val group = dependency.group
                        if (group in forbiddenGroups) "$configurationName -> $group:${dependency.name}" else null
                    }

                    else -> null
                }
            }
        }

        check(violations.isEmpty()) {
            "cli:scripting must stay script-only and transport-neutral. Forbidden dependencies:\n" +
                violations.joinToString(separator = "\n") { "  - $it" }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyCliScriptingBoundary)
}

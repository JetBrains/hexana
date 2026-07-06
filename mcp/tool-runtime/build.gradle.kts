import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}

val verifyToolRuntimeBoundary = tasks.register("verifyToolRuntimeBoundary") {
    description = "Fails if mcp:tool-runtime declares dependencies outside the runtime contract boundary."
    group = "verification"

    doLast {
        val forbiddenProjectPaths = setOf(
            ":idea-plugin",
            ":mcp:core",
            ":mcp:server",
            ":mcp:tools-generator",
        )
        val forbiddenGroups = setOf(
            "com.github.ajalt.clikt",
            "com.jetbrains.intellij",
            "com.jetbrains.intellij.idea",
            "com.jetbrains.intellij.platform",
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
            configurations.getByName(configurationName).dependencies.mapNotNull { dependency ->
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
            "mcp:tool-runtime must stay contracts-only. Forbidden dependencies:\n" +
                violations.joinToString(separator = "\n") { "  - $it" }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyToolRuntimeBoundary)
}

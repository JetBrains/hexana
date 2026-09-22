import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

base {
    archivesName.set("cli-wasm-tools-core")
}

dependencies {
    api(project(":cli:runtime"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}

val verifyWasmToolsCoreBoundary = tasks.register("verifyWasmToolsCoreBoundary") {
    description = "Fails if cli:wasm-tools-core depends on product, MCP, transport, or IDE layers."
    group = "verification"

    doLast {
        val allowedProjectPaths = setOf(":cli:runtime")
        val forbiddenGroups = listOf("com.github.ajalt.clikt", "com.jetbrains.intellij", "io.ktor", "io.modelcontextprotocol")
        val directConfigurations = setOf("api", "implementation", "compileOnly", "runtimeOnly", "testImplementation", "testCompileOnly", "testRuntimeOnly")
        val resolvedConfigurations = setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
        fun isForbiddenGroup(group: String?): Boolean = group != null && forbiddenGroups.any { group == it || group.startsWith("$it.") }
        val directViolations = directConfigurations.flatMap { name ->
            configurations.getByName(name).dependencies.mapNotNull { dependency ->
                when (dependency) {
                    is ProjectDependency -> dependency.path.takeIf { it !in allowedProjectPaths }?.let { "$name -> project($it)" }
                    is ExternalModuleDependency -> dependency.group?.takeIf(::isForbiddenGroup)?.let { "$name -> $it:${dependency.name}" }
                    else -> null
                }
            }
        }
        val resolvedViolations = resolvedConfigurations.flatMap { name ->
            configurations.getByName(name).incoming.resolutionResult.allComponents.mapNotNull { component ->
                when (val id = component.id) {
                    is ProjectComponentIdentifier -> id.projectPath.takeIf { it != project.path && it !in allowedProjectPaths }
                        ?.let { "$name resolves project($it)" }
                    is ModuleComponentIdentifier -> id.group.takeIf(::isForbiddenGroup)?.let { "$name resolves $it:${id.module}:${id.version}" }
                    else -> null
                }
            }
        }.distinct()
        val violations = directViolations + resolvedViolations
        check(violations.isEmpty()) {
            "cli:wasm-tools-core must remain workflow-neutral. Forbidden dependencies:\n" + violations.joinToString("\n")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyWasmToolsCoreBoundary)
}

tasks.named("test") {
    dependsOn(verifyWasmToolsCoreBoundary)
}

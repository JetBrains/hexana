import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

val libs = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
val sourceSets = extensions.getByType<SourceSetContainer>()
val java = extensions.getByType<JavaPluginExtension>()

val mainSourceSet = sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME).get()
val mcpTokenCounterSourceSet = java.sourceSets.create("mcpTokenCounter") {
    java.srcDir("src/mcpTokenCounter/kotlin")
    java.srcDir("src/mcpTokenCounter/java")
    compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
    runtimeClasspath += output + compileClasspath + mainSourceSet.runtimeClasspath
    resources.srcDir("src/mcpTokenCounter/resources")
}

configurations.named(mcpTokenCounterSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.getByName("implementation"))
}
configurations.named(mcpTokenCounterSourceSet.compileOnlyConfigurationName) {
    extendsFrom(configurations.getByName("compileOnly"))
}
configurations.named(mcpTokenCounterSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.getByName("runtimeOnly"))
}

dependencies {
    add(
        mcpTokenCounterSourceSet.implementationConfigurationName,
        libs.findLibrary("kotlinx-serialization-json").get()
    )
}

tasks.register<JavaExec>("measureMcpTokens") {
    group = "verification"
    description =
        "Measures the size of Hexana MCP tool descriptors and writes CSV/JSON reports to build/reports/mcp-tokens."

    val reportDir = layout.buildDirectory.dir("reports/mcp-tokens")

    dependsOn(tasks.named(mcpTokenCounterSourceSet.classesTaskName))
    classpath = mcpTokenCounterSourceSet.runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tooling.McpTokenCounterKt")
    outputs.dir(reportDir)
    doFirst {
        args = listOf(reportDir.get().asFile.absolutePath)
    }
}

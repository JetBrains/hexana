import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    application
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana.cli"
version = providers.gradleProperty("hexanaToolsVersion").get()

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val jvmSuffix = if (isWindowsHost) ".bat" else ""
val nativeSuffix = if (isWindowsHost) ".exe" else ""

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

val generatedVersionDir = layout.buildDirectory.dir("generated/sources/hexanaWasmToolsVersion/main/kotlin")
val generateHexanaWasmToolsVersion by tasks.registering {
    val releaseVersion = version.toString()
    inputs.property("hexanaToolsVersion", releaseVersion)
    outputs.dir(generatedVersionDir)
    doLast {
        val escaped = releaseVersion.replace("\\", "\\\\").replace("\"", "\\\"")
        val outputDir = generatedVersionDir.get().asFile.resolve("org/jetbrains/hexana/cli/hexanawasmtools")
        outputDir.mkdirs()
        outputDir.resolve("HexanaWasmToolsVersion.kt").writeText(
            "package org.jetbrains.hexana.cli.hexanawasmtools\n\n" +
                "internal const val HEXANA_WASM_TOOLS_VERSION: String = \"$escaped\"\n",
        )
    }
}

dependencies {
    implementation(project(":cli:metadata"))
    implementation(project(":cli:product"))
    implementation(project(":cli:runtime"))
    implementation(project(":cli:wasm-tools-core"))
    implementation(project(":mcp:core"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)

    add(integrationTestSourceSet.implementationConfigurationName, project(":cli:integration-tests"))
    add(integrationTestSourceSet.implementationConfigurationName, libs.gson)
    add(integrationTestSourceSet.implementationConfigurationName, libs.junit4)
    add(integrationTestSourceSet.implementationConfigurationName, libs.kotlin.test)
}

application {
    applicationName = "hexana-wasm-tools"
    mainClass = "org.jetbrains.hexana.cli.hexanawasmtools.MainKt"
    applicationDefaultJvmArgs = listOf("-Dhexana.version=$version")
}

graalvmNative {
    testSupport.set(false)
    toolchainDetection.set(true)
    binaries {
        named("main") {
            imageName.set("hexana-wasm-tools")
            mainClass.set(application.mainClass)
            fallback.set(false)
            buildArgs.addAll(
                "-Dhexana.version=$version",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=kotlin.DeprecationLevel",
                "--enable-url-protocols=http,https",
            )
        }
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs installed hexana-wasm-tools JVM contracts."
    group = "verification"
    dependsOn(tasks.named("installDist"))
    dependsOn(":cli:hexana-cli:installDist", ":cli:hexana-cli-script:installDist")
    shouldRunAfter(tasks.named("test"))
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    testLogging { events("failed", "passed", "skipped") }
    systemProperty("hexana.cli.integration.launcher", layout.buildDirectory.file("install/hexana-wasm-tools/bin/hexana-wasm-tools$jvmSuffix").get().asFile.absolutePath)
    systemProperty("hexana.cli.integration.standardLauncher", project(":cli:hexana-cli").layout.buildDirectory.file("install/hexana-cli/bin/hexana-cli$jvmSuffix").get().asFile.absolutePath)
    systemProperty("hexana.cli.integration.scriptLauncher", project(":cli:hexana-cli-script").layout.buildDirectory.file("install/hexana-cli-script/bin/hexana-cli-script$jvmSuffix").get().asFile.absolutePath)
    systemProperty("hexana.cli.integration.target", "jvm")
    systemProperty("hexana.cli.integration.version", version)
}

tasks.register<Test>("nativeIntegrationTest") {
    description = "Runs installed hexana-wasm-tools native contracts."
    group = "verification"
    dependsOn(tasks.named("nativeCompile"))
    dependsOn(":cli:hexana-cli:installDist", ":cli:hexana-cli-script:installDist")
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    testLogging { events("failed", "passed", "skipped") }
    systemProperty("hexana.cli.integration.launcher", layout.buildDirectory.file("native/nativeCompile/hexana-wasm-tools$nativeSuffix").get().asFile.absolutePath)
    systemProperty("hexana.cli.integration.standardLauncher", project(":cli:hexana-cli").layout.buildDirectory.file("install/hexana-cli/bin/hexana-cli$jvmSuffix").get().asFile.absolutePath)
    systemProperty("hexana.cli.integration.scriptLauncher", project(":cli:hexana-cli-script").layout.buildDirectory.file("install/hexana-cli-script/bin/hexana-cli-script$jvmSuffix").get().asFile.absolutePath)
    systemProperty("hexana.cli.integration.target", "native")
    systemProperty("hexana.cli.integration.version", version)
}

val verifyHexanaWasmToolsBoundary = tasks.register("verifyHexanaWasmToolsBoundary") {
    description = "Rejects scripting and MCP transport dependencies from hexana-wasm-tools."
    group = "verification"
    dependsOn(tasks.named("installDist"))
    doLast {
        val forbiddenProjects = setOf(":cli:core", ":cli:scripting", ":mcp:scripting", ":mcp:server", ":mcp:packaging", ":mcp:vscode-server")
        val forbiddenGroups = listOf("com.github.ajalt.clikt", "io.ktor", "io.modelcontextprotocol", "org.graalvm.js")
        val violations = configurations.named("runtimeClasspath").get().incoming.resolutionResult.allComponents.mapNotNull { component ->
            when (val id = component.id) {
                is ProjectComponentIdentifier -> id.projectPath.takeIf { it in forbiddenProjects }?.let { "project($it)" }
                is ModuleComponentIdentifier -> id.group.takeIf { group -> forbiddenGroups.any { group == it || group.startsWith("$it.") } }
                    ?.let { "$it:${id.module}:${id.version}" }
                else -> null
            }
        }
        check(violations.isEmpty()) { "hexana-wasm-tools contains scripting or MCP transport dependencies: ${violations.joinToString()}" }
    }
}

kotlin {
    sourceSets.named("main") { kotlin.srcDir(generateHexanaWasmToolsVersion) }
    jvmToolchain(21)
}

tasks.named("check") { dependsOn(verifyHexanaWasmToolsBoundary) }

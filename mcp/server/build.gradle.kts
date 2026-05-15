import java.util.*

plugins {
    application
    idea
    alias(libs.plugins.kotlin.jvm)
}

// Mark `src/main/gen` (generated HexanaToolDispatcher.kt) as generated so IntelliJ dims the file,
// keeps it out of default Find-in-Path scope, and warns on hand edits.
idea {
    module {
        generatedSourceDirs.add(file("src/main/gen"))
    }
}

group = "org.jetbrains.hexana"
version = Properties().apply {
    project.file("gradle.properties").inputStream().use(::load)
}.getProperty("mcpServerVersion")

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

val generatedToolDispatcherDir = layout.projectDirectory.dir("src/main/gen")
val verifiedToolDispatcherDir = layout.buildDirectory.dir("generator-verify/dispatcher")

tasks.register<JavaExec>("generateHexanaToolDispatcher") {
    description = "Regenerates HexanaToolDispatcher.kt into src/main/gen; the output is checked into git."
    group = "generation"

    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(generatedToolDispatcherDir.asFile.absolutePath)
    })

    inputs.files(project(":mcp:tools-generator").tasks.named("classes"))
    outputs.file(generatedToolDispatcherDir.file("org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt"))

    dependsOn(":mcp:tools-generator:classes")
}

val regenerateHexanaToolDispatcherForVerification = tasks.register<JavaExec>("regenerateHexanaToolDispatcherForVerification") {
    description = "Re-emits HexanaToolDispatcher.kt into build/ so verifyHexanaToolDispatcherUpToDate can diff it."
    group = "verification"

    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(verifiedToolDispatcherDir.get().asFile.absolutePath)
    })
    doFirst { verifiedToolDispatcherDir.get().asFile.deleteRecursively() }

    dependsOn(":mcp:tools-generator:classes")
}

tasks.register("verifyHexanaToolDispatcherUpToDate") {
    description = "Fails if src/main/gen/HexanaToolDispatcher.kt drifts from the tools-generator DSL."
    group = "verification"
    dependsOn(regenerateHexanaToolDispatcherForVerification)
    mustRunAfter(tasks.named("generateHexanaToolDispatcher"))

    val committed = generatedToolDispatcherDir.file("org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt").asFile
    val fresh = verifiedToolDispatcherDir.map {
        it.file("org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt").asFile
    }

    inputs.file(committed)
    inputs.dir(verifiedToolDispatcherDir)

    doLast {
        if (!committed.exists()) {
            error("Committed HexanaToolDispatcher.kt is missing at $committed. Run :mcp:server:generateHexanaToolDispatcher.")
        }
        val freshFile = fresh.get()
        if (committed.readText().replace("\r\n", "\n") != freshFile.readText().replace("\r\n", "\n")) {
            error(
                "HexanaToolDispatcher.kt is out of date with the tools-generator DSL.\n" +
                    "  Committed:   ${committed.absolutePath}\n" +
                    "  Regenerated: ${freshFile.absolutePath}\n" +
                    "  Fix: ./gradlew :mcp:server:generateHexanaToolDispatcher, then commit src/main/gen/."
            )
        }
    }
}

dependencies {
    implementation(project(":analytics"))
    implementation(project(":mcp:core"))
    implementation(libs.clikt)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.logback.classic)
    implementation(libs.mcp.kotlin.sdk.jvm)
    implementation(libs.ktor.server.cio.jvm)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(project(":graalwasm:wat2wasm"))
    testImplementation(libs.gson)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)

    add(integrationTestSourceSet.implementationConfigurationName, project(":graalwasm:wat2wasm"))
    add(integrationTestSourceSet.implementationConfigurationName, libs.junit4)
    add(integrationTestSourceSet.implementationConfigurationName, libs.kotlin.test)
    add(integrationTestSourceSet.implementationConfigurationName, libs.ktor.client.cio.jvm)
}

application {
    mainClass = "org.jetbrains.hexana.mcp.server.MainKt"
    applicationDefaultJvmArgs = listOf("-Dhexana.version=$version")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Version" to project.version
        )
    }
}

val plainClaudePluginDir = project(":mcp").layout.buildDirectory.dir("packaging-plain/claude-plugin")
val plainCodexPluginDir = project(":mcp").layout.buildDirectory.dir("packaging-plain/codex-plugin")
val obfuscatedServerInstallDir = project(":mcp").layout.buildDirectory.dir("install/server-r8")
val obfuscatedClaudePluginDir = project(":mcp").layout.buildDirectory.dir("packaging/claude-plugin")
val obfuscatedCodexPluginDir = project(":mcp").layout.buildDirectory.dir("packaging/codex-plugin")
val overrideClaudePluginDir = providers.gradleProperty("hexanaClaudePluginDir").orNull
val overrideCodexPluginDir = providers.gradleProperty("hexanaCodexPluginDir").orNull

fun Test.configureMcpPackageSmoke(
    serverInstallDir: Provider<Directory>,
    claudePluginDir: Provider<Directory>,
    codexPluginDir: Provider<Directory>,
) {
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    testLogging {
        showStandardStreams = true
        events("failed", "passed", "skipped")
    }
    systemProperty(
        "hexana.integration.installDir",
        serverInstallDir.get().asFile.absolutePath
    )
    systemProperty(
        "hexana.integration.claudePluginDir",
        claudePluginDir.get().asFile.absolutePath
    )
    systemProperty(
        "hexana.integration.codexPluginDir",
        codexPluginDir.get().asFile.absolutePath
    )
    filter {
        includeTestsMatching("*HexanaStdioIntegrationTest")
        includeTestsMatching("*HexanaHttpIntegrationTest")
        includeTestsMatching("*HexanaClaudePluginWrapperIntegrationTest")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs black-box HTTP and stdio integration tests against the installed standalone MCP server."
    group = "verification"
    dependsOn(tasks.named("installDist"))
    if (overrideClaudePluginDir == null) {
        dependsOn(":mcp:assemblePlainClaudePluginPackage")
    }
    if (overrideCodexPluginDir == null) {
        dependsOn(":mcp:assemblePlainCodexPluginPackage")
    }
    shouldRunAfter(tasks.named("test"))
    configureMcpPackageSmoke(
        serverInstallDir = layout.buildDirectory.dir("install/${application.applicationName}"),
        claudePluginDir = layout.dir(
            providers.provider {
                file(overrideClaudePluginDir ?: plainClaudePluginDir.get().asFile.absolutePath)
            }
        ),
        codexPluginDir = layout.dir(
            providers.provider {
                file(overrideCodexPluginDir ?: plainCodexPluginDir.get().asFile.absolutePath)
            }
        ),
    )
}

tasks.register<Test>("plainMcpPackagingSmoke") {
    description = "Smoke-tests pre-R8 standalone, Claude, and Codex MCP packages."
    group = "verification"
    dependsOn(":mcp:assembleMcpPackagingPlain")
    shouldRunAfter(tasks.named("test"))
    configureMcpPackageSmoke(
        serverInstallDir = layout.buildDirectory.dir("install/${application.applicationName}"),
        claudePluginDir = plainClaudePluginDir,
        codexPluginDir = plainCodexPluginDir,
    )
}

tasks.register<Test>("obfuscatedMcpPackagingSmoke") {
    description = "Smoke-tests R8-obfuscated standalone, Claude, and Codex MCP packages."
    group = "verification"
    dependsOn(":mcp:assembleMcpPackaging")
    shouldRunAfter(tasks.named("plainMcpPackagingSmoke"))
    configureMcpPackageSmoke(
        serverInstallDir = obfuscatedServerInstallDir,
        claudePluginDir = obfuscatedClaudePluginDir,
        codexPluginDir = obfuscatedCodexPluginDir,
    )
}

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir("src/main/gen")
    }
}

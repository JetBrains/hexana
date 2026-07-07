import java.io.ByteArrayOutputStream
import java.util.*
import java.util.zip.ZipFile

plugins {
    application
    idea
    alias(libs.plugins.graalvm.native)
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
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/hexanaBuildInfo/main/kotlin")
val generatedBuildInfoFile = generatedBuildInfoDir.map {
    it.file("org/jetbrains/hexana/mcp/server/HexanaBuildInfo.kt")
}
val nativeExecutableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "hexana-mcp.exe"
} else {
    "hexana-mcp"
}
val nativeExecutable = layout.buildDirectory.file("native/nativeCompile/$nativeExecutableName")

val generateHexanaBuildInfo = tasks.register("generateHexanaBuildInfo") {
    description = "Generates compile-time MCP server build metadata."
    group = "generation"

    val serverVersion = project.version.toString()
    inputs.property("mcpServerVersion", serverVersion)
    outputs.file(generatedBuildInfoFile)

    doLast {
        val outputFile = generatedBuildInfoFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package org.jetbrains.hexana.mcp.server

            internal object HexanaBuildInfo {
                const val VERSION = "$serverVersion"
            }
            """.trimIndent() + "\n"
        )
    }
}

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
    implementation(project(":graalwasm:wasmprinter"))
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

graalvmNative {
    testSupport.set(false)
    toolchainDetection.set(true)

    binaries {
        named("main") {
            imageName.set("hexana-mcp")
            mainClass.set(application.mainClass)
            fallback.set(false)
            buildArgs.addAll(
                "-Dhexana.version=$version",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=kotlin.DeprecationLevel"
            )
        }
    }
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

val java21RuntimeVersion = 21
val java21ClassFileMajorVersion = 65

fun isNewerMultiReleaseRuntimeClass(entryName: String): Boolean {
    val versionPath = entryName.removePrefix("META-INF/versions/")
    if (versionPath == entryName) return false
    val version = versionPath.substringBefore('/').toIntOrNull() ?: return false
    return version > java21RuntimeVersion
}

tasks.register("verifyMcpRuntimeClassFileVersions") {
    description = "Fails if the standalone MCP runtime contains class files newer than Java 21."
    group = "verification"
    dependsOn(tasks.named("installDist"))

    val runtimeLibDir = layout.buildDirectory.dir("install/${application.applicationName}/lib")
    inputs.dir(runtimeLibDir)

    doLast {
        val tooNewClasses = mutableListOf<String>()
        runtimeLibDir.get().asFileTree.matching {
            include("*.jar")
        }.files.sortedBy { it.name }.forEach { jarFile ->
            ZipFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    // Java 21 ignores multi-release entries for newer runtimes, such as GraalWasm's Java 25 module descriptor.
                    .filterNot { isNewerMultiReleaseRuntimeClass(it.name) }
                    .forEach { entry ->
                        jar.getInputStream(entry).use { input ->
                            val header = ByteArray(8)
                            var offset = 0
                            while (offset < header.size) {
                                val read = input.read(header, offset, header.size - offset)
                                if (read == -1) break
                                offset += read
                            }
                            if (offset == header.size) {
                                val majorVersion = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                                if (majorVersion > java21ClassFileMajorVersion) {
                                    tooNewClasses += "${jarFile.name}!/${entry.name} has class file major version $majorVersion"
                                }
                            }
                        }
                    }
            }
        }

        check(tooNewClasses.isEmpty()) {
            buildString {
                appendLine("Standalone MCP runtime must run on Java 21, but found newer class files:")
                tooNewClasses.take(50).forEach { appendLine("  $it") }
                if (tooNewClasses.size > 50) {
                    appendLine("  ... and ${tooNewClasses.size - 50} more")
                }
            }
        }
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

fun assertNativeMcpVersionSmokeOutput(output: String, expectedVersion: String) {
    val trimmedOutput = output.trim()
    val expectedOutput = "hexana-mcp $expectedVersion"
    check(trimmedOutput == expectedOutput) {
        "Expected `$expectedOutput` from native executable, got: $trimmedOutput"
    }
}

tasks.register("verifyNativeMcpVersionSmokeAssertion") {
    description = "Verifies that the native MCP version smoke gate rejects fallback development versions."
    group = "verification"

    val expectedVersion = project.version.toString()
    inputs.property("mcpServerVersion", expectedVersion)

    doLast {
        assertNativeMcpVersionSmokeOutput("hexana-mcp $expectedVersion", expectedVersion)
        val devResult = runCatching {
            assertNativeMcpVersionSmokeOutput("hexana-mcp dev", expectedVersion)
        }
        check(devResult.exceptionOrNull() is IllegalStateException) {
            "nativeMcpVersionSmoke assertion must reject `hexana-mcp dev`."
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("verifyMcpRuntimeClassFileVersions"))
    dependsOn(tasks.named("verifyNativeMcpVersionSmokeAssertion"))
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

tasks.register<Exec>("nativeMcpVersionSmoke") {
    description = "Builds the experimental GraalVM native MCP executable and checks that `hexana-mcp version` reports the exact MCP server version."
    group = "verification"
    dependsOn(tasks.named("nativeCompile"))
    outputs.upToDateWhen { false }

    val stdout = ByteArrayOutputStream()
    standardOutput = stdout
    val expectedVersion = project.version.toString()

    doFirst {
        stdout.reset()
        val executableFile = nativeExecutable.get().asFile
        check(executableFile.canExecute()) {
            "Expected native executable at ${executableFile.absolutePath}. Run :mcp:server:nativeCompile first."
        }
        commandLine(executableFile.absolutePath, "version")
    }

    doLast {
        assertNativeMcpVersionSmokeOutput(stdout.toString(Charsets.UTF_8), expectedVersion)
    }
}

tasks.register<Test>("nativeMcpStdioSmoke") {
    description = "Builds the experimental GraalVM native MCP executable and runs the stdio integration smoke test."
    group = "verification"
    dependsOn(tasks.named("nativeCompile"))
    shouldRunAfter(tasks.named("integrationTest"))

    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    testLogging {
        showStandardStreams = true
        events("failed", "passed", "skipped")
    }
    doFirst {
        val executableFile = nativeExecutable.get().asFile
        check(executableFile.canExecute()) {
            "Expected native executable at ${executableFile.absolutePath}. Run :mcp:server:nativeCompile first."
        }
        systemProperty("hexana.integration.nativeExecutable", executableFile.absolutePath)
    }
    filter {
        includeTestsMatching("*HexanaNativeStdioIntegrationTest")
    }
}

tasks.register<Test>("nativeMcpHttpSmoke") {
    description = "Builds the experimental GraalVM native MCP executable and runs the HTTP integration smoke test."
    group = "verification"
    dependsOn(tasks.named("nativeCompile"))
    shouldRunAfter(tasks.named("nativeMcpStdioSmoke"))

    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    testLogging {
        showStandardStreams = true
        events("failed", "passed", "skipped")
    }
    doFirst {
        val executableFile = nativeExecutable.get().asFile
        check(executableFile.canExecute()) {
            "Expected native executable at ${executableFile.absolutePath}. Run :mcp:server:nativeCompile first."
        }
        systemProperty("hexana.integration.nativeExecutable", executableFile.absolutePath)
    }
    filter {
        includeTestsMatching("*HexanaNativeHttpIntegrationTest")
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir("src/main/gen")
        kotlin.srcDir(generatedBuildInfoDir)
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateHexanaBuildInfo)
    mustRunAfter(tasks.named("generateHexanaToolDispatcher"))
}

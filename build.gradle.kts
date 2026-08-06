import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport

plugins {
    alias(libs.plugins.graalvm.native) apply(false)
    alias(libs.plugins.kotlin.jvm) apply(false)
    alias(libs.plugins.kotlin.multiplatform) apply(false)
    alias(libs.plugins.compose.compiler) apply(false)
    alias(libs.plugins.compose.multiplatform) apply(false)
}

val kotlinNodeVersion = "22.22.0"
val graalVmVersion = libs.versions.graalvm.get()
val requiredGraalVmMajorVersion = graalVmVersion.substringBefore('.').toInt()

repositories {
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://www.jetbrains.com/intellij-repository/snapshots")
}

allprojects {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        // See settings.gradle.kts for the cache-redirector rationale —
        // hitting Maven Central directly from TeamCity agents triggers
        // HTTP 429 (INTERNAL-TICKET / INTERNAL-TICKET).
        maven("https://cache-redirector.jetbrains.com/maven-central")
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
    }
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().yarnLockAutoReplace = true
}

// Kotlin JS/Wasm tooling downloads its own Node distribution, so pin it at the root.
plugins.withType<NodeJsRootPlugin> {
    extensions.configure<NodeJsEnvSpec> {
        version.set(kotlinNodeVersion)
    }
}

plugins.withType<WasmNodeJsRootPlugin> {
    extensions.configure<WasmNodeJsEnvSpec> {
        version.set(kotlinNodeVersion)
    }
}

// Aggregator lists driven by ci-configuration's build configurations.
// The lists travel with the code branch so release branches stay self-contained.
val teamcityCoreTestTaskPaths = listOf(
    ":analytics:allTests",
    ":binaryProvider:allTests",
    ":debugInfo:dwarf:allTests",
    ":disassembly:capstone-wasm:allTests",
    ":disassembly:core:allTests",
    ":disassembly:llvm-objdump:test",
    ":encdec:allTests",
    ":hexanaDocument:allTests",
    ":jvm-jit:allTests",
    ":parsing:arrow:allTests",
    ":parsing:codec:allTests",
    ":parsing:common:allTests",
    ":parsing:dex:allTests",
    ":parsing:elf:allTests",
    ":parsing:flatbuffers:allTests",
    ":parsing:javaBytecode:allTests",
    ":parsing:macho:allTests",
    ":parsing:parquet:allTests",
    ":parsing:pe:allTests",
    ":parsing:protobuf:allTests",
    ":parsing:thrift:allTests",
    ":parsing:zip:allTests",
    ":plugins-shared:allTests",
    ":wasmParser:allTests",
)

val teamcityVscodePluginTestTaskPaths = listOf(
    ":vscode-plugin:extension-lib:allTests",
)

val teamcityIntellijPluginTestTaskPaths = listOf(
    ":idea-plugin:test",
    ":idea-plugin:verifyHexanaToolsetUpToDate",
)

val teamcityServerCliTestTaskPaths = listOf(
    ":mcp:tools-generator:test",
    ":mcp:core:build",
    ":mcp:scripting:test",
    ":mcp:server:test",
    ":mcp:server:verifyMcpRuntimeClassFileVersions",
    ":mcp:server:verifyMcpServerOwnershipBoundary",
    ":mcp:server:verifyNativeMcpVersionSmokeAssertion",
    ":mcp:server:verifyHexanaToolDispatcherUpToDate",
    ":mcp:verifyClaudeDesktopMcpbManifestTemplateUpToDate",
    ":mcp:server:integrationTest",
    ":mcp:vscode-server:test",
)

val teamcityCiOwnedTestTaskPaths =
    teamcityCoreTestTaskPaths +
        teamcityVscodePluginTestTaskPaths +
        teamcityIntellijPluginTestTaskPaths +
        teamcityServerCliTestTaskPaths

// Keep the broad rollout aggregate in its pre-split order while ownership moves to dedicated lanes.
val teamcityCiTestTaskOrder = listOf(
    ":analytics:allTests",
    ":binaryProvider:allTests",
    ":debugInfo:dwarf:allTests",
    ":disassembly:capstone-wasm:allTests",
    ":disassembly:core:allTests",
    ":disassembly:llvm-objdump:test",
    ":encdec:allTests",
    ":hexanaDocument:allTests",
    ":jvm-jit:allTests",
    ":mcp:tools-generator:test",
    ":parsing:arrow:allTests",
    ":parsing:codec:allTests",
    ":parsing:common:allTests",
    ":parsing:dex:allTests",
    ":parsing:elf:allTests",
    ":parsing:flatbuffers:allTests",
    ":parsing:javaBytecode:allTests",
    ":parsing:macho:allTests",
    ":parsing:parquet:allTests",
    ":parsing:pe:allTests",
    ":parsing:protobuf:allTests",
    ":parsing:thrift:allTests",
    ":parsing:zip:allTests",
    ":plugins-shared:allTests",
    ":vscode-plugin:extension-lib:allTests",
    ":wasmParser:allTests",
    ":idea-plugin:test",
    ":mcp:core:build",
    ":mcp:scripting:test",
    ":mcp:server:test",
    ":mcp:server:verifyMcpRuntimeClassFileVersions",
    ":mcp:server:verifyMcpServerOwnershipBoundary",
    ":mcp:server:verifyNativeMcpVersionSmokeAssertion",
    ":mcp:server:verifyHexanaToolDispatcherUpToDate",
    ":idea-plugin:verifyHexanaToolsetUpToDate",
    ":mcp:verifyClaudeDesktopMcpbManifestTemplateUpToDate",
    ":mcp:server:integrationTest",
    ":mcp:vscode-server:test",
)

val teamcityCiTestTaskPaths =
    teamcityCiTestTaskOrder.filter(teamcityCiOwnedTestTaskPaths::contains) +
        teamcityCiOwnedTestTaskPaths.filterNot(teamcityCiTestTaskOrder::contains)

val teamcityCliTestTaskPaths = listOf(
    ":cli:runtime:build",
    ":cli:metadata:build",
    ":cli:core:check",
    ":cli:scripting:check",
    ":cli:product:check",
    ":cli:integration-tests:test",
    ":cli:hexana-cli:check",
    ":cli:hexana-cli:integrationTest",
    ":cli:hexana-cli-script:check",
    ":cli:hexana-cli-script:integrationTest",
    ":mcp:verifyCliR8ReleaseArchives",
)

tasks.register("verifyTeamcityCoreTestsCoverage") {
    group = "verification"
    description = "Verify the TeamCity core aggregate includes all required shared test tasks."
    doLast {
        val requiredTaskPaths = listOf(
            ":analytics:allTests",
            ":binaryProvider:allTests",
            ":debugInfo:dwarf:allTests",
            ":disassembly:capstone-wasm:allTests",
            ":disassembly:core:allTests",
            ":disassembly:llvm-objdump:test",
            ":encdec:allTests",
            ":hexanaDocument:allTests",
            ":jvm-jit:allTests",
            ":parsing:arrow:allTests",
            ":parsing:codec:allTests",
            ":parsing:common:allTests",
            ":parsing:dex:allTests",
            ":parsing:elf:allTests",
            ":parsing:flatbuffers:allTests",
            ":parsing:javaBytecode:allTests",
            ":parsing:macho:allTests",
            ":parsing:parquet:allTests",
            ":parsing:pe:allTests",
            ":parsing:protobuf:allTests",
            ":parsing:thrift:allTests",
            ":parsing:zip:allTests",
            ":plugins-shared:allTests",
            ":wasmParser:allTests",
        )
        val missingTaskPaths = requiredTaskPaths.filterNot(teamcityCoreTestTaskPaths::contains)
        check(missingTaskPaths.isEmpty()) {
            "teamcityCoreTests is missing required core test tasks: ${missingTaskPaths.joinToString()}"
        }
    }
}

tasks.register("verifyTeamcityVscodePluginTestsCoverage") {
    group = "verification"
    description = "Verify the TeamCity VS Code plugin aggregate includes all required test tasks."
    doLast {
        val requiredTaskPaths = listOf(
            ":vscode-plugin:extension-lib:allTests",
        )
        val missingTaskPaths = requiredTaskPaths.filterNot(teamcityVscodePluginTestTaskPaths::contains)
        check(missingTaskPaths.isEmpty()) {
            "teamcityVscodePluginTests is missing required VS Code plugin test tasks: ${missingTaskPaths.joinToString()}"
        }
    }
}

tasks.register("verifyTeamcityIntellijPluginTestsCoverage") {
    group = "verification"
    description = "Verify the TeamCity IntelliJ plugin aggregate includes all required test tasks."
    doLast {
        val requiredTaskPaths = listOf(
            ":idea-plugin:test",
            ":idea-plugin:verifyHexanaToolsetUpToDate",
        )
        val missingTaskPaths = requiredTaskPaths.filterNot(teamcityIntellijPluginTestTaskPaths::contains)
        check(missingTaskPaths.isEmpty()) {
            "teamcityIntellijPluginTests is missing required IntelliJ plugin test tasks: ${missingTaskPaths.joinToString()}"
        }
    }
}

tasks.register("verifyTeamcityServerCliTestsCoverage") {
    group = "verification"
    description = "Verify the TeamCity server and CLI aggregate includes all required MCP server test tasks."
    doLast {
        val requiredTaskPaths = listOf(
            ":mcp:tools-generator:test",
            ":mcp:core:build",
            ":mcp:scripting:test",
            ":mcp:server:test",
            ":mcp:server:verifyMcpRuntimeClassFileVersions",
            ":mcp:server:verifyMcpServerOwnershipBoundary",
            ":mcp:server:verifyNativeMcpVersionSmokeAssertion",
            ":mcp:server:verifyHexanaToolDispatcherUpToDate",
            ":mcp:verifyClaudeDesktopMcpbManifestTemplateUpToDate",
            ":mcp:server:integrationTest",
            ":mcp:vscode-server:test",
        )
        val missingTaskPaths = requiredTaskPaths.filterNot(teamcityServerCliTestTaskPaths::contains)
        check(missingTaskPaths.isEmpty()) {
            "teamcityServerCliTests is missing required MCP server test tasks: ${missingTaskPaths.joinToString()}"
        }
    }
}

tasks.register("verifyTeamcityCiTestsPartition") {
    group = "verification"
    description = "Verify dedicated TeamCity lanes exactly partition the broad CI test aggregate."
    doLast {
        val duplicateTaskPaths =
            teamcityCiOwnedTestTaskPaths
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val missingTaskPaths = teamcityCiTestTaskOrder.filterNot(teamcityCiOwnedTestTaskPaths::contains)
        val unexpectedTaskPaths = teamcityCiOwnedTestTaskPaths.filterNot(teamcityCiTestTaskOrder::contains)
        check(duplicateTaskPaths.isEmpty() && missingTaskPaths.isEmpty() && unexpectedTaskPaths.isEmpty()) {
            "TeamCity test ownership must exactly partition teamcityCiTests; " +
                "duplicates: ${duplicateTaskPaths.joinToString()}, " +
                "missing: ${missingTaskPaths.joinToString()}, " +
                "unexpected: ${unexpectedTaskPaths.joinToString()}"
        }
    }
}

tasks.register("verifyTeamcityCliTestsReleaseGateCoverage") {
    group = "verification"
    description = "Verify the TeamCity CLI aggregate includes all product-owned JVM release gates."
    doLast {
        val requiredTaskPaths = listOf(
            ":cli:runtime:build",
            ":cli:metadata:build",
            ":cli:core:check",
            ":cli:scripting:check",
            ":cli:product:check",
            ":cli:integration-tests:test",
            ":cli:hexana-cli:check",
            ":cli:hexana-cli:integrationTest",
            ":cli:hexana-cli-script:check",
            ":cli:hexana-cli-script:integrationTest",
            ":mcp:verifyCliR8ReleaseArchives",
        )
        val missingTaskPaths = requiredTaskPaths.filterNot(teamcityCliTestTaskPaths::contains)
        check(missingTaskPaths.isEmpty()) {
            "teamcityCliTests is missing required CLI release gate tasks: ${missingTaskPaths.joinToString()}"
        }
    }
}

tasks.register("teamcityCiTests") {
    group = "verification"
    description = "Aggregate general verification surfaces for the TeamCity Tests build."
    dependsOn("verifyTeamcityCiTestsPartition")
    teamcityCiTestTaskPaths.forEach { dependsOn(it) }
}

tasks.register("teamcityCoreTests") {
    group = "verification"
    description = "Aggregate shared core tests for the TeamCity Core Tests build."
    dependsOn("verifyTeamcityCoreTestsCoverage")
    teamcityCoreTestTaskPaths.forEach { dependsOn(it) }
}

tasks.register("teamcityVscodePluginTests") {
    group = "verification"
    description = "Aggregate VS Code plugin tests for the TeamCity VS Code Plugin Tests build."
    dependsOn("verifyTeamcityVscodePluginTestsCoverage")
    teamcityVscodePluginTestTaskPaths.forEach { dependsOn(it) }
}

tasks.register("teamcityIntellijPluginTests") {
    group = "verification"
    description = "Aggregate IntelliJ plugin tests for the TeamCity IntelliJ Plugin Tests build."
    dependsOn("verifyTeamcityIntellijPluginTestsCoverage")
    teamcityIntellijPluginTestTaskPaths.forEach { dependsOn(it) }
}

tasks.register("teamcityCliTests") {
    group = "verification"
    description = "Aggregate CLI-owned JVM release gates for the TeamCity CLI Tests build."
    dependsOn("verifyTeamcityCliTestsReleaseGateCoverage")
    teamcityCliTestTaskPaths.forEach { dependsOn(it) }
}

tasks.register("teamcityServerCliTests") {
    group = "verification"
    description = "Aggregate CLI and MCP server tests for the TeamCity Server and CLI Tests build."
    dependsOn("verifyTeamcityServerCliTestsCoverage")
    dependsOn("teamcityCliTests")
    teamcityServerCliTestTaskPaths.forEach { dependsOn(it) }
}

subprojects {
    plugins.withId("org.graalvm.buildtools.native") {
        val verifyNativeImageVersion = tasks.register("verifyNativeImageVersion") {
            group = "verification"
            description = "Verifies that native-image is GraalVM $requiredGraalVmMajorVersion or newer before native builds."

            doLast {
                val nativeImageExecutableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    "native-image.cmd"
                } else {
                    "native-image"
                }
                val candidates = listOfNotNull(
                    providers.environmentVariable("GRAALVM_HOME").orNull?.let {
                        file(it).resolve("bin").resolve(nativeImageExecutableName).absolutePath
                    },
                    providers.environmentVariable("JAVA_HOME").orNull?.let {
                        file(it).resolve("bin").resolve(nativeImageExecutableName).absolutePath
                    },
                    nativeImageExecutableName,
                )

                var versionOutput = ""
                val command = candidates.firstOrNull { candidate ->
                    try {
                        val process = ProcessBuilder(candidate, "--version")
                            .redirectErrorStream(true)
                            .start()
                        if (!process.waitFor(10, TimeUnit.SECONDS)) {
                            process.destroyForcibly()
                            false
                        } else {
                            versionOutput = process.inputStream.bufferedReader().readText().trim()
                            process.exitValue() == 0
                        }
                    } catch (_: Exception) {
                        false
                    }
                } ?: error(
                    "nativeCompile requires GraalVM $requiredGraalVmMajorVersion native-image, but no usable native-image " +
                        "was found via GRAALVM_HOME, JAVA_HOME, or PATH. The repository JVM baseline remains JDK 21; " +
                        "native packaging requires GraalVM $requiredGraalVmMajorVersion because wasm-language " +
                        "$graalVmVersion uses VectorAPISupport."
                )
                val majorVersion = Regex("""\b(?:GraalVM\s+)?(?:CE\s+)?(\d+)\.""")
                    .find(versionOutput)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()

                check(majorVersion != null && majorVersion >= requiredGraalVmMajorVersion) {
                    "nativeCompile requires GraalVM $requiredGraalVmMajorVersion native-image, but `$command --version` " +
                        "reported:\n$versionOutput\n\nThe repository JVM baseline remains JDK 21; native packaging " +
                        "requires GraalVM $requiredGraalVmMajorVersion because wasm-language $graalVmVersion " +
                        "uses VectorAPISupport."
                }
            }
        }

        tasks.matching { it.name == "nativeCompile" }.configureEach {
            dependsOn(verifyNativeImageVersion)
        }

        extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension>("graalvmNative") {
            binaries.configureEach {
                @Suppress("DEPRECATION")
                requiredVersion.set(requiredGraalVmMajorVersion.toString())
            }
        }
    }

    val ignoreTestFailuresOnTeamCity = providers.gradleProperty("teamcityIgnoreTestFailures")
        .map(String::toBoolean)
        .getOrElse(false)
    val sharedWebKarmaConfigDir = rootProject.layout.projectDirectory.dir("karma.config.d/web").asFile

    // We will enable this for tests on teamcity, because we are already fetching test failure there
    tasks.withType<AbstractTestTask>().configureEach {
        ignoreFailures = ignoreTestFailuresOnTeamCity
    }

    // Browser test tasks are KotlinJsTest, not AbstractTestTask, so TeamCity's
    // ignore-failures flag has to be wired separately for them.
    tasks.withType<KotlinJsTest>().configureEach {
        ignoreFailures = ignoreTestFailuresOnTeamCity
        ignoreRunFailures = ignoreTestFailuresOnTeamCity

        listOf(
            "hx.tests.web.enableChromeOrChromium",
            "hx.tests.web.enableFirefox",
            "hx.tests.web.enableSafari",
        ).filter {
            project.findProperty(it)?.toString() == "1"
        }.forEach { propertyName ->
            environment(propertyName, "1")
        }

        if (!project.projectDir.resolve("karma.config.d/web").isDirectory) {
            useKarma {
                useChrome()
                useConfigDirectory(sharedWebKarmaConfigDir)
            }
        }
    }

    tasks.withType<KotlinTestReport>().configureEach {
        ignoreFailures = ignoreTestFailuresOnTeamCity
        checkFailedTests = !ignoreTestFailuresOnTeamCity

        doFirst {
            if (ignoreTestFailuresOnTeamCity) {
                ignoreFailures = true
                checkFailedTests = false
            }
        }
    }

    plugins.withType<NodeJsPlugin> {
        extensions.configure<NodeJsEnvSpec> {
            version.set(kotlinNodeVersion)
        }
    }

    plugins.withType<WasmNodeJsPlugin> {
        extensions.configure<WasmNodeJsEnvSpec> {
            version.set(kotlinNodeVersion)
        }
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            compilerOptions {
                // expect actual are surprisingly still in beta - https://youtrack.jetbrains.com/issue/KT-61573
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }
}

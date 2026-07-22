import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport
import java.util.concurrent.TimeUnit

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

// Aggregator task driven by ci-configuration's `Tests` build configuration.
// The list travels with the code branch so release branches stay self-contained.
val teamcityCiTestTaskPaths = listOf(
    ":analytics:allTests",
    ":binaryProvider:allTests",
    ":debugInfo:dwarf:allTests",
    ":disassembly:capstone-wasm:allTests",
    ":disassembly:core:allTests",
    ":disassembly:llvm-objdump:test",
    ":encdec:allTests",
    ":hexanaDocument:allTests",
    ":jvm-jit:allTests",
    ":cli:runtime:build",
    ":cli:metadata:build",
    ":cli:core:check",
    ":cli:core:test",
    ":cli:scripting:check",
    ":cli:product:check",
    ":cli:integration-tests:test",
    ":cli:hexana-cli:check",
    ":cli:hexana-cli:integrationTest",
    ":cli:hexana-cli-script:check",
    ":cli:hexana-cli-script:integrationTest",
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
    ":mcp:server:verifyNativeMcpVersionSmokeAssertion",
    ":mcp:server:verifyHexanaToolDispatcherUpToDate",
    ":idea-plugin:verifyHexanaToolsetUpToDate",
    ":mcp:verifyClaudeDesktopMcpbManifestTemplateUpToDate",
    ":mcp:verifyCliR8ReleaseArchives",
    ":mcp:server:integrationTest",
    ":mcp:vscode-server:test",
)

tasks.register("verifyTeamcityCiTestsCliReleaseGateCoverage") {
    group = "verification"
    description = "Verify TeamCity CI aggregate includes the MCP-155 product-owned CLI integration and runner-safety gates."
    doLast {
        val requiredTaskPaths = listOf(
            ":cli:core:check",
            ":cli:scripting:check",
            ":cli:product:check",
            ":cli:integration-tests:test",
            ":cli:hexana-cli:check",
            ":cli:hexana-cli:integrationTest",
            ":cli:hexana-cli-script:check",
            ":cli:hexana-cli-script:integrationTest",
            ":mcp:scripting:test",
            ":mcp:verifyCliR8ReleaseArchives",
        )
        val missingTaskPaths = requiredTaskPaths.filterNot(teamcityCiTestTaskPaths::contains)
        check(missingTaskPaths.isEmpty()) {
            "teamcityCiTests is missing required CLI release gate tasks: ${missingTaskPaths.joinToString()}"
        }
    }
}

tasks.register("teamcityCiTests") {
    group = "verification"
    description = "Aggregate every subproject's test surface for the TeamCity Tests build."
    dependsOn("verifyTeamcityCiTestsCliReleaseGateCoverage")
    teamcityCiTestTaskPaths.forEach { dependsOn(it) }
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

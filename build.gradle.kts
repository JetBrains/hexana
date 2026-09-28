import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.graalvm.native) apply(false)
    alias(libs.plugins.kotlin.jvm) apply(false)
    alias(libs.plugins.kotlin.multiplatform) apply(false)
    alias(libs.plugins.compose.compiler) apply(false)
    alias(libs.plugins.compose.multiplatform) apply(false)
    alias(libs.plugins.detekt) apply(false)
}

val kotlinNodeVersion = "22.22.0"
val graalVmVersion = libs.versions.graalvm.get()
val requiredGraalVmMajorVersion = graalVmVersion.substringBefore('.').toInt()

val corePatronusConfigurationId = "InnoHub_HexFlow_CoreTestsPatronus"
val intellijPatronusConfigurationId = "InnoHub_HexFlow_IntellijPluginTestsPatronus"
val vscodePatronusConfigurationId = "InnoHub_HexFlow_VscodePluginTestsPatronus"
val webappPatronusConfigurationId = "InnoHub_HexFlow_WebappTestsPatronus"
val serverCliPatronusConfigurationId = "InnoHub_HexFlow_ServerCliTestsPatronus"

abstract class ExportPatronusProjectImpactGraph : DefaultTask() {
    @get:Input
    abstract val projectRecords: ListProperty<String>

    @get:Input
    abstract val dependencyRecords: ListProperty<String>

    @get:Input
    abstract val laneSeedRecords: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun export() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("hexana-project-impact-graph\t2")
                projectRecords.get().sorted().forEach { appendLine("project\t$it") }
                dependencyRecords.get().sorted().forEach { appendLine("dependency\t$it") }
                laneSeedRecords.get().sorted().forEach { appendLine("lane-seed\t$it") }
            },
        )
    }
}

val exportPatronusProjectImpactGraph = tasks.register<ExportPatronusProjectImpactGraph>("exportPatronusProjectImpactGraph") {
    group = "verification"
    description = "Export declared Gradle project dependencies and TeamCity lane seeds for Patronus routing."
    outputFile.set(layout.buildDirectory.file("patronus/project-impact-graph.tsv"))
}

gradle.projectsEvaluated {
    val gradleProjectPath = Regex(""":|(?::[A-Za-z0-9][A-Za-z0-9._-]*)+""")
    val gradleConfigurationName = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
    val repositoryRoot = rootProject.projectDir.canonicalFile.toPath()

    fun Project.validatedPath(): String = path.also {
        check(gradleProjectPath.matches(it)) {
            "Project path must be ':' or colon-separated ASCII Gradle name segments: $it"
        }
    }

    fun Project.relativeDirectory(): String {
        val canonicalProjectDirectory = projectDir.canonicalFile.toPath()
        check(canonicalProjectDirectory.startsWith(repositoryRoot)) {
            "$path project directory must be inside repository root $repositoryRoot, but was $canonicalProjectDirectory"
        }
        if (canonicalProjectDirectory == repositoryRoot) return "."
        val relativeDirectory = repositoryRoot.relativize(canonicalProjectDirectory).joinToString("/")
        check(
            relativeDirectory.isNotEmpty() && relativeDirectory.split('/').all {
                it.isNotEmpty() && it != "." && it != ".." && it.none { character ->
                    character == '\t' || character == '\r' || character == '\n' || character.code < 0x20 || character == '\u007f'
                }
            },
        ) { "$path project directory is not a canonical repository-relative path: $relativeDirectory" }
        return relativeDirectory
    }

    val projectRecords = allprojects.map { "${it.validatedPath()}\t${it.relativeDirectory()}" }.sorted()
    val dependencyRecords = allprojects.flatMap { consumer ->
        consumer.configurations.flatMap { configuration ->
            check(gradleConfigurationName.matches(configuration.name)) {
                "Configuration name must be non-empty ASCII without separators or control characters: ${configuration.name}"
            }
            configuration.dependencies.withType(ProjectDependency::class.java).map { dependency ->
                val provider = project(dependency.path)
                "${consumer.validatedPath()}\t${consumer.relativeDirectory()}\t" +
                    "${provider.validatedPath()}\t${provider.relativeDirectory()}\t${configuration.name}"
            }
        }
    }.distinct().sorted()
    exportPatronusProjectImpactGraph.configure {
        this.projectRecords.set(projectRecords)
        this.dependencyRecords.set(dependencyRecords)
    }
}

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

// Advisory visual baseline: keep this deliberately limited to the measured CLI/MCP projects.
val detektCliMcpProjectPaths = listOf(
    ":cli:runtime",
    ":cli:metadata",
    ":cli:core",
    ":cli:scripting",
    ":cli:product",
    ":cli:integration-tests",
    ":cli:hexana-cli",
    ":cli:hexana-cli-script",
    ":mcp:core",
    ":mcp:scripting",
    ":mcp:tools-generator",
    ":mcp:server",
    ":mcp:vscode-server",
)
val detektCliMcpTaskPaths = detektCliMcpProjectPaths.map { "$it:detekt" }

detektCliMcpProjectPaths.forEach { projectPath ->
    project(projectPath) {
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            pluginManager.apply("io.gitlab.arturbosch.detekt")

            extensions.configure<DetektExtension> {
                // Match Detekt's default JVM scope explicitly: production and unit-test Kotlin sources only.
                source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
            }

            tasks.withType<Detekt>().configureEach {
                ignoreFailures = true
                reports.xml.required.set(true)
                reports.xml.outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt-checkstyle.xml"))
                reports.html.required.set(true)
                reports.html.outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt.html"))
                reports.sarif.required.set(false)
                reports.md.required.set(false)
            }
        }
    }
}

tasks.register("detektCliMcp") {
    group = "verification"
    description = "Run advisory Detekt on main and test sources for the 13 selected CLI/MCP projects."
    detektCliMcpTaskPaths.forEach(::dependsOn)
}

tasks.register("generateHexanaToolArtifacts") {
    group = "generation"
    description = "Generate each independently owned Hexana tool artifact and the build-only resolved catalog report without merging outputs."
    dependsOn(
        ":idea-plugin:generateHexanaToolset",
        ":mcp:server:generateHexanaToolDispatcher",
        ":cli:metadata:generateHexanaCliMetadata",
        ":mcp:generateClaudeDesktopMcpbManifestTemplate",
        ":mcp:tools-generator:generateHexanaToolCatalogReport",
    )
}

tasks.register("verifyHexanaToolArtifactsUpToDate") {
    group = "verification"
    description = "Verify independent generated tool artifacts are current, then refresh the non-versioned resolved catalog report for review."
    dependsOn(
        ":idea-plugin:verifyHexanaToolsetUpToDate",
        ":mcp:server:verifyHexanaToolDispatcherUpToDate",
        ":cli:metadata:verifyHexanaCliMetadataUpToDate",
        ":mcp:verifyClaudeDesktopMcpbManifestTemplateUpToDate",
        ":mcp:tools-generator:generateHexanaToolCatalogReport",
    )
}

val verifyCliReleaseValidatorTests = tasks.register<Exec>("verifyCliReleaseValidatorTests") {
    group = "verification"
    description = "Run CLI release descriptor and archive validator tests."
    dependsOn(":cli:metadata:verifyHexanaCliMetadataUpToDate")
    workingDir = rootProject.projectDir
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-m", "unittest", "discover", "-s", "cli/packaging", "-p", "test_*.py")
}

val verifyCliDownloadsPublisherSelfTest = tasks.register<Exec>("verifyCliDownloadsPublisherSelfTest") {
    group = "verification"
    description = "Run the CLI downloads publisher against fake local storage."
    dependsOn(":cli:metadata:verifyHexanaCliMetadataUpToDate")
    workingDir = rootProject.projectDir
    environment("PYTHONDONTWRITEBYTECODE", "1")
    environment("CLI_DOWNLOADS_BUNDLE_SELF_TEST", "true")
    commandLine("bash", "cli/packaging/publish-cli-downloads-release-bundle.sh")
}

tasks.register("verifyCliReleasePackaging") {
    group = "verification"
    description = "Verify CLI release artifact validation and downloads publication."
    dependsOn(verifyCliReleaseValidatorTests, verifyCliDownloadsPublisherSelfTest)
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
    ":parsing:arsc:allTests",
    ":parsing:codec:allTests",
    ":parsing:common:allTests",
    ":parsing:dex:allTests",
    ":parsing:elf:allTests",
    ":parsing:flatbuffers:allTests",
    ":parsing:javaBytecode:allTests",
    ":parsing:macho:allTests",
    ":parsing:parquet:allTests",
    ":parsing:pcap:allTests",
    ":parsing:pe:allTests",
    ":parsing:protobuf:allTests",
    ":parsing:thrift:allTests",
    ":parsing:tar:allTests",
    ":parsing:zip:allTests",
    ":plugins-shared:allTests",
    ":plugins-shared:verifyBundledWasmFeaturesUpToDate",
    ":wasmParser:allTests",
    ":teamcityFallbackCoreTests",
)

val teamcityVscodePluginTestTaskPaths = listOf(
    ":vscode-plugin:extension-lib:allTests",
    ":vscode-plugin:webview:allTests",
)

val teamcityWebappTestTaskPaths = listOf(
    ":webapp:testWebappDeploymentBridge",
    ":webapp:testWebappLiveSmoke",
    ":webapp:testWebappOciLocalhostBrowserSmoke",
    ":webapp:testWebappVulnerabilityReportContract",
    ":webapp:compileProductionExecutableKotlinJs",
    ":webapp:jsBrowserProductionWebpack",
    ":webapp:verifyPublicWebappCapabilityPolicy",
    ":webapp:jsBrowserDistribution",
    ":webapp:verifyProductionPackage",
    ":webapp:verifyCloudConsoleDeploymentContract",
    ":webapp:productionPackageChromeSmoke",
    ":webapp:buildComponent",
    ":webapp:jsBrowserTest",
    ":web-ui:jsBrowserTest",
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
    ":mcp:server:obfuscatedMcpPackagingSmoke",
    ":mcp:verifyMcpR8ReleaseArchives",
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
    ":parsing:arsc:allTests",
    ":parsing:codec:allTests",
    ":parsing:common:allTests",
    ":parsing:dex:allTests",
    ":parsing:elf:allTests",
    ":parsing:flatbuffers:allTests",
    ":parsing:javaBytecode:allTests",
    ":parsing:macho:allTests",
    ":parsing:parquet:allTests",
    ":parsing:pcap:allTests",
    ":parsing:pe:allTests",
    ":parsing:protobuf:allTests",
    ":parsing:thrift:allTests",
    ":parsing:tar:allTests",
    ":parsing:zip:allTests",
    ":plugins-shared:allTests",
    ":plugins-shared:verifyBundledWasmFeaturesUpToDate",
    ":vscode-plugin:extension-lib:allTests",
    ":vscode-plugin:webview:allTests",
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
    ":mcp:server:obfuscatedMcpPackagingSmoke",
    ":mcp:verifyMcpR8ReleaseArchives",
    ":mcp:vscode-server:test",
    ":teamcityFallbackCoreTests",
)

val teamcityCiTestTaskPaths =
    teamcityCiTestTaskOrder.filter(teamcityCiOwnedTestTaskPaths::contains) +
        teamcityCiOwnedTestTaskPaths.filterNot(teamcityCiTestTaskOrder::contains)

val teamcityCliTestTaskPaths = listOf(
    ":verifyCliReleasePackaging",
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

fun taskOwningProjectPath(taskPath: String): String =
    taskPath.substringBeforeLast(':').ifEmpty { ":" }

val patronusLaneTaskPaths = linkedMapOf(
    corePatronusConfigurationId to teamcityCoreTestTaskPaths,
    intellijPatronusConfigurationId to teamcityIntellijPluginTestTaskPaths,
    vscodePatronusConfigurationId to teamcityVscodePluginTestTaskPaths,
    webappPatronusConfigurationId to teamcityWebappTestTaskPaths,
    serverCliPatronusConfigurationId to teamcityServerCliTestTaskPaths + teamcityCliTestTaskPaths,
)
val patronusImmediateNonUiTaskPaths = patronusLaneTaskPaths.values.flatten()
val patronusImmediateUiExcludedTaskPaths = setOf(":ui-driver-tests:teamcityUiDriverTests")
val patronusScheduledOnlyBuildTaskPaths = setOf(":clean", ":assemble")
val patronusLaneSeedProjectPaths = patronusLaneTaskPaths.mapValues { (_, taskPaths) ->
    taskPaths.map(::taskOwningProjectPath).filterNot { it == ":" }.distinct().sorted()
}
val patronusLaneOwnedProjectPaths = patronusLaneSeedProjectPaths.values.flatten().toSet()
// Dependency-aware selection must not silently change the existing Core aggregate's task membership.
val fallbackCoreExcludedProjectPaths = setOf(
    ":graalwasm", ":graalwasm:common", ":graalwasm:wasmprinter", ":graalwasm:wat2wasm",
    ":jvm-jit-jmh", ":patronus-config", ":ui-driver-tests",
)

fun Project.isUnclassifiedNonExemptProject(): Boolean =
    path != ":" && path !in patronusLaneOwnedProjectPaths && path !in fallbackCoreExcludedProjectPaths

exportPatronusProjectImpactGraph.configure {
    laneSeedRecords.set(
        patronusLaneSeedProjectPaths.flatMap { (configurationId, projectPaths) ->
            projectPaths.map { "$configurationId\t$it" }
        },
    )
}

val teamcityFallbackCoreJvmTestTaskPaths = linkedSetOf<String>()

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        if (!isUnclassifiedNonExemptProject()) return@withId
        val standardTest = tasks.named<Test>("test")
        val standardTestPath = "$path:test"
        check(teamcityFallbackCoreJvmTestTaskPaths.add(standardTestPath)) {
            "teamcityFallbackCoreTests discovered duplicate Kotlin/JVM test task $standardTestPath"
        }
        rootProject.tasks.named("teamcityFallbackCoreTests").configure {
            dependsOn(standardTest)
        }
    }
}

fun verifyExactTaskPaths(
    subject: String,
    requiredTaskPaths: List<String>,
    actualTaskPaths: List<String>,
) {
    val missingTaskPaths = requiredTaskPaths.filterNot(actualTaskPaths::contains)
    val unexpectedTaskPaths = actualTaskPaths.filterNot(requiredTaskPaths::contains)
    val duplicateTaskPaths =
        actualTaskPaths
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    check(missingTaskPaths.isEmpty() && unexpectedTaskPaths.isEmpty() && duplicateTaskPaths.isEmpty()) {
        "$subject must exactly match required task paths; " +
            "missing: ${missingTaskPaths.joinToString()}, " +
            "unexpected: ${unexpectedTaskPaths.joinToString()}, " +
            "duplicates: ${duplicateTaskPaths.joinToString()}"
    }
}

fun verifyAggregateDirectDependencies(
    aggregateTaskName: String,
    requiredTaskPaths: List<String>,
) {
    val aggregateTask = tasks.getByName(aggregateTaskName)

    fun normalizeDependency(dependency: Any?): List<String> =
        when (dependency) {
            is String -> listOf(if (dependency.startsWith(":")) dependency else ":$dependency")
            is Task -> listOf(dependency.path)
            is TaskProvider<*> -> listOf(dependency.get().path)
            is Provider<*> -> normalizeDependency(dependency.orNull)
            is Iterable<*> -> dependency.flatMap(::normalizeDependency)
            is Array<*> -> dependency.flatMap(::normalizeDependency)
            else -> error("$aggregateTaskName has unsupported direct dependency notation: $dependency")
        }

    val actualTaskPaths =
        aggregateTask.dependsOn.flatMap(::normalizeDependency)
    verifyExactTaskPaths("$aggregateTaskName direct dependencies", requiredTaskPaths, actualTaskPaths)
}

tasks.register("verifyPatronusRoutingCoverage") {
    group = "verification"
    description = "Verify generated dependency-aware Patronus routing."
    dependsOn(
        ":patronus-config:test",
        ":patronus-config:verifyPatronusProjectImpactGraph",
        "verifyPatronusConfigUpToDate",
        "verifyPatronusImmediateNonUiLanePartition",
    )
}
tasks.register("updatePatronusConfig") {
    group = "build setup"
    description = "Regenerate .patronus/config.yaml from the Patronus Kotlin DSL."
    dependsOn(":patronus-config:updatePatronusConfig")
}

tasks.register("verifyPatronusConfigUpToDate") {
    group = "verification"
    description = "Verify that .patronus/config.yaml matches the Patronus Kotlin DSL."
    dependsOn(":patronus-config:verifyPatronusConfigUpToDate")
}

tasks.register("verifyTeamcityFallbackCoreTestsCoverage") {
    group = "verification"
    description = "Verify conservative Core fallback coverage for unclassified Kotlin/JVM projects."
    doLast {
        val fallbackTaskPaths = teamcityFallbackCoreJvmTestTaskPaths.toList()
        fallbackTaskPaths.forEach { taskPath ->
            val task = tasks.getByPath(taskPath)
            check(task is Test && task.name == "test") {
                "teamcityFallbackCoreTests may include only standard Kotlin/JVM :test tasks, but found $taskPath"
            }
            check(task.project.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
                "teamcityFallbackCoreTests task $taskPath must belong to a Kotlin/JVM project"
            }
            check(task.project.isUnclassifiedNonExemptProject()) {
                "teamcityFallbackCoreTests task $taskPath must belong to an unclassified non-exempt project"
            }
        }
        val multiplatformDebt = rootProject.allprojects
            .filterNot { it == rootProject }
            .filter(Project::isUnclassifiedNonExemptProject)
            .filter { it.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") }
            .map { "${it.path}:allTests" }
            .sorted()
        check(multiplatformDebt.none(fallbackTaskPaths::contains)) {
            "Kotlin Multiplatform fallback tests are not proven safe for the Core Docker lane: $multiplatformDebt"
        }
        logger.lifecycle(
            "teamcityFallbackCoreTests Kotlin Multiplatform debt intentionally excluded from Core until proven safe: " +
                multiplatformDebt.joinToString().ifEmpty { "none" },
        )
        verifyAggregateDirectDependencies(
            "teamcityFallbackCoreTests",
            fallbackTaskPaths + ":verifyTeamcityFallbackCoreTestsCoverage",
        )
    }
}

tasks.register("teamcityFallbackCoreTests") {
    group = "verification"
    description = "Run standard Kotlin/JVM tests for unclassified non-exempt projects through the Core lane."
    dependsOn("verifyTeamcityFallbackCoreTestsCoverage")
}

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
            ":parsing:arsc:allTests",
            ":parsing:codec:allTests",
            ":parsing:common:allTests",
            ":parsing:dex:allTests",
            ":parsing:elf:allTests",
            ":parsing:flatbuffers:allTests",
            ":parsing:javaBytecode:allTests",
            ":parsing:macho:allTests",
            ":parsing:parquet:allTests",
            ":parsing:pcap:allTests",
            ":parsing:pe:allTests",
            ":parsing:protobuf:allTests",
            ":parsing:tar:allTests",
            ":parsing:thrift:allTests",
            ":parsing:zip:allTests",
            ":plugins-shared:allTests",
            ":plugins-shared:verifyBundledWasmFeaturesUpToDate",
            ":wasmParser:allTests",
            ":teamcityFallbackCoreTests",
        )
        verifyExactTaskPaths("teamcityCoreTests inventory", requiredTaskPaths, teamcityCoreTestTaskPaths)
        verifyAggregateDirectDependencies(
            "teamcityCoreTests",
            teamcityCoreTestTaskPaths + ":verifyTeamcityCoreTestsCoverage",
        )
    }
}

tasks.register("verifyTeamcityVscodePluginTestsCoverage") {
    group = "verification"
    description = "Verify the TeamCity VS Code plugin aggregate includes all required test tasks."
    doLast {
        val requiredTaskPaths = listOf(
            ":vscode-plugin:extension-lib:allTests",
            ":vscode-plugin:webview:allTests",
        )
        verifyExactTaskPaths(
            "teamcityVscodePluginTests inventory",
            requiredTaskPaths,
            teamcityVscodePluginTestTaskPaths,
        )
        verifyAggregateDirectDependencies(
            "teamcityVscodePluginTests",
            teamcityVscodePluginTestTaskPaths + ":verifyTeamcityVscodePluginTestsCoverage",
        )
    }
}

tasks.register("verifyTeamcityWebappTestsCoverage") {
    group = "verification"
    description = "Verify the TeamCity webapp aggregate includes production packaging and Chrome browser tests."
    doLast {
        val requiredTaskPaths = listOf(
            ":webapp:testWebappDeploymentBridge",
            ":webapp:testWebappLiveSmoke",
            ":webapp:testWebappOciLocalhostBrowserSmoke",
            ":webapp:testWebappVulnerabilityReportContract",
            ":webapp:compileProductionExecutableKotlinJs",
            ":webapp:jsBrowserProductionWebpack",
            ":webapp:verifyPublicWebappCapabilityPolicy",
            ":webapp:jsBrowserDistribution",
            ":webapp:verifyProductionPackage",
            ":webapp:verifyCloudConsoleDeploymentContract",
            ":webapp:productionPackageChromeSmoke",
            ":webapp:buildComponent",
            ":webapp:jsBrowserTest",
            ":web-ui:jsBrowserTest",
        )
        verifyExactTaskPaths("teamcityWebappTests inventory", requiredTaskPaths, teamcityWebappTestTaskPaths)
        verifyAggregateDirectDependencies(
            "teamcityWebappTests",
            teamcityWebappTestTaskPaths + ":verifyTeamcityWebappTestsCoverage",
        )
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
        verifyExactTaskPaths(
            "teamcityIntellijPluginTests inventory",
            requiredTaskPaths,
            teamcityIntellijPluginTestTaskPaths,
        )
        verifyAggregateDirectDependencies(
            "teamcityIntellijPluginTests",
            teamcityIntellijPluginTestTaskPaths + ":verifyTeamcityIntellijPluginTestsCoverage",
        )
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
            ":mcp:server:obfuscatedMcpPackagingSmoke",
            ":mcp:verifyMcpR8ReleaseArchives",
            ":mcp:vscode-server:test",
        )
        verifyExactTaskPaths(
            "teamcityServerCliTests inventory",
            requiredTaskPaths,
            teamcityServerCliTestTaskPaths,
        )
        verifyAggregateDirectDependencies(
            "teamcityServerCliTests",
            teamcityServerCliTestTaskPaths +
                listOf(":verifyTeamcityServerCliTestsCoverage", ":teamcityCliTests"),
        )
    }
}

tasks.register("verifyTeamcityCiTestsPartition") {
    group = "verification"
    description = "Verify dedicated TeamCity lanes exactly partition the broad CI test aggregate."
    val requiredLaneGuardTaskPaths = listOf(
        ":verifyTeamcityCoreTestsCoverage",
        ":verifyTeamcityVscodePluginTestsCoverage",
        ":verifyTeamcityIntellijPluginTestsCoverage",
        ":verifyTeamcityServerCliTestsCoverage",
    )
    requiredLaneGuardTaskPaths.forEach { dependsOn(it) }
    doLast {
        verifyExactTaskPaths("TeamCity test ownership", teamcityCiTestTaskOrder, teamcityCiOwnedTestTaskPaths)
        verifyAggregateDirectDependencies("verifyTeamcityCiTestsPartition", requiredLaneGuardTaskPaths)
        verifyAggregateDirectDependencies(
            "teamcityCiTests",
            teamcityCiTestTaskPaths + listOf(":verifyTeamcityCiTestsPartition", ":verifyPatronusRoutingCoverage"),
        )
    }
}

tasks.register("verifyPatronusImmediateNonUiLanePartition") {
    group = "verification"
    description = "Verify immediate non-UI Patronus lanes partition former Gradle tests without UI or scheduled-only work."
    val laneCoverageTasks = listOf(
        ":verifyTeamcityCoreTestsCoverage",
        ":verifyTeamcityVscodePluginTestsCoverage",
        ":verifyTeamcityIntellijPluginTestsCoverage",
        ":verifyTeamcityWebappTestsCoverage",
        ":verifyTeamcityServerCliTestsCoverage",
    )
    laneCoverageTasks.forEach { dependsOn(it) }
    doLast {
        val formerSafeMergeGradleTaskPaths =
            teamcityCiTestTaskPaths + teamcityCliTestTaskPaths + teamcityWebappTestTaskPaths

        verifyExactTaskPaths(
            "Former Safe Merge Gradle test inventory",
            formerSafeMergeGradleTaskPaths,
            patronusImmediateNonUiTaskPaths,
        )
        check(teamcityWebappTestTaskPaths.all(patronusImmediateNonUiTaskPaths::contains)) {
            "Every Webapp task must remain in the immediate Webapp Patronus lane"
        }
        val duplicateImmediateTaskPaths = patronusImmediateNonUiTaskPaths
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        check(duplicateImmediateTaskPaths.isEmpty()) {
            "Immediate non-UI Patronus lanes must not duplicate tasks: ${duplicateImmediateTaskPaths.joinToString()}"
        }
        check(patronusImmediateNonUiTaskPaths.none(patronusImmediateUiExcludedTaskPaths::contains)) {
            "UI driver work remains the UiTestsPatronus exception, not an immediate non-UI lane"
        }
        check(patronusImmediateNonUiTaskPaths.none(patronusScheduledOnlyBuildTaskPaths::contains)) {
            "Build Everything remains scheduled-only, not an immediate Patronus lane"
        }
        logger.lifecycle(
            "VS Code TypeScript/Mocha is intentionally outside this Gradle partition; its immediate ownership is the " +
                "separate TeamCity VS Code Patronus composite.",
        )
    }
}

tasks.register("verifyTeamcityCliTestsReleaseGateCoverage") {
    group = "verification"
    description = "Verify the TeamCity CLI aggregate includes all product-owned JVM release gates."
    doLast {
        val requiredTaskPaths = listOf(
            ":verifyCliReleasePackaging",
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
        verifyExactTaskPaths("teamcityCliTests inventory", requiredTaskPaths, teamcityCliTestTaskPaths)
        verifyAggregateDirectDependencies(
            "teamcityCliTests",
            teamcityCliTestTaskPaths + ":verifyTeamcityCliTestsReleaseGateCoverage",
        )
    }
}

tasks.register("teamcityCiTests") {
    group = "verification"
    description = "Aggregate general verification surfaces for the TeamCity Tests build."
    dependsOn("verifyTeamcityCiTestsPartition")
    dependsOn("verifyPatronusRoutingCoverage")
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

tasks.register("teamcityWebappTests") {
    group = "verification"
    description = "Aggregate production webapp packaging and Chrome-backed browser tests for TeamCity."
    dependsOn("verifyTeamcityWebappTestsCoverage")
    teamcityWebappTestTaskPaths.forEach { dependsOn(it) }
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

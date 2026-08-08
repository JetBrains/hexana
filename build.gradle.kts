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
    ":mcp:server:obfuscatedMcpPackagingSmoke",
    ":mcp:verifyMcpR8ReleaseArchives",
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

data class PatronusCheck(
    val configurationId: String,
    val groupId: String?,
    val isFallback: Boolean,
    val executeOnChanges: List<String>,
)

fun readPatronusChecks(configFile: File): List<PatronusCheck> {
    val lines = configFile.readLines()
    check(lines.take(3) == listOf("version: \"1.0\"", "", "checks:")) {
        "${configFile.path} must start with the supported Patronus version and checks header"
    }

    val configurationPattern = Regex("""  - configurationId: \"([^\"]+)\"""")
    val groupIdPattern = Regex("""      id: \"([^\"]+)\"""")
    val fallbackPattern = Regex("""      isFallback: (true|false)""")
    val changePattern = Regex("""      - \"([^\"]+)\"""")
    val checks = mutableListOf<PatronusCheck>()
    var lineIndex = 3
    while (lineIndex < lines.size) {
        if (lines[lineIndex].isBlank()) {
            lineIndex++
            continue
        }
        val configurationId = configurationPattern.matchEntire(lines[lineIndex])?.groupValues?.get(1)
            ?: error("Unsupported Patronus check syntax at ${configFile.path}:${lineIndex + 1}: ${lines[lineIndex]}")
        lineIndex++
        var groupSeen = false
        var groupId: String? = null
        var isFallback = false
        var fallbackSeen = false
        var changesSeen = false
        val executeOnChanges = mutableListOf<String>()
        while (lineIndex < lines.size && configurationPattern.matchEntire(lines[lineIndex]) == null) {
            val line = lines[lineIndex]
            when {
                line.isBlank() -> Unit
                line == "    group:" -> {
                    check(!groupSeen) { "$configurationId declares group more than once" }
                    check(!changesSeen) { "$configurationId declares group after executeOnChanges" }
                    groupSeen = true
                }
                groupIdPattern.matches(line) -> {
                    check(groupSeen && !changesSeen) { "$configurationId group id must be nested under group" }
                    check(groupId == null) { "$configurationId declares groupId more than once" }
                    groupId = groupIdPattern.matchEntire(line)!!.groupValues[1]
                }
                fallbackPattern.matches(line) -> {
                    check(groupSeen && !changesSeen) { "$configurationId isFallback must be nested under group" }
                    check(!fallbackSeen) { "$configurationId declares isFallback more than once" }
                    fallbackSeen = true
                    isFallback = fallbackPattern.matchEntire(line)!!.groupValues[1].toBoolean()
                }
                line == "    executeOnChanges:" -> {
                    check(!changesSeen) { "$configurationId declares executeOnChanges more than once" }
                    check(!groupSeen || groupId != null) { "$configurationId group must declare an id" }
                    changesSeen = true
                }
                changePattern.matches(line) -> {
                    check(changesSeen) { "$configurationId has a change path before executeOnChanges" }
                    executeOnChanges += changePattern.matchEntire(line)!!.groupValues[1]
                }
                else -> error("Unsupported Patronus check syntax at ${configFile.path}:${lineIndex + 1}: $line")
            }
            lineIndex++
        }
        check(changesSeen && executeOnChanges.isNotEmpty()) {
            "$configurationId must declare at least one executeOnChanges path"
        }
        checks += PatronusCheck(configurationId, groupId, isFallback, executeOnChanges)
    }
    return checks
}

tasks.register("verifyPatronusRoutingCoverage") {
    group = "verification"
    description = "Verify Patronus check policy and exhaustive product-project routing."
    doLast {
        val safeMergeId = "InnoHub_HexFlow_SafeMergeTests"
        val uiId = "InnoHub_HexFlow_UiTestsPatronus"
        val intellijId = "InnoHub_HexFlow_IntellijPluginTestsPatronus"
        val coreId = "InnoHub_HexFlow_CoreTestsPatronus"
        val vscodeId = "InnoHub_HexFlow_VscodePluginTestsPatronus"
        val serverCliId = "InnoHub_HexFlow_ServerCliTestsPatronus"
        val productGroupId = "hexana-product-tests"
        val expectedChecks = linkedMapOf(
            safeMergeId to PatronusCheck(safeMergeId, null, false, listOf("*", "!/README.md")),
            uiId to PatronusCheck(
                uiId,
                null,
                false,
                listOf("/ui-driver-tests/**", "/idea-plugin/src/ui-tests-framework/**"),
            ),
            intellijId to PatronusCheck(intellijId, productGroupId, false, listOf("/idea-plugin/**")),
            coreId to PatronusCheck(
                coreId,
                productGroupId,
                true,
                listOf(
                    "/build.gradle.kts", "/settings.gradle.kts", "/gradle.properties", "/gradle/**", "/gradlew",
                    "/gradlew.bat", "/karma.config.d/**", "/analytics/**", "/binaryProvider/**", "/debugInfo/**",
                    "/disassembly/**", "/encdec/**", "/hexanaDocument/**", "/jvm-jit/**", "/parsing/**",
                    "/plugins-shared/**", "/wasmParser/**", "/graalwasm/**", "/wasm-modules/**",
                    "/kotlin-js-store/**", "/mcp/core/**", "/mcp/scripting/**", "/mcp/tools-generator/**",
                ),
            ),
            vscodeId to PatronusCheck(vscodeId, productGroupId, false, listOf("/vscode-plugin/**", "/web-ui/**")),
            serverCliId to PatronusCheck(
                serverCliId,
                productGroupId,
                false,
                listOf(
                    "/cli/**", "/mcp/**", "!/mcp/core/**", "!/mcp/scripting/**", "!/mcp/tools-generator/**",
                ),
            ),
        )
        val checks = readPatronusChecks(rootProject.file(".patronus/config.yaml"))
        val expectedCheckIds = expectedChecks.keys.toList()
        val actualCheckIds = checks.map { it.configurationId }
        check(actualCheckIds == expectedCheckIds) {
            "Patronus check order must be exact; expected: $expectedCheckIds, actual: $actualCheckIds"
        }
        val checksById = checks.associateBy { it.configurationId }
        expectedChecks.forEach { (configurationId, expected) ->
            val actual = checksById.getValue(configurationId)
            check(actual.groupId == expected.groupId) {
                "$configurationId groupId must be ${expected.groupId ?: "unset"}, but was ${actual.groupId ?: "unset"}"
            }
            check(actual.isFallback == expected.isFallback) {
                "$configurationId isFallback must be ${expected.isFallback}, but was ${actual.isFallback}"
            }
            check(actual.executeOnChanges == expected.executeOnChanges) {
                "$configurationId executeOnChanges order must be exact; " +
                    "expected: ${expected.executeOnChanges}, actual: ${actual.executeOnChanges}"
            }
        }
        check(checks.filter(PatronusCheck::isFallback).map { it.configurationId } == listOf(coreId)) {
            "$coreId must be the sole Patronus fallback; found: " +
                checks.filter(PatronusCheck::isFallback).joinToString { it.configurationId }
        }

        val primaryLaneProjectPaths = linkedMapOf(
            coreId to listOf(
                ":analytics", ":binaryProvider", ":debugInfo", ":debugInfo:dwarf", ":disassembly",
                ":disassembly:capstone-wasm", ":disassembly:core", ":disassembly:llvm-objdump", ":encdec",
                ":graalwasm", ":graalwasm:common", ":graalwasm:wasmprinter", ":graalwasm:wat2wasm",
                ":hexanaDocument", ":jvm-jit", ":mcp:core", ":mcp:scripting", ":mcp:tools-generator", ":parsing",
                ":parsing:arrow", ":parsing:codec", ":parsing:common", ":parsing:dex", ":parsing:elf",
                ":parsing:flatbuffers", ":parsing:javaBytecode", ":parsing:macho", ":parsing:parquet",
                ":parsing:pcap", ":parsing:pe", ":parsing:protobuf", ":parsing:thrift", ":parsing:zip",
                ":plugins-shared", ":wasmParser",
            ),
            intellijId to listOf(":idea-plugin"),
            vscodeId to listOf(":vscode-plugin", ":vscode-plugin:extension-lib", ":vscode-plugin:webview", ":web-ui"),
            serverCliId to listOf(
                ":cli", ":cli:core", ":cli:hexana-cli", ":cli:hexana-cli-script", ":cli:integration-tests",
                ":cli:metadata", ":cli:product", ":cli:runtime", ":cli:scripting", ":mcp", ":mcp:server",
                ":mcp:vscode-server",
            ),
        )
        val exemptProjectReasons = mapOf(
            ":ui-driver-tests" to "UI/daily lane",
            ":jvm-jit-jmh" to "benchmark/Safe-Merge-only",
            ":webapp" to "browser shell without tests/Safe-Merge-only",
        )
        val actualProjects = rootProject.allprojects.filterNot { it == rootProject }.associateBy { it.path }
        val classifications =
            primaryLaneProjectPaths.flatMap { (lane, paths) -> paths.map { it to lane } } +
                exemptProjectReasons.map { (path, reason) -> path to "exempt: $reason" }
        val duplicateClassifications = classifications.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
        val classifiedPaths = classifications.map { it.first }.toSet()
        val missingClassifications = actualProjects.keys - classifiedPaths
        val staleClassifications = classifiedPaths - actualProjects.keys
        check(duplicateClassifications.isEmpty() && missingClassifications.isEmpty() && staleClassifications.isEmpty()) {
            "Patronus Gradle project inventory must classify every project exactly once; " +
                "missing classifications: ${missingClassifications.sorted().joinToString()}, " +
                "duplicates: ${duplicateClassifications.sorted().joinToString()}, " +
                "stale classifications: ${staleClassifications.sorted().joinToString()}"
        }

        fun selectedPrimaryLanes(changePath: String): List<String> =
            primaryLaneProjectPaths.keys.filter { laneId ->
                val patterns = checksById.getValue(laneId).executeOnChanges
                fun matches(pattern: String): Boolean =
                    if (pattern.endsWith("/**")) changePath.startsWith(pattern.removeSuffix("**"))
                    else changePath == pattern
                patterns.fold(false) { selected, pattern ->
                    val excluded = pattern.startsWith("!")
                    if (matches(pattern.removePrefix("!"))) !excluded else selected
                }
            }

        actualProjects.values.forEach { project ->
            val relativeDirectory = project.projectDir.relativeTo(rootProject.projectDir).invariantSeparatorsPath
            val expectedDirectory = project.path.removePrefix(":").replace(':', '/')
            check(relativeDirectory == expectedDirectory) {
                "${project.path} uses unexpected project directory /$relativeDirectory; expected /$expectedDirectory"
            }
            val expectedLane = classifications.single { it.first == project.path }.second
            val actualLanes = selectedPrimaryLanes("/$relativeDirectory/__routing_probe__")
            val expectedLanes = if (expectedLane.startsWith("exempt:")) emptyList() else listOf(expectedLane)
            check(actualLanes == expectedLanes) {
                "${project.path} (/$relativeDirectory/**) must route to ${expectedLanes.ifEmpty { listOf(expectedLane) }}, " +
                    "but selected ${actualLanes.ifEmpty { listOf("no primary lane") }}"
            }
        }
        mapOf(
            "wasm-modules" to coreId,
            "karma.config.d" to coreId,
            "kotlin-js-store" to coreId,
        ).forEach { (relativeDirectory, expectedLane) ->
            check(rootProject.file(relativeDirectory).isDirectory) {
                "Selected non-Gradle product root /$relativeDirectory is missing"
            }
            val actualLanes = selectedPrimaryLanes("/$relativeDirectory/__routing_probe__")
            check(actualLanes == listOf(expectedLane)) {
                "Non-Gradle product root /$relativeDirectory/** must route only to $expectedLane, but selected $actualLanes"
            }
        }
    }
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

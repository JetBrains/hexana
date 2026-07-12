package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val BACKTICKED_IDENTIFIER = Regex("""`([a-z][a-z0-9_]+)`""")

class HexanaClaudePluginWrapperIntegrationTest {
    private val logger = LoggerFactory.getLogger(HexanaClaudePluginWrapperIntegrationTest::class.java)

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun assembledClaudePluginVersionCommandExitsPromptly() = runBlocking {
        assertVersionCommandExitsPromptly(StdioLauncher.ClaudePluginWrapper)
    }

    @Test
    fun assembledClaudePluginRunsExecutableCliCommand() = runBlocking {
        assertExecutableCliCommandRunsThroughWrapper(StdioLauncher.ClaudePluginWrapper)
    }

    @Test
    fun assembledCodexPluginVersionCommandExitsPromptly() = runBlocking {
        assertVersionCommandExitsPromptly(StdioLauncher.CodexPluginWrapper)
    }

    @Test
    fun assembledCodexPluginRunsExecutableCliCommand() = runBlocking {
        assertExecutableCliCommandRunsThroughWrapper(StdioLauncher.CodexPluginWrapper)
    }

    @Test
    fun assembledCodexPluginMcpConfigPinsPluginRootWorkingDirectory() {
        val installDir = Path.of(
            System.getProperty(StdioLauncher.CodexPluginWrapper.installDirSystemProperty)
                ?: error("Missing `${StdioLauncher.CodexPluginWrapper.installDirSystemProperty}` system property")
        )
        val config = Json.parseToJsonElement(Files.readString(installDir.resolve(".mcp.json"))).jsonObject
        val hexanaServer = config.getValue("mcpServers").jsonObject.getValue("hexana").jsonObject

        assertEquals("./bin/hexana-mcp", hexanaServer.getValue("command").jsonPrimitive.content)
        assertEquals(".", hexanaServer.getValue("cwd").jsonPrimitive.content)
    }

    @Test
    fun assembledPluginWrappersShipWindowsLaunchers() {
        val claudeInstallDir = Path.of(
            System.getProperty(StdioLauncher.ClaudePluginWrapper.installDirSystemProperty)
                ?: error("Missing `${StdioLauncher.ClaudePluginWrapper.installDirSystemProperty}` system property")
        )
        val codexInstallDir = Path.of(
            System.getProperty(StdioLauncher.CodexPluginWrapper.installDirSystemProperty)
                ?: error("Missing `${StdioLauncher.CodexPluginWrapper.installDirSystemProperty}` system property")
        )

        assertTrue(Files.isRegularFile(claudeInstallDir.resolve("bin/hexana-mcp.cmd")))
        assertTrue(Files.isRegularFile(codexInstallDir.resolve("bin/hexana-mcp.cmd")))
    }

    @Test
    fun assembledPluginSkillsReferenceOnlyProductionTools() {
        val productionToolNames = EXPECTED_PRODUCTION_HEXANA_TOOL_NAMES.toSet()
        val knownToolNames = EXPECTED_ADVANCED_HEXANA_TOOL_NAMES.toSet()

        for (launcher in listOf(StdioLauncher.ClaudePluginWrapper, StdioLauncher.CodexPluginWrapper)) {
            val installDir = Path.of(
                System.getProperty(launcher.installDirSystemProperty)
                    ?: error("Missing `${launcher.installDirSystemProperty}` system property")
            )
            val skill = Files.readString(installDir.resolve("skills/wasm-inspector/SKILL.md"))
            val referencedKnownTools = BACKTICKED_IDENTIFIER.findAll(skill)
                .map { it.groupValues[1] }
                .filter { it in knownToolNames }
                .toSet()
            val advancedOnlyTools = referencedKnownTools - productionToolNames

            assertTrue(
                "summarize_binary" in referencedKnownTools,
                "${launcher.clientName} wasm-inspector skill should reference production tool names"
            )
            assertTrue(
                advancedOnlyTools.isEmpty(),
                "${launcher.clientName} wasm-inspector skill references tools not available in the default production profile: $advancedOnlyTools"
            )
        }
    }

    @Test
    fun assembledClaudePluginExposesProductionToolsByDefaultOverStdio() = runBlocking {
        assertWrapperExposesToolsOverStdio(
            clientLabel = "Claude",
            launcher = StdioLauncher.ClaudePluginWrapper,
            expectedToolNames = EXPECTED_PRODUCTION_HEXANA_TOOL_NAMES,
        )
    }

    @Test
    fun assembledCodexPluginExposesProductionToolsByDefaultOverStdio() = runBlocking {
        assertWrapperExposesToolsOverStdio(
            clientLabel = "Codex",
            launcher = StdioLauncher.CodexPluginWrapper,
            expectedToolNames = EXPECTED_PRODUCTION_HEXANA_TOOL_NAMES,
        )
    }

    @Test
    fun assembledClaudePluginAdvancedProfileExposesAllToolsOverStdio() = runBlocking {
        assertWrapperExposesToolsOverStdio(
            clientLabel = "Claude",
            launcher = StdioLauncher.ClaudePluginWrapper,
            expectedToolNames = EXPECTED_ADVANCED_HEXANA_TOOL_NAMES,
            toolProfile = HexanaMcpToolProfile.ADVANCED,
            callTool = false,
        )
    }

    @Test
    fun assembledCodexPluginAdvancedProfileExposesAllToolsOverStdio() = runBlocking {
        assertWrapperExposesToolsOverStdio(
            clientLabel = "Codex",
            launcher = StdioLauncher.CodexPluginWrapper,
            expectedToolNames = EXPECTED_ADVANCED_HEXANA_TOOL_NAMES,
            toolProfile = HexanaMcpToolProfile.ADVANCED,
            callTool = false,
        )
    }

    private suspend fun assertVersionCommandExitsPromptly(launcher: StdioLauncher) {
        val result = HexanaInstalledStdioClient.runCommand(launcher, "version")
        assertFalse(
            result.timedOut,
            "Version command timed out. stdout=${result.stdout}, stderr=${result.stderr}, command=${result.command}"
        )
        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        assertTrue(
            result.stdout.trim().matches(Regex("""hexana-mcp \S+""")),
            "Unexpected version output: ${result.stdout}"
        )
    }

    private suspend fun assertExecutableCliCommandRunsThroughWrapper(launcher: StdioLauncher) {
        val wasm = temporaryFolder.newFile("${launcher.clientName}-summary.wasm").toPath().also {
            Files.write(
                it,
                wat2wasm(
                    """
                    (module
                      (memory 1)
                      (func (export "main")))
                    """.trimIndent()
                )
            )
        }

        val result = HexanaInstalledStdioClient.runCommand(
            launcher,
            "summarize-binary",
            wasm.toString(),
            "--format",
            "json",
        )
        assertFalse(
            result.timedOut,
            "summarize-binary command timed out. stdout=${result.stdout}, stderr=${result.stderr}, command=${result.command}"
        )
        assertEquals(0, result.exitCode, "stderr=${result.stderr}, command=${result.command}")
        assertEquals("", result.unexpectedStderr, "Executable CLI command should not log to stderr.")

        val json = Json.parseToJsonElement(result.stdout).jsonObject
        val module = json.getValue("module").jsonObject
        val counts = json.getValue("counts").jsonObject
        assertEquals("wasm", module.getValue("format").jsonPrimitive.content)
        assertEquals(wasm.toString(), module.getValue("path").jsonPrimitive.content)
        assertTrue(module.getValue("byteLength").jsonPrimitive.long > 0)
        assertEquals(1, counts.getValue("functions").jsonPrimitive.int)
        assertEquals(1, counts.getValue("memories").jsonPrimitive.int)
        assertEquals(1, counts.getValue("exports").jsonPrimitive.int)
    }

    private suspend fun assertRunScriptCliCommandRunsThroughWrapper(launcher: StdioLauncher) {
        val binary = temporaryFolder.newFile("${launcher.clientName}-run-script.bin").toPath()
        Files.write(binary, byteArrayOf(5, 6, 7))

        val result = HexanaInstalledStdioClient.runCommand(
            launcher,
            "run-script",
            binary.toString(),
            "--script-text",
            "print(binary.bytes.size); print(binary.bytes.get(2));",
            "--format",
            "raw",
        )
        assertFalse(
            result.timedOut,
            "run-script command timed out. stdout=${result.stdout}, stderr=${result.stderr}, command=${result.command}"
        )
        assertEquals(0, result.exitCode, "stderr=${result.stderr}, command=${result.command}")
        assertEquals("", result.unexpectedStderr, "Executable CLI command should not log to stderr.")
        assertEquals("3\n7", result.stdout)
    }

    private suspend fun assertWrapperExposesToolsOverStdio(
        clientLabel: String,
        launcher: StdioLauncher,
        expectedToolNames: List<String>,
        toolProfile: HexanaMcpToolProfile? = null,
        callTool: Boolean = true,
    ) {
        KotlinLoggingConfiguration.logStartupMessage = false
        withInstalledStdioClient(
            launcher = launcher,
            toolProfile = toolProfile,
            allowedRoots = listOf(temporaryFolder.root.toPath())
        ) { server ->
            logger.info("Connected {} plugin wrapper stdio client", clientLabel)

            val tools = server.client.listTools(ListToolsRequest())
            val toolNames = tools.tools.map { it.name }.sorted()
            logger.info("{} wrapper tools: {}", clientLabel, toolNames)
            assertEquals(expectedToolNames, toolNames)

            if (!callTool) {
                return@withInstalledStdioClient
            }

            val wasm = temporaryFolder.newFile("claude-wrapper-summary.wasm").toPath().also {
                Files.write(it, wat2wasm(
                    """
                    (module
                      (memory 1)
                      (func (export "main")))
                    """.trimIndent()
                ))
            }

            val summary = server.client.callTool(
                name = "summarize_module",
                arguments = mapOf("path" to wasm.toString()),
                meta = emptyMap()
            )
            assertFalse(summary.isError == true)
            val summaryText = summary.content.single() as TextContent
            logger.info("{} wrapper summarize_module payload: {}", clientLabel, summaryText.text)
            assertEquals(
                1,
                summary.structuredContent
                    ?.get("functions")
                    ?.jsonPrimitive
                    ?.int
            )

            assertTrue(
                server.stderr.contains("Starting stdio MCP transport"),
                "$clientLabel plugin wrapper should proxy stdio server logs on stderr. stderr was: ${server.stderr}"
            )

            assertComponentToolsOverStdio(clientLabel, server)
        }
    }

    private suspend fun assertComponentToolsOverStdio(
        clientLabel: String,
        server: HexanaInstalledStdioClient,
    ) {
        val projectRoot = temporaryFolder.newFolder("${clientLabel.lowercase()}-component-project").toPath()
        val calculator = copyComponentFixture("calculator.wasm", projectRoot.resolve("calculator.wasm"))
        val adder = copyComponentFixture("add.component.wasm", projectRoot.resolve("deps/add.component.wasm"))

        val compatibility = server.client.callTool(
            name = "check_component_compatibility",
            arguments = mapOf(
                "path" to calculator.toString(),
                "dependencyPaths" to listOf(adder.toString()),
            ),
            meta = emptyMap()
        )
        assertFalse(
            compatibility.isError == true,
            "$clientLabel check_component_compatibility failed: ${surfaceText(compatibility)}"
        )
        assertEquals(
            false,
            compatibility.structuredContent?.get("compatible")?.jsonPrimitive?.boolean,
            "$clientLabel check_component_compatibility should report the missing subtractor import"
        )
        assertTrue(
            compatibility.structuredContent
                ?.get("matches")
                ?.jsonArray
                ?.any {
                    it.jsonObject.getValue("importName").jsonPrimitive.content.contains("component:adder/add")
                } == true,
            "$clientLabel check_component_compatibility did not report adder match: ${surfaceText(compatibility)}"
        )
        assertTrue(
            compatibility.structuredContent
                ?.get("missingImports")
                ?.jsonArray
                ?.any {
                    it.jsonObject.getValue("base_name").jsonPrimitive.content.contains("component:subtractor/subtract")
                } == true,
            "$clientLabel check_component_compatibility did not report missing subtractor: ${surfaceText(compatibility)}"
        )
        assertEquals(
            "missing_provider",
            compatibility.structuredContent?.get("selectedIncompatibilityReason")?.jsonPrimitive?.content,
            "$clientLabel check_component_compatibility should select the missing provider reason"
        )
        assertTrue(
            compatibility.structuredContent
                ?.get("candidateProviders")
                ?.jsonArray
                ?.any {
                    it.jsonObject.getValue("dependencyPath").jsonPrimitive.content == adder.toString() &&
                        it.jsonObject.getValue("verdict").jsonPrimitive.content == "compatible"
                } == true,
            "$clientLabel check_component_compatibility did not report candidate provider verdicts: ${surfaceText(compatibility)}"
        )
    }

    private fun copyComponentFixture(name: String, target: Path): Path {
        Files.createDirectories(target.parent)
        return Files.copy(componentFixture(name), target)
    }

    private fun componentFixture(name: String): Path =
        repoRoot().resolve("wasmParser/src/commonTest/resources/component/$name")

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (dir.parent != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts")) && Files.exists(dir.resolve("wasmParser"))) {
                return dir
            }
            dir = dir.parent
        }
        error("Repository root not found")
    }

    private fun surfaceText(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
}

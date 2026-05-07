package org.jetbrains.hexana.mcp.vscode.server

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class VsCodeServerStdioSmokeTest {
    @Test
    fun installedVsCodeServerSupportsListToolsAndToolCallsOverStdio() = runBlocking {
        val installDir = vscodeServerInstallDir()
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(stdioCommand(installDir))
                .directory(installDir.toFile())
                .start()
        }
        val client = Client(Implementation(name = "hexana-vscode-mcp-smoke-test", version = "1.0"))
        val stderrBuffer = StringBuffer()
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
            classifyStderr = { chunk ->
                synchronized(stderrBuffer) {
                    stderrBuffer.append(chunk)
                }
                StdioClientTransport.StderrSeverity.IGNORE
            }
        )

        try {
            withTimeout(30.seconds) {
                client.connect(transport)

                val tools = client.listTools(ListToolsRequest()).tools
                val summarizeModule = tools.single { it.name == "summarize_module" }
                assertTrue(summarizeModule.annotations?.readOnlyHint == true)
                assertFalse(summarizeModule.annotations?.destructiveHint == true)
                assertTrue(summarizeModule.annotations?.idempotentHint == true)

                val summary = client.callTool(
                    name = "summarize_module",
                    arguments = mapOf("bytes" to MinimalWasmModuleBytes),
                    meta = emptyMap<String, Any?>()
                )
                assertFalse(summary.isError == true)
                assertJsonTextMatchesStructured(summary)
                assertEquals(0, summary.structuredContent?.get("functions")?.jsonPrimitive?.int)
            }
        } finally {
            runCatching {
                withTimeout(3.seconds) {
                    client.close()
                }
            }
            stopProcess(process)
        }
    }

    private fun vscodeServerInstallDir(): Path {
        val path = Path.of(
            System.getProperty("hexana.vscodeServer.installDir")
                ?: error("Missing `hexana.vscodeServer.installDir` system property")
        )
        require(Files.isDirectory(path)) { "VS Code MCP server install directory does not exist: $path" }
        return path
    }

    private fun stdioCommand(installDir: Path): List<String> {
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf(
                "cmd.exe",
                "/c",
                installDir.resolve("bin/hexana-vscode-mcp-server.bat").toString(),
                "--transport",
                "stdio",
                "--log-level",
                "quiet"
            )
        } else {
            listOf(
                installDir.resolve("bin/hexana-vscode-mcp-server").toString(),
                "--transport",
                "stdio",
                "--log-level",
                "quiet"
            )
        }
    }

    private fun assertJsonTextMatchesStructured(result: CallToolResult) {
        val textPayload = (result.content.single() as TextContent).text
        assertEquals(result.structuredContent, Json.parseToJsonElement(textPayload).jsonObject)
    }

    private suspend fun stopProcess(process: Process) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                return@withContext
            }
            process.destroy()
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                return@withContext
            }
            process.destroyForcibly()
            process.waitFor()
        }
    }
}

private val MinimalWasmModuleBytes = listOf(
    0x00, 0x61, 0x73, 0x6d,
    0x01, 0x00, 0x00, 0x00
)

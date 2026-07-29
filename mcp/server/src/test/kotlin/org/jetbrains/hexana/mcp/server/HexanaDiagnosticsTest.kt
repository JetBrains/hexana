package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HexanaDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun closeDiagnostics() {
        HexanaDiagnostics.close()
    }

    @Test
    fun concurrentFallbackReportsShareOneSessionAndFile() {
        val logDirectory = temporaryFolder.newFolder("fallback-logs").toPath()
        val reportCount = 16
        val ready = CountDownLatch(reportCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(reportCount)

        val references = try {
            (1..reportCount).map { index ->
                executor.submit<HexanaDiagnosticReference> {
                    ready.countDown()
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    HexanaDiagnostics.report(
                        HexanaFailureCategory.TOOL_INTERNAL,
                        "concurrent-$index",
                        IllegalStateException("failure-$index"),
                        envReader = { logDirectory.toString() },
                    )
                }
            }.also {
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
            }.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, references.map { it.location }.distinct().size)
        val file = diagnosticFiles(logDirectory).single()
        val records = Files.readAllLines(file).filter(String::isNotBlank).map(Json::parseToJsonElement)
        assertEquals(1, records.count { it.jsonObject.getValue("event").jsonPrimitive.content == "session_start" })
        assertEquals(reportCount, records.count { it.jsonObject.getValue("event").jsonPrimitive.content == "failure" })
    }

    @Test
    fun closeClearsSessionIsIdempotentAndReportCreatesFreshSession() {
        val logDirectory = temporaryFolder.newFolder("lifecycle-logs").toPath()
        val envReader: (String) -> String? = { logDirectory.toString() }
        HexanaDiagnostics.start(minimalContext(), envReader)
        val originalFile = HexanaDiagnostics.status.file ?: error("Missing initial diagnostic file")
        assertTrue(Files.exists(originalFile))

        HexanaDiagnostics.close()
        assertNull(HexanaDiagnostics.status.file)
        HexanaDiagnostics.close()
        val reference = HexanaDiagnostics.report(
            HexanaFailureCategory.TOOL_INTERNAL,
            "after-close",
            IllegalStateException("failure"),
            envReader,
        )

        val replacementFile = HexanaDiagnostics.status.file ?: error("Missing replacement diagnostic file")
        assertTrue(reference.available)
        assertNotEquals(originalFile, replacementFile)
        assertEquals(replacementFile.toString(), reference.location)
    }

    @Test
    fun writesSafeJsonlContextAndExceptionDetailsWithoutMessagesOrPaths() {
        val logDirectory = temporaryFolder.newFolder("logs").toPath()
        val secretPath = "/private/customer/secret.wasm"
        HexanaDiagnosticSession.open(
            context = HexanaDiagnosticContext(
                version = "1.2.3",
                command = "stdio",
                transport = "stdio",
                toolProfile = "advanced",
                logLevel = "debug",
                telemetryEnabled = true,
                allowedRootCount = 2,
                resourceLimits = HexanaResourceLimits(maxOutputBytes = 1234),
            ),
            envReader = { if (it == HEXANA_MCP_LOG_DIR_ENV) logDirectory.toString() else null },
            now = { Instant.parse("2026-07-29T12:00:00Z") },
        ).use { session ->
            val failure = IllegalStateException("failed to read $secretPath", IllegalArgumentException("secret payload"))
            failure.stackTrace = arrayOf(StackTraceElement("private.Type", "secretMethod", secretPath, 42))
            val reference = session.report(
                HexanaFailureCategory.TOOL_INTERNAL,
                "Unexpected internal tool failure.",
                failure,
            )

            assertTrue(reference.available)
            val text = session.status.file?.toFile()?.readText() ?: error("Missing diagnostic file")
            assertFalse(secretPath in text, text)
            assertFalse("secret payload" in text, text)
            assertTrue("private.Type.secretMethod:42" in text, text)
            val records = text.lineSequence().filter { it.isNotBlank() }.map(Json::parseToJsonElement).toList()
            assertEquals(listOf("session_start", "failure"), records.map { it.jsonObject.getValue("event").jsonPrimitive.content })
            val failureRecord = records.last().jsonObject
            assertEquals("TOOL_INTERNAL", failureRecord.getValue("category").jsonPrimitive.content)
            assertEquals(
                "java.lang.IllegalStateException",
                failureRecord.getValue("exception").jsonObject.getValue("type").jsonPrimitive.content,
            )
            val context = failureRecord.getValue("context").jsonObject
            assertEquals("stdio", context.getValue("transport").jsonPrimitive.content)
            assertEquals("2", context.getValue("allowed_root_count").jsonPrimitive.content)
            assertEquals("1234", context.getValue("limits").jsonObject.getValue("max_output_bytes").jsonPrimitive.content)
        }
    }

    @Test
    fun unavailableDestinationNeverPreventsFailureReporting() {
        val blockingFile = temporaryFolder.newFile("not-a-directory").toPath()
        HexanaDiagnosticSession.open(
            context = minimalContext(),
            envReader = { blockingFile.resolve("logs").toString() },
        ).use { session ->
            val reference = session.report(
                HexanaFailureCategory.STARTUP,
                "Startup failed.",
                IllegalStateException("boom"),
            )

            assertFalse(session.status.available)
            assertNull(session.status.file)
            assertFalse(reference.available)
            assertTrue(reference.id.isNotBlank())
        }
    }

    @Test
    fun defaultDestinationUsesExistingHexanaDirectoryHierarchy() {
        val home = temporaryFolder.newFolder("home").toPath()

        val status = diagnosticStatus(envReader = { null }, userHome = home.toString())

        assertEquals(home.resolve(".hexana/mcp/logs"), status.directory)
        assertTrue(status.available)
    }

    private fun diagnosticFiles(directory: Path): List<Path> = Files.list(directory).use { entries ->
        entries.filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().startsWith("hexana-mcp-") }
            .filter { it.fileName.toString().endsWith(".jsonl") }
            .toList()
    }

    private fun minimalContext() = HexanaDiagnosticContext(
        version = "test",
        command = "stdio",
        transport = "stdio",
        toolProfile = "default",
        logLevel = "quiet",
        telemetryEnabled = false,
        allowedRootCount = 0,
        resourceLimits = HexanaResourceLimits(),
    )
}

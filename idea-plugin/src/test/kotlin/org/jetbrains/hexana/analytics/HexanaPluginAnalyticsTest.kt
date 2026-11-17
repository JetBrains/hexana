package org.jetbrains.hexana.analytics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.HexanaFileEditorProvider
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.HexanaSettings
import org.jetbrains.hexana.WasmFileType
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class HexanaPluginAnalyticsTest : BasePlatformTestCase() {
    private lateinit var settings: HexanaSettings
    private var previousSendAnalytics: Boolean = true

    override fun setUp() {
        super.setUp()
        settings = HexanaSettings.getInstance()
        previousSendAnalytics = settings.sendAnalytics
    }

    override fun tearDown() {
        try {
            HexanaAnalytics.shutdown()
            settings.sendAnalytics = previousSendAnalytics
        } finally {
            super.tearDown()
        }
    }

    fun testWasmOpenSendsAnalyticsWhenEnabled() {
        FakePostHogServer.start().use { server ->
            settings.sendAnalytics = true
            waitForServiceAnalyticsInitialization()
            initializeAnalytics(server)

            createEditor(createMinimalWasmFile("fixtures/analytics/enabled.wasm")).use {
                val event = server.awaitEvent("binary_opened")
                event
                    .requireDistinctId(TEST_DISTINCT_ID)
                    .assertProperty("format", "wasm")
                    .assertProperty("surface", "ide_plugin")
                    .assertProperty("file_size_bytes", MINIMAL_WASM_BYTES.size)
                    .assertProperty("ide_type", "IDEA")
                    .assertProperty("ide_build", "TEST")
            }
        }
    }

    fun testWasmOpenSendsNoAnalyticsWhenDisabled() {
        FakePostHogServer.start().use { server ->
            settings.sendAnalytics = false
            waitForServiceAnalyticsInitialization()
            initializeAnalytics(server)

            createEditor(createMinimalWasmFile("fixtures/analytics/disabled.wasm")).use {
                HexanaAnalytics.client.flush()
                server.assertNoRequests()
            }
        }
    }

    fun testGlobalPropertiesCarrySurfaceAndPluginVersion() {
        val properties = ideAnalyticsGlobalProperties(pluginVersion = "1.2.3")

        assertEquals("ide_plugin", properties["surface"])
        assertEquals("1.2.3", properties["plugin_version"])
        assertTrue("ide_type should be populated", (properties["ide_type"] as String).isNotBlank())
        assertTrue("ide_build should be populated", (properties["ide_build"] as String).isNotBlank())
    }

    fun testEventWithoutOwnSurfaceInheritsTheIdeSurface() {
        FakePostHogServer.start().use { server ->
            settings.sendAnalytics = true
            waitForServiceAnalyticsInitialization()
            initializeAnalytics(server, ideAnalyticsGlobalProperties(pluginVersion = "1.2.3"))

            // wasm_unparsed_opcodes carries no surface of its own; without the
            // global fallback it would arrive unattributed.
            HexanaAnalytics.client.capture(
                HexanaEvent.WasmUnparsedOpcodes(unparsedCount = 2, totalFunctions = 7)
            )

            server.awaitEvent("wasm_unparsed_opcodes")
                .assertProperty("surface", "ide_plugin")
                .assertProperty("plugin_version", "1.2.3")
                .assertProperty("unparsed_count", 2)
        }
    }

    fun testShutdownFlushDeliversEventsBelowTheBatchThreshold() {
        FakePostHogServer.start().use { server ->
            settings.sendAnalytics = true
            waitForServiceAnalyticsInitialization()
            // Production flush settings: flushAt is 20 and the timer is 30s, so a
            // lone event can only reach PostHog via the shutdown flush. This is
            // the regression test for "open IDE, inspect one binary, quit".
            HexanaAnalytics.initialize(
                config = AnalyticsConfig(apiKey = "test-key", host = server.url),
                consentProvider = IntellijConsentProvider(),
                distinctIdProvider = DistinctIdProvider { TEST_DISTINCT_ID },
                globalProperties = ideAnalyticsGlobalProperties(),
            )

            HexanaAnalytics.client.capture(
                HexanaEvent.HexViewActivated(surface = HexanaEvent.Surface.IDE_PLUGIN)
            )
            server.assertNoRequests()

            flushAndShutdownAnalytics()

            server.awaitEvent("hex_view_activated")
                .requireDistinctId(TEST_DISTINCT_ID)
                .assertProperty("surface", "ide_plugin")
        }
    }

    fun testShutdownRetiresTheClientBeforeReturningEvenWhileTheFlushIsStillRunning() {
        // The flush is bounded by a timeout, so it can still be in flight when
        // the call returns. Retirement must not be left to that thread: it
        // resets HexanaAnalytics.client, and on the consent-change path the
        // caller installs a replacement the moment this returns. A worker that
        // reset the client afterwards left the service reporting Ready while
        // every capture went to the no-op client — analytics silently dead for
        // the rest of the session, triggered by the user *enabling* it.
        //
        // A listening socket that never accepts holds the connection in the
        // kernel backlog: OkHttp connects and then blocks on read for its
        // 10s default, far beyond the timeout used here.
        val blackHole = ServerSocket(0, /* backlog = */ 1)
        try {
            settings.sendAnalytics = true
            waitForServiceAnalyticsInitialization()
            HexanaAnalytics.initialize(
                config = AnalyticsConfig(
                    apiKey = "test-key",
                    host = "http://127.0.0.1:${blackHole.localPort}",
                    flushAt = 1_000,
                    flushIntervalMs = 600_000,
                ),
                consentProvider = IntellijConsentProvider(),
                distinctIdProvider = DistinctIdProvider { TEST_DISTINCT_ID },
                globalProperties = ideAnalyticsGlobalProperties(),
            )
            val retiring = HexanaAnalytics.client
            // Queued, not sent: flushAt is far above one event, so the flush
            // below is what tries to deliver it — and hangs.
            retiring.capture(HexanaEvent.HexViewActivated(surface = HexanaEvent.Surface.IDE_PLUGIN))

            val startedAt = System.nanoTime()
            flushAndShutdownAnalytics(timeoutMillis = 200)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(
                "the flush must stay bounded by its timeout, took ${elapsedMillis}ms",
                elapsedMillis < 5_000,
            )
            assertNotSame(
                "the client must be retired by the time the call returns, " +
                    "not whenever the flush thread happens to finish",
                retiring,
                HexanaAnalytics.client,
            )
        } finally {
            blackHole.close()
        }
    }

    private fun initializeAnalytics(server: FakePostHogServer) {
        HexanaAnalytics.initialize(
            config = AnalyticsConfig(
                apiKey = "test-key",
                host = server.url,
                flushAt = 1,
                flushIntervalMs = 1_000,
            ),
            consentProvider = IntellijConsentProvider(),
            distinctIdProvider = DistinctIdProvider { TEST_DISTINCT_ID },
            globalProperties = mapOf(
                "ide_type" to "IDEA",
                "ide_build" to "TEST",
            )
        )
    }

    private fun initializeAnalytics(server: FakePostHogServer, globalProperties: Map<String, Any>) {
        HexanaAnalytics.initialize(
            config = AnalyticsConfig(
                apiKey = "test-key",
                host = server.url,
                flushAt = 1,
                flushIntervalMs = 1_000,
            ),
            consentProvider = IntellijConsentProvider(),
            distinctIdProvider = DistinctIdProvider { TEST_DISTINCT_ID },
            globalProperties = globalProperties,
        )
    }

    private fun waitForServiceAnalyticsInitialization() {
        runBlocking {
            HexanaService.getInstance().awaitAnalyticsInitialization()
        }
    }

    private fun createEditor(file: VirtualFile): FileEditor {
        val editorRef = arrayOfNulls<FileEditor>(1)
        ApplicationManager.getApplication().invokeAndWait {
            editorRef[0] = HexanaFileEditorProvider().createEditor(project, file)
        }
        return editorRef[0] ?: error("Hexana editor was not created")
    }

    private fun createMinimalWasmFile(projectRelativePath: String): VirtualFile {
        val projectBasePath = project.basePath ?: error("Project base path is not available")
        val fullPath = Path.of(projectBasePath).resolve(projectRelativePath)
        Files.createDirectories(fullPath.parent)
        Files.write(fullPath, MINIMAL_WASM_BYTES)

        val file = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fullPath)
                ?: error("Failed to refresh $fullPath")
        }
        assertEquals(WasmFileType.INSTANCE, file.fileType)
        return file
    }

    private fun FileEditor.use(block: () -> Unit) {
        try {
            block()
        } finally {
            dispose()
        }
    }

    private companion object {
        val MINIMAL_WASM_BYTES = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
        )
        const val TEST_DISTINCT_ID = "ide-test-distinct-id"
    }
}

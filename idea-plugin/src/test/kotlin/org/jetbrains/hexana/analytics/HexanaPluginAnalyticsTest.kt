package org.jetbrains.hexana.analytics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.hexana.HexanaFileEditorProvider
import org.jetbrains.hexana.HexanaSettings
import org.jetbrains.hexana.WasmFileType
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
            initializeAnalytics(server)

            createEditor(createMinimalWasmFile("fixtures/analytics/enabled.wasm")).use {
                val event = server.awaitEvent("wasm_file_opened")
                event
                    .requireDistinctId(TEST_DISTINCT_ID)
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
            initializeAnalytics(server)

            createEditor(createMinimalWasmFile("fixtures/analytics/disabled.wasm")).use {
                HexanaAnalytics.client.flush()
                server.assertNoRequests()
            }
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

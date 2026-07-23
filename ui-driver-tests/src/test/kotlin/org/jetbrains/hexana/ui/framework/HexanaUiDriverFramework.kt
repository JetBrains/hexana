package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.closeToolWindow
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import org.jetbrains.hexana.ui.FakePostHogServer
import org.jetbrains.hexana.ui.HexanaUiDriverTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val HEXANA_PLUGIN_ID = "org.jetbrains.hexana"
private val defaultRunTimeout = 15.minutes
private val defaultIndicatorTimeout = 5.minutes

internal fun hexanaIde(
    testName: String,
    runTimeout: Duration = defaultRunTimeout,
    indicatorTimeout: Duration = defaultIndicatorTimeout,
    configureContext: (IDETestContext) -> IDETestContext = { it },
    configure: HexanaIdeConfiguration.() -> Unit = {},
    action: HexanaIde.() -> Unit,
) {
    val configuration = HexanaIdeConfiguration().apply(configure)
    val context = configuration.applyToContext(configureContext(HexanaUiDriverTestSupport.newContext(testName)))
    context.runIdeWithDriver(runTimeout = configuration.runTimeoutOverride ?: runTimeout) { withScreenRecording() }
        .useDriverAndCloseIde {
            waitForIndicators(configuration.indicatorTimeoutOverride ?: indicatorTimeout)
            HexanaIde(context, this).action()
        }
}

internal class HexanaIdeConfiguration internal constructor() {
    internal var runTimeoutOverride: Duration? = null
        private set
    internal var indicatorTimeoutOverride: Duration? = null
        private set

    private val contextPatches = mutableListOf<(IDETestContext) -> IDETestContext>()

    fun runTimeout(timeout: Duration) {
        runTimeoutOverride = timeout
    }

    fun indicatorTimeout(timeout: Duration) {
        indicatorTimeoutOverride = timeout
    }

    fun patchContext(patch: (IDETestContext) -> IDETestContext) {
        contextPatches += patch
    }

    fun enableGlobalMcp(port: Int = HexanaUiDriverTestSupport.defaultMcpPort) {
        patchContext { context -> HexanaUiDriverTestSupport.enableGlobalMcpServer(context, port) }
    }

    fun analytics(server: FakePostHogServer, enabled: Boolean) {
        patchContext { context -> HexanaUiDriverTestSupport.configurePostHog(context, server) }
        patchContext { context -> HexanaUiDriverTestSupport.configureSendAnalytics(context, enabled) }
    }

    fun showWhatsNewOnNewVersion(enabled: Boolean) {
        patchContext { context -> HexanaUiDriverTestSupport.configureShowWhatsNewOnNewVersion(context, enabled) }
    }

    internal fun applyToContext(context: IDETestContext): IDETestContext =
        contextPatches.fold(context) { patchedContext, patch -> patch(patchedContext) }
}

internal class HexanaIde internal constructor(
    val context: IDETestContext,
    val driver: Driver,
) {
    private val probe: HexanaEditorUiTestProbeRef
        get() = driver.utility(HexanaEditorUiTestProbeRef::class)

    fun closeProjectToolWindow() {
        driver.closeToolWindow("Project")
    }

    fun closeStructureToolWindow() {
        driver.closeToolWindow("Structure")
    }

    fun openFile(fileName: String): HexanaEditor {
        driver.openFile(fileName, waitForCodeAnalysis = false, isTextEditor = false)
        return HexanaEditor(driver, fileName, context.projectRelativeFileIdentifier(fileName))
    }

    fun openWasm(fileName: String = HexanaUiDriverTestSupport.wasmFileName): HexanaEditor =
        openFile(fileName)

    fun openAbsoluteFile(path: Path): HexanaEditor {
        val absolutePath = path.toAbsolutePath().normalize()
        probe.openAbsoluteFile(absolutePath.toString())
        return HexanaEditor(
            driver,
            absolutePath.fileName?.toString() ?: absolutePath.toString(),
            ABSOLUTE_FILE_IDENTIFIER_PREFIX + absolutePath.toSystemIndependentPath(),
        )
    }

    fun openAbsoluteFileWithScriptTab(path: Path, scriptPath: Path): HexanaEditor {
        val absolutePath = path.toAbsolutePath().normalize()
        probe.openAbsoluteFileWithScriptTab(absolutePath.toString(), scriptPath.toAbsolutePath().normalize().toString())
        return HexanaEditor(
            driver,
            absolutePath.fileName?.toString() ?: absolutePath.toString(),
            ABSOLUTE_FILE_IDENTIFIER_PREFIX + absolutePath.toSystemIndependentPath(),
        )
    }

    fun activateStructureToolWindow(editor: HexanaEditor) {
        probe.activateStructureToolWindow(editor.fileIdentifier)
    }

    fun openComponentDiff(leftPath: String, rightPath: String): HexanaEditor {
        val payload = probe.openComponentDiff(leftPath, rightPath)
        val fileName = payload.firstOrNull { it.startsWith("fileName=") }
            ?.removePrefix("fileName=")
            ?: "$leftPath ↔ $rightPath"
        return HexanaEditor(driver, fileName, fileName)
    }

    fun openNativeDiff(leftPath: Path, rightPath: Path): HexanaEditor {
        val payload = probe.openNativeDiff(leftPath.toAbsolutePath().normalize().toString(), rightPath.toAbsolutePath().normalize().toString())
        val fileName = payload.firstOrNull { it.startsWith("fileName=") }
            ?.removePrefix("fileName=")
            ?: "${leftPath.fileName} ↔ ${rightPath.fileName}"
        return HexanaEditor(driver, fileName, fileName)
    }

    fun hexanaEditor(
        fileName: String = HexanaUiDriverTestSupport.wasmFileName,
        action: HexanaEditor.() -> Unit = {},
    ) {
        val editor = openFile(fileName)
        driver.ideFrame {
            editorTabs {
                tab(fileName.toEditorTabTitle(), fullMatch = false).waitFound()
            }
        }
        editor.action()
    }
}

internal class HexanaEditor internal constructor(
    val driver: Driver,
    val fileName: String,
    val fileIdentifier: String,
) {
    private val probe: HexanaEditorUiTestProbeRef
        get() = driver.utility(HexanaEditorUiTestProbeRef::class)

    fun snapshot(): HexanaEditorSnapshot =
        HexanaProbePayload.snapshot(probe.editorSnapshot(fileIdentifier))

    fun searchState(): HexanaEditorSearchState =
        HexanaProbePayload.searchState(probe.searchState(fileIdentifier))

    fun watState(): HexanaEditorWatState =
        HexanaProbePayload.watState(probe.watState(fileIdentifier))

    fun componentDiffState(): HexanaComponentDiffState =
        HexanaProbePayload.componentDiffState(probe.componentDiffState(fileIdentifier))

    fun archiveState(): HexanaArchiveState =
        HexanaProbePayload.archiveState(probe.archiveState(fileIdentifier))

    fun nativeDiffState(): HexanaNativeDiffState =
        HexanaProbePayload.nativeDiffState(probe.nativeDiffState(fileIdentifier))

    fun setTopFraction(topFraction: Float) {
        probe.setEditorTopFraction(fileIdentifier, topFraction)
    }

    fun selectTab(tabName: String) {
        probe.selectTab(fileIdentifier, tabName)
    }

    fun expect(action: HexanaEditorExpectations.() -> Unit) {
        HexanaEditorExpectations(this, probe).action()
    }
}

internal class HexanaEditorExpectations internal constructor(
    private val editor: HexanaEditor,
    private val probe: HexanaEditorUiTestProbeRef,
) {
    fun probeResponds() {
        assertEquals("pong", probe.ping())
        assertEquals("1", probe.frameworkVersion())
    }

    fun fileName(expected: String) {
        val snapshot = editor.snapshot()
        assertEquals(expected, snapshot.fileName, "Unexpected file name in Hexana editor snapshot")
    }

    fun providerKind(expected: HexanaProviderKind) {
        providerKind(expected.id)
    }

    fun providerKind(expected: String) {
        val snapshot = editor.snapshot()
        assertEquals(expected, snapshot.providerKind, "Unexpected provider kind for ${snapshot.fileName}")
    }

    fun tabsContain(vararg expectedTabs: HexanaEditorTab) {
        val snapshot = editor.snapshot()
        expectedTabs.forEach { tab ->
            assertTrue(
                tab.presentIn(snapshot.panelNames),
                "Expected tab '${tab.tabName}' for ${snapshot.fileName}; available tabs: ${snapshot.panelNames}",
            )
        }
    }

    fun tabsContain(vararg expectedTabs: String) {
        val snapshot = editor.snapshot()
        expectedTabs.forEach { expected ->
            assertTrue(
                expected in snapshot.panelNames,
                "Expected tab '$expected' for ${snapshot.fileName}; available tabs: ${snapshot.panelNames}",
            )
        }
    }

    fun tabsDoNotContain(vararg unexpectedTabs: HexanaEditorTab) {
        val snapshot = editor.snapshot()
        unexpectedTabs.forEach { tab ->
            assertFalse(
                tab.presentIn(snapshot.panelNames),
                "Unexpected tab '${tab.tabName}' for ${snapshot.fileName}; available tabs: ${snapshot.panelNames}",
            )
        }
    }

    fun tabsDoNotContain(vararg unexpectedTabs: String) {
        val snapshot = editor.snapshot()
        unexpectedTabs.forEach { unexpected ->
            assertFalse(
                unexpected in snapshot.panelNames,
                "Unexpected tab '$unexpected' for ${snapshot.fileName}; available tabs: ${snapshot.panelNames}",
            )
        }
    }

    fun selectedTabIs(expectedTab: HexanaEditorTab) {
        selectedTabIs(expectedTab.tabName)
    }

    fun selectedTabIs(expectedTab: String) {
        val snapshot = editor.snapshot()
        assertEquals(
            expectedTab,
            snapshot.selectedTabName,
            "Unexpected selected tab for ${snapshot.fileName}; available tabs: ${snapshot.panelNames}",
        )
    }
}

internal enum class HexanaProviderKind(val id: String) {
    CORE_WASM("core-wasm"),
    COMPONENT_WASM("component-wasm"),
    GENERIC("generic"),
}

internal enum class HexanaEditorTab(val tabName: String, val prefixMatch: Boolean = false) {
    DEPENDENCIES("Dependencies"),
    FUNCTIONS("Functions"),
    EXPORTS("Exports"),
    IMPORTS("Imports"),
    MODULES("Modules"),
    MONOS("Monos"),
    GARBAGE("Garbage", prefixMatch = true),
    DOMINATORS("Dominators"),
    TOP("Top"),
    WAT("Wat"),
    WAT_VIRTUALIZED("WAT (virtualized)"),
    ;

    /** Returns `true` when [panelName] matches this tab, respecting [prefixMatch]. */
    fun matches(panelName: String): Boolean =
        if (prefixMatch) panelName == tabName || panelName.startsWith("$tabName: ")
        else panelName == tabName

    /** Returns `true` when any element of [panelNames] matches this tab. */
    fun presentIn(panelNames: List<String>): Boolean =
        panelNames.any { matches(it) }
}

private const val PROJECT_RELATIVE_FILE_IDENTIFIER_PREFIX = "project-relative:"
private const val ABSOLUTE_FILE_IDENTIFIER_PREFIX = "absolute:"

private fun IDETestContext.projectRelativeFileIdentifier(fileName: String): String {
    val projectRoot = resolvedProjectHome.toAbsolutePath().normalize()
    val requestedPath = Path.of(fileName)
    val absolutePath = if (requestedPath.isAbsolute) {
        requestedPath.toAbsolutePath().normalize()
    } else {
        projectRoot.resolve(requestedPath).normalize()
    }

    return if (absolutePath.startsWith(projectRoot)) {
        PROJECT_RELATIVE_FILE_IDENTIFIER_PREFIX + projectRoot.relativize(absolutePath).toSystemIndependentPath()
    } else {
        ABSOLUTE_FILE_IDENTIFIER_PREFIX + absolutePath.toSystemIndependentPath()
    }
}

private fun Path.toSystemIndependentPath(): String =
    toString().replace('\\', '/')

private fun String.toEditorTabTitle(): String =
    Path.of(this).fileName?.toString() ?: this

internal data class HexanaEditorSnapshot(
    val fileName: String,
    val providerKind: String,
    val panelNames: List<String>,
    val selectedTabName: String?,
    val cursorOffset: Int?,
    /** True once background tab resolution finished for the current provider (all placeholders replaced or removed). */
    val panelsResolved: Boolean,
)

internal data class HexanaEditorSearchState(
    val query: String,
    val binaryMode: Boolean,
    val resultCount: Int,
    val currentResultIndex: Int,
    val cursorOffset: Int?,
)

internal data class HexanaEditorWatState(
    val selectedTabName: String?,
    val conversionState: String,
    val containsModuleText: Boolean,
    val containsFunctionText: Boolean,
    val textSummary: String,
    val error: String?,
) {
    val isSuccess: Boolean
        get() = conversionState in setOf("show-editor", "show-lite-editor", "show-multifile-editor")
}

internal data class HexanaComponentDiffState(
    val fileName: String,
    val leftName: String,
    val rightName: String,
    val totalPairings: Int,
    val differingCount: Int,
    val pairings: List<HexanaComponentDiffPairing>,
)

internal data class HexanaComponentDiffPairing(
    val index: Int,
    val kind: String,
    val status: String,
    val label: String,
)

internal data class HexanaArchiveState(
    val fileName: String,
    val memberCount: Int,
    val openableCount: Int,
    val members: List<HexanaArchiveMemberState>,
)

internal data class HexanaNativeDiffState(
    val status: String,
    val changedRowCount: Int,
    val sections: List<String>,
    val error: String?,
)

internal data class HexanaArchiveMemberState(
    val index: Int,
    val name: String,
    val format: String,
    val size: Long,
    val openable: Boolean,
)

@Remote(
    "org.jetbrains.hexana.uitestframework.HexanaEditorUiTestProbe",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface HexanaEditorUiTestProbeRef {
    fun frameworkVersion(): String
    fun ping(): String
    fun openAbsoluteFile(path: String): List<String>
    fun openAbsoluteFileWithScriptTab(path: String, scriptPath: String): List<String>
    fun activateStructureToolWindow(fileName: String): List<String>
    fun openComponentDiff(leftPath: String, rightPath: String): List<String>
    fun openNativeDiff(leftPath: String, rightPath: String): List<String>
    fun selectTab(fileName: String, tabName: String): List<String>
    fun setEditorTopFraction(fileName: String, topFraction: Float): List<String>
    fun componentDiffState(fileName: String): List<String>
    fun archiveState(fileName: String): List<String>
    fun nativeDiffState(fileName: String): List<String>
    fun editorSnapshot(fileName: String): List<String>
    fun searchState(fileName: String): List<String>
    fun watState(fileName: String): List<String>
}

package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.closeToolWindow
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.ActionButtonUi
import com.intellij.driver.sdk.ui.components.elements.JTextFieldUI
import com.intellij.driver.sdk.ui.components.elements.actionButton
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.waitSelected
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import org.jetbrains.hexana.ui.FakePostHogServer
import org.jetbrains.hexana.ui.HexanaUiDriverTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.awt.event.KeyEvent
import java.nio.file.Path
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val HEXANA_PLUGIN_ID = "org.jetbrains.hexana"
private const val HEX_VIEW_FIRST_OFFSET_TEXT = "00000000"
private const val HEX_SEARCH_FIELD_ACCESSIBLE_NAME = "Hexana search field"
private const val HEX_SEARCH_BINARY_MODE_ACCESSIBLE_NAME = "Hexana search binary mode"
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

    internal fun applyToContext(context: IDETestContext): IDETestContext =
        contextPatches.fold(context) { patchedContext, patch -> patch(patchedContext) }
}

internal class HexanaIde internal constructor(
    val context: IDETestContext,
    val driver: Driver,
) {
    fun closeProjectToolWindow() {
        driver.closeToolWindow("Project")
    }

    fun openFile(fileName: String): HexanaEditor {
        driver.openFile(fileName, waitForCodeAnalysis = false, isTextEditor = false)
        return HexanaEditor(driver, fileName, context.projectRelativeFileIdentifier(fileName))
    }

    fun openWasm(fileName: String = HexanaUiDriverTestSupport.wasmFileName): HexanaEditor =
        openFile(fileName)

    fun hexanaEditor(
        fileName: String = HexanaUiDriverTestSupport.wasmFileName,
        action: HexanaEditor.() -> Unit = {},
    ) {
        val editor = openFile(fileName)
        driver.ideFrame {
            editorTabs {
                tab(fileName, fullMatch = false).waitFound()
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

    private val accessibilityProbe: HexanaAccessibilityUiTestProbeRef
        get() = driver.utility(HexanaAccessibilityUiTestProbeRef::class)

    fun snapshot(): HexanaEditorSnapshot =
        HexanaProbePayload.snapshot(probe.editorSnapshot(fileIdentifier))

    fun searchState(): HexanaEditorSearchState =
        HexanaProbePayload.searchState(probe.searchState(fileIdentifier))

    fun performSearchThroughUi(text: String, binaryMode: Boolean = false): HexanaEditorSearchState {
        val searchField = openSearchThroughUi()
        driver.ui.actionButton { byAccessibleName(HEX_SEARCH_BINARY_MODE_ACCESSIBLE_NAME) }
            .waitFound(30.seconds)
            .setSelectedIfNeeded(binaryMode)

        searchField.setFocus()
        searchField.text = text

        return waitFor(
            message = "Waiting for Hex search state after UI-driven query '$text'",
            timeout = 30.seconds,
            errorMessage = { state: HexanaEditorSearchState -> "Last search state: $state" },
            getter = { searchState() },
            checker = { state: HexanaEditorSearchState ->
                state.query == text &&
                    state.binaryMode == binaryMode
            },
        )
    }

    private fun openSearchThroughUi(): JTextFieldUI {
        repeat(3) {
            focusHexViewThroughUi()
            driver.ui.keyboard {
                hotKey(platformShortcutKeyCode(), KeyEvent.VK_F)
            }
            runCatching {
                driver.ui.textField { byAccessibleName(HEX_SEARCH_FIELD_ACCESSIBLE_NAME) }
                    .waitFound(10.seconds)
            }.getOrNull()?.let { return it }
        }

        return driver.ui.textField { byAccessibleName(HEX_SEARCH_FIELD_ACCESSIBLE_NAME) }
            .waitFound(30.seconds)
    }

    private fun focusHexViewThroughUi() {
        driver.ui.x { byText(HEX_VIEW_FIRST_OFFSET_TEXT) }
            .waitFound(30.seconds)
            .click()
    }

    fun searchThroughUi(text: String, binaryMode: Boolean = false): HexanaEditorSearchState {
        performSearchThroughUi(text, binaryMode)
        return waitFor(
            message = "Waiting for Hex search results after UI-driven query '$text'",
            timeout = 30.seconds,
            errorMessage = { state: HexanaEditorSearchState -> "Last search state: $state" },
            getter = { searchState() },
            checker = { state: HexanaEditorSearchState ->
                state.query == text &&
                    state.binaryMode == binaryMode &&
                    state.resultCount > 0 &&
                    state.currentResultIndex >= 0 &&
                    state.cursorOffset != null &&
                    state.cursorOffset >= 0
            },
        )
    }

    fun closeSearchThroughUi(): HexanaEditorSearchState {
        driver.ui.keyboard {
            escape()
        }

        return waitFor(
            message = "Waiting for Hex search state to clear after Escape",
            timeout = 30.seconds,
            errorMessage = { state: HexanaEditorSearchState -> "Last search state: $state" },
            getter = { searchState() },
            checker = { state: HexanaEditorSearchState ->
                state.query.isEmpty() &&
                    state.resultCount == 0 &&
                    state.currentResultIndex == -1
            },
        )
    }

    private fun ActionButtonUi.setSelectedIfNeeded(selected: Boolean) {
        if (isSelected != selected) {
            performAction()
            waitSelected(selected)
        }
    }

    fun watState(): HexanaEditorWatState =
        HexanaProbePayload.watState(probe.watState(fileIdentifier))

    fun selectTabThroughUi(tab: HexanaEditorTab) {
        selectTabThroughUi(tab.tabName)
    }

    fun selectTabThroughUi(tabName: String) {
        clickTabThroughUi(tabName)
        if (waitForSelectedTab(tabName, timeout = 5.seconds)) {
            return
        }

        selectTabThroughKeyboard(tabName)
        awaitSelectedTab(tabName, timeout = 30.seconds)
    }

    private fun clickTabThroughUi(tabName: String) {
        val accessibleName = hexanaEditorTabAccessibleName(tabName)
        driver.ui.x { byAttribute("contentdescription", accessibleName) }
            .waitFound(30.seconds)
            .click()
    }

    private fun selectTabThroughKeyboard(tabName: String) {
        val snapshot = snapshot()
        val currentTabName = snapshot.selectedTabName ?: return
        val currentIndex = snapshot.panelNames.indexOf(currentTabName)
        val targetIndex = snapshot.panelNames.indexOf(tabName)
        if (currentIndex < 0 || targetIndex < 0 || currentIndex == targetIndex) {
            return
        }

        driver.ui.x { byAttribute("contentdescription", hexanaEditorTabAccessibleName(currentTabName)) }
            .waitFound(30.seconds)
            .setFocus()
        val keyCode = if (targetIndex > currentIndex) KeyEvent.VK_RIGHT else KeyEvent.VK_LEFT
        repeat(abs(targetIndex - currentIndex)) {
            driver.ui.keyboard {
                key(keyCode)
            }
        }
    }

    private fun waitForSelectedTab(tabName: String, timeout: Duration): Boolean =
        runCatching {
            awaitSelectedTab(tabName, timeout)
        }.isSuccess

    private fun awaitSelectedTab(tabName: String, timeout: Duration): HexanaEditorSnapshot =
        waitFor(
            message = "Waiting for Hexana editor tab '$tabName' to become selected",
            timeout = timeout,
            errorMessage = { snapshot: HexanaEditorSnapshot -> "Last editor snapshot: $snapshot" },
            getter = { snapshot() },
            checker = { snapshot: HexanaEditorSnapshot -> snapshot.selectedTabName == tabName },
        )

    fun expect(action: HexanaEditorExpectations.() -> Unit) {
        HexanaEditorExpectations(this, probe, accessibilityProbe).action()
    }
}

internal class HexanaEditorExpectations internal constructor(
    private val editor: HexanaEditor,
    private val probe: HexanaEditorUiTestProbeRef,
    private val accessibilityProbe: HexanaAccessibilityUiTestProbeRef,
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
        tabsContain(*expectedTabs.tabNames())
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

    fun renderedTabsContain(vararg expectedTabs: HexanaEditorTab) {
        renderedTabsContain(*expectedTabs.tabNames())
    }

    fun renderedTabsContain(vararg expectedTabs: String) {
        val expectedAccessibleNames = expectedTabs.map(::hexanaEditorTabAccessibleName)
        waitFor(
            message = "Finding rendered Hexana editor tabs through accessibility",
            timeout = 30.seconds,
            errorMessage = { names: List<String> ->
                "Expected accessible tabs $expectedAccessibleNames; accessible names: $names"
            },
            getter = { accessibilityProbe.accessibleNames() },
            checker = { names: List<String> -> names.containsAll(expectedAccessibleNames) },
        )
    }

    fun tabsDoNotContain(vararg unexpectedTabs: HexanaEditorTab) {
        tabsDoNotContain(*unexpectedTabs.tabNames())
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

    fun searchFinds(text: String, binaryMode: Boolean = false) {
        val state = editor.searchThroughUi(text, binaryMode)
        assertEquals(text, state.query)
        assertEquals(binaryMode, state.binaryMode)
        assertTrue(
            state.resultCount > 0,
            "Expected search '$text' to find at least one match in ${editor.fileIdentifier}; state: $state",
        )
        assertTrue(
            state.currentResultIndex >= 0,
            "Expected search '$text' to select a current match in ${editor.fileIdentifier}; state: $state",
        )
        assertTrue(
            state.cursorOffset != null && state.cursorOffset >= 0,
            "Expected search '$text' to move the cursor in ${editor.fileIdentifier}; state: $state",
        )
    }

    fun searchDoesNotFind(text: String, binaryMode: Boolean = false) {
        val state = editor.performSearchThroughUi(text, binaryMode)
        assertEquals(text, state.query)
        assertEquals(binaryMode, state.binaryMode)
        assertEquals(
            0,
            state.resultCount,
            "Expected search '$text' to find no matches in ${editor.fileIdentifier}; state: $state",
        )
        assertEquals(
            -1,
            state.currentResultIndex,
            "Expected search '$text' to have no current match in ${editor.fileIdentifier}; state: $state",
        )
    }

    fun searchClosesAndClears() {
        val state = editor.closeSearchThroughUi()
        assertEquals(
            "",
            state.query,
            "Expected Escape to clear Hex search query for ${editor.fileIdentifier}; state: $state",
        )
        assertEquals(
            0,
            state.resultCount,
            "Expected Escape to clear Hex search results for ${editor.fileIdentifier}; state: $state",
        )
        assertEquals(
            -1,
            state.currentResultIndex,
            "Expected Escape to clear Hex search current index for ${editor.fileIdentifier}; state: $state",
        )
    }

    fun watConversionContainsModuleAndFunctionText() {
        renderedTabsContain(HexanaEditorTab.WAT)
        editor.selectTabThroughUi(HexanaEditorTab.WAT)
        val state = waitFor(
            message = "Waiting for UI-driven WAT conversion for ${editor.fileIdentifier}",
            timeout = 2.minutes,
            errorMessage = { state: HexanaEditorWatState -> "Last WAT state: $state" },
            getter = { editor.watState() },
            checker = { state: HexanaEditorWatState ->
                state.selectedTabName == HexanaEditorTab.WAT.tabName &&
                    state.isSuccess &&
                    state.containsModuleText &&
                    state.containsFunctionText
            },
        )

        assertEquals(HexanaEditorTab.WAT.tabName, state.selectedTabName)
        assertTrue(
            state.isSuccess,
            "Expected WAT conversion to succeed for ${editor.fileIdentifier}; state: $state",
        )
        assertTrue(
            state.containsModuleText,
            "Expected WAT conversion for ${editor.fileIdentifier} to contain module text; state: $state",
        )
        assertTrue(
            state.containsFunctionText,
            "Expected WAT conversion for ${editor.fileIdentifier} to contain function text; state: $state",
        )
    }
}

internal enum class HexanaProviderKind(val id: String) {
    CORE_WASM("core-wasm"),
    COMPONENT_WASM("component-wasm"),
    GENERIC("generic"),
}

internal enum class HexanaEditorTab(val tabName: String) {
    FUNCTIONS("Functions"),
    EXPORTS("Exports"),
    IMPORTS("Imports"),
    MODULES("Modules"),
    MONOS("Monos"),
    GARBAGE("Garbage"),
    DOMINATORS("Dominators"),
    TOP("Top"),
    WAT("Wat"),
    WAT_VIRTUALIZED("WAT (virtualized)"),
}

private fun Array<out HexanaEditorTab>.tabNames(): Array<String> =
    map { it.tabName }.toTypedArray()

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

private fun platformShortcutKeyCode(): Int =
    if (System.getProperty("os.name").lowercase().startsWith("mac")) KeyEvent.VK_META else KeyEvent.VK_CONTROL

private fun hexanaEditorTabAccessibleName(tabName: String): String =
    "Hexana editor tab $tabName"

internal data class HexanaEditorSnapshot(
    val fileName: String,
    val providerKind: String,
    val panelNames: List<String>,
    val selectedTabName: String?,
    val cursorOffset: Int?,
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

@Remote(
    "org.jetbrains.hexana.uitestframework.HexanaEditorUiTestProbe",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface HexanaEditorUiTestProbeRef {
    fun frameworkVersion(): String
    fun ping(): String
    fun editorSnapshot(fileName: String): List<String>
    fun searchState(fileName: String): List<String>
    fun watState(fileName: String): List<String>
}

@Remote(
    "org.jetbrains.hexana.uitestframework.HexanaAccessibilityUiTestProbe",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface HexanaAccessibilityUiTestProbeRef {
    fun accessibleNames(): List<String>
}

package org.jetbrains.hexana.webapp

import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import org.jetbrains.hexana.vscode.BinaryEditorContent
import org.jetbrains.hexana.vscode.Host
import org.jetbrains.hexana.vscode.LocalHost
import org.jetbrains.hexana.vscode.LocalTabsPanelDock
import org.jetbrains.hexana.vscode.TabsPanelDock
import org.jetbrains.hexana.vscode.computeExtraTabs
import org.khronos.webgl.Int8Array
import org.w3c.fetch.RequestInit
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLStyleElement
import org.w3c.fetch.Response
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private var dockedDomRootSequence = 0

/**
 * The analysis tabs docked to the right of the hex view (VS Code's `hexana.tabsPanelPosition`), laid
 * out by the browser with the real `editor.css`: a narrow panel must keep tables and the IL listing
 * readable and let them scroll sideways, and expanding must leave only a strip of the hex view.
 */
@OptIn(DelicateCoroutinesApi::class)
class DockedTabsLayoutDomTest {

    @Test
    fun dockedPanelListsTabsVerticallyOnItsRightEdge(): Promise<Unit> = GlobalScope.promise {
        withDockedEditor { root ->
            val panel = awaitElement(root, "the docked panel") { root.querySelector(".hexana-panel-docked-right") as? HTMLElement }
            val list = awaitElement(root, "the vertical tab list") { root.querySelector(".hexana-vertical-tab-list") as? HTMLElement }
            val content = panel.querySelector(".hexana-tab-content") as HTMLElement
            assertTrue(list.rect().left >= content.rect().right - 1, "tab list ${list.rect().left} is not right of the content ${content.rect().right}")
            assertTrue(panel.rect().right >= root.rect().right - 1, "panel does not reach the right edge")
            assertNotNull(verticalTab(root, "Methods"))
            assertNotNull(verticalTab(root, "IL Disassembly"))
        }
    }

    @Test
    fun narrowPanelKeepsTableColumnsReadableAndScrollsSideways(): Promise<Unit> = GlobalScope.promise {
        withDockedEditor { root ->
            awaitElement(root, "the Methods tab") { verticalTab(root, "Methods") }.click()
            val headers = awaitList(root, "the Methods table headers") { elements(root, ".hexana-panel-docked-right th.panel-th") }
            // Only the shown table: a tab can keep other, hidden tables in the DOM.
            val labelled = headers.filter { it.textContent.orEmpty().trim().isNotEmpty() && it.rect().height > 0 }
            val heights = labelled.map { it.rect().height }
            assertTrue(heights.max() - heights.min() < 1.0, "header heights differ (wrapped?): $heights")
            for (th in labelled) {
                assertTrue(th.rect().width >= MIN_READABLE_COLUMN_PX, "column '${th.textContent}' is ${th.rect().width}px wide")
            }
            val table = headers.first().closest("table") as HTMLElement
            val scroller = table.parentElement as HTMLElement
            // The default docked width cannot fit every column: the table must be wider than its
            // viewport and the viewport must actually scroll to it.
            assertTrue(table.rect().width > scroller.clientWidth, "table ${table.rect().width}px fits ${scroller.clientWidth}px; the test no longer exercises overflow")
            scroller.scrollLeft = 10_000.0
            delay(20)
            assertTrue(scroller.scrollLeft > 0, "the table viewport does not scroll sideways")
        }
    }

    @Test
    fun narrowPanelKeepsIlLinesOnOneRow(): Promise<Unit> = GlobalScope.promise {
        withDockedEditor { root ->
            awaitElement(root, "the IL Disassembly tab") { verticalTab(root, "IL Disassembly") }.click()
            // The row itself: the innermost element holding the whole header text.
            val header = awaitElement(root, "a type header") {
                elements(root, ".hexana-panel-docked-right .hexana-tab-content div")
                    .filter { it.textContent.orEmpty().contains("class Hexana.Sample.Greeter") && it.children.length >= 3 }
                    .minByOrNull { it.textContent.orEmpty().length * 100_000 + it.querySelectorAll("div").length }
                    ?.takeIf { it.querySelectorAll("div").length == 0 }
            }
            // A fixed-height virtualized row: content taller than the row would spill over the next one.
            assertTrue(header.scrollHeight <= header.clientHeight + 1, "type header wraps: ${header.scrollHeight}px in ${header.clientHeight}px")
            for (i in 0 until header.children.length) {
                val span = header.children.item(i) as HTMLElement
                assertTrue(span.rect().height <= header.clientHeight + 1, "'${span.textContent}' wraps to ${span.rect().height}px")
            }
        }
    }

    @Test
    fun expandingLeavesOnlyAStripOfTheHexView(): Promise<Unit> = GlobalScope.promise {
        withDockedEditor { root ->
            val panel = awaitElement(root, "the docked panel") { root.querySelector(".hexana-panel-docked-right") as? HTMLElement }
            val expand = awaitElement(root, "the expand button") {
                root.querySelector("[title='Expand the tabs over the hex view']") as? HTMLElement
            }
            expand.click()
            awaitElement(root, "the restore button") { root.querySelector("[title='Restore the hex view']") as? HTMLElement }
            delay(50)
            val hexColumnWidth = panel.rect().left - root.rect().left
            assertTrue(hexColumnWidth in 8.0..20.0, "the hex view keeps ${hexColumnWidth}px")
        }
    }
}

private const val MIN_READABLE_COLUMN_PX = 40.0

private suspend fun withDockedEditor(block: suspend (HTMLElement) -> Unit) {
    val css = window.fetch("/kotlin/layout-fixtures/editor.css", js("({})").unsafeCast<RequestInit>()).await().text().await()
    val style = (document.createElement("style") as HTMLStyleElement).also {
        it.textContent = css
        document.head!!.appendChild(it)
    }
    val response: Response = window.fetch("/kotlin/layout-fixtures/sample-clr.exe", js("({})").unsafeCast<RequestInit>()).await()
    val bytes = Int8Array(response.arrayBuffer().await()).let { array ->
        ByteArray(array.length) { i -> array.asDynamic()[i] as Byte }
    }
    val provider = nativeOrGenericBinaryProvider(bytes.toCommonByteBuffer())
    val extraTabs = computeExtraTabs(provider.bytes.duplicate().position(0), provider)
    val root = document.createElement("div").unsafeCast<HTMLDivElement>().also {
        it.id = "docked-tabs-test-root-${dockedDomRootSequence++}"
        it.style.width = "1000px"
        it.style.height = "600px"
        it.style.display = "flex"
        it.style.flexDirection = "column"
    }
    document.body!!.appendChild(root)
    var composition: Composition? = null
    try {
        composition = renderComposable(rootElementId = root.id) {
            CompositionLocalProvider(
                LocalHost provides LayoutTestHost(),
                LocalTabsPanelDock provides TabsPanelDock(onRight = true) {},
            ) {
                BinaryEditorContent(fileName = "sample-clr.exe", provider = provider, extraTabs = extraTabs)
            }
        }
        block(root)
    } finally {
        composition?.dispose()
        root.parentNode?.removeChild(root)
        style.parentNode?.removeChild(style)
    }
}

private fun HTMLElement.rect() = getBoundingClientRect()

private fun verticalTab(root: HTMLElement, label: String): HTMLElement? =
    elements(root, ".hexana-vertical-tab").firstOrNull { it.textContent.orEmpty().startsWith(label) }

private fun elements(root: HTMLElement, selector: String): List<HTMLElement> {
    val nodes = root.querySelectorAll(selector)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? HTMLElement }
}

private suspend fun <T : Any> awaitElement(root: HTMLElement, what: String, find: () -> T?): T {
    repeat(200) {
        find()?.let { return it }
        delay(20)
    }
    error("timed out waiting for $what in: ${root.textContent.orEmpty().take(400)}")
}

private suspend fun awaitList(root: HTMLElement, what: String, find: () -> List<HTMLElement>): List<HTMLElement> =
    awaitElement(root, what) { find().takeIf { it.isNotEmpty() } }.also { delay(50) }

private class LayoutTestHost : Host {
    override fun showWat(watText: String, warning: String?) = Unit
    override fun runExport(exportName: String, invokeArgs: String, programArgs: String, isComponent: Boolean, wasmBytes: ByteArray?, runtime: String?, action: String?) = Unit
    override fun tabSelected(tabName: String) = Unit
    override fun updateRuntimeSetting(runtime: String) = Unit
    override fun navigateToParent() = Unit
    override fun saveAs() = Unit
    override fun openZipEntry(name: String, dataOffset: Long, compressedSize: Long, uncompressedSize: Long, compressionMethod: Int) = Unit
    override fun openTarEntry(name: String, dataOffset: Long, size: Long) = Unit
    override fun overwriteFile(bytes: ByteArray) = Unit
    override fun saveCopy(outputName: String, bytes: ByteArray) = Unit
    override fun openUrl(url: String) = Unit
    override fun openModule(moduleIndex: Int, fileName: String, data: ByteArray) = Unit
    override fun openDts(raw: Boolean) = Unit
    override fun openHttpBody(fileName: String, bodyBytes: ByteArray, httpContentType: String?, httpContentEncoding: String?) = Unit
    override fun openTextFile(fileName: String, content: String) = Unit
    override fun pickScriptFile() = Unit
    override fun createScriptFromTemplate(fileName: String, template: String?, jsConfigContent: String?) = Unit
    override fun openScriptForEditing(scriptPath: String, jsConfigContent: String?) = Unit
    override fun readScriptFile(scriptPath: String) = Unit
    override fun scriptTabAdded(scriptPath: String) = Unit
    override fun scriptTabRemoved(scriptPath: String) = Unit
    override fun ready() = Unit
}

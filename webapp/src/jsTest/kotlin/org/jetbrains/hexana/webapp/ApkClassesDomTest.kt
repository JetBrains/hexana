package org.jetbrains.hexana.webapp

import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.vscode.BinaryEditorContent
import org.jetbrains.hexana.vscode.Host
import org.jetbrains.hexana.vscode.LocalHost
import org.jetbrains.hexana.vscode.TableRowSelectionRequest
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import org.w3c.fetch.Response
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private var apkDomRootSequence = 0

@OptIn(DelicateCoroutinesApi::class)
class ApkClassesDomTest {

    @Test
    fun apkClassesTabListsEveryDexAndRevealsAClassInItsDex(): Promise<Unit> = GlobalScope.promise {
        val dex = minimalDex(listOf("Lcom/example/A;", "Lcom/example/B;"))
        val dex2 = minimalDex(listOf("Lcom/example/C;"))
        // classes2.dex is deflated, so the tab has to inflate it.
        val apk = storedZip(
            listOf(
                ZipFixtureEntry("AndroidManifest.xml", byteArrayOf(0), byteArrayOf(0), method = 0),
                ZipFixtureEntry("classes.dex", dex, dex, method = 0),
                ZipFixtureEntry("classes2.dex", deflateRaw(dex2), dex2, method = 8),
            ),
        )
        val host = RevealRecordingHost()
        withRendered({
            CompositionLocalProvider(LocalHost provides host) {
                BinaryEditorContent(fileName = "app.apk", provider = ZipBinaryProvider(apk.toCommonByteBuffer()))
            }
        }) { root ->
            // Listed, but nothing is read before the tab is opened.
            val classesTab = awaitElement(root, "the Classes tab") { tab(root, "Classes") }
            assertTrue(!root.textContent.orEmpty().contains("Lcom/example/"), "classes were read before the tab was opened")

            classesTab.click()
            awaitElement(root, "the classes of both DEX files") {
                root.takeIf { it.textContent.orEmpty().contains("Lcom/example/C;") }
            }
            val rows = rows(root)
            assertEquals(3, rows.size)
            assertTrue(rows[2].textContent.orEmpty().contains("classes2.dex"))

            rows[2].dispatchEvent(MouseEvent("contextmenu", MouseEventInit(bubbles = true, cancelable = true, clientX = 10, clientY = 10)))
            val reveal = awaitElement(root, "the Reveal in DEX menu item") {
                elements(document.body!!, ".hexana-context-menu-item").firstOrNull { it.textContent == "Reveal in DEX" }
            }
            reveal.click()
            assertEquals(listOf("classes2.dex:Classes:0"), host.reveals)
        }
    }

    @Test
    fun aRowSelectionRequestSelectsThatClassOnTheDexClassesTab(): Promise<Unit> = GlobalScope.promise {
        val dex = minimalDex(listOf("Lcom/example/A;", "Lcom/example/B;", "Lcom/example/C;"))
        val request: MutableState<TableRowSelectionRequest?> = mutableStateOf(null)
        withRendered({
            CompositionLocalProvider(LocalHost provides RevealRecordingHost()) {
                BinaryEditorContent(
                    fileName = "classes.dex",
                    provider = DexBinaryProvider(dex.toCommonByteBuffer()),
                    tableRowSelection = request.value,
                )
            }
        }) { root ->
            awaitElement(root, "the DEX editor") { tab(root, "Classes") }
            request.value = TableRowSelectionRequest("Classes", 2, requestId = 1)
            val focused = awaitElement(root, "the selected class") { root.querySelector("tr[data-focused]") as? HTMLElement }
            assertEquals("Classes", root.querySelector(".hexana-tab-active")?.textContent)
            assertTrue(focused.textContent.orEmpty().contains("Lcom/example/C;"), focused.textContent)

            // A later request moves the selection.
            request.value = TableRowSelectionRequest("Classes", 0, requestId = 2)
            awaitElement(root, "the newly selected class") {
                (root.querySelector("tr[data-focused]") as? HTMLElement)
                    ?.takeIf { it.textContent.orEmpty().contains("Lcom/example/A;") }
            }
            assertNull(elements(root, "tr[data-focused]").getOrNull(1))
        }
    }

    @Test
    fun aRowSelectionRequestClearsASearchThatHidesTheClass(): Promise<Unit> = GlobalScope.promise {
        val dex = minimalDex(listOf("Lcom/example/A;", "Lcom/example/B;", "Lcom/example/C;"))
        // Built once: a provider made during composition would be a new file on every
        // recomposition, and reset the search this test is about.
        val provider = DexBinaryProvider(dex.toCommonByteBuffer())
        val request: MutableState<TableRowSelectionRequest?> = mutableStateOf(null)
        withRendered({
            CompositionLocalProvider(LocalHost provides RevealRecordingHost()) {
                BinaryEditorContent(
                    fileName = "classes.dex",
                    provider = provider,
                    tableRowSelection = request.value,
                )
            }
        }) { root ->
            awaitElement(root, "the DEX editor") { tab(root, "Classes") }.click()
            val search = awaitElement(root, "the search field") {
                root.querySelector(".hexana-search-input") as? HTMLInputElement
            }
            search.value = "example/A"
            search.dispatchEvent(Event("input", org.w3c.dom.EventInit(bubbles = true)))
            awaitElement(root, "the search to hide class C") {
                root.takeIf { rows(it).size == 1 && !it.textContent.orEmpty().contains("Lcom/example/C;") }
            }

            request.value = TableRowSelectionRequest("Classes", 2, requestId = 1)
            awaitElement(root, "the selected class") {
                (root.querySelector("tr[data-focused]") as? HTMLElement)
                    ?.takeIf { it.textContent.orEmpty().contains("Lcom/example/C;") }
            }
            // The same search field, cleared, rather than a table built afresh.
            assertTrue(search.isConnected)
            assertEquals("", (root.querySelector(".hexana-search-input") as HTMLInputElement).value)
        }
    }
}

private class RevealRecordingHost : Host {
    val reveals = mutableListOf<String>()
    override val canRevealZipEntryRow: Boolean get() = true
    override fun revealZipEntryRow(
        name: String, dataOffset: Long, compressedSize: Long, uncompressedSize: Long, compressionMethod: Int,
        tabName: String, rowIndex: Int,
    ) {
        reveals += "$name:$tabName:$rowIndex"
    }
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

private suspend fun withRendered(
    content: @androidx.compose.runtime.Composable () -> Unit,
    block: suspend (HTMLElement) -> Unit,
) {
    val root = document.createElement("div").unsafeCast<HTMLDivElement>().also {
        it.id = "apk-classes-test-root-${apkDomRootSequence++}"
        it.style.height = "800px"
        it.style.display = "flex"
        it.style.flexDirection = "column"
    }
    document.body!!.appendChild(root)
    var composition: Composition? = null
    try {
        composition = renderComposable(rootElementId = root.id) { content() }
        block(root)
    } finally {
        composition?.dispose()
        root.parentNode?.removeChild(root)
    }
}

private suspend fun <T : Any> awaitElement(root: HTMLElement, what: String, find: () -> T?): T {
    repeat(200) {
        find()?.let { return it }
        delay(25)
    }
    error("Timed out waiting for $what; the page reads: ${root.textContent.orEmpty().take(400)}")
}

private fun tab(root: HTMLElement, label: String): HTMLElement? =
    elements(root, ".hexana-tab").firstOrNull { it.textContent == label }

private fun rows(root: HTMLElement): List<HTMLElement> =
    elements(root, ".hexana-panel-row").filter { it.textContent.orEmpty().contains("Lcom/example/") }

private fun elements(root: HTMLElement, selector: String): List<HTMLElement> {
    val nodes = root.querySelectorAll(selector)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? HTMLElement }
}

/**
 * A DEX the parser accepts, declaring one public class per descriptor in [classes], each
 * extending `java.lang.Object` and without class data.
 */
private fun minimalDex(classes: List<String>): ByteArray {
    val strings = classes + "Ljava/lang/Object;"
    val headerSize = 0x70
    val stringIdsOff = headerSize
    val typeIdsOff = stringIdsOff + strings.size * 4
    val classDefsOff = typeIdsOff + strings.size * 4
    val dataOff = classDefsOff + classes.size * 32
    val stringData = strings.map { byteArrayOf(it.length.toByte()) + it.encodeToByteArray() + 0 }
    val out = ByteArray(dataOff + stringData.sumOf { it.size })
    fun u4(at: Int, value: Int) {
        for (i in 0 until 4) out[at + i] = (value ushr (8 * i)).toByte()
    }
    "dex\n035\u0000".encodeToByteArray().copyInto(out)
    u4(0x20, out.size)
    u4(0x24, headerSize)
    u4(0x28, 0x12345678)
    u4(0x38, strings.size); u4(0x3C, stringIdsOff)
    u4(0x40, strings.size); u4(0x44, typeIdsOff)
    u4(0x60, classes.size); u4(0x64, classDefsOff)
    u4(0x68, out.size - dataOff); u4(0x6C, dataOff)
    var at = dataOff
    stringData.forEachIndexed { i, bytes ->
        u4(stringIdsOff + i * 4, at)
        u4(typeIdsOff + i * 4, i)
        bytes.copyInto(out, at)
        at += bytes.size
    }
    classes.indices.forEach { i ->
        val def = classDefsOff + i * 32
        u4(def, i) // class_idx
        u4(def + 4, 0x1) // public
        u4(def + 8, strings.lastIndex) // superclass: java.lang.Object
        u4(def + 16, -1) // no source file
    }
    return out
}

private class ZipFixtureEntry(val name: String, val data: ByteArray, val uncompressed: ByteArray, val method: Int)

/** A ZIP of [entries] as given — compressed or not; CRCs are left zero, which nothing here checks. */
private fun storedZip(entries: List<ZipFixtureEntry>): ByteArray {
    val out = mutableListOf<Byte>()
    fun u2(v: Int) { out += (v and 0xFF).toByte(); out += ((v shr 8) and 0xFF).toByte() }
    fun u4(v: Int) { u2(v and 0xFFFF); u2((v ushr 16) and 0xFFFF) }
    val offsets = mutableListOf<Int>()
    for (e in entries) {
        offsets += out.size
        val name = e.name.encodeToByteArray()
        u4(0x04034b50); u2(20); u2(0); u2(e.method); u2(0); u2(0); u4(0)
        u4(e.data.size); u4(e.uncompressed.size); u2(name.size); u2(0)
        out += name.toList(); out += e.data.toList()
    }
    val cdOffset = out.size
    entries.forEachIndexed { i, e ->
        val name = e.name.encodeToByteArray()
        u4(0x02014b50); u2(20); u2(20); u2(0); u2(e.method); u2(0); u2(0); u4(0)
        u4(e.data.size); u4(e.uncompressed.size); u2(name.size); u2(0); u2(0); u2(0); u2(0); u4(0)
        u4(offsets[i])
        out += name.toList()
    }
    val cdSize = out.size - cdOffset
    u4(0x06054b50); u2(0); u2(0); u2(entries.size); u2(entries.size); u4(cdSize); u4(cdOffset); u2(0)
    return out.toByteArray()
}

private external class CompressionStream(format: String) {
    val readable: dynamic
    val writable: dynamic
}

/** Raw-deflates [data] with the browser's own compressor. */
private suspend fun deflateRaw(data: ByteArray): ByteArray {
    val int8 = data.unsafeCast<Int8Array>()
    val stream = CompressionStream("deflate-raw")
    val writer = stream.writable.getWriter()
    writer.write(Uint8Array(int8.buffer, int8.byteOffset, data.size))
    writer.close()
    val compressed = Response(stream.readable).arrayBuffer().await()
    return Int8Array(compressed).unsafeCast<ByteArray>()
}

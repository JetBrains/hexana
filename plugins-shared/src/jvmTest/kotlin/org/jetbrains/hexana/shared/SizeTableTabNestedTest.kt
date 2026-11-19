package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Top tab of a component breaks its embedded modules and nested components down in place,
 * nested under their parent row, instead of one opaque `Module` / `Component` row the user has to
 * open in a separate tab.
 */
class SizeTableTabNestedTest {

    private fun topTab(provider: BinaryProvider): TableTabDescriptor = SizeTableTabsProvider().tableTabs(provider).single()

    private fun topTab(bytes: ByteArray, name: String): TableTabDescriptor =
        topTab(createBinaryProvider(bytes.toCommonByteBuffer(), name))

    private fun TableTabDescriptor.names(): List<String> = (0 until rowCount).map { columns[0].cellProvider(it).representation }

    private fun TableTabChildRows.names(): List<String> = (0 until rowCount).map { columns[0].cellProvider(it).representation }

    private fun TableTabDescriptor.row(name: String): Int = names().indexOf(name).also { assertTrue(it >= 0, "no row '$name' in ${names()}") }

    private fun TableTabChildRows.row(name: String): Int = names().indexOf(name).also { assertTrue(it >= 0, "no row '$name' in ${names()}") }

    private fun TableTabDescriptor.children(name: String): TableTabChildRows = assertNotNull(childrenProvider!!(row(name)), name)

    private fun TableTabChildRows.children(name: String): TableTabChildRows = assertNotNull(childrenProvider!!(row(name)), name)

    private fun TableTabChildRows.selection(name: String): RowSelectionData = assertNotNull(rowSelectionProvider(row(name)), name)

    private fun TreemapNode.child(name: String): TreemapNode =
        children.singleOrNull { it.name == name } ?: error("no treemap child '$name' under '${this.name}': ${children.map { it.name }}")

    // --- synthetic components ------------------------------------------------------------

    private fun leb(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
        return out.toByteArray()
    }

    private fun section(id: Int, content: ByteArray): ByteArray = byteArrayOf(id.toByte()) + leb(content.size) + content

    private val coreHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    private val componentHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)

    private fun customSection(name: String, payloadSize: Int): ByteArray =
        section(0, leb(name.length) + name.encodeToByteArray() + ByteArray(payloadSize))

    /** A component embedding one large core module (a 1000-byte custom section) and one tiny one. */
    private val component: ByteArray =
        componentHeader + section(1, coreHeader + customSection("big", 1000)) + section(1, coreHeader)

    /** Component 0 › Component 0 › Module 0 › Custom: inner — three levels below the top row. */
    private fun deeplyNested(innerPayload: Int, fillerPayload: Int): ByteArray =
        componentHeader + customSection("filler", fillerPayload) +
            section(4, componentHeader + section(4, componentHeader + section(1, coreHeader + customSection("inner", innerPayload))))

    @Test
    fun `embedded modules are numbered rows that expand with absolute offsets`() {
        val tab = topTab(component, "c.wasm")
        assertEquals(listOf("Module 0", "Module 1"), tab.names())

        val big = tab.children("Module 0").selection("Custom: big")
        assertEquals(0x00, component[big.offset.toInt()].toInt(), "offset points at the custom section header")
        assertEquals("big", String(component, big.offset.toInt() + 4, 3), "id, 2-byte size, name length, then the name")

        val small = assertNotNull(tab.rowSelectionProvider(tab.row("Module 1")))
        assertEquals(1, component[small.offset.toInt()].toInt(), "offset points at the module section header")
        assertEquals(10L, small.size)
    }

    @Test
    fun `the treemap breaks a large nested container down past the base depth`() {
        // ~5% of the file, above the deep-treemap cut-off.
        val tab = topTab(deeplyNested(innerPayload = 100, fillerPayload = 2000), "deep.wasm")
        val inner = tab.treemapData!!.root.child("Component 0").child("Component 0").child("Module 0").child("Custom: inner")
        assertEquals(4, inner.treePath.size, "three levels below the top row")

        // The tree path addresses the same row in the table.
        var rows = assertNotNull(tab.childrenProvider!!(inner.treePath[0]))
        for (index in inner.treePath.drop(1).dropLast(1)) rows = assertNotNull(rows.childrenProvider!!(index))
        assertEquals("Custom: inner", rows.names()[inner.treePath.last()])
    }

    @Test
    fun `the treemap keeps a small nested container at the base depth`() {
        // ~0.5% of the file.
        val tab = topTab(deeplyNested(innerPayload = 100, fillerPayload = 30_000), "deep.wasm")
        val inner = tab.treemapData!!.root.child("Component 0").child("Component 0")
        assertTrue(inner.children.isEmpty(), "a small container is one block below the base depth: ${inner.children.map { it.name }}")
    }

    // --- composed.wasm -------------------------------------------------------------------

    private val composedFile = File("../wasmParser/src/commonTest/resources/component/composed.wasm")
    private val composed = composedFile.readBytes()

    /** How the IDE opens a local file: memory-mapped, so slices keep a shared read cursor. */
    private fun mappedComposed(): CommonByteBuffer =
        FileChannel.open(composedFile.toPath(), StandardOpenOption.READ).use {
            it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
        }.toCommonByteBuffer()

    @Test
    fun `composed component breaks nested components down under their rows`() {
        val tab = topTab(composed, "composed.wasm")
        for (component in 0..3) {
            val module = tab.children("Component $component").children("Module 0")
            assertTrue("Custom: .debug_str" in module.names(), "component $component: ${module.names()}")

            // Offsets are absolute in the outer file, so selecting a row highlights the right bytes.
            val code = module.selection("Code")
            assertEquals(0x0A, composed[code.offset.toInt()].toInt(), "code section id in component $component")
        }

        val treemapModule = tab.treemapData!!.root.child("Component 3").child("Module 0")
        assertTrue(treemapModule.children.any { it.name == "Custom: .debug_str" }, "the treemap draws inside nested modules")
    }

    @Test
    fun `deep treemap levels draw only large children and fold the rest`() {
        val tab = topTab(composed, "composed.wasm")
        val threshold = composed.size * TREEMAP_DEEP_SIZE_FRACTION
        // Component 3 (level 0) › Module 0 (level 1) › its sections are the first level drawn deep.
        val component = tab.treemapData!!.root.child("Component 3")
        val module = component.child("Module 0")
        val rest = module.children.single { it.name.endsWith(" smaller items") }
        assertEquals(module.treePath, rest.treePath, "the folded block selects the parent row")
        assertTrue(module.children.filter { it !== rest }.all { it.size >= threshold }, module.children.map { it.name }.toString())
        assertTrue("Custom: .debug_str" in module.children.map { it.name })

        // Folding keeps each drawn child at its true share: drawn + folded = the module's children.
        val tableChildren = tab.children("Component 3").children("Module 0")
        val tableTotal = (0 until tableChildren.rowCount).sumOf { (tableChildren.columns[1].cellProvider(it).value as Int).toLong() }
        assertEquals(tableTotal, module.children.sumOf { it.size })

        // No function is 1% of the file, so the code section is drawn as one block, not a mosaic.
        assertTrue(module.child("Code").children.isEmpty())
    }

    @Test
    fun `functions of nested modules expand from the nested module`() {
        val tab = topTab(composed, "composed.wasm")
        val module = tab.children("Component 3").children("Module 0")
        val codeSection = module.selection("Code")
        val code = module.children("Code")
        assertTrue(code.rowCount > 100)
        for (index in 0 until code.rowCount) {
            val function = assertNotNull(code.rowSelectionProvider(index))
            assertTrue(
                function.offset >= codeSection.offset && function.offset + function.size <= codeSection.offset + codeSection.size,
                "func $index lies inside its code section (absolute offsets)",
            )
        }
        assertNotNull(code.childrenProvider!!(2), "function detail resolves from the nested module")
    }

    @Test
    fun `a memory-mapped component expands nested containers on every build`() {
        // Regression: the nested slices live in the provider's cached size tree; a mapped slice keeps
        // its cursor, so the first parse left it at the end and every later build saw empty containers.
        val provider = createBinaryProvider(mappedComposed(), "composed.wasm")
        repeat(3) { build ->
            val tab = topTab(provider)
            assertTrue("Module 0" in tab.children("Component 3").names(), "build $build")
            assertTrue(tab.treemapData!!.root.child("Component 3").children.isNotEmpty(), "build $build")
        }
    }

    @Test
    fun `the Components tab lists every nested component`() {
        val provider = assertIs<WasmComponentBinaryProvider>(createBinaryProvider(mappedComposed(), "composed.wasm"))
        assertEquals(4, provider.nestedComponents.size)
        val tab = WasmComponentTableTabsProvider().tableTabs(provider).single { it.name == "Components" }
        assertEquals(4, tab.rowCount)
        val offsets = (0 until tab.rowCount).map { tab.columns[0].cellProvider(it).representation }
        assertEquals(4, offsets.toSet().size, "each row names its own section: $offsets")
        // Same order and offsets as the Top tab's Component rows.
        val top = topTab(provider)
        for (index in 0..3) {
            val section = provider.nestedComponents[index].section
            val row = assertNotNull(top.rowSelectionProvider(top.row("Component $index")))
            assertEquals(section.sectionOffset.toLong(), row.offset)
        }
    }
}

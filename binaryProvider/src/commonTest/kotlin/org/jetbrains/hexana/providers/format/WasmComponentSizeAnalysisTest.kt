package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.hexana.wasm.analysis.SizeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the size profiler (`SizeAnalysisBuilder`) against the composed-component bug: it used to
 * read the singular `componentSection`, so a component whose code lives in MULTIPLE nested
 * sub-components (e.g. composed.wasm) had most of its bytes unaccounted. The builder must enumerate
 * every nested component from the raw section list and attach each one's byte slice so the size
 * panel can expand it lazily (the breakdown is built on demand, not eagerly — eager recursion over a
 * 15 MB composed component froze the Top panel).
 */
class WasmComponentSizeAnalysisTest {

    private val componentHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)
    private val coreA = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    private val coreB = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)

    private fun wrap(vararg sections: Pair<Byte, ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        componentHeader.forEach { out.add(it) }
        for ((id, content) in sections) {
            require(content.size < 0x80)
            out.add(id)
            out.add(content.size.toByte())
            content.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    private fun sizeTree(bytes: ByteArray) =
        WasmComponentBinaryProvider(bytes.toCommonByteBuffer()).sizeAnalysis

    /** Mirrors PanelSize.lazyEmbeddedChildren: parse a node's slice and build its sub-breakdown. */
    private fun lazyChildren(slice: CommonByteBuffer?): List<Any> {
        val sub = (WasmParser().parse(slice!!) as? WasmParseSuccess)?.wasmModule ?: return emptyList()
        return SizeAnalysisBuilder(slice, sub).buildTree().children
    }

    @Test
    fun `all nested components are attributed with a lazily-expandable slice (composed-wasm shape)`() {
        // No direct modules; two nested components each holding a core module — like composed.wasm.
        val root = sizeTree(wrap(4.toByte() to wrap(1.toByte() to coreA), 4.toByte() to wrap(1.toByte() to coreB)))
        assertNotNull(root)

        val embedded = root.children.filterIsInstance<SizeNode.EmbeddedComponent>()
        assertEquals(2, embedded.size, "both nested components must appear (not just the singular field)")
        assertTrue(embedded.all { it.slice != null }, "each carries its byte slice for lazy expansion")
        assertTrue(
            embedded.all { lazyChildren(it.slice).filterIsInstance<SizeNode.EmbeddedModule>().isNotEmpty() },
            "expanding a nested component lazily yields its inner core module",
        )
    }

    @Test
    fun `direct embedded core module carries an expandable slice`() {
        val root = sizeTree(wrap(1.toByte() to coreB))
        assertNotNull(root)
        val modules = root.children.filterIsInstance<SizeNode.EmbeddedModule>()
        assertEquals(1, modules.size)
        assertNotNull(modules[0].slice)
    }

    @Test
    fun `truncated submodule ranges remain visible without expandable slices`() {
        listOf(1.toByte(), 4.toByte()).forEach { sectionId ->
            val truncated = componentHeader + byteArrayOf(sectionId, 0x40, 0x00, 0x61, 0x73, 0x6D)
            val root = sizeTree(truncated)

            assertNotNull(root)
            assertEquals(1, root.children.filterIsInstance<SizeNode.Section>().size)
            assertTrue(root.children.none { it is SizeNode.EmbeddedModule || it is SizeNode.EmbeddedComponent })
        }
    }
}

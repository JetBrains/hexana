package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Recursion tests for [ComponentCoreModuleCollector]. Fixtures are hand-built minimal component
 * binaries (the established pattern in this module): a `\0asm 0D 00 01 00` header followed by
 * id-1 module sections and id-4 component sections, nested arbitrarily. The collector must find
 * core modules at any depth AND across MULTIPLE nested components per level — the case that
 * `composed.wasm` hits and that the parser's single `componentSection` field cannot represent.
 */
class ComponentCoreModuleCollectorTest {

    private val componentHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)
    private val coreA = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    private val coreB = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)

    /** Builds a component binary from (sectionId, content) pairs. Content must be < 128 bytes. */
    private fun wrap(vararg sections: Pair<Byte, ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        componentHeader.forEach { out.add(it) }
        for ((id, content) in sections) {
            require(content.size < 0x80) { "test content must be < 128 bytes (single-byte LEB size)" }
            out.add(id)
            out.add(content.size.toByte())
            content.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    private fun provider(bytes: ByteArray) = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())

    private fun collect(bytes: ByteArray) = ComponentCoreModuleCollector.collect(provider(bytes))

    @Test
    fun `collects a direct core module`() {
        val result = collect(wrap(1.toByte() to coreA))
        assertEquals(1, result.size)
        assertEquals("module[0]", result[0].path)
        assertEquals(coreA.size, result[0].size)
        assertContentEquals(coreA, result[0].bytes.toByteArray())
    }

    @Test
    fun `recurses into a nested component`() {
        // outer: [ module(coreA), component( [module(coreB)] ) ]
        val result = collect(wrap(1.toByte() to coreA, 4.toByte() to wrap(1.toByte() to coreB)))
        assertEquals(2, result.size)
        assertEquals(listOf("module[0]", "component[0]/module[0]"), result.map { it.path })
        assertContentEquals(coreA, result[0].bytes.toByteArray())
        assertContentEquals(coreB, result[1].bytes.toByteArray())
    }

    @Test
    fun `enumerates multiple nested components at one level (composed-wasm shape)`() {
        // outer: [ component([module(coreA)]), component([module(coreB)]) ] — no direct modules,
        // exactly like composed.wasm. The singular parser field would miss one; the collector
        // walks the full section list and finds both.
        val result = collect(
            wrap(
                4.toByte() to wrap(1.toByte() to coreA),
                4.toByte() to wrap(1.toByte() to coreB),
            ),
        )
        assertEquals(2, result.size)
        assertEquals(listOf("component[0]/module[0]", "component[1]/module[0]"), result.map { it.path })
        assertContentEquals(coreA, result[0].bytes.toByteArray())
        assertContentEquals(coreB, result[1].bytes.toByteArray())
    }

    @Test
    fun `deeply nested core module is found`() {
        // outer -> component -> component -> module(coreA)
        val result = collect(wrap(4.toByte() to wrap(4.toByte() to wrap(1.toByte() to coreA))))
        assertEquals(1, result.size)
        assertEquals("component[0]/component[0]/module[0]", result[0].path)
        assertContentEquals(coreA, result[0].bytes.toByteArray())
    }

    @Test
    fun `component with no core modules yields empty`() {
        assertEquals(0, collect(componentHeader).size)
    }

    @Test
    fun `truncated submodule ranges are ignored`() {
        listOf(1.toByte(), 4.toByte()).forEach { sectionId ->
            val truncated = componentHeader + byteArrayOf(sectionId, 0x40, 0x00, 0x61, 0x73, 0x6D)
            assertEquals(emptyList(), collect(truncated))
        }
    }
}

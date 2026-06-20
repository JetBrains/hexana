package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Submodule-pairing tests for the Component Model diff. Fixtures are hand-built (the
 * established pattern in this module) minimal component binaries:
 * `\0asm 0D 00 01 00` header + id-1 module sections (and, where noted, an id-4 component
 * section) whose contents are self-contained core/component preambles. The parser records
 * each embedded section's byte range without parsing its contents, which is all the
 * orchestrator needs.
 */
class WasmComponentDiffOrchestratorTest {

    // Component header: \0asm, version=0x0D, layer=1.
    private val componentHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)

    // Minimal empty core module (8-byte preamble).
    private val coreA = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    // Same preamble + an empty custom section (id 0, size 1, name-len 0) so it differs from coreA.
    private val coreB = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)

    /** Wraps each core block as an id-1 module section inside one component binary. */
    private fun component(vararg cores: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        componentHeader.forEach { out.add(it) }
        for (core in cores) out.appendSection(0x01, core)
        return out.toByteArray()
    }

    /** A component with one core module plus one nested component (id-4) section. */
    private fun componentWithNested(core: ByteArray, nested: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        componentHeader.forEach { out.add(it) }
        out.appendSection(0x01, core)
        out.appendSection(0x04, nested)
        return out.toByteArray()
    }

    private fun ArrayList<Byte>.appendSection(id: Byte, content: ByteArray) {
        require(content.size < 0x80) { "test sections must be < 128 bytes (single-byte LEB size)" }
        add(id)
        add(content.size.toByte())
        content.forEach { add(it) }
    }

    private fun provider(bytes: ByteArray) = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())

    @Test
    fun `framing - component exposes its embedded core modules`() {
        assertEquals(1, provider(component(coreA)).modules.size)
        assertEquals(2, provider(component(coreA, coreB)).modules.size)
    }

    @Test
    fun `self diff is all identical`() {
        val result = WasmComponentDiffOrchestrator.diff(
            provider(component(coreA, coreB)),
            provider(component(coreA, coreB)),
        )
        assertNotNull(result)
        assertEquals(2, result.pairings.size)
        assertEquals(0, result.differingCount)
        assertTrue(result.pairings.all { it.status == SubmoduleStatus.IDENTICAL })
    }

    @Test
    fun `changed submodule is flagged modified`() {
        val result = WasmComponentDiffOrchestrator.diff(provider(component(coreA)), provider(component(coreB)))
        assertNotNull(result)
        assertEquals(1, result.pairings.size)
        assertEquals(1, result.differingCount)
        assertEquals(SubmoduleStatus.MODIFIED, result.pairings.single().status)
    }

    @Test
    fun `extra submodule on the base is reported removed`() {
        val result = WasmComponentDiffOrchestrator.diff(
            provider(component(coreA, coreB)),
            provider(component(coreA)),
        )
        assertNotNull(result)
        assertEquals(1, result.pairings.count { it.status == SubmoduleStatus.IDENTICAL })
        assertEquals(1, result.pairings.count { it.status == SubmoduleStatus.REMOVED })
        assertEquals(0, result.pairings.count { it.status == SubmoduleStatus.ADDED })
    }

    @Test
    fun `extra submodule on the head is reported added`() {
        val result = WasmComponentDiffOrchestrator.diff(
            provider(component(coreA)),
            provider(component(coreA, coreB)),
        )
        assertNotNull(result)
        assertEquals(1, result.pairings.count { it.status == SubmoduleStatus.ADDED })
        assertEquals(0, result.pairings.count { it.status == SubmoduleStatus.REMOVED })
    }

    @Test
    fun `reordered identical submodules match by content not position`() {
        val result = WasmComponentDiffOrchestrator.diff(
            provider(component(coreA, coreB)),
            provider(component(coreB, coreA)),
        )
        assertNotNull(result)
        assertEquals(2, result.pairings.size)
        assertEquals(0, result.differingCount)
        assertTrue(result.pairings.all { it.status == SubmoduleStatus.IDENTICAL })
    }

    @Test
    fun `nested component is enumerated as a component submodule`() {
        val cm = componentWithNested(coreB, component(coreA))
        // Sanity: the outer parse sees one module and one nested component section.
        assertEquals(1, provider(cm).modules.size)
        assertNotNull(provider(cm).wasmModule?.componentSection)

        val result = WasmComponentDiffOrchestrator.diff(provider(cm), provider(cm))
        assertNotNull(result)
        assertEquals(2, result.pairings.size)
        assertEquals(1, result.pairings.count { it.kind == SubmoduleKind.CORE_MODULE })
        assertEquals(1, result.pairings.count { it.kind == SubmoduleKind.COMPONENT })
        assertEquals(0, result.differingCount)
    }
}

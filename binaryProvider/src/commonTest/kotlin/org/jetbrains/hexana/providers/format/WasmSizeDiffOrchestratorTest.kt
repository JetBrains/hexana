package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.NodeKind
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WasmSizeDiffOrchestratorTest {

    /** Module exporting one function `main` (type ()->i32), one memory. Func body: i32.const 5. */
    private val withExportName: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x07, 0x0E, 0x02,
        0x04, 0x6D, 0x61, 0x69, 0x6E, 0x00, 0x00,
        0x03, 0x6D, 0x65, 0x6D, 0x02, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    /** Same as [withExportName] but the function body has two extra `nop`s (grows by 2 bytes). */
    private val withExportNameLargerBody: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x07, 0x0E, 0x02,
        0x04, 0x6D, 0x61, 0x69, 0x6E, 0x00, 0x00,
        0x03, 0x6D, 0x65, 0x6D, 0x02, 0x00,
        0x0A, 0x08, 0x01, 0x06, 0x00, 0x41, 0x05, 0x01, 0x01, 0x0B,
    )

    /** Module with one function and no name sources (no exports, no name section). */
    private val anonymous: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    private fun provider(bytes: ByteArray) = WasmBinaryProvider(bytes.toCommonByteBuffer())

    private fun theFunction(diff: SizeDiffNode): SizeDiffNode =
        diff.flatten().single { it.kind == NodeKind.FUNCTION }

    @Test
    fun `self-diff resolves export name on both sides and is identical`() {
        val diff = WasmSizeDiffOrchestrator.diff(provider(withExportName), provider(withExportName))
        assertNotNull(diff)
        assertEquals(DiffClass.IDENTICAL, diff.classification)

        val fn = theFunction(diff)
        assertEquals(DiffClass.IDENTICAL, fn.classification)
        assertEquals("main", fn.leftName)
        assertEquals("main", fn.rightName)
        assertEquals(0, fn.delta)
    }

    @Test
    fun `function matched by name across a body-size change is MODIFIED`() {
        val diff = WasmSizeDiffOrchestrator.diff(
            provider(withExportName),
            provider(withExportNameLargerBody),
        )
        assertNotNull(diff)

        val fn = theFunction(diff)
        assertEquals(DiffClass.MODIFIED, fn.classification)
        assertEquals("main", fn.leftName)
        assertEquals("main", fn.rightName)
        assertEquals(2, fn.delta, "body grew by two nops")
        // No spurious add/remove: exactly one function node, matched.
        assertEquals(1, diff.flatten().count { it.kind == NodeKind.FUNCTION })
    }

    @Test
    fun `function with no name falls back to global-index display name`() {
        val diff = WasmSizeDiffOrchestrator.diff(provider(anonymous), provider(anonymous))
        assertNotNull(diff)

        val fn = theFunction(diff)
        assertEquals(DiffClass.IDENTICAL, fn.classification)
        // No imports, code index 0 -> global index 0.
        assertEquals("\$func0", fn.leftName)
        assertEquals("\$func0", fn.rightName)
    }
}

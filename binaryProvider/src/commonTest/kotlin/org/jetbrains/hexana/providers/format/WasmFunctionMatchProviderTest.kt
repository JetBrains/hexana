package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.match.MatchEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class WasmFunctionMatchProviderTest {

    /**
     * Two defined functions: C = `nop` (exported, so its call sites carry a name), and
     * A = `call 0` (NOT exported). Module differs only in the export NAME of C ("foo" vs
     * "bar"). C matches round-1 by identical body; A's round-1 hash differs because the
     * callee name folded into it differs — so only round-2 (partner-id substitution) pairs A.
     */
    private fun module(name: ByteArray): ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,             // type ()->()
        0x03, 0x03, 0x02, 0x00, 0x00,                   // 2 funcs, type 0
        0x07, 0x07, 0x01, 0x03, name[0], name[1], name[2], 0x00, 0x00, // export <name> -> func 0
        0x0A, 0x0A, 0x02,                               // code: 2 funcs
        0x03, 0x00, 0x01, 0x0B,                         // func0 C: nop
        0x04, 0x00, 0x10, 0x00, 0x0B,                   // func1 A: call 0
    )

    private val moduleFoo = module(byteArrayOf(0x66, 0x6F, 0x6F)) // "foo"
    private val moduleBar = module(byteArrayOf(0x62, 0x61, 0x72)) // "bar"

    @Test
    fun `round 2 matches a function whose only difference is a renamed but matched callee`() {
        val result = WasmFunctionMatchProvider.match(
            WasmBinaryProvider(moduleFoo.toCommonByteBuffer()),
            WasmBinaryProvider(moduleBar.toCommonByteBuffer()),
        )
        assertNotNull(result)
        val a = result.matches.single { it.leftIdx == 1 }
        assertEquals(1, a.rightIdx)
        assertEquals(MatchEvidence.HASH_ROUND2, a.evidence, "A should be paired by round-2 substitution")
        assertEquals(0, result.unmatchedLeft.size + result.unmatchedRight.size)
    }

    // One function, exported "foo" (named) vs "Z" (minified), with DIFFERENT bodies (const 5 vs 6)
    // so the content hash misses. Without a map, names differ -> no NAME_ANCHOR. A byIndex symbol
    // map on the minified side restores "foo" -> the pair is NAME_ANCHORed (MODIFIED).
    private val named: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x07, 0x07, 0x01, 0x03, 0x66, 0x6F, 0x6F, 0x00, 0x00, // export "foo" -> func 0
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,       // body: i32.const 5
    )
    private val minified: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x07, 0x05, 0x01, 0x01, 0x5A, 0x00, 0x00,             // export "Z" -> func 0
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x06, 0x0B,       // body: i32.const 6
    )

    @Test
    fun `symbol map relights name anchoring on a renamed function`() {
        val left = WasmBinaryProvider(named.toCommonByteBuffer())
        val right = WasmBinaryProvider(minified.toCommonByteBuffer())

        val without = WasmFunctionMatchProvider.match(left, right)
        assertNotNull(without)
        assertNotEquals(MatchEvidence.NAME_ANCHOR, without.matches.single().evidence)

        val map = WasmSymbolMap(byIndex = mapOf(0 to "foo"), translate = emptyMap())
        val withMap = WasmFunctionMatchProvider.match(left, right, rightMap = map)
        assertNotNull(withMap)
        val m = withMap.matches.single()
        assertEquals(MatchEvidence.NAME_ANCHOR, m.evidence)
        assertEquals(DiffClass.MODIFIED, m.classification)
    }
}

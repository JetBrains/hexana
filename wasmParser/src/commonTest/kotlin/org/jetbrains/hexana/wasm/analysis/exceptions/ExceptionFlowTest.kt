package org.jetbrains.hexana.wasm.analysis.exceptions

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.sections.parseCodeEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExceptionFlowTest {

    private suspend fun module() = resourceAsByteBuffer("snippets/exceptions.wasm").let { buf ->
        val result = WasmParser().parse(buf)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)
        buf to module
    }

    @Test
    fun `tag section decodes attribute and typeidx`() = runTest {
        val (buf, module) = module()
        val section = module.exceptionSection!!
        val tags = with(section) { buf.scanTags() }
        assertEquals(2, tags.size)
        // Both tags are exception tags (attribute 0); typeidx 0 = (i32), 1 = (f64).
        assertTrue(tags.all { it.attribute == 0 })
        assertEquals(listOf(0, 1), tags.map { it.typeIdx })
    }

    private suspend fun sitesOfFunction(codeIdx: Int): List<ThrowSite> {
        val (buf, module) = module()
        val offsets = module.codeSection!!.let { with(it) { buf.scanOffsets() } }
        val record = buf.parseCodeEntry(offsets[codeIdx].offset)
        return ExceptionFlow.analyze(record.opCodes)
    }

    // Code order: 0 $caught, 1 $catchall, 2 $escapes, 3 $mixed.

    @Test
    fun `throw caught by matching tag resolves to the handler`() = runTest {
        val site = sitesOfFunction(0).single()
        assertEquals(0, site.tagIdx)
        val r = site.resolution
        assertIs<ThrowResolution.CaughtHere>(r)
        assertTrue(!r.byCatchAll, "caught by a tag-specific clause, not catch_all")
    }

    @Test
    fun `throw caught by catch_all is flagged as such`() = runTest {
        val site = sitesOfFunction(1).single()
        assertEquals(1, site.tagIdx)
        val r = site.resolution
        assertIs<ThrowResolution.CaughtHere>(r)
        assertTrue(r.byCatchAll)
    }

    @Test
    fun `throw with no handler escapes`() = runTest {
        val site = sitesOfFunction(2).single()
        assertEquals(0, site.tagIdx)
        assertIs<ThrowResolution.Escapes>(site.resolution)
        assertEquals(setOf(0), ExceptionFlow.escapeSet(sitesFn(2)).tags)
    }

    @Test
    fun `throw of a tag the enclosing try_table does not catch escapes`() = runTest {
        val site = sitesOfFunction(3).single()
        assertEquals(1, site.tagIdx, "throws \$t1 (tag 1)")
        assertIs<ThrowResolution.Escapes>(site.resolution) // try_table only catches tag 0
    }

    // Helper mirroring sitesOfFunction but returning opcodes for escapeSet.
    private suspend fun sitesFn(codeIdx: Int) = run {
        val (buf, module) = module()
        val offsets = module.codeSection!!.let { with(it) { buf.scanOffsets() } }
        buf.parseCodeEntry(offsets[codeIdx].offset).opCodes
    }
}

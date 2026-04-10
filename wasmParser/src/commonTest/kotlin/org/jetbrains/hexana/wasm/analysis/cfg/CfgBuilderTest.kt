package org.jetbrains.hexana.wasm.analysis.cfg

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmParseSuccess
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CfgBuilderTest {

    @Test
    fun `linear function has entry and exit blocks`() = runTest {
        val buffer = resourceAsByteBuffer("snippets/addition.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val codeRecords = module.codeSection?.let { section ->
            with(section) { buffer.scan() }
        } ?: emptyList()

        assertTrue(codeRecords.isNotEmpty(), "Should have at least one function")

        val cfgResult = CfgBuilder.build(codeRecords[0], funcIndex = 0)

        assertTrue(cfgResult.blocks.isNotEmpty(), "Should have at least one block")
        assertEquals(BlockKind.Entry, cfgResult.blocks.first().kind)
        for (block in cfgResult.blocks) {
            assertTrue(
                cfgResult.dominators.dominates(cfgResult.blocks.first().id, block.id),
                "Entry block should dominate all blocks"
            )
        }
    }

    @Test
    fun `if-else creates branching cfg`() = runTest {
        val buffer = resourceAsByteBuffer("snippets/ifelse.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val codeRecords = module.codeSection?.let { section ->
            with(section) { buffer.scan() }
        } ?: emptyList()

        assertTrue(codeRecords.isNotEmpty())

        val cfgResult = CfgBuilder.build(codeRecords[0], funcIndex = 0)

        assertTrue(cfgResult.blocks.size >= 3, "if-else should create at least 3 blocks, got ${cfgResult.blocks.size}")
        val entry = cfgResult.blocks.first()
        for (block in cfgResult.blocks) {
            assertTrue(cfgResult.dominators.dominates(entry.id, block.id))
        }
    }

    @Test
    fun `loop creates loop header block`() = runTest {
        val buffer = resourceAsByteBuffer("snippets/loop.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val codeRecords = module.codeSection?.let { section ->
            with(section) { buffer.scan() }
        } ?: emptyList()

        assertTrue(codeRecords.isNotEmpty())

        val cfgResult = CfgBuilder.build(codeRecords[0], funcIndex = 0)

        val loopHeaders = cfgResult.blocks.filter { it.kind == BlockKind.LoopHeader }
        assertTrue(loopHeaders.isNotEmpty(), "Should have a loop header block")
    }

    @Test
    fun `block with br produces valid cfg`() = runTest {
        val buffer = resourceAsByteBuffer("snippets/br.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val codeRecords = module.codeSection?.let { section ->
            with(section) { buffer.scan() }
        } ?: emptyList()

        assertTrue(codeRecords.isNotEmpty())

        val cfgResult = CfgBuilder.build(codeRecords[0], funcIndex = 0)

        assertNotNull(cfgResult.dominators)
        assertTrue(cfgResult.blocks.isNotEmpty())
    }

    @Test
    fun `unreachable instruction produces valid cfg`() = runTest {
        val buffer = resourceAsByteBuffer("snippets/unreachable.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val codeRecords = module.codeSection?.let { section ->
            with(section) { buffer.scan() }
        } ?: emptyList()

        assertTrue(codeRecords.isNotEmpty())

        val cfgResult = CfgBuilder.build(codeRecords[0], funcIndex = 0)

        assertNotNull(cfgResult.dominators)
    }

    @Test
    fun `large function cfg`() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val codeRecords = module.codeSection?.let { section ->
            with(section) { buffer.scan() }
        } ?: emptyList()

        val largest = codeRecords.maxByOrNull { it.opCodes.size }
        assertNotNull(largest)

        val cfgResult = CfgBuilder.build(largest, funcIndex = 0)

        assertTrue(cfgResult.blocks.isNotEmpty())
        assertNotNull(cfgResult.dominators)
    }
}
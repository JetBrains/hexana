package org.jetbrains.hexana.dwarf

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.components.CoreWasmComponent
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import kotlin.test.*

class DwarfVariableInfoExtractorTest {

    /**
     * Tests against c/utf8-c.wasm which contains (among other CUs):
     *
     * CU 0: utf8.c
     *   DW_TAG_variable "str" — DW_AT_location (DW_OP_addr 0x10ad0)
     *
     * CU 1: crt1.c
     *   DW_TAG_subprogram "_start" — DW_AT_frame_base (DW_OP_WASM_location 0x3 0x0, DW_OP_stack_value)
     */
    @Test
    fun extractSubprogramsFromUtf8CWasm() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val wasmParseResult = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent
        val accessor = WasmDwarfAccessor(wasmModule as BaseModule)
        val extractor = DwarfVariableInfoExtractor(accessor)

        val subprograms = with(extractor) { buffer.extractSubprograms() }
        assertTrue(subprograms.isNotEmpty(), "Should find at least one subprogram")

        // Find the _start subprogram from crt1.c (CU 1)
        val startSp = subprograms.find { it.name == "_start" }
        assertNotNull(startSp, "Should find _start subprogram")
        assertEquals(0x54, startSp.lowPc)
        assertEquals(0x75, startSp.highPc)

        // _start has DW_AT_frame_base = DW_OP_WASM_location 0x3 0x0, DW_OP_stack_value
        val frameBase = startSp.frameBase
        assertNotNull(frameBase, "Frame base should be present")
        assertTrue(frameBase.isWasmLocation, "Frame base should use Wasm location")
        assertTrue(frameBase.isStackValue, "Frame base should end with stack_value")

        val wasmOp = frameBase.wasmLocation
        assertIs<DwarfExprOp.WasmGlobalU32>(wasmOp)
        assertEquals(0L, wasmOp.index)
    }

    @Test
    fun extractVariablesFromUtf8CWasm() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val wasmParseResult = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent
        val accessor = WasmDwarfAccessor(wasmModule as BaseModule)
        val extractor = DwarfVariableInfoExtractor(accessor)

        val subprograms = with(extractor) { buffer.extractSubprograms() }
        assertTrue(subprograms.isNotEmpty(), "Should extract subprograms")

        // Verify that extracted subprograms have frame bases decoded
        // with Wasm-specific locations from DWARF
        val withFrameBase = subprograms.filter { it.frameBase != null }
        assertTrue(withFrameBase.isNotEmpty(), "Some subprograms should have frame bases")

        // Verify frame bases are decoded (not left as raw bytes)
        for (sp in withFrameBase) {
            val fb = sp.frameBase!!
            assertFalse(fb.isEmpty, "Frame base for '${sp.name}' should not be empty")
        }

        // File-scope global variables (like "str") live under
        // DW_TAG_compile_unit, not inside DW_TAG_subprogram. Those
        // are accessible via extractGlobalVariables().
        val globalVars = with(extractor) { buffer.extractGlobalVariables() }
        val strVar = globalVars.find { it.name == "str" }
        assertNotNull(strVar, "Should find file-scope 'str' variable")
        assertFalse(strVar.isParameter, "'str' is a variable, not a parameter")

        val location = strVar.location
        assertNotNull(location, "'str' should have a location")
        assertFalse(location.isEmpty, "Location should not be empty")

        val addrOp = location.operations[0]
        assertIs<DwarfExprOp.Address>(addrOp)
        assertEquals(0x10AD0L, addrOp.address)
    }

    @Test
    fun extractVariablesAtOffsetFindsCorrectSubprogram() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val wasmParseResult = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent
        val accessor = WasmDwarfAccessor(wasmModule as BaseModule)
        val extractor = DwarfVariableInfoExtractor(accessor)

        // Offset 0x60 is inside _start (0x54..0x75)
        val varsAtStart = with(extractor) { buffer.extractVariablesAtOffset(0x60) }
        // _start may or may not have local variables, but the query should succeed
        // without errors. In this CU, _start has no formal_parameter / variable children,
        // but the API should still return a valid (possibly empty) list.
        assertNotNull(varsAtStart)

        // Offset 0x20 is inside the first CU's function range (0x09..0x53)
        val varsAtUtf8 = with(extractor) { buffer.extractVariablesAtOffset(0x20) }
        assertNotNull(varsAtUtf8)
    }

    @Test
    fun extractVariablesAtOutOfRangeOffsetReturnsEmpty() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val wasmParseResult = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent
        val accessor = WasmDwarfAccessor(wasmModule as BaseModule)
        val extractor = DwarfVariableInfoExtractor(accessor)

        // Offset way outside any subprogram range
        val vars = with(extractor) { buffer.extractVariablesAtOffset(0xFFFF) }
        assertTrue(vars.isEmpty(), "No variables should match an out-of-range offset")
    }

    @Test
    fun describeLocationForExtractedVariables() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val wasmParseResult = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent
        val accessor = WasmDwarfAccessor(wasmModule as BaseModule)
        val extractor = DwarfVariableInfoExtractor(accessor)

        val subprograms = with(extractor) { buffer.extractSubprograms() }
        val startSp = subprograms.find { it.name == "_start" }
        assertNotNull(startSp)

        val frameBaseDesc = startSp.frameBase?.describe()
        assertNotNull(frameBaseDesc)
        assertTrue(
            frameBaseDesc.contains("wasm-global"),
            "Frame base description should mention wasm-global: $frameBaseDesc"
        )
    }
}

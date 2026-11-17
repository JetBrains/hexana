package org.jetbrains.hexana.dwarf

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.components.CoreWasmComponent
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import kotlin.test.*

/**
 * Diagnostic tests for `/rust/hello_world.wasm` — a Rust wasm-bindgen
 * binary compiled with `rustc 1.93.1` for `wasm32-wasip1`.
 *
 * Expected DWARF for the user function `add(a: i32, b: i32) -> i32`:
 *
 * ```
 * DW_TAG_subprogram
 *   DW_AT_low_pc  (0x0000017d)
 *   DW_AT_high_pc (0x000001db)
 *   DW_AT_frame_base (DW_OP_WASM_location 0x0 0x2, DW_OP_stack_value)
 *   DW_AT_name    ("add")
 *
 *   DW_TAG_formal_parameter
 *     DW_AT_location (DW_OP_fbreg +8)
 *     DW_AT_name     ("a")
 *
 *   DW_TAG_formal_parameter
 *     DW_AT_location (DW_OP_fbreg +12)
 *     DW_AT_name     ("b")
 * ```
 */
class HelloWorldWasmDwarfTest {

    private suspend fun createExtractor(): Triple<DwarfVariableInfoExtractor, org.jetbrains.hexana.io.CommonByteBuffer, WasmDwarfAccessor> {
        val buffer = resourceAsByteBuffer("rust/hello_world.wasm")
        val wasmParseResult = WasmParser().parse(buffer)
        check(wasmParseResult is WasmParseSuccess) { "Failed to parse hello_world.wasm" }
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent
        val accessor = WasmDwarfAccessor(wasmModule as BaseModule)
        val extractor = DwarfVariableInfoExtractor(accessor)
        return Triple(extractor, buffer, accessor)
    }

    @Test
    fun dwarfSectionsArePresent() = runTest {
        val (_, _, accessor) = createExtractor()
        assertNotNull(accessor.debugInfoSection(), ".debug_info should be present")
        assertNotNull(accessor.debugAbbrevSection(), ".debug_abbrev should be present")
        assertNotNull(accessor.debugStrSection(), ".debug_str should be present")
    }

    @Test
    fun extractsAddSubprogram() = runTest {
        val (extractor, buffer, _) = createExtractor()
        val subprograms = with(extractor) { buffer.extractSubprograms() }

        assertTrue(subprograms.isNotEmpty(), "Should find subprograms")
        println("[DEBUG_LOG] Total subprograms: ${subprograms.size}")

        val addSp = subprograms.find { it.name == "add" }
        assertNotNull(addSp, "Should find 'add' subprogram")
        println("[DEBUG_LOG] add: lowPc=0x${addSp.lowPc?.toString(16)}, highPc=0x${addSp.highPc?.toString(16)}")
        println("[DEBUG_LOG] add linkageName: ${addSp.linkageName}")

        assertEquals(0x17d, addSp.lowPc, "add lowPc")
        assertEquals(0x1db, addSp.highPc, "add highPc")
    }

    @Test
    fun addSubprogramHasFrameBase() = runTest {
        val (extractor, buffer, _) = createExtractor()
        val subprograms = with(extractor) { buffer.extractSubprograms() }
        val addSp = subprograms.find { it.name == "add" }
        assertNotNull(addSp, "Should find 'add' subprogram")

        val frameBase = addSp.frameBase
        assertNotNull(frameBase, "add should have DW_AT_frame_base")
        println("[DEBUG_LOG] add frame_base: ${frameBase.describe()}")
        println("[DEBUG_LOG] add frame_base operations: ${frameBase.operations}")
        println("[DEBUG_LOG] add frame_base isWasmLocation: ${frameBase.isWasmLocation}")
        println("[DEBUG_LOG] add frame_base isStackValue: ${frameBase.isStackValue}")

        assertTrue(frameBase.isWasmLocation, "Frame base should use DW_OP_WASM_location")
        assertTrue(frameBase.isStackValue, "Frame base should end with DW_OP_stack_value")

        val wasmOp = frameBase.wasmLocation
        assertIs<DwarfExprOp.WasmLocal>(wasmOp, "Frame base should be a Wasm local (0x0)")
        assertEquals(2L, wasmOp.index, "Frame base should reference local #2")
    }

    @Test
    fun addSubprogramHasParametersWithLocations() = runTest {
        val (extractor, buffer, _) = createExtractor()
        val subprograms = with(extractor) { buffer.extractSubprograms() }
        val addSp = subprograms.find { it.name == "add" }
        assertNotNull(addSp, "Should find 'add' subprogram")

        println("[DEBUG_LOG] add variables count: ${addSp.variables.size}")
        for (v in addSp.variables) {
            println("[DEBUG_LOG]   var: name=${v.name}, isParam=${v.isParameter}, location=${v.location?.describe()}")
        }

        val paramA = addSp.variables.find { it.name == "a" }
        assertNotNull(paramA, "Should find parameter 'a'")
        assertTrue(paramA.isParameter, "'a' should be a formal parameter")
        val locA = paramA.location
        assertNotNull(locA, "'a' should have a location")
        assertFalse(locA.isEmpty, "'a' location should not be empty")
        val opA = locA.operations.first()
        assertIs<DwarfExprOp.FrameBaseOffset>(opA, "'a' should use DW_OP_fbreg")
        assertEquals(8L, opA.offset, "'a' should be at fbreg+8")

        val paramB = addSp.variables.find { it.name == "b" }
        assertNotNull(paramB, "Should find parameter 'b'")
        assertTrue(paramB.isParameter, "'b' should be a formal parameter")
        val locB = paramB.location
        assertNotNull(locB, "'b' should have a location")
        val opB = locB.operations.first()
        assertIs<DwarfExprOp.FrameBaseOffset>(opB, "'b' should use DW_OP_fbreg")
        assertEquals(12L, opB.offset, "'b' should be at fbreg+12")
    }

    @Test
    fun extractVariablesAtAddFunctionOffset() = runTest {
        val (extractor, buffer, _) = createExtractor()
        // 0x190 is inside the add function (0x17d..0x1db)
        val vars = with(extractor) { buffer.extractVariablesAtOffset(0x190) }
        println("[DEBUG_LOG] Variables at offset 0x190: ${vars.size}")
        for (v in vars) {
            println("[DEBUG_LOG]   var: name=${v.name}, isParam=${v.isParameter}, location=${v.location?.describe()}")
        }
        assertTrue(vars.isNotEmpty(), "Should find variables inside add()")
        assertTrue(vars.any { it.name == "a" }, "Should find 'a'")
        assertTrue(vars.any { it.name == "b" }, "Should find 'b'")
    }

    @Test
    fun allSubprogramsWithFrameBaseUseWasmLocation() = runTest {
        val (extractor, buffer, _) = createExtractor()
        val subprograms = with(extractor) { buffer.extractSubprograms() }
        val withFrameBase = subprograms.filter { it.frameBase != null }
        println("[DEBUG_LOG] Subprograms with frame base: ${withFrameBase.size} / ${subprograms.size}")

        for (sp in withFrameBase.take(10)) {
            val fb = sp.frameBase!!
            println("[DEBUG_LOG]   ${sp.name}: frameBase=${fb.describe()}, isWasm=${fb.isWasmLocation}")
        }

        // All frame bases in Rust Wasm binaries should use DW_OP_WASM_location
        for (sp in withFrameBase) {
            val fb = sp.frameBase!!
            assertTrue(
                fb.isWasmLocation,
                "Frame base for '${sp.name}' should use DW_OP_WASM_location: ${fb.describe()}"
            )
        }
    }
}

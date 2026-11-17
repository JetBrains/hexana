package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.dwarf.DwarfExprOp
import org.jetbrains.hexana.dwarf.DwarfTypeInfo
import org.jetbrains.hexana.dwarf.DwarfTypeResolver
import org.jetbrains.hexana.dwarf.DwarfVariableInfoExtractor
import org.jetbrains.hexana.dwarf.buildDieIndex
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.components.CoreWasmComponent
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parser-level coverage of `rust/dwarf-memvars.wasm`, an unoptimized
 * (`-C opt-level=0`) Rust wasm32 build where every local lives in linear
 * memory rather than in wasm locals. Source and build command:
 * `rust/dwarf-memvars.rs` (rustc --target=wasm32-unknown-unknown
 * --crate-type=cdylib -g -C opt-level=0 -C lto=off).
 *
 * What -O0 DWARF uniquely exercises, unlike the optimized fixtures already
 * in the corpus:
 * - `DW_AT_frame_base` as `DW_OP_WASM_location local N` (shadow stack pointer)
 * - variables located via `DW_OP_fbreg <offset>` (frame-relative memory slots)
 * - a `DW_AT_type` chain to a struct with byte-exact member offsets
 */
class DwarfMemvarsFixtureTest {

    @Test
    fun fbregStructDebugInfoParses() = runTest {
        val buffer = resourceAsByteBuffer("rust/dwarf-memvars.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule as CoreWasmComponent

        // --- name section: exported function present under its name ---
        val nameSection = with(module) { getCustomSectionByName("name") }
        assertNotNull(nameSection, "name custom section must exist in a -g build")
        val namemap = with(nameSection) { buffer.parseAsNameSection() }.functionSubsection?.namemap
        assertNotNull(namemap, "function name subsection must parse")
        assertTrue(namemap.containsValue("make"), "expected function name 'make' in $namemap")

        // --- DWARF: the make subprogram and its frame-relative variables ---
        val accessor = WasmDwarfAccessor(module as BaseModule)
        val extractor = DwarfVariableInfoExtractor(accessor)
        val subprograms = with(extractor) { buffer.extractSubprograms() }
        val make = subprograms.firstOrNull { it.name == "make" }
            ?: error("subprogram 'make' not found among ${subprograms.mapNotNull { it.name }}")

        // Frame base is the shadow stack pointer in wasm local 1
        // (DW_AT_frame_base: DW_OP_WASM_location 0x0 0x1, DW_OP_stack_value).
        val frameBaseOp = make.frameBase?.operations?.firstOrNull()
        assertEquals(DwarfExprOp.WasmLocal(1), frameBaseOp)

        // p: struct local at DW_OP_fbreg +4, not a parameter.
        val p = make.variables.firstOrNull { it.name == "p" }
            ?: error("variable 'p' not found in ${make.variables.mapNotNull { it.name }}")
        assertEquals(DwarfExprOp.FrameBaseOffset(4), p.location?.operations?.firstOrNull())
        assertTrue(!p.isParameter, "p is a local, not a parameter")

        // v: i32 parameter at DW_OP_fbreg +12.
        val v = make.variables.firstOrNull { it.name == "v" }
            ?: error("variable 'v' not found")
        assertEquals(DwarfExprOp.FrameBaseOffset(12), v.location?.operations?.firstOrNull())
        assertTrue(v.isParameter, "v is a formal parameter")

        // --- DW_AT_type chain: p resolves to struct Point {x@0:i32, y@4:i16, z@6:i8} ---
        val infoSection = accessor.debugInfoSection()
        assertNotNull(infoSection)
        val cus = with(infoSection) { buffer.scanDwarfInfo() }
        val allDies = buildMap {
            for (cu in cus) for (child in cu.children) putAll(buildDieIndex(child))
        }
        val typeResolver = DwarfTypeResolver(allDies, accessor, infoSection.contentOffset, cus.map { it.offset })
        val cuOffset = make.cuOffset ?: error("subprogram must carry its CU offset")
        val typeOffset = p.typeOffset ?: error("p must carry a DW_AT_type reference")
        val pType = with(typeResolver) { buffer.resolveTypeAtOffset(cuOffset + typeOffset) }

        val struct = pType as? DwarfTypeInfo.Struct ?: error("p must resolve to a struct, got $pType")
        assertEquals("Point", struct.name)
        assertEquals(8, struct.byteSize)
        val members = struct.members.associate { (it.name ?: "?") to it.offset }
        assertEquals(mapOf("x" to 0, "y" to 4, "z" to 6), members)
        val memberTypes = struct.members.mapNotNull { (it.type as? DwarfTypeInfo.Base)?.name }
        assertEquals(listOf("i32", "i16", "i8"), memberTypes)
    }
}

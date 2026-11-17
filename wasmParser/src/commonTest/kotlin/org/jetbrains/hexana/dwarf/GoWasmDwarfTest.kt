package org.jetbrains.hexana.dwarf

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import kotlin.test.*

/**
 * Tests for DWARF support in Go/TinyGo-compiled WASM binaries.
 *
 * TinyGo's WASM output uses DWARF v4 with location lists (`.debug_loc`)
 * for some variables, and `DW_OP_WASM_location` for Wasm-specific
 * register mappings. Some function parameters (like `add(x, y)`) may
 * lack `DW_AT_location` entirely — a known TinyGo limitation.
 */
class GoWasmDwarfTest {

    private suspend fun createExtractorAndAccessor(): Triple<DwarfVariableInfoExtractor, org.jetbrains.hexana.io.CommonByteBuffer, WasmDwarfAccessor> {
        val buffer = resourceAsByteBuffer("go/main.wasm")
        val parseResult = WasmParser().parse(buffer)
        check(parseResult is WasmParseSuccess) { "Failed to parse go/main.wasm" }
        val wasmModule = parseResult.wasmModule as BaseModule
        val accessor = WasmDwarfAccessor(wasmModule)
        val extractor = DwarfVariableInfoExtractor(accessor)
        return Triple(extractor, buffer, accessor)
    }

    @Test
    fun dwarfSectionsIncludeDebugLoc() = runTest {
        val (_, _, accessor) = createExtractorAndAccessor()
        assertNotNull(accessor.debugInfoSection(), ".debug_info should be present")
        assertNotNull(accessor.debugAbbrevSection(), ".debug_abbrev should be present")
        assertNotNull(accessor.debugStrSection(), ".debug_str should be present")
        assertNotNull(accessor.debugLocSection(), ".debug_loc should be present in TinyGo binary")
    }

    @Test
    fun extractsAddSubprogram() = runTest {
        val (extractor, buffer, _) = createExtractorAndAccessor()
        val subprograms = with(extractor) { buffer.extractSubprograms() }
        assertTrue(subprograms.isNotEmpty(), "Should find subprograms")

        // TinyGo uses "./main.go.add" as name and "add" as linkage name
        val addSp = subprograms.find {
            it.linkageName == "add" || it.name?.contains("add") == true
        }
        assertNotNull(addSp, "Should find 'add' subprogram")
        assertNotNull(addSp.lowPc, "add should have lowPc")
        assertNotNull(addSp.highPc, "add should have highPc")
    }

    @Test
    fun addParametersLackLocation() = runTest {
        // TinyGo does not emit DW_AT_location for the add function's
        // parameters — this is the root cause of the "DWARF metadata
        // missing" message for x and y. This test documents the
        // toolchain limitation.
        val (extractor, buffer, _) = createExtractorAndAccessor()
        val subprograms = with(extractor) { buffer.extractSubprograms() }
        val addSp = subprograms.find {
            it.linkageName == "add" || it.name?.contains("add") == true
        }!!

        val paramX = addSp.variables.find { it.name == "x" }
        assertNotNull(paramX, "Should find parameter 'x'")
        assertTrue(paramX.isParameter, "'x' should be a formal parameter")
        assertNull(paramX.location, "'x' should have no location (TinyGo limitation)")
        assertTrue(paramX.locationList.isEmpty(), "'x' should have no location list")

        val paramY = addSp.variables.find { it.name == "y" }
        assertNotNull(paramY, "Should find parameter 'y'")
        assertTrue(paramY.isParameter, "'y' should be a formal parameter")
        assertNull(paramY.location, "'y' should have no location (TinyGo limitation)")
    }

    @Test
    fun variablesWithLocationListsAreExtracted() = runTest {
        // TinyGo uses .debug_loc for several variables. After our fix,
        // these should have non-empty locationList fields.
        val (extractor, buffer, _) = createExtractorAndAccessor()
        val subprograms = with(extractor) { buffer.extractSubprograms() }

        val varsWithLocLists = subprograms.flatMap { it.variables }
            .filter { it.locationList.isNotEmpty() }

        assertTrue(
            varsWithLocLists.isNotEmpty(),
            "Should find variables with location lists from .debug_loc"
        )

        // Each location list entry should have valid address ranges
        for (v in varsWithLocLists) {
            for (entry in v.locationList) {
                assertTrue(entry.startAddress >= 0, "Start address should be non-negative")
                assertTrue(entry.endAddress > entry.startAddress, "End > start for ${v.name}")
                assertFalse(entry.location.isEmpty, "Location expression should not be empty")
            }
        }
    }

    @Test
    fun printstringVariableIHasLocationList() = runTest {
        // In the Go binary, runtime.printstring's variable "i" has a
        // location list: [0x2aa, 0x2ac): DW_OP_WASM_location 0x0 0x2, DW_OP_stack_value
        val (extractor, buffer, _) = createExtractorAndAccessor()
        val subprograms = with(extractor) { buffer.extractSubprograms() }

        val printstringSp = subprograms.find {
            it.name == "runtime.printstring" || it.linkageName == "runtime.printstring"
        }
        assertNotNull(printstringSp, "Should find runtime.printstring")

        val varI = printstringSp.variables.find { it.name == "i" }
        assertNotNull(varI, "Should find variable 'i'")
        assertTrue(varI.locationList.isNotEmpty(), "'i' should have a location list")
        assertNotNull(varI.location, "'i' should have a primary location from the list")

        // First entry should contain DW_OP_WASM_location
        val firstEntry = varI.locationList.first()
        assertTrue(
            firstEntry.location.isWasmLocation,
            "First location list entry should use DW_OP_WASM_location"
        )
    }

    @Test
    fun locationListEntriesHaveCorrectAddressRanges() = runTest {
        val (extractor, buffer, _) = createExtractorAndAccessor()
        val subprograms = with(extractor) { buffer.extractSubprograms() }
        val printstringSp = subprograms.find {
            it.name == "runtime.printstring" || it.linkageName == "runtime.printstring"
        }!!

        val varI = printstringSp.variables.find { it.name == "i" }!!
        val entries = varI.locationList

        // From dwarfdump: [0x2aa, 0x2ac): DW_OP_WASM_location 0x0 0x2, DW_OP_stack_value
        val wasmEntry = entries.find { it.location.isWasmLocation }
        assertNotNull(wasmEntry, "Should find a WASM_location entry")
        assertEquals(0x2aaL, wasmEntry.startAddress)
        assertEquals(0x2acL, wasmEntry.endAddress)

        val wasmOp = wasmEntry.location.wasmLocation
        assertIs<DwarfExprOp.WasmLocal>(wasmOp)
        assertEquals(2L, wasmOp.index, "Should reference wasm local[2]")
    }
}

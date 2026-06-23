package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.CdpSourceLocationResolver
import java.io.File

class CdpSourceLocationResolverTest : UsefulTestCase() {

    private fun loadDebugAddWasm(): ByteArray {
        val file = File("../ui-driver-tests/testData/projects/hexana_wasm_project/debug-add.wasm")
        assertTrue("Test resource must exist: ${file.absolutePath}", file.exists())
        return file.readBytes()
    }

    fun testCodeSectionOffsetAndBreakpointOffset() {
        val bytes = loadDebugAddWasm()
        val resolver = CdpSourceLocationResolver.create(bytes)
        assertNotNull("Resolver should be created from debug-add.wasm", resolver)
        resolver!!

        // DWARF line table for debug_add.rs:
        //   line 9  (fn signature) -> DWARF offset 0x1e = 30
        //   line 10 (a + b)        -> DWARF offset 0x46 = 70
        // Code section content starts at byte 78 in the module.
        // V8 CDP expects module-absolute offsets as columnNumber.
        // So line 10 should map to module offset 78 + 70 = 148.

        // IDE line is 0-based, so source line 10 = IDE line 9
        val offsets = resolver.sourceToAllWasmOffsets("debug_add.rs", 9)
        assertFalse("Should find offsets for debug_add.rs line 10 (IDE line 9)", offsets.isEmpty())

        println("[DEBUG_LOG] offsets for debug_add.rs IDE line 9: $offsets")
        println("[DEBUG_LOG] offsets hex: ${offsets.map { "0x${it.toString(16)}" }}")

        // The correct module offset should be 148 (78 + 70), not 195
        // If this fails, the codeSectionOffset or DWARF offset base is wrong
        for (offset in offsets) {
            println("[DEBUG_LOG] offset=$offset (0x${offset.toString(16)})")
        }

        // The correct module offset is 148 = codeSectionOffset(78) + DWARF offset(0x46=70)
        // 0x46 is the first (prologue_end) entry for line 10 in the add function.
        // Previously the code used MAX offset per function which picked 0x75 (epilogue),
        // giving the wrong module offset 195.
        assertTrue("Expected offset 148 (0x94) for line 10, got $offsets", offsets.contains(148.toULong()))
    }
}

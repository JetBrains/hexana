package org.jetbrains.hexana.ui.panel

import com.intellij.testFramework.UsefulTestCase
import java.io.File
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * Pins the offset convention of the wasm source-location chain against
 * debug-add.wasm (Rust, DWARF; see CdpSourceLocationResolverTest for the
 * ground truth: `a + b` on debug_add.rs line 10 sits at DWARF code-relative
 * offset 70, and the code section content starts at module byte 78).
 */
class WasmSourceLocationTest : UsefulTestCase() {

    private fun provider(): WasmBinaryProvider {
        val file = File("../ui-driver-tests/testData/projects/hexana_wasm_project/debug-add.wasm")
        assertTrue("Test resource must exist: ${file.absolutePath}", file.exists())
        return WasmBinaryProvider(file.readBytes().toCommonByteBuffer())
    }

    fun testProviderChainResolvesModuleAbsoluteOffsets() {
        val p = provider()
        // `a + b` (debug_add.rs line 10) sits at DWARF code-relative offset 70
        // = module-absolute 148. The chain takes module-absolute offsets.
        val absolute = p.sourceLocationProvider.getSourceLocationForOffset(78 + 70)
        assertNotNull("module-absolute offset must resolve (hex view convention)", absolute)
        assertTrue(absolute!!.filePath.endsWith("debug_add.rs"))
        assertEquals(10, absolute.line)

        // A code-relative offset below the code-section base must NOT resolve —
        // passing the wrong convention should fail loudly, not mis-navigate.
        assertNull(p.sourceLocationProvider.getSourceLocationForOffset(70))
    }

    fun testFunctionBodyRangeResolvesToSignatureLine() {
        val p = provider()
        // codeIdx 1 is `add` (codeIdx 0 is rust_begin_unwind, per wasm-objdump).
        val location = wasmFunctionSourceLocation(p, 1)
        assertNotNull("the add function should resolve to debug_add.rs", location)
        assertTrue(location!!.filePath.endsWith("debug_add.rs"))
        // First line row inside the body is the fn signature (line 9,
        // DWARF code-relative offset 30 per CdpSourceLocationResolverTest).
        assertEquals(9, location.line)

        // Every defined function in this debug build has some location.
        assertNotNull(wasmFunctionSourceLocation(p, 0))
    }

    fun testSourceLocationLabelShortens() {
        val label = sourceLocationLabel(
            org.jetbrains.hexana.providers.HexanaSourceLocation("/home/user/project/src/markdown/render.rs", 87, 5),
        )
        assertEquals("markdown/render.rs:87", label)
    }
}

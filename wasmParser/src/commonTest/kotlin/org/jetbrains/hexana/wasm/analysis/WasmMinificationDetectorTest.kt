package org.jetbrains.hexana.wasm.analysis

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasmMinificationDetectorTest {

    @Test
    fun `dense short names and collapsed module are detected as minified`() {
        val r = WasmMinificationDetector.detect(
            importModules = List(8) { "a" },
            importFields = listOf("a", "b", "c", "d", "e", "f", "g", "h"),
            exportNames = listOf("Ec", "Fc", "Hc", "Ic", "Jc", "Kc"),
        )
        assertTrue(r.minified, r.evidence)
        assertTrue(r.confidence >= 0.7f, "confidence=${r.confidence}")
    }

    @Test
    fun `readable symbol names are not minified`() {
        val r = WasmMinificationDetector.detect(
            importModules = listOf("env", "wasi_snapshot_preview1"),
            importFields = listOf("abort", "fd_write", "emscripten_resize_heap"),
            exportNames = listOf("__wasm_call_ctors", "org_jetbrains_skia_Typeface_nGetFontStyle", "main", "stackSave"),
        )
        assertFalse(r.minified, r.evidence)
    }

    @Test
    fun `too few public symbols is inconclusive`() {
        val r = WasmMinificationDetector.detect(emptyList(), listOf("a", "b"), emptyList())
        assertFalse(r.minified)
    }
}

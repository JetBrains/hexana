package org.jetbrains.hexana

import org.jetbrains.hexana.wasm.components.ComponentSort
import org.jetbrains.hexana.wasm.components.ComponentSortIndex
import org.jetbrains.hexana.wasm.components.WasmComponentExport
import org.junit.Assert.*
import org.junit.Test

class ComponentResolveUtilTest {

    private fun makeExport(name: String): WasmComponentExport =
        WasmComponentExport(0, 0, name, null, ComponentSortIndex(ComponentSort.Func, 0u), null)

    @Test
    fun testIsWasiCliRunExport() {
        assertTrue(isWasiCliRunExport(makeExport("wasi:cli/run")))
        assertTrue(isWasiCliRunExport(makeExport("wasi:cli/run@0.2.0")))
        assertTrue(isWasiCliRunExport(makeExport("wasi:cli/run@0.2.10")))
        assertTrue(isWasiCliRunExport(makeExport("wasi:cli/run@0.3.0-rc-2026-03-15")))
    }

    @Test
    fun testIsWasiCliRunExportNegative() {
        assertFalse(isWasiCliRunExport(makeExport("wasi:cli/environment")))
        assertFalse(isWasiCliRunExport(makeExport("wasi:cli/stdout@0.2.10")))
        assertFalse(isWasiCliRunExport(makeExport("wasi:http/incoming-handler@0.2.9")))
        assertFalse(isWasiCliRunExport(makeExport("component:calculator/calculate@0.1.0")))
        assertFalse(isWasiCliRunExport(makeExport("run")))
    }
}

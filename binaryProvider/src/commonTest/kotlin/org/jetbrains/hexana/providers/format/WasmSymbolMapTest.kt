package org.jetbrains.hexana.providers.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasmSymbolMapTest {

    @Test
    fun `parses index and translate lines, splitting on first colon`() {
        val map = WasmSymbolMap.parse(
            """
            0:__wasm_call_ctors
            5:org_jetbrains_skia_foo
            42:ns::mangled::name
            Ec:org_jetbrains_skia_bar
            a.a:env.abort
            # a comment
            // another comment

            bad-line-no-colon
            :leading-colon-no-key
            """.trimIndent(),
        )
        assertEquals("__wasm_call_ctors", map.byIndex[0])
        assertEquals("org_jetbrains_skia_foo", map.byIndex[5])
        // first-colon split preserves a value that itself contains colons (C++ mangling)
        assertEquals("ns::mangled::name", map.byIndex[42])
        assertEquals("org_jetbrains_skia_bar", map.translate["Ec"])
        assertEquals("env.abort", map.translate["a.a"])
        assertEquals(3, map.byIndex.size)   // 0, 5, 42
        assertEquals(2, map.translate.size) // Ec, a.a
        assertTrue(!map.isEmpty)
    }

    @Test
    fun `blank input is empty`() {
        assertTrue(WasmSymbolMap.parse("").isEmpty)
        assertTrue(WasmSymbolMap.parse("\n\n# only a comment\n").isEmpty)
    }
}

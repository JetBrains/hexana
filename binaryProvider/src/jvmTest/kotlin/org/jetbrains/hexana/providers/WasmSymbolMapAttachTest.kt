package org.jetbrains.hexana.providers

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.format.WasmSymbolMap
import org.jetbrains.hexana.providers.format.WasmSymbolResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `funcIndex:name` symbol map (e.g. .NET's `dotnet.native.js.symbols`) naming a stripped module. */
class WasmSymbolMapAttachTest {
    /** `(import "env" "foo" (func))` plus two defined functions, no name section. */
    private val stripped = byteArrayOf(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x02, 0x0b, 0x01, 0x03, 'e'.code.toByte(), 'n'.code.toByte(), 'v'.code.toByte(),
        0x03, 'f'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 0x00, 0x00,
        0x03, 0x03, 0x02, 0x00, 0x00,
        0x0a, 0x07, 0x02, 0x02, 0x00, 0x0b, 0x02, 0x00, 0x0b,
    )

    private fun provider() = WasmBinaryProvider(stripped.toCommonByteBuffer())

    @Test
    fun `an attached map names every function`() {
        val provider = provider()
        assertEquals(3, provider.functionIndexSpaceSize)
        assertNull(provider.nameSection)
        assertTrue(provider.attachSymbolMap(WasmSymbolMap.parse("0:foo\n1:mono_wasm_load_runtime\n2:mini_init"), "dotnet.native.js.symbols"))
        assertEquals(mapOf(0 to "foo", 1 to "mono_wasm_load_runtime", 2 to "mini_init"), provider.nameSection?.functionSubsection?.namemap)
        assertEquals("dotnet.native.js.symbols", provider.symbolMapPath)
        assertEquals("mini_init", WasmSymbolResolver(provider).resolve(SymbolKind.FUNCTION, 2))
    }

    @Test
    fun `a map of another module is rejected`() {
        val provider = provider()
        // Index past the function index space.
        assertFalse(provider.attachSymbolMap(WasmSymbolMap.parse("1:a\n3:b"), null))
        // A build with fewer functions: its last index is not the module's last.
        assertFalse(provider.attachSymbolMap(WasmSymbolMap.parse("0:foo\n1:a"), null))
        // The import entry disagrees with the import section.
        assertFalse(provider.attachSymbolMap(WasmSymbolMap.parse("0:bar\n1:a\n2:b"), null))
        // Only `minified:original` lines: nothing to name functions with.
        assertFalse(provider.attachSymbolMap(WasmSymbolMap.parse("a:b"), null))
        assertNull(provider.nameSection)
        assertNull(provider.symbolMapPath)
    }
}

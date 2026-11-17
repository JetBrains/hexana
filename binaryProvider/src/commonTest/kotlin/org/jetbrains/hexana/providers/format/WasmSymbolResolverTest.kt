package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WasmSymbolResolverTest {

    /** type ()->(); one function import `env.abort`; no defined functions. */
    private val importOnly: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x02, 0x0D, 0x01,
        0x03, 0x65, 0x6E, 0x76,                   // module "env"
        0x05, 0x61, 0x62, 0x6F, 0x72, 0x74,       // field "abort"
        0x00, 0x00,                                // kind=func, typeidx=0
    )

    @Test
    fun `imported function resolves to module dot field`() {
        val resolver = WasmSymbolResolver(WasmBinaryProvider(importOnly.toCommonByteBuffer()))
        assertEquals("env.abort", resolver.resolve(SymbolKind.FUNCTION, 0))
        // No function at index 1 -> still null (import branch only fills 0..importFuncCount-1).
        assertNull(resolver.resolve(SymbolKind.FUNCTION, 1))
    }

    /** One defined function, no imports/exports/name section -> resolves to null without a map. */
    private val anonymous: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    /** One defined function exported under a minified name "Ec". */
    private val minifiedExport: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x07, 0x06, 0x01, 0x02, 0x45, 0x63, 0x00, 0x00, // export "Ec" -> func 0
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    @Test
    fun `byIndex symbol map names an otherwise-anonymous function`() {
        val provider = WasmBinaryProvider(anonymous.toCommonByteBuffer())
        assertNull(WasmSymbolResolver(provider).resolve(SymbolKind.FUNCTION, 0))
        val map = WasmSymbolMap(byIndex = mapOf(0 to "myFunc"), translate = emptyMap())
        assertEquals("myFunc", WasmSymbolResolver(provider, symbolMap = map).resolve(SymbolKind.FUNCTION, 0))
    }

    @Test
    fun `translate symbol map de-minifies an export binding`() {
        val provider = WasmBinaryProvider(minifiedExport.toCommonByteBuffer())
        assertEquals("Ec", WasmSymbolResolver(provider).resolve(SymbolKind.FUNCTION, 0))
        val map = WasmSymbolMap(byIndex = emptyMap(), translate = mapOf("Ec" to "originalName"))
        assertEquals("originalName", WasmSymbolResolver(provider, symbolMap = map).resolve(SymbolKind.FUNCTION, 0))
    }
}

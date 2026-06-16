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
}

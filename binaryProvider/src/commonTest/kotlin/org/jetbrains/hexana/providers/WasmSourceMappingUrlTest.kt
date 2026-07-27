package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WasmSourceMappingUrlTest {

    /**
     * Header + one custom section (id 0):
     * name "sourceMappingURL" (16 bytes), payload = string "app.wasm.map".
     * Content = 0x10 + name + 0x0C + payload = 30 bytes (0x1E).
     */
    private val withSection: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x00, 0x1E,
        0x10,
        0x73, 0x6F, 0x75, 0x72, 0x63, 0x65, 0x4D, 0x61,
        0x70, 0x70, 0x69, 0x6E, 0x67, 0x55, 0x52, 0x4C,
        0x0C,
        0x61, 0x70, 0x70, 0x2E, 0x77, 0x61, 0x73, 0x6D, 0x2E, 0x6D, 0x61, 0x70,
    )

    private val withoutSection: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
    )

    @Test
    fun `sourceMappingURL payload is exposed as the map url`() {
        val provider = WasmBinaryProvider(withSection.toCommonByteBuffer())
        assertEquals("app.wasm.map", provider.sourceMappingUrl)
    }

    @Test
    fun `absent section yields null`() {
        val provider = WasmBinaryProvider(withoutSection.toCommonByteBuffer())
        assertNull(provider.sourceMappingUrl)
    }
}

package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertNull

class WasmDebugModulesTruncationTest {

    @Test
    fun `truncated embedded module with huge declared size is not indexed`() {
        val coreModule = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
            0x03, 0x02, 0x01, 0x00,
            0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b,
        )
        val component = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00,
            0x01, 0xf1.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07,
        ) + coreModule

        assertNull(WasmDebugModules.create(component.toCommonByteBuffer()))
    }
}

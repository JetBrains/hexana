package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests [WasmtimeAddrmap] against a real wasmtime artifact.
 *
 * Fixture: wasmtime-tiny.cwasm — `wasmtime compile -D debug-info -O opt-level=0`
 * (wasmtime 37.0.1, aarch64-macos) of a three-function module (add, sum_to,
 * store_load). Ground truth below was extracted with an independent Python
 * decoder and cross-checked against `wasm-objdump -d`: the addrmap has 16
 * entries, `.text` is at address 0 with size 16384, and every non-null
 * FilePos is an exact wasm instruction offset (e.g. 0x52 = `i32.add` in
 * `add`, 0x7e = `i32.store` in `store_load`).
 */
class WasmtimeAddrmapTest {

    private fun loadFixture(): WasmtimeAddrmap {
        val stream = WasmtimeAddrmapTest::class.java.classLoader.getResourceAsStream("wasmtime-tiny.cwasm")
            ?: error("Test resource not found: wasmtime-tiny.cwasm")
        val map = WasmtimeAddrmap.parse(stream.readBytes().toCommonByteBuffer())
        return assertNotNull(map, "fixture must parse")
    }

    @Test fun `parses entry count and text mapping`() {
        val map = loadFixture()
        assertEquals(16, map.entryCount)
        assertEquals(0uL, map.textLoadAddress)
        // .text size is 16384: last byte inside, first byte past the end outside.
        assertEquals(true, map.containsPc(16383uL))
        assertEquals(false, map.containsPc(16384uL))
    }

    @Test fun `exact entry pc maps to its wasm offset`() {
        val map = loadFixture()
        assertEquals(0x52, map.wasmOffsetForPc(0x0cuL)) // i32.add in `add`
        assertEquals(0x53, map.wasmOffsetForPc(0x10uL)) // end of `add`
        assertEquals(0x61, map.wasmOffsetForPc(0x34uL)) // br_if in `sum_to`
        assertEquals(0x7e, map.wasmOffsetForPc(0x6cuL)) // i32.store in `store_load`
        assertEquals(0x86, map.wasmOffsetForPc(0x80uL)) // end of `store_load`
    }

    @Test fun `pc between entries falls into the earlier bucket`() {
        val map = loadFixture()
        // 0x0c and 0x10 are adjacent entries; 0x0e belongs to the 0x0c bucket.
        assertEquals(0x52, map.wasmOffsetForPc(0x0euL))
        // Past the last entry (0x80) but still inside .text: last bucket wins.
        assertEquals(0x86, map.wasmOffsetForPc(0x100uL))
    }

    @Test fun `entries without source position yield null`() {
        val map = loadFixture()
        assertNull(map.wasmOffsetForPc(0x00uL)) // function prologue, FilePos = 0xFFFFFFFF
        assertNull(map.wasmOffsetForPc(0x20uL)) // prologue of `sum_to`
    }

    @Test fun `pc outside text yields null`() {
        val map = loadFixture()
        assertNull(map.wasmOffsetForPc(16384uL))
        assertNull(map.wasmOffsetForPc(ULong.MAX_VALUE))
    }

    @Test fun `garbage input parses to null`() {
        assertNull(WasmtimeAddrmap.parse(ByteArray(0).toCommonByteBuffer()))
        assertNull(WasmtimeAddrmap.parse(ByteArray(64) { 0x2A }.toCommonByteBuffer()))
        // A valid wasm module is not an ELF artifact.
        assertNull(
            WasmtimeAddrmap.parse(
                byteArrayOf(0, 'a'.code.toByte(), 's'.code.toByte(), 'm'.code.toByte(), 1, 0, 0, 0).toCommonByteBuffer()
            )
        )
    }
}

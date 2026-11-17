package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.*

class DwarfExpressionParserTest {

    @Test
    fun parseEmptyExpression() {
        val result = DwarfExpressionParser.parse(byteArrayOf())
        assertTrue(result.isEmpty)
        assertEquals("<empty location>", result.describe())
    }

    // --- DW_OP_WASM_location tests ---

    @Test
    fun parseWasmLocalLocation() {
        // DW_OP_WASM_location (0xED) + wasm-local (0x00) + index 8 (ULEB128)
        val bytes = byteArrayOf(0xED.toByte(), 0x00, 0x08)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.WasmLocal>(op)
        assertEquals(8L, op.index)
        assertTrue(result.isWasmLocation)
        assertEquals("wasm-local[8]", result.describe())
    }

    @Test
    fun parseWasmGlobalLocation() {
        // DW_OP_WASM_location (0xED) + wasm-global (0x01) + index 3 (ULEB128)
        val bytes = byteArrayOf(0xED.toByte(), 0x01, 0x03)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.WasmGlobal>(op)
        assertEquals(3L, op.index)
        assertTrue(result.isWasmLocation)
    }

    @Test
    fun parseWasmGlobalU32Location() {
        // DW_OP_WASM_location (0xED) + wasm-global-u32 (0x03) + index 0 (4 bytes LE)
        val bytes = byteArrayOf(0xED.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.WasmGlobalU32>(op)
        assertEquals(0L, op.index)
        assertTrue(result.isWasmLocation)
    }

    @Test
    fun parseWasmOperandStackLocation() {
        // DW_OP_WASM_location (0xED) + wasm-operand-stack (0x02) + index 5 (ULEB128)
        val bytes = byteArrayOf(0xED.toByte(), 0x02, 0x05)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.WasmOperandStack>(op)
        assertEquals(5L, op.index)
        assertTrue(result.isWasmLocation)
    }

    @Test
    fun parseWasmLocationWithStackValue() {
        // DW_OP_WASM_location 0x03 0x00000000, DW_OP_stack_value
        // This is the typical frame_base pattern from the test wasm file
        val bytes = byteArrayOf(
            0xED.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00,  // DW_OP_WASM_location 0x03 global[0]
            0x9F.toByte()                                    // DW_OP_stack_value
        )
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(2, result.operations.size)
        assertIs<DwarfExprOp.WasmGlobalU32>(result.operations[0])
        assertIs<DwarfExprOp.StackValue>(result.operations[1])
        assertTrue(result.isWasmLocation)
        assertTrue(result.isStackValue)
        assertEquals("wasm-global[0], DW_OP_stack_value", result.describe())
    }

    // --- Standard DWARF operations ---

    @Test
    fun parseAddr() {
        // DW_OP_addr (0x03) + 4-byte address 0x10ad0 (LE)
        val bytes = byteArrayOf(
            0x03, 0xD0.toByte(), 0x0A, 0x01, 0x00
        )
        val result = DwarfExpressionParser.parse(bytes, addressSize = 4)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.Address>(op)
        assertEquals(0x10AD0L, op.address)
        assertFalse(result.isWasmLocation)
        assertEquals("DW_OP_addr(0x10ad0)", result.describe())
    }

    @Test
    fun parseFbreg() {
        // DW_OP_fbreg (0x91) + SLEB128 offset -28 (0x64 in SLEB128 = 0b1100100 → -28 = 0x64)
        // -28 in SLEB128: 0xe4 0x7f
        val bytes = byteArrayOf(0x91.toByte(), 0xE4.toByte(), 0x7F)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.FrameBaseOffset>(op)
        assertEquals(-28L, op.offset)
        assertEquals("DW_OP_fbreg(-28)", result.describe())
    }

    @Test
    fun parseStackValue() {
        // DW_OP_stack_value (0x9F)
        val bytes = byteArrayOf(0x9F.toByte())
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        assertIs<DwarfExprOp.StackValue>(result.operations[0])
        assertTrue(result.isStackValue)
    }

    @Test
    fun parseRegN() {
        // DW_OP_reg7 (0x57) — register 7
        val bytes = byteArrayOf(0x57)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.Register>(op)
        assertEquals(7, op.regNumber)
        assertEquals("DW_OP_reg7", result.describe())
    }

    @Test
    fun parseBregN() {
        // DW_OP_breg0 (0x70) + SLEB128 offset 16
        val bytes = byteArrayOf(0x70, 0x10)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.BaseRegisterOffset>(op)
        assertEquals(0, op.regNumber)
        assertEquals(16L, op.offset)
        assertEquals("DW_OP_breg0(16)", result.describe())
    }

    @Test
    fun parseConstu() {
        // DW_OP_constu (0x10) + ULEB128 value 42
        val bytes = byteArrayOf(0x10, 0x2A)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.ConstUnsigned>(op)
        assertEquals(42L, op.value)
    }

    @Test
    fun parseConsts() {
        // DW_OP_consts (0x11) + SLEB128 value -1 (0x7f)
        val bytes = byteArrayOf(0x11, 0x7F)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.ConstSigned>(op)
        assertEquals(-1L, op.value)
    }

    @Test
    fun parsePlusUconst() {
        // DW_OP_plus_uconst (0x23) + ULEB128 value 4
        val bytes = byteArrayOf(0x23, 0x04)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.PlusUConst>(op)
        assertEquals(4L, op.value)
    }

    @Test
    fun parseDeref() {
        // DW_OP_deref (0x06)
        val bytes = byteArrayOf(0x06)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        assertIs<DwarfExprOp.Deref>(result.operations[0])
    }

    @Test
    fun parseDerefSize() {
        // DW_OP_deref_size (0x94) + size 4
        val bytes = byteArrayOf(0x94.toByte(), 0x04)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.DerefSize>(op)
        assertEquals(4, op.size)
    }

    @Test
    fun parsePiece() {
        // DW_OP_piece (0x93) + ULEB128 byte_size 4
        val bytes = byteArrayOf(0x93.toByte(), 0x04)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.Piece>(op)
        assertEquals(4L, op.byteSize)
    }

    @Test
    fun parseCallFrameCfa() {
        // DW_OP_call_frame_cfa (0x9C)
        val bytes = byteArrayOf(0x9C.toByte())
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        assertIs<DwarfExprOp.CallFrameCfa>(result.operations[0])
    }

    @Test
    fun parseRegx() {
        // DW_OP_regx (0x90) + ULEB128 register 100
        val bytes = byteArrayOf(0x90.toByte(), 0x64)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.RegisterX>(op)
        assertEquals(100L, op.regNumber)
    }

    @Test
    fun parseBregx() {
        // DW_OP_bregx (0x92) + ULEB128 register 54 + SLEB128 offset 32
        val bytes = byteArrayOf(0x92.toByte(), 0x36, 0x20)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.BaseRegisterOffsetX>(op)
        assertEquals(54L, op.regNumber)
        assertEquals(32L, op.offset)
    }

    @Test
    fun parseConst1u() {
        // DW_OP_const1u (0x08) + value 255
        val bytes = byteArrayOf(0x08, 0xFF.toByte())
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.ConstFixed>(op)
        assertEquals(255L, op.value)
        assertEquals(1, op.byteSize)
        assertFalse(op.signed)
    }

    @Test
    fun parseImplicitValue() {
        // DW_OP_implicit_value (0x9E) + ULEB128 length 3 + 3 bytes data
        val bytes = byteArrayOf(0x9E.toByte(), 0x03, 0x01, 0x02, 0x03)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.ImplicitValue>(op)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), op.bytes)
    }

    // --- DW_OP_litN (0x30..0x4f) ---

    @Test
    fun parseLit0() {
        // DW_OP_lit0 (0x30) — push literal 0
        val bytes = byteArrayOf(0x30)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.ConstFixed>(op)
        assertEquals(0L, op.value)
        assertEquals(0, op.byteSize)
    }

    @Test
    fun parseLit23() {
        // DW_OP_lit23 (0x47) — push literal 23
        val bytes = byteArrayOf(0x47)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.ConstFixed>(op)
        assertEquals(23L, op.value)
    }

    @Test
    fun parseLit31() {
        // DW_OP_lit31 (0x4f) — push literal 31
        val bytes = byteArrayOf(0x4f)
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertIs<DwarfExprOp.ConstFixed>(op)
        assertEquals(31L, op.value)
    }

    @Test
    fun parseLit0WithStackValue() {
        // DW_OP_lit0, DW_OP_stack_value — constant value 0
        // This pattern is used by TinyGo in location lists.
        val bytes = byteArrayOf(0x30, 0x9F.toByte())
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(2, result.operations.size)
        assertIs<DwarfExprOp.ConstFixed>(result.operations[0])
        assertEquals(0L, (result.operations[0] as DwarfExprOp.ConstFixed).value)
        assertIs<DwarfExprOp.StackValue>(result.operations[1])
        assertTrue(result.isStackValue)
    }

    // --- Multi-operation expressions ---

    @Test
    fun parseMultipleOperations() {
        // DW_OP_WASM_location 0x00 4, DW_OP_stack_value
        // = frame base in wasm local[4], value is the variable itself
        val bytes = byteArrayOf(
            0xED.toByte(), 0x00, 0x04,  // DW_OP_WASM_location local[4]
            0x9F.toByte()                // DW_OP_stack_value
        )
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(2, result.operations.size)
        assertIs<DwarfExprOp.WasmLocal>(result.operations[0])
        assertEquals(4L, (result.operations[0] as DwarfExprOp.WasmLocal).index)
        assertIs<DwarfExprOp.StackValue>(result.operations[1])
        assertTrue(result.isWasmLocation)
        assertTrue(result.isStackValue)
    }

    @Test
    fun parseCallFrameCfaWithFbreg() {
        // DW_OP_call_frame_cfa, DW_OP_constu 4, DW_OP_plus
        val bytes = byteArrayOf(
            0x9C.toByte(),  // DW_OP_call_frame_cfa
            0x10, 0x04,     // DW_OP_constu 4
            0x22            // DW_OP_plus
        )
        val result = DwarfExpressionParser.parse(bytes)
        assertEquals(3, result.operations.size)
        assertIs<DwarfExprOp.CallFrameCfa>(result.operations[0])
        assertIs<DwarfExprOp.ConstUnsigned>(result.operations[1])
        assertIs<DwarfExprOp.Other>(result.operations[2]) // DW_OP_plus
    }

    // --- Describe method ---

    @Test
    fun describeEmptyLocation() {
        val desc = DwarfLocationDescription(emptyList())
        assertEquals("<empty location>", desc.describe())
    }

    @Test
    fun describeComplexExpression() {
        val desc = DwarfLocationDescription(
            listOf(
                DwarfExprOp.WasmLocal(4),
                DwarfExprOp.StackValue,
            )
        )
        assertEquals("wasm-local[4], DW_OP_stack_value", desc.describe())
    }

    // --- wasmLocation property ---

    @Test
    fun wasmLocationReturnsNullForNonWasm() {
        val desc = DwarfLocationDescription(
            listOf(DwarfExprOp.FrameBaseOffset(-28))
        )
        assertNull(desc.wasmLocation)
        assertFalse(desc.isWasmLocation)
    }

    @Test
    fun wasmLocationReturnsFirstWasmOp() {
        val desc = DwarfLocationDescription(
            listOf(
                DwarfExprOp.WasmGlobalU32(0),
                DwarfExprOp.StackValue,
            )
        )
        assertIs<DwarfExprOp.WasmGlobalU32>(desc.wasmLocation)
    }

    // --- Location list parsing ---

    @Test
    fun readLocationListSingleEntry() {
        // Build a minimal .debug_loc section with one entry:
        //   start=0x100, end=0x200, expr = DW_OP_WASM_location 0x00 0x02, DW_OP_stack_value
        // Terminated by (0, 0)
        val expr = byteArrayOf(0xED.toByte(), 0x00, 0x02, 0x9F.toByte())
        val sectionBytes = buildLocSectionBytes(
            listOf(LocEntry(0x100, 0x200, expr)),
        )
        val buffer = sectionBytes.toCommonByteBuffer()
        val locSection = DwarfLocSection(0, sectionBytes.size, stubAccessor)
        val entries = buffer.readLocationList(locSection, 0, addressSize = 4)

        assertEquals(1, entries.size)
        assertEquals(0x100L, entries[0].startAddress)
        assertEquals(0x200L, entries[0].endAddress)
        assertEquals(2, entries[0].location.operations.size)
        assertIs<DwarfExprOp.WasmLocal>(entries[0].location.operations[0])
        assertEquals(2L, (entries[0].location.operations[0] as DwarfExprOp.WasmLocal).index)
        assertIs<DwarfExprOp.StackValue>(entries[0].location.operations[1])
    }

    @Test
    fun readLocationListMultipleEntries() {
        // Two entries like TinyGo's .debug_loc for runtime.printstring:
        //   [0x2aa, 0x2ac): DW_OP_WASM_location 0x0 0x2, DW_OP_stack_value
        //   [0x1b1, 0x1bf): DW_OP_lit0, DW_OP_stack_value
        val expr1 = byteArrayOf(0xED.toByte(), 0x00, 0x02, 0x9F.toByte())
        val expr2 = byteArrayOf(0x30, 0x9F.toByte())  // DW_OP_lit0, DW_OP_stack_value
        val sectionBytes = buildLocSectionBytes(
            listOf(
                LocEntry(0x2aa, 0x2ac, expr1),
                LocEntry(0x1b1, 0x1bf, expr2),
            ),
        )
        val buffer = sectionBytes.toCommonByteBuffer()
        val locSection = DwarfLocSection(0, sectionBytes.size, stubAccessor)
        val entries = buffer.readLocationList(locSection, 0, addressSize = 4)

        assertEquals(2, entries.size)
        assertEquals(0x2aaL, entries[0].startAddress)
        assertEquals(0x2acL, entries[0].endAddress)
        assertIs<DwarfExprOp.WasmLocal>(entries[0].location.operations[0])
        assertEquals(0x1b1L, entries[1].startAddress)
        assertEquals(0x1bfL, entries[1].endAddress)
        assertIs<DwarfExprOp.ConstFixed>(entries[1].location.operations[0])
        assertEquals(0L, (entries[1].location.operations[0] as DwarfExprOp.ConstFixed).value)
    }

    @Test
    fun readLocationListEmptySection() {
        // Just the terminator (0, 0)
        val sectionBytes = buildLocSectionBytes(emptyList())
        val buffer = sectionBytes.toCommonByteBuffer()
        val locSection = DwarfLocSection(0, sectionBytes.size, stubAccessor)
        val entries = buffer.readLocationList(locSection, 0, addressSize = 4)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun readLocationListWithOffset() {
        // First loc list at offset 0, second at offset after the first
        val expr1 = byteArrayOf(0x30, 0x9F.toByte())  // DW_OP_lit0, DW_OP_stack_value
        val expr2 = byteArrayOf(0xED.toByte(), 0x00, 0x05, 0x9F.toByte())
        val firstList = buildLocSectionBytes(listOf(LocEntry(0x100, 0x200, expr1)))
        val secondList = buildLocSectionBytes(listOf(LocEntry(0x300, 0x400, expr2)))
        val combined = firstList + secondList
        val buffer = combined.toCommonByteBuffer()
        val locSection = DwarfLocSection(0, combined.size, stubAccessor)

        // Read the second list at offset = firstList.size
        val entries = buffer.readLocationList(locSection, firstList.size, addressSize = 4)
        assertEquals(1, entries.size)
        assertEquals(0x300L, entries[0].startAddress)
        assertIs<DwarfExprOp.WasmLocal>(entries[0].location.operations[0])
        assertEquals(5L, (entries[0].location.operations[0] as DwarfExprOp.WasmLocal).index)
    }

    // --- Helpers for location list tests ---

    private data class LocEntry(val start: Int, val end: Int, val expr: ByteArray)

    private fun buildLocSectionBytes(entries: List<LocEntry>): ByteArray {
        val out = mutableListOf<Byte>()
        for (e in entries) {
            // 4-byte little-endian start address
            out.addAll(intToLE(e.start))
            // 4-byte little-endian end address
            out.addAll(intToLE(e.end))
            // 2-byte little-endian expression length
            out.add((e.expr.size and 0xFF).toByte())
            out.add(((e.expr.size shr 8) and 0xFF).toByte())
            // Expression bytes
            out.addAll(e.expr.toList())
        }
        // Terminator: (0, 0)
        out.addAll(intToLE(0))
        out.addAll(intToLE(0))
        return out.toByteArray()
    }

    private fun intToLE(value: Int): List<Byte> = listOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private val stubAccessor = object : DwarfAccessor {
        override fun section(name: String): DwarfSection? = null
        override fun debugLineSection(): DwarfLineSection? = null
        override fun debugInfoSection(): DwarfInfoSection? = null
        override fun debugAbbrevSection(): DwarfAbbrevSection? = null
        override fun debugStrSection(): DwarfStringSection? = null
        override fun debugAddrSection(): DwarfAddressSection? = null
        override fun debugLocSection(): DwarfLocSection? = null
    }
}

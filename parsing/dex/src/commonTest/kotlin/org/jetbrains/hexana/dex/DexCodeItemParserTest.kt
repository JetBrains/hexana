package org.jetbrains.hexana.dex

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DexCodeItemParserTest {

    @Test
    fun `a zero code offset means no code item`() {
        val buffer = ByteArray(16).toCommonByteBuffer()
        assertNull(DexFileParser.parseCodeItem(buffer, 0L))
    }

    @Test
    fun `parses the register frame and instruction stream`() {
        // A real code_item never sits at offset 0 (the header is there), so the
        // structure is placed after a filler prefix.
        val code = DexBytes()
            .bytes(ByteArray(CODE_OFFSET))
            .u2(3) // registers_size
            .u2(1) // ins_size
            .u2(2) // outs_size
            .u2(0) // tries_size
            .u4(0) // debug_info_off
            .u4(2) // insns_size, in code units
            .bytes(insns(0x1012, 0x000E)) // const/4 v0, #1 ; return-void
            .build()

        val item = DexFileParser.parseCodeItem(code.toCommonByteBuffer(), CODE_OFFSET.toLong())!!

        assertEquals(3, item.registersSize)
        assertEquals(1, item.insSize)
        assertEquals(2, item.outsSize)
        assertEquals(2, item.insnsSize)
        assertEquals(CODE_OFFSET + 16, item.insnsFileOffset)
        // v0 and v1 are locals, v2 holds the single incoming argument.
        assertEquals(2, item.firstParameterRegister)

        assertEquals(listOf(DexOpcode.CONST_4, DexOpcode.RETURN_VOID), item.instructions.map { it.opcode })
        assertEquals(1L, item.instructions[0].operands.literal)
        assertEquals(emptyList(), item.tries)
        assertEquals(emptyList(), item.handlers)
    }

    @Test
    fun `parses tries and their typed and catch-all handlers`() {
        val code = DexBytes()
            .bytes(ByteArray(CODE_OFFSET))
            .u2(2) // registers_size
            .u2(0) // ins_size
            .u2(0) // outs_size
            .u2(2) // tries_size
            .u4(0) // debug_info_off
            .u4(3) // insns_size — odd, so a padding code unit follows insns[]
            .bytes(insns(0x1012, 0x000E, 0x0000))
            .u2(0) // padding
            // try_item[0]: [0, 2) handled at handler-list offset 1
            .u4(0).u2(2).u2(1)
            // try_item[1]: [2, 3) handled at handler-list offset 4
            .u4(2).u2(1).u2(4)
            // encoded_catch_handler_list
            .uleb128(2) // two handlers
            .sleb128(1).uleb128(5).uleb128(2) // typed only: catch type@5 -> 2
            .sleb128(-1).uleb128(7).uleb128(3).uleb128(4) // typed + catch-all -> 4
            .build()

        val item = DexFileParser.parseCodeItem(code.toCommonByteBuffer(), CODE_OFFSET.toLong())!!

        assertEquals(
            listOf(DexTry(startAddr = 0, insnCount = 2, handlerOff = 1), DexTry(2, 1, 4)),
            item.tries,
        )
        assertEquals(listOf(0, 2), item.tries.map { it.startAddr })
        assertEquals(listOf(2, 3), item.tries.map { it.endAddr })

        val first = item.handlerFor(item.tries[0])!!
        assertEquals(listOf(DexTypeAddrPair(typeIdx = 5, addr = 2)), first.typeHandlers)
        assertNull(first.catchAllAddr)

        val second = item.handlerFor(item.tries[1])!!
        assertEquals(listOf(DexTypeAddrPair(typeIdx = 7, addr = 3)), second.typeHandlers)
        assertEquals(4, second.catchAllAddr)
    }

    @Test
    fun `a handler count past the end of the file is rejected, not allocated`() {
        // A corrupt encoded_catch_handler_list size. Without a bound this asks
        // for a list of 2^31 entries before the first read can fail.
        val code = DexBytes()
            .bytes(ByteArray(CODE_OFFSET))
            .u2(1) // registers_size
            .u2(0) // ins_size
            .u2(0) // outs_size
            .u2(1) // tries_size
            .u4(0) // debug_info_off
            .u4(2) // insns_size
            .bytes(insns(0x000E, 0x0000))
            .u4(0).u2(1).u2(0) // try_item
            .uleb128(0x7FFFFFFF) // handler count
            .build()

        val failure = assertFails {
            DexFileParser.parseCodeItem(code.toCommonByteBuffer(), CODE_OFFSET.toLong())
        }
        assertTrue(
            failure.message?.contains("encoded_catch_handler_list") == true,
            "unhelpful message: ${failure.message}",
        )
    }

    @Test
    fun `an insns size past the end of the file is rejected`() {
        val code = DexBytes()
            .bytes(ByteArray(CODE_OFFSET))
            .u2(1).u2(0).u2(0).u2(0)
            .u4(0)
            .u4(0xFFFFFF) // insns_size, far past the end
            .build()

        val failure = assertFails {
            DexFileParser.parseCodeItem(code.toCommonByteBuffer(), CODE_OFFSET.toLong())
        }
        assertTrue(failure.message?.contains("insns") == true, "unhelpful message: ${failure.message}")
    }

}

/** Filler in front of the hand-built `code_item`, so its offset is non-zero. */
private const val CODE_OFFSET = 16

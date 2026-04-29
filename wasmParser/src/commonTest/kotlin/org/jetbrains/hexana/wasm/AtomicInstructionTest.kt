package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.analysis.OpcodeCategory
import org.jetbrains.hexana.wasm.analysis.classifyOpcode
import org.jetbrains.hexana.wasm.opcodes.AtomicOpCode
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.atomic.Atomic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Inlined tests for every `0xFE`-prefixed atomic instruction defined by the
 * Threads proposal (now part of the core WebAssembly specification).
 *
 * Coverage matches the spec opcode table 1:1 — 67 instructions:
 *  - 3 wait/notify   (`memory.atomic.{notify, wait32, wait64}`)
 *  - 1 fence         (`atomic.fence`)
 *  - 7 atomic loads
 *  - 7 atomic stores
 *  - 49 RMW ops      (7 size/sign × 7 ops {add, sub, and, or, xor, xchg, cmpxchg})
 *
 * Each test compiles a tiny `(memory ... shared)` module via wat2wasm and
 * asserts that the produced opcode AST contains exactly the expected
 * subclass with the right [MemArg]. Atomic ops require natural alignment,
 * so `align` in the asserted MemArg = log2(byte width):
 *   1B → 0, 2B → 1, 4B → 2, 8B → 3.
 *
 * Spec: https://webassembly.github.io/threads/core/binary/instructions.html
 */
class AtomicInstructionTest {

    private suspend fun parseInlineOpcodes(wat: String): List<OpCode> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records[0].opCodes
    }

    private fun module(body: String): String = """
        (module
          (memory 1 1 shared)
          $body
        )
    """.trimIndent()

    // ========================================================================
    // wait / notify
    // ========================================================================

    @Test
    fun `memory atomic notify`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                memory.atomic.notify
              )
        """))
        val op = ops.filterIsInstance<Atomic.MemoryAtomicNotify>().single()
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    @Test
    fun `memory atomic wait32`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i32 i64) (result i32)
                local.get 0
                local.get 1
                local.get 2
                memory.atomic.wait32
              )
        """))
        val op = ops.filterIsInstance<Atomic.MemoryAtomicWait32>().single()
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    @Test
    fun `memory atomic wait64`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i64 i64) (result i32)
                local.get 0
                local.get 1
                local.get 2
                memory.atomic.wait64
              )
        """))
        val op = ops.filterIsInstance<Atomic.MemoryAtomicWait64>().single()
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }

    // ========================================================================
    // fence
    // ========================================================================

    @Test
    fun `atomic fence`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f")
                atomic.fence
              )
        """))
        ops.filterIsInstance<Atomic.Fence>().single()
    }

    // ========================================================================
    // atomic loads — 0x10..0x16
    // ========================================================================

    @Test
    fun `i32 atomic load`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.atomic.load
              )
        """))
        val op = ops.filterIsInstance<Atomic.I32Load>().single()
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    @Test
    fun `i64 atomic load`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.atomic.load
              )
        """))
        val op = ops.filterIsInstance<Atomic.I64Load>().single()
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }

    @Test
    fun `i32 atomic load8_u`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.atomic.load8_u
              )
        """))
        val op = ops.filterIsInstance<Atomic.I32Load8U>().single()
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }

    @Test
    fun `i32 atomic load16_u`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.atomic.load16_u
              )
        """))
        val op = ops.filterIsInstance<Atomic.I32Load16U>().single()
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }

    @Test
    fun `i64 atomic load8_u`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.atomic.load8_u
              )
        """))
        val op = ops.filterIsInstance<Atomic.I64Load8U>().single()
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }

    @Test
    fun `i64 atomic load16_u`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.atomic.load16_u
              )
        """))
        val op = ops.filterIsInstance<Atomic.I64Load16U>().single()
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }

    @Test
    fun `i64 atomic load32_u`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.atomic.load32_u
              )
        """))
        val op = ops.filterIsInstance<Atomic.I64Load32U>().single()
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    // ========================================================================
    // atomic stores — 0x17..0x1D
    // ========================================================================

    @Test
    fun `i32 atomic store`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i32)
                local.get 0
                local.get 1
                i32.atomic.store
              )
        """))
        val op = ops.filterIsInstance<Atomic.I32Store>().single()
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    @Test
    fun `i64 atomic store`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i64)
                local.get 0
                local.get 1
                i64.atomic.store
              )
        """))
        val op = ops.filterIsInstance<Atomic.I64Store>().single()
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }

    @Test
    fun `i32 atomic store8`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i32)
                local.get 0
                local.get 1
                i32.atomic.store8
              )
        """))
        val op = ops.filterIsInstance<Atomic.I32Store8>().single()
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }

    @Test
    fun `i32 atomic store16`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i32)
                local.get 0
                local.get 1
                i32.atomic.store16
              )
        """))
        val op = ops.filterIsInstance<Atomic.I32Store16>().single()
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }

    @Test
    fun `i64 atomic store8`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i64)
                local.get 0
                local.get 1
                i64.atomic.store8
              )
        """))
        val op = ops.filterIsInstance<Atomic.I64Store8>().single()
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }

    @Test
    fun `i64 atomic store16`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i64)
                local.get 0
                local.get 1
                i64.atomic.store16
              )
        """))
        val op = ops.filterIsInstance<Atomic.I64Store16>().single()
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }

    @Test
    fun `i64 atomic store32`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i64)
                local.get 0
                local.get 1
                i64.atomic.store32
              )
        """))
        val op = ops.filterIsInstance<Atomic.I64Store32>().single()
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    // ========================================================================
    // RMW — add (0x1E..0x24)
    // ========================================================================

    @Test fun `i32 atomic rmw add`() = runTest {
        val op = singleRmw<Atomic.I32RmwAdd>("i32", "i32.atomic.rmw.add")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw add`() = runTest {
        val op = singleRmw<Atomic.I64RmwAdd>("i64", "i64.atomic.rmw.add")
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw8 add_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw8AddU>("i32", "i32.atomic.rmw8.add_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw16 add_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw16AddU>("i32", "i32.atomic.rmw16.add_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw8 add_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw8AddU>("i64", "i64.atomic.rmw8.add_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw16 add_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw16AddU>("i64", "i64.atomic.rmw16.add_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw32 add_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw32AddU>("i64", "i64.atomic.rmw32.add_u")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    // ========================================================================
    // RMW — sub (0x25..0x2B)
    // ========================================================================

    @Test fun `i32 atomic rmw sub`() = runTest {
        val op = singleRmw<Atomic.I32RmwSub>("i32", "i32.atomic.rmw.sub")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw sub`() = runTest {
        val op = singleRmw<Atomic.I64RmwSub>("i64", "i64.atomic.rmw.sub")
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw8 sub_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw8SubU>("i32", "i32.atomic.rmw8.sub_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw16 sub_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw16SubU>("i32", "i32.atomic.rmw16.sub_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw8 sub_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw8SubU>("i64", "i64.atomic.rmw8.sub_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw16 sub_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw16SubU>("i64", "i64.atomic.rmw16.sub_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw32 sub_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw32SubU>("i64", "i64.atomic.rmw32.sub_u")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    // ========================================================================
    // RMW — and (0x2C..0x32)
    // ========================================================================

    @Test fun `i32 atomic rmw and`() = runTest {
        val op = singleRmw<Atomic.I32RmwAnd>("i32", "i32.atomic.rmw.and")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw and`() = runTest {
        val op = singleRmw<Atomic.I64RmwAnd>("i64", "i64.atomic.rmw.and")
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw8 and_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw8AndU>("i32", "i32.atomic.rmw8.and_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw16 and_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw16AndU>("i32", "i32.atomic.rmw16.and_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw8 and_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw8AndU>("i64", "i64.atomic.rmw8.and_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw16 and_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw16AndU>("i64", "i64.atomic.rmw16.and_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw32 and_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw32AndU>("i64", "i64.atomic.rmw32.and_u")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    // ========================================================================
    // RMW — or (0x33..0x39)
    // ========================================================================

    @Test fun `i32 atomic rmw or`() = runTest {
        val op = singleRmw<Atomic.I32RmwOr>("i32", "i32.atomic.rmw.or")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw or`() = runTest {
        val op = singleRmw<Atomic.I64RmwOr>("i64", "i64.atomic.rmw.or")
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw8 or_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw8OrU>("i32", "i32.atomic.rmw8.or_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw16 or_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw16OrU>("i32", "i32.atomic.rmw16.or_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw8 or_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw8OrU>("i64", "i64.atomic.rmw8.or_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw16 or_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw16OrU>("i64", "i64.atomic.rmw16.or_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw32 or_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw32OrU>("i64", "i64.atomic.rmw32.or_u")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    // ========================================================================
    // RMW — xor (0x3A..0x40)
    // ========================================================================

    @Test fun `i32 atomic rmw xor`() = runTest {
        val op = singleRmw<Atomic.I32RmwXor>("i32", "i32.atomic.rmw.xor")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw xor`() = runTest {
        val op = singleRmw<Atomic.I64RmwXor>("i64", "i64.atomic.rmw.xor")
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw8 xor_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw8XorU>("i32", "i32.atomic.rmw8.xor_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw16 xor_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw16XorU>("i32", "i32.atomic.rmw16.xor_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw8 xor_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw8XorU>("i64", "i64.atomic.rmw8.xor_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw16 xor_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw16XorU>("i64", "i64.atomic.rmw16.xor_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw32 xor_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw32XorU>("i64", "i64.atomic.rmw32.xor_u")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    // ========================================================================
    // RMW — xchg (0x41..0x47)
    // ========================================================================

    @Test fun `i32 atomic rmw xchg`() = runTest {
        val op = singleRmw<Atomic.I32RmwXchg>("i32", "i32.atomic.rmw.xchg")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw xchg`() = runTest {
        val op = singleRmw<Atomic.I64RmwXchg>("i64", "i64.atomic.rmw.xchg")
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw8 xchg_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw8XchgU>("i32", "i32.atomic.rmw8.xchg_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw16 xchg_u`() = runTest {
        val op = singleRmw<Atomic.I32Rmw16XchgU>("i32", "i32.atomic.rmw16.xchg_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw8 xchg_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw8XchgU>("i64", "i64.atomic.rmw8.xchg_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw16 xchg_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw16XchgU>("i64", "i64.atomic.rmw16.xchg_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw32 xchg_u`() = runTest {
        val op = singleRmw<Atomic.I64Rmw32XchgU>("i64", "i64.atomic.rmw32.xchg_u")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    // ========================================================================
    // RMW — cmpxchg (0x48..0x4E) — three-operand form: addr, expected, replacement
    // ========================================================================

    @Test fun `i32 atomic rmw cmpxchg`() = runTest {
        val op = singleCmpxchg<Atomic.I32RmwCmpxchg>("i32", "i32.atomic.rmw.cmpxchg")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw cmpxchg`() = runTest {
        val op = singleCmpxchg<Atomic.I64RmwCmpxchg>("i64", "i64.atomic.rmw.cmpxchg")
        assertEquals(MemArg(align = 3, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw8 cmpxchg_u`() = runTest {
        val op = singleCmpxchg<Atomic.I32Rmw8CmpxchgU>("i32", "i32.atomic.rmw8.cmpxchg_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i32 atomic rmw16 cmpxchg_u`() = runTest {
        val op = singleCmpxchg<Atomic.I32Rmw16CmpxchgU>("i32", "i32.atomic.rmw16.cmpxchg_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw8 cmpxchg_u`() = runTest {
        val op = singleCmpxchg<Atomic.I64Rmw8CmpxchgU>("i64", "i64.atomic.rmw8.cmpxchg_u")
        assertEquals(MemArg(align = 0, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw16 cmpxchg_u`() = runTest {
        val op = singleCmpxchg<Atomic.I64Rmw16CmpxchgU>("i64", "i64.atomic.rmw16.cmpxchg_u")
        assertEquals(MemArg(align = 1, offset = 0), op.mem)
    }
    @Test fun `i64 atomic rmw32 cmpxchg_u`() = runTest {
        val op = singleCmpxchg<Atomic.I64Rmw32CmpxchgU>("i64", "i64.atomic.rmw32.cmpxchg_u")
        assertEquals(MemArg(align = 2, offset = 0), op.mem)
    }

    // ========================================================================
    // Cross-cutting concerns
    // ========================================================================

    /**
     * Non-zero `offset` immediate should round-trip through `consumeMemArg` —
     * exercises the second LEB128 of the memarg, which the per-instruction
     * tests above can't catch since they all use offset=0.
     */
    @Test
    fun `memarg offset is decoded from LEB128`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.atomic.load offset=128
              )
        """))
        val op = ops.filterIsInstance<Atomic.I32Load>().single()
        assertEquals(MemArg(align = 2, offset = 128), op.mem)
    }

    /**
     * Every opcode the parser emits must implement [AtomicOpCode] so the
     * size-analysis classifier can route it via the marker interface
     * instead of enumerating 67 subtypes.
     */
    @Test
    fun `every atomic op implements AtomicOpCode marker`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 i32 i64) (result i32)
                local.get 0
                local.get 1
                memory.atomic.notify
                drop
                local.get 0
                i32.atomic.load
                drop
                local.get 0
                local.get 1
                i32.atomic.store
                local.get 0
                local.get 1
                i32.atomic.rmw.add
                drop
                local.get 0
                local.get 1
                local.get 1
                i32.atomic.rmw.cmpxchg
                drop
                atomic.fence
                local.get 0
                local.get 1
                local.get 2
                memory.atomic.wait32
              )
        """))
        val atomics = ops.filter { it is AtomicOpCode }
        assertTrue(atomics.size >= 7, "expected ≥7 atomic opcodes, got $atomics")
        atomics.forEach { assertIs<AtomicOpCode>(it) }
    }

    /**
     * Atomic ops must classify as [OpcodeCategory.Memory] for size analysis.
     * `atomic.fence` is included because it is the structural odd-one-out
     * (no memarg) — easy to forget in the marker-interface plumbing.
     */
    @Test
    fun `atomic ops classify as Memory`() = runTest {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.atomic.load
                drop
                atomic.fence
                local.get 0
              )
        """))
        val atomics = ops.filter { it is AtomicOpCode }
        assertTrue(atomics.size >= 2, "expected load+fence, got $atomics")
        atomics.forEach { assertEquals(OpcodeCategory.Memory, classifyOpcode(it)) }
    }

    // ========================================================================
    // Helpers — RMW and cmpxchg follow a fixed shape, so the per-op WAT is
    // identical apart from the type and mnemonic. Pulling that into a helper
    // keeps each @Test to a single line.
    // ========================================================================

    private suspend inline fun <reified T : AtomicOpCode> singleRmw(
        wasmType: String,
        mnemonic: String,
    ): T {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 $wasmType) (result $wasmType)
                local.get 0
                local.get 1
                $mnemonic
              )
        """))
        return ops.filterIsInstance<T>().single()
    }

    private suspend inline fun <reified T : AtomicOpCode> singleCmpxchg(
        wasmType: String,
        mnemonic: String,
    ): T {
        val ops = parseInlineOpcodes(module("""
              (func (export "f") (param i32 $wasmType $wasmType) (result $wasmType)
                local.get 0
                local.get 1
                local.get 2
                $mnemonic
              )
        """))
        return ops.filterIsInstance<T>().single()
    }
}

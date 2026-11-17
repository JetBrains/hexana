package org.jetbrains.hexana.wasm.opcodes.atomic

import org.jetbrains.hexana.wasm.opcodes.AtomicOpCode
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset

/**
 * `0xFE`-prefixed atomic instructions from the Threads proposal
 * (now merged into the core WebAssembly specification).
 *
 * Encoding: `0xFE <subop:LEB128> <memarg>` for every atomic memory access.
 * `atomic.fence` is the lone exception — `0xFE 0x03 0x00` (single reserved
 * byte, no memarg).
 *
 * Spec: https://webassembly.github.io/threads/core/binary/instructions.html
 */
object Atomic {

    /**
     * `memory.atomic.notify memarg` — `0xFE 0x00`.
     * Wakes up to N waiters on the given linear-memory address.
     */
    class MemoryAtomicNotify(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object {
            const val code: UInt = 0x00u
        }
    }

    /**
     * `memory.atomic.wait32 memarg` — `0xFE 0x01`.
     * Blocks until the i32 at the given address differs from the expected value.
     */
    class MemoryAtomicWait32(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object {
            const val code: UInt = 0x01u
        }
    }

    /**
     * `memory.atomic.wait64 memarg` — `0xFE 0x02`.
     * Blocks until the i64 at the given address differs from the expected value.
     */
    class MemoryAtomicWait64(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object {
            const val code: UInt = 0x02u
        }
    }

    /**
     * `atomic.fence` — `0xFE 0x03 0x00`.
     * Sequentially-consistent fence. The trailing `0x00` is reserved for
     * future memory-order encodings; current spec rejects any other value.
     */
    class Fence(offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object {
            const val code: UInt = 0x03u
        }
    }

    // ------------------------------------------------------------------
    // i32 atomic loads — 0xFE 0x10..0x13
    // ------------------------------------------------------------------

    /** `i32.atomic.load memarg` — `0xFE 0x10`. */
    class I32Load(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x10u }
    }

    /** `i64.atomic.load memarg` — `0xFE 0x11`. */
    class I64Load(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x11u }
    }

    /** `i32.atomic.load8_u memarg` — `0xFE 0x12`. */
    class I32Load8U(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x12u }
    }

    /** `i32.atomic.load16_u memarg` — `0xFE 0x13`. */
    class I32Load16U(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x13u }
    }

    /** `i64.atomic.load8_u memarg` — `0xFE 0x14`. */
    class I64Load8U(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x14u }
    }

    /** `i64.atomic.load16_u memarg` — `0xFE 0x15`. */
    class I64Load16U(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x15u }
    }

    /** `i64.atomic.load32_u memarg` — `0xFE 0x16`. */
    class I64Load32U(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x16u }
    }

    // ------------------------------------------------------------------
    // i32/i64 atomic stores — 0xFE 0x17..0x1D
    // ------------------------------------------------------------------

    /** `i32.atomic.store memarg` — `0xFE 0x17`. */
    class I32Store(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x17u }
    }

    /** `i64.atomic.store memarg` — `0xFE 0x18`. */
    class I64Store(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x18u }
    }

    /** `i32.atomic.store8 memarg` — `0xFE 0x19`. */
    class I32Store8(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x19u }
    }

    /** `i32.atomic.store16 memarg` — `0xFE 0x1A`. */
    class I32Store16(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x1Au }
    }

    /** `i64.atomic.store8 memarg` — `0xFE 0x1B`. */
    class I64Store8(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x1Bu }
    }

    /** `i64.atomic.store16 memarg` — `0xFE 0x1C`. */
    class I64Store16(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x1Cu }
    }

    /** `i64.atomic.store32 memarg` — `0xFE 0x1D`. */
    class I64Store32(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x1Du }
    }

    // ------------------------------------------------------------------
    // RMW — add (0x1E..0x24)
    // ------------------------------------------------------------------

    /** `i32.atomic.rmw.add memarg` — `0xFE 0x1E`. */
    class I32RmwAdd(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x1Eu }
    }

    /** `i64.atomic.rmw.add memarg` — `0xFE 0x1F`. */
    class I64RmwAdd(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x1Fu }
    }

    /** `i32.atomic.rmw8.add_u memarg` — `0xFE 0x20`. */
    class I32Rmw8AddU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x20u }
    }

    /** `i32.atomic.rmw16.add_u memarg` — `0xFE 0x21`. */
    class I32Rmw16AddU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x21u }
    }

    /** `i64.atomic.rmw8.add_u memarg` — `0xFE 0x22`. */
    class I64Rmw8AddU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x22u }
    }

    /** `i64.atomic.rmw16.add_u memarg` — `0xFE 0x23`. */
    class I64Rmw16AddU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x23u }
    }

    /** `i64.atomic.rmw32.add_u memarg` — `0xFE 0x24`. */
    class I64Rmw32AddU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x24u }
    }

    // ------------------------------------------------------------------
    // RMW — sub (0x25..0x2B)
    // ------------------------------------------------------------------

    /** `i32.atomic.rmw.sub memarg` — `0xFE 0x25`. */
    class I32RmwSub(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x25u }
    }

    /** `i64.atomic.rmw.sub memarg` — `0xFE 0x26`. */
    class I64RmwSub(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x26u }
    }

    /** `i32.atomic.rmw8.sub_u memarg` — `0xFE 0x27`. */
    class I32Rmw8SubU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x27u }
    }

    /** `i32.atomic.rmw16.sub_u memarg` — `0xFE 0x28`. */
    class I32Rmw16SubU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x28u }
    }

    /** `i64.atomic.rmw8.sub_u memarg` — `0xFE 0x29`. */
    class I64Rmw8SubU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x29u }
    }

    /** `i64.atomic.rmw16.sub_u memarg` — `0xFE 0x2A`. */
    class I64Rmw16SubU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x2Au }
    }

    /** `i64.atomic.rmw32.sub_u memarg` — `0xFE 0x2B`. */
    class I64Rmw32SubU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x2Bu }
    }

    // ------------------------------------------------------------------
    // RMW — and (0x2C..0x32)
    // ------------------------------------------------------------------

    /** `i32.atomic.rmw.and memarg` — `0xFE 0x2C`. */
    class I32RmwAnd(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x2Cu }
    }

    /** `i64.atomic.rmw.and memarg` — `0xFE 0x2D`. */
    class I64RmwAnd(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x2Du }
    }

    /** `i32.atomic.rmw8.and_u memarg` — `0xFE 0x2E`. */
    class I32Rmw8AndU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x2Eu }
    }

    /** `i32.atomic.rmw16.and_u memarg` — `0xFE 0x2F`. */
    class I32Rmw16AndU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x2Fu }
    }

    /** `i64.atomic.rmw8.and_u memarg` — `0xFE 0x30`. */
    class I64Rmw8AndU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x30u }
    }

    /** `i64.atomic.rmw16.and_u memarg` — `0xFE 0x31`. */
    class I64Rmw16AndU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x31u }
    }

    /** `i64.atomic.rmw32.and_u memarg` — `0xFE 0x32`. */
    class I64Rmw32AndU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x32u }
    }

    // ------------------------------------------------------------------
    // RMW — or (0x33..0x39)
    // ------------------------------------------------------------------

    /** `i32.atomic.rmw.or memarg` — `0xFE 0x33`. */
    class I32RmwOr(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x33u }
    }

    /** `i64.atomic.rmw.or memarg` — `0xFE 0x34`. */
    class I64RmwOr(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x34u }
    }

    /** `i32.atomic.rmw8.or_u memarg` — `0xFE 0x35`. */
    class I32Rmw8OrU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x35u }
    }

    /** `i32.atomic.rmw16.or_u memarg` — `0xFE 0x36`. */
    class I32Rmw16OrU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x36u }
    }

    /** `i64.atomic.rmw8.or_u memarg` — `0xFE 0x37`. */
    class I64Rmw8OrU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x37u }
    }

    /** `i64.atomic.rmw16.or_u memarg` — `0xFE 0x38`. */
    class I64Rmw16OrU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x38u }
    }

    /** `i64.atomic.rmw32.or_u memarg` — `0xFE 0x39`. */
    class I64Rmw32OrU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x39u }
    }

    // ------------------------------------------------------------------
    // RMW — xor (0x3A..0x40)
    // ------------------------------------------------------------------

    /** `i32.atomic.rmw.xor memarg` — `0xFE 0x3A`. */
    class I32RmwXor(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x3Au }
    }

    /** `i64.atomic.rmw.xor memarg` — `0xFE 0x3B`. */
    class I64RmwXor(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x3Bu }
    }

    /** `i32.atomic.rmw8.xor_u memarg` — `0xFE 0x3C`. */
    class I32Rmw8XorU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x3Cu }
    }

    /** `i32.atomic.rmw16.xor_u memarg` — `0xFE 0x3D`. */
    class I32Rmw16XorU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x3Du }
    }

    /** `i64.atomic.rmw8.xor_u memarg` — `0xFE 0x3E`. */
    class I64Rmw8XorU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x3Eu }
    }

    /** `i64.atomic.rmw16.xor_u memarg` — `0xFE 0x3F`. */
    class I64Rmw16XorU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x3Fu }
    }

    /** `i64.atomic.rmw32.xor_u memarg` — `0xFE 0x40`. */
    class I64Rmw32XorU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x40u }
    }

    // ------------------------------------------------------------------
    // RMW — xchg (0x41..0x47)
    // ------------------------------------------------------------------

    /** `i32.atomic.rmw.xchg memarg` — `0xFE 0x41`. */
    class I32RmwXchg(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x41u }
    }

    /** `i64.atomic.rmw.xchg memarg` — `0xFE 0x42`. */
    class I64RmwXchg(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x42u }
    }

    /** `i32.atomic.rmw8.xchg_u memarg` — `0xFE 0x43`. */
    class I32Rmw8XchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x43u }
    }

    /** `i32.atomic.rmw16.xchg_u memarg` — `0xFE 0x44`. */
    class I32Rmw16XchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x44u }
    }

    /** `i64.atomic.rmw8.xchg_u memarg` — `0xFE 0x45`. */
    class I64Rmw8XchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x45u }
    }

    /** `i64.atomic.rmw16.xchg_u memarg` — `0xFE 0x46`. */
    class I64Rmw16XchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x46u }
    }

    /** `i64.atomic.rmw32.xchg_u memarg` — `0xFE 0x47`. */
    class I64Rmw32XchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x47u }
    }

    // ------------------------------------------------------------------
    // RMW — cmpxchg (0x48..0x4E)
    // ------------------------------------------------------------------

    /** `i32.atomic.rmw.cmpxchg memarg` — `0xFE 0x48`. */
    class I32RmwCmpxchg(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x48u }
    }

    /** `i64.atomic.rmw.cmpxchg memarg` — `0xFE 0x49`. */
    class I64RmwCmpxchg(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x49u }
    }

    /** `i32.atomic.rmw8.cmpxchg_u memarg` — `0xFE 0x4A`. */
    class I32Rmw8CmpxchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x4Au }
    }

    /** `i32.atomic.rmw16.cmpxchg_u memarg` — `0xFE 0x4B`. */
    class I32Rmw16CmpxchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x4Bu }
    }

    /** `i64.atomic.rmw8.cmpxchg_u memarg` — `0xFE 0x4C`. */
    class I64Rmw8CmpxchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x4Cu }
    }

    /** `i64.atomic.rmw16.cmpxchg_u memarg` — `0xFE 0x4D`. */
    class I64Rmw16CmpxchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x4Du }
    }

    /** `i64.atomic.rmw32.cmpxchg_u memarg` — `0xFE 0x4E`. */
    class I64Rmw32CmpxchgU(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), AtomicOpCode {
        companion object { const val code: UInt = 0x4Eu }
    }
}

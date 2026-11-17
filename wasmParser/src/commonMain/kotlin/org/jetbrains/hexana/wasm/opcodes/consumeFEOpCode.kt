package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.atomic.Atomic

/**
 * Decodes a `0xFE`-prefixed atomic instruction (Threads proposal, merged
 * into the core spec). All atomic memory accesses share the same shape:
 * `<subop:LEB128> <align:LEB128> <offset:LEB128>` (i.e. a [MemArg]).
 *
 * `atomic.fence` (`0x03`) is the lone exception — it carries a single
 * reserved byte instead of a memarg. The current spec only allows `0x00`
 * here; any other value is a validation error, but we still accept the
 * byte non-strictly so malformed modules can be inspected.
 */
fun CommonByteBuffer.consumeFEBasedOpCode(offset: Int, code: UInt): OpCode {
    return when (code) {
        Atomic.MemoryAtomicNotify.code -> Atomic.MemoryAtomicNotify(consumeMemArg(), offset)
        Atomic.MemoryAtomicWait32.code -> Atomic.MemoryAtomicWait32(consumeMemArg(), offset)
        Atomic.MemoryAtomicWait64.code -> Atomic.MemoryAtomicWait64(consumeMemArg(), offset)
        Atomic.Fence.code -> {
            // Reserved byte — currently must be 0; consumed unconditionally so
            // the parser stays aligned even on out-of-spec inputs.
            get()
            Atomic.Fence(offset)
        }

        Atomic.I32Load.code -> Atomic.I32Load(consumeMemArg(), offset)
        Atomic.I64Load.code -> Atomic.I64Load(consumeMemArg(), offset)
        Atomic.I32Load8U.code -> Atomic.I32Load8U(consumeMemArg(), offset)
        Atomic.I32Load16U.code -> Atomic.I32Load16U(consumeMemArg(), offset)
        Atomic.I64Load8U.code -> Atomic.I64Load8U(consumeMemArg(), offset)
        Atomic.I64Load16U.code -> Atomic.I64Load16U(consumeMemArg(), offset)
        Atomic.I64Load32U.code -> Atomic.I64Load32U(consumeMemArg(), offset)

        Atomic.I32Store.code -> Atomic.I32Store(consumeMemArg(), offset)
        Atomic.I64Store.code -> Atomic.I64Store(consumeMemArg(), offset)
        Atomic.I32Store8.code -> Atomic.I32Store8(consumeMemArg(), offset)
        Atomic.I32Store16.code -> Atomic.I32Store16(consumeMemArg(), offset)
        Atomic.I64Store8.code -> Atomic.I64Store8(consumeMemArg(), offset)
        Atomic.I64Store16.code -> Atomic.I64Store16(consumeMemArg(), offset)
        Atomic.I64Store32.code -> Atomic.I64Store32(consumeMemArg(), offset)

        Atomic.I32RmwAdd.code -> Atomic.I32RmwAdd(consumeMemArg(), offset)
        Atomic.I64RmwAdd.code -> Atomic.I64RmwAdd(consumeMemArg(), offset)
        Atomic.I32Rmw8AddU.code -> Atomic.I32Rmw8AddU(consumeMemArg(), offset)
        Atomic.I32Rmw16AddU.code -> Atomic.I32Rmw16AddU(consumeMemArg(), offset)
        Atomic.I64Rmw8AddU.code -> Atomic.I64Rmw8AddU(consumeMemArg(), offset)
        Atomic.I64Rmw16AddU.code -> Atomic.I64Rmw16AddU(consumeMemArg(), offset)
        Atomic.I64Rmw32AddU.code -> Atomic.I64Rmw32AddU(consumeMemArg(), offset)

        Atomic.I32RmwSub.code -> Atomic.I32RmwSub(consumeMemArg(), offset)
        Atomic.I64RmwSub.code -> Atomic.I64RmwSub(consumeMemArg(), offset)
        Atomic.I32Rmw8SubU.code -> Atomic.I32Rmw8SubU(consumeMemArg(), offset)
        Atomic.I32Rmw16SubU.code -> Atomic.I32Rmw16SubU(consumeMemArg(), offset)
        Atomic.I64Rmw8SubU.code -> Atomic.I64Rmw8SubU(consumeMemArg(), offset)
        Atomic.I64Rmw16SubU.code -> Atomic.I64Rmw16SubU(consumeMemArg(), offset)
        Atomic.I64Rmw32SubU.code -> Atomic.I64Rmw32SubU(consumeMemArg(), offset)

        Atomic.I32RmwAnd.code -> Atomic.I32RmwAnd(consumeMemArg(), offset)
        Atomic.I64RmwAnd.code -> Atomic.I64RmwAnd(consumeMemArg(), offset)
        Atomic.I32Rmw8AndU.code -> Atomic.I32Rmw8AndU(consumeMemArg(), offset)
        Atomic.I32Rmw16AndU.code -> Atomic.I32Rmw16AndU(consumeMemArg(), offset)
        Atomic.I64Rmw8AndU.code -> Atomic.I64Rmw8AndU(consumeMemArg(), offset)
        Atomic.I64Rmw16AndU.code -> Atomic.I64Rmw16AndU(consumeMemArg(), offset)
        Atomic.I64Rmw32AndU.code -> Atomic.I64Rmw32AndU(consumeMemArg(), offset)

        Atomic.I32RmwOr.code -> Atomic.I32RmwOr(consumeMemArg(), offset)
        Atomic.I64RmwOr.code -> Atomic.I64RmwOr(consumeMemArg(), offset)
        Atomic.I32Rmw8OrU.code -> Atomic.I32Rmw8OrU(consumeMemArg(), offset)
        Atomic.I32Rmw16OrU.code -> Atomic.I32Rmw16OrU(consumeMemArg(), offset)
        Atomic.I64Rmw8OrU.code -> Atomic.I64Rmw8OrU(consumeMemArg(), offset)
        Atomic.I64Rmw16OrU.code -> Atomic.I64Rmw16OrU(consumeMemArg(), offset)
        Atomic.I64Rmw32OrU.code -> Atomic.I64Rmw32OrU(consumeMemArg(), offset)

        Atomic.I32RmwXor.code -> Atomic.I32RmwXor(consumeMemArg(), offset)
        Atomic.I64RmwXor.code -> Atomic.I64RmwXor(consumeMemArg(), offset)
        Atomic.I32Rmw8XorU.code -> Atomic.I32Rmw8XorU(consumeMemArg(), offset)
        Atomic.I32Rmw16XorU.code -> Atomic.I32Rmw16XorU(consumeMemArg(), offset)
        Atomic.I64Rmw8XorU.code -> Atomic.I64Rmw8XorU(consumeMemArg(), offset)
        Atomic.I64Rmw16XorU.code -> Atomic.I64Rmw16XorU(consumeMemArg(), offset)
        Atomic.I64Rmw32XorU.code -> Atomic.I64Rmw32XorU(consumeMemArg(), offset)

        Atomic.I32RmwXchg.code -> Atomic.I32RmwXchg(consumeMemArg(), offset)
        Atomic.I64RmwXchg.code -> Atomic.I64RmwXchg(consumeMemArg(), offset)
        Atomic.I32Rmw8XchgU.code -> Atomic.I32Rmw8XchgU(consumeMemArg(), offset)
        Atomic.I32Rmw16XchgU.code -> Atomic.I32Rmw16XchgU(consumeMemArg(), offset)
        Atomic.I64Rmw8XchgU.code -> Atomic.I64Rmw8XchgU(consumeMemArg(), offset)
        Atomic.I64Rmw16XchgU.code -> Atomic.I64Rmw16XchgU(consumeMemArg(), offset)
        Atomic.I64Rmw32XchgU.code -> Atomic.I64Rmw32XchgU(consumeMemArg(), offset)

        Atomic.I32RmwCmpxchg.code -> Atomic.I32RmwCmpxchg(consumeMemArg(), offset)
        Atomic.I64RmwCmpxchg.code -> Atomic.I64RmwCmpxchg(consumeMemArg(), offset)
        Atomic.I32Rmw8CmpxchgU.code -> Atomic.I32Rmw8CmpxchgU(consumeMemArg(), offset)
        Atomic.I32Rmw16CmpxchgU.code -> Atomic.I32Rmw16CmpxchgU(consumeMemArg(), offset)
        Atomic.I64Rmw8CmpxchgU.code -> Atomic.I64Rmw8CmpxchgU(consumeMemArg(), offset)
        Atomic.I64Rmw16CmpxchgU.code -> Atomic.I64Rmw16CmpxchgU(consumeMemArg(), offset)
        Atomic.I64Rmw32CmpxchgU.code -> Atomic.I64Rmw32CmpxchgU(consumeMemArg(), offset)

        else -> throw UnsupportedOpsException(code, offset)
    }
}

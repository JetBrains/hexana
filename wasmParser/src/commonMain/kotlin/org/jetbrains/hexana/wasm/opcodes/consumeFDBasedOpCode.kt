package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.vector.*

fun CommonByteBuffer.consumeFDBasedOpCode(offset: Int, code: UInt): OpCode {
    return when (code) {
        // v128 load/store
        V128.Load.code -> V128.Load(consumeMemArg(), offset)
        V128.Load8x8S.code -> V128.Load8x8S(consumeMemArg(), offset)
        V128.Load8x8U.code -> V128.Load8x8U(consumeMemArg(), offset)
        V128.Load16x4S.code -> V128.Load16x4S(consumeMemArg(), offset)
        V128.Load16x4U.code -> V128.Load16x4U(consumeMemArg(), offset)
        V128.Load32x2S.code -> V128.Load32x2S(consumeMemArg(), offset)
        V128.Load32x2U.code -> V128.Load32x2U(consumeMemArg(), offset)
        V128.Load8Splat.code -> V128.Load8Splat(consumeMemArg(), offset)
        V128.Load16Splat.code -> V128.Load16Splat(consumeMemArg(), offset)
        V128.Load32Splat.code -> V128.Load32Splat(consumeMemArg(), offset)
        V128.Load64Splat.code -> V128.Load64Splat(consumeMemArg(), offset)
        V128.Store.code -> V128.Store(consumeMemArg(), offset)

        // v128.const — 16 immediate bytes
        V128.Const.code -> V128.Const(
            get(), get(), get(), get(),
            get(), get(), get(), get(),
            get(), get(), get(), get(),
            get(), get(), get(), get(),
            offset,
        )

        // i8x16.shuffle — 16 lane index immediates
        i8x16.Shuffle.code -> i8x16.Shuffle(
            get().toUByte(), get().toUByte(), get().toUByte(), get().toUByte(),
            get().toUByte(), get().toUByte(), get().toUByte(), get().toUByte(),
            get().toUByte(), get().toUByte(), get().toUByte(), get().toUByte(),
            get().toUByte(), get().toUByte(), get().toUByte(), get().toUByte(),
            offset,
        )

        // swizzle, splat
        i8x16.Swizzle.code -> i8x16.Swizzle
        i8x16.Splat.code -> i8x16.Splat
        i16x8.Splat.code -> i16x8.Splat
        i32x4.Splat.code -> i32x4.Splat
        i64x2.Splat.code -> i64x2.Splat
        f32x4.Splat.code -> f32x4.Splat
        f64x2.Splat.code -> f64x2.Splat

        // extract_lane / replace_lane — 1 byte lane index
        i8x16.ExtractLaneS.code -> i8x16.ExtractLaneS(get().toUByte(), offset)
        i8x16.ExtractLaneU.code -> i8x16.ExtractLaneU(get().toUByte(), offset)
        i8x16.ReplaceLine.code -> i8x16.ReplaceLine(get().toUByte(), offset)
        i16x8.ExtractLaneS.code -> i16x8.ExtractLaneS(get().toUByte(), offset)
        i16x8.ExtractLaneU.code -> i16x8.ExtractLaneU(get().toUByte(), offset)
        i16x8.ReplaceLine.code -> i16x8.ReplaceLine(get().toUByte(), offset)
        i32x4.ExtractLaneU.code -> i32x4.ExtractLaneU(get().toUByte(), offset)
        i32x4.ReplaceLine.code -> i32x4.ReplaceLine(get().toUByte(), offset)
        i64x2.ExtractLaneU.code -> i64x2.ExtractLaneU(get().toUByte(), offset)
        i64x2.ReplaceLine.code -> i64x2.ReplaceLine(get().toUByte(), offset)
        f32x4.ExtractLaneU.code -> f32x4.ExtractLaneU(get().toUByte(), offset)
        f32x4.ReplaceLine.code -> f32x4.ReplaceLine(get().toUByte(), offset)
        f64x2.ExtractLaneU.code -> f64x2.ExtractLaneU(get().toUByte(), offset)
        f64x2.ReplaceLine.code -> f64x2.ReplaceLine(get().toUByte(), offset)

        // i8x16 comparison
        i8x16.Eq.code -> i8x16.Eq
        i8x16.Ne.code -> i8x16.Ne
        i8x16.LtS.code -> i8x16.LtS
        i8x16.LtU.code -> i8x16.LtU
        i8x16.GtS.code -> i8x16.GtS
        i8x16.GtU.code -> i8x16.GtU
        i8x16.LeS.code -> i8x16.LeS
        i8x16.LeU.code -> i8x16.LeU
        i8x16.GeS.code -> i8x16.GeS
        i8x16.GeU.code -> i8x16.GeU

        // i16x8 comparison
        i16x8.Eq.code -> i16x8.Eq
        i16x8.Ne.code -> i16x8.Ne
        i16x8.LtS.code -> i16x8.LtS
        i16x8.LtU.code -> i16x8.LtU
        i16x8.GtS.code -> i16x8.GtS
        i16x8.GtU.code -> i16x8.GtU
        i16x8.LeS.code -> i16x8.LeS
        i16x8.LeU.code -> i16x8.LeU
        i16x8.GeS.code -> i16x8.GeS
        i16x8.GeU.code -> i16x8.GeU

        // i32x4 comparison
        i32x4.Eq.code -> i32x4.Eq
        i32x4.Ne.code -> i32x4.Ne
        i32x4.LtS.code -> i32x4.LtS
        i32x4.LtU.code -> i32x4.LtU
        i32x4.GtS.code -> i32x4.GtS
        i32x4.GtU.code -> i32x4.GtU
        i32x4.LeS.code -> i32x4.LeS
        i32x4.LeU.code -> i32x4.LeU
        i32x4.GeS.code -> i32x4.GeS
        i32x4.GeU.code -> i32x4.GeU

        // f32x4 comparison
        f32x4.Eq.code -> f32x4.Eq
        f32x4.Ne.code -> f32x4.Ne
        f32x4.Lt.code -> f32x4.Lt
        f32x4.Gt.code -> f32x4.Gt
        f32x4.Le.code -> f32x4.Le
        f32x4.Ge.code -> f32x4.Ge

        // f64x2 comparison
        f64x2.Eq.code -> f64x2.Eq
        f64x2.Ne.code -> f64x2.Ne
        f64x2.Lt.code -> f64x2.Lt
        f64x2.Gt.code -> f64x2.Gt
        f64x2.Le.code -> f64x2.Le
        f64x2.Ge.code -> f64x2.Ge

        // v128 bitwise
        V128.Not.code -> V128.Not
        V128.And.code -> V128.And
        V128.AndNot.code -> V128.AndNot
        V128.Or.code -> V128.Or
        V128.Xor.code -> V128.Xor
        V128.BitSelect.code -> V128.BitSelect
        V128.AnyTrue.code -> V128.AnyTrue

        // v128 lane loads/stores — memarg + laneIdx
        V128.Load8Lane.code -> { val m = consumeMemArg(); V128.Load8Lane(m, get().toUByte(), offset) }
        V128.Load16Lane.code -> { val m = consumeMemArg(); V128.Load16Lane(m, get().toUByte(), offset) }
        V128.Load32Lane.code -> { val m = consumeMemArg(); V128.Load32Lane(m, get().toUByte(), offset) }
        V128.Load64Lane.code -> { val m = consumeMemArg(); V128.Load64Lane(m, get().toUByte(), offset) }
        V128.Store8Lane.code -> { val m = consumeMemArg(); V128.Store8Lane(m, get().toUByte(), offset) }
        V128.Store16Lane.code -> { val m = consumeMemArg(); V128.Store16Lane(m, get().toUByte(), offset) }
        V128.Store32Lane.code -> { val m = consumeMemArg(); V128.Store32Lane(m, get().toUByte(), offset) }
        V128.Store64Lane.code -> { val m = consumeMemArg(); V128.Store64Lane(m, get().toUByte(), offset) }

        // v128 load zero
        V128.Load32Zero.code -> V128.Load32Zero(consumeMemArg(), offset)
        V128.Load64Zero.code -> V128.Load64Zero(consumeMemArg(), offset)

        // f32x4 / f64x2 rounding and conversions in code range 94-106
        f32x4.DemoteZeroF64x2.code -> f32x4.DemoteZeroF64x2
        f64x2.PromoteLowF32x4.code -> f64x2.PromoteLowF32x4

        // i8x16 arithmetic
        i8x16.Abs.code -> i8x16.Abs
        i8x16.Neg.code -> i8x16.Neg
        i8x16.PopCnt.code -> i8x16.PopCnt
        i8x16.AllTrue.code -> i8x16.AllTrue
        i8x16.BitMask.code -> i8x16.BitMask
        i8x16.NarrowI16x8S.code -> i8x16.NarrowI16x8S
        i8x16.NarrowI16x8U.code -> i8x16.NarrowI16x8U
        f32x4.Ceil.code -> f32x4.Ceil
        f32x4.Floor.code -> f32x4.Floor
        f32x4.Trunc.code -> f32x4.Trunc
        f32x4.Nearest.code -> f32x4.Nearest
        i8x16.Shl.code -> i8x16.Shl
        i8x16.ShrS.code -> i8x16.ShrS
        i8x16.ShrU.code -> i8x16.ShrU
        i8x16.Add.code -> i8x16.Add
        i8x16.AddSatS.code -> i8x16.AddSatS
        i8x16.AddSatU.code -> i8x16.AddSatU
        i8x16.Sub.code -> i8x16.Sub
        i8x16.SubSatS.code -> i8x16.SubSatS
        i8x16.SubSatU.code -> i8x16.SubSatU
        f64x2.Ceil.code -> f64x2.Ceil
        f64x2.Floor.code -> f64x2.Floor
        i8x16.MinS.code -> i8x16.MinS
        i8x16.MinU.code -> i8x16.MinU
        i8x16.MaxS.code -> i8x16.MaxS
        i8x16.MaxU.code -> i8x16.MaxU
        f64x2.Trunc.code -> f64x2.Trunc
        i8x16.AvgrU.code -> i8x16.AvgrU

        // i16x8 arithmetic
        i16x8.ExtAddPairwiseSi8x16.code -> i16x8.ExtAddPairwiseSi8x16
        i16x8.ExtAddPairwiseUi8x16.code -> i16x8.ExtAddPairwiseUi8x16
        i32x4.ExtAddPairwiseSi16x8.code -> i32x4.ExtAddPairwiseSi16x8
        i32x4.ExtAddPairwiseUi16x8.code -> i32x4.ExtAddPairwiseUi16x8
        i16x8.Abs.code -> i16x8.Abs
        i16x8.Neg.code -> i16x8.Neg
        i16x8.Q15MulrSatS.code -> i16x8.Q15MulrSatS
        i16x8.AllTrue.code -> i16x8.AllTrue
        i16x8.BitMask.code -> i16x8.BitMask
        i16x8.NarrowI32x4S.code -> i16x8.NarrowI32x4S
        i16x8.NarrowI32x4U.code -> i16x8.NarrowI32x4U
        i16x8.ExtendLowSi8x16.code -> i16x8.ExtendLowSi8x16
        i16x8.ExtendHighSi8x16.code -> i16x8.ExtendHighSi8x16
        i16x8.ExtendLowUi8x16.code -> i16x8.ExtendLowUi8x16
        i16x8.ExtendHighUi8x16.code -> i16x8.ExtendHighUi8x16
        i16x8.Shl.code -> i16x8.Shl
        i16x8.ShrS.code -> i16x8.ShrS
        i16x8.ShrU.code -> i16x8.ShrU
        i16x8.Add.code -> i16x8.Add
        i16x8.AddSatS.code -> i16x8.AddSatS
        i16x8.AddSatU.code -> i16x8.AddSatU
        i16x8.Sub.code -> i16x8.Sub
        i16x8.SubSatS.code -> i16x8.SubSatS
        i16x8.SubSatU.code -> i16x8.SubSatU
        f64x2.Nearest.code -> f64x2.Nearest
        i16x8.Mul.code -> i16x8.Mul
        i16x8.MinS.code -> i16x8.MinS
        i16x8.MinU.code -> i16x8.MinU
        i16x8.MaxS.code -> i16x8.MaxS
        i16x8.MaxU.code -> i16x8.MaxU
        i16x8.AvgrU.code -> i16x8.AvgrU
        i16x8.ExtmulLowSi8x16.code -> i16x8.ExtmulLowSi8x16
        i16x8.ExtmulHighSi8x16.code -> i16x8.ExtmulHighSi8x16
        i16x8.ExtmulLowUi8x16.code -> i16x8.ExtmulLowUi8x16
        i16x8.ExtmulHighUi8x16.code -> i16x8.ExtmulHighUi8x16

        // i32x4 arithmetic
        i32x4.Abs.code -> i32x4.Abs
        i32x4.Neg.code -> i32x4.Neg
        i32x4.AllTrue.code -> i32x4.AllTrue
        i32x4.BitMask.code -> i32x4.BitMask
        i32x4.ExtendLowSi16x8.code -> i32x4.ExtendLowSi16x8
        i32x4.ExtendHighSi16x8.code -> i32x4.ExtendHighSi16x8
        i32x4.ExtendLowUi16x8.code -> i32x4.ExtendLowUi16x8
        i32x4.ExtendHighUi16x8.code -> i32x4.ExtendHighUi16x8
        i32x4.Shl.code -> i32x4.Shl
        i32x4.ShrS.code -> i32x4.ShrS
        i32x4.ShrU.code -> i32x4.ShrU
        i32x4.Add.code -> i32x4.Add
        i32x4.Sub.code -> i32x4.Sub
        i32x4.Mul.code -> i32x4.Mul
        i32x4.MinS.code -> i32x4.MinS
        i32x4.MinU.code -> i32x4.MinU
        i32x4.MaxS.code -> i32x4.MaxS
        i32x4.MaxU.code -> i32x4.MaxU
        i32x4.DotSi16x8.code -> i32x4.DotSi16x8
        i32x4.ExtmulLowSi16x8.code -> i32x4.ExtmulLowSi16x8
        i32x4.ExtmulHighSi16x8.code -> i32x4.ExtmulHighSi16x8
        i32x4.ExtmulLowUi16x8.code -> i32x4.ExtmulLowUi16x8
        i32x4.ExtmulHighUi16x8.code -> i32x4.ExtmulHighUi16x8

        // i64x2 arithmetic
        i64x2.Abs.code -> i64x2.Abs
        i64x2.Neg.code -> i64x2.Neg
        i64x2.AllTrue.code -> i64x2.AllTrue
        i64x2.BitMask.code -> i64x2.BitMask
        i64x2.ExtendLowSi32x4.code -> i64x2.ExtendLowSi32x4
        i64x2.ExtendHighSi32x4.code -> i64x2.ExtendHighSi32x4
        i64x2.ExtendLowUi32x4.code -> i64x2.ExtendLowUi32x4
        i64x2.ExtendHighUi32x4.code -> i64x2.ExtendHighUi32x4
        i64x2.Shl.code -> i64x2.Shl
        i64x2.ShrS.code -> i64x2.ShrS
        i64x2.ShrU.code -> i64x2.ShrU
        i64x2.Add.code -> i64x2.Add
        i64x2.Sub.code -> i64x2.Sub
        i64x2.Mul.code -> i64x2.Mul
        i64x2.Eq.code -> i64x2.Eq
        i64x2.Ne.code -> i64x2.Ne
        i64x2.LtS.code -> i64x2.LtS
        i64x2.GtS.code -> i64x2.GtS
        i64x2.LeS.code -> i64x2.LeS
        i64x2.GeS.code -> i64x2.GeS
        i64x2.ExtmulLowSi32x4.code -> i64x2.ExtmulLowSi32x4
        i64x2.ExtmulHighSi32x4.code -> i64x2.ExtmulHighSi32x4
        i64x2.ExtmulLowUi32x4.code -> i64x2.ExtmulLowUi32x4
        i64x2.ExtmulHighUi32x4.code -> i64x2.ExtmulHighUi32x4

        // f32x4 arithmetic
        f32x4.Abs.code -> f32x4.Abs
        f32x4.Neg.code -> f32x4.Neg
        f32x4.Sqrt.code -> f32x4.Sqrt
        f32x4.Add.code -> f32x4.Add
        f32x4.Sub.code -> f32x4.Sub
        f32x4.Mul.code -> f32x4.Mul
        f32x4.Div.code -> f32x4.Div
        f32x4.Min.code -> f32x4.Min
        f32x4.Max.code -> f32x4.Max
        f32x4.PMin.code -> f32x4.PMin
        f32x4.PMax.code -> f32x4.PMax

        // f64x2 arithmetic
        f64x2.Abs.code -> f64x2.Abs
        f64x2.Neg.code -> f64x2.Neg
        f64x2.Sqrt.code -> f64x2.Sqrt
        f64x2.Add.code -> f64x2.Add
        f64x2.Sub.code -> f64x2.Sub
        f64x2.Mul.code -> f64x2.Mul
        f64x2.Div.code -> f64x2.Div
        f64x2.Min.code -> f64x2.Min
        f64x2.Max.code -> f64x2.Max
        f64x2.PMin.code -> f64x2.PMin
        f64x2.PMax.code -> f64x2.PMax

        // conversions
        i32x4.TruncSatSF32x4.code -> i32x4.TruncSatSF32x4
        i32x4.TruncSatUF32x4.code -> i32x4.TruncSatUF32x4
        f32x4.ConvertSI32x4.code -> f32x4.ConvertSI32x4
        f32x4.ConvertUF32x4.code -> f32x4.ConvertUF32x4
        i32x4.TruncSatSZeroF64x2.code -> i32x4.TruncSatSZeroF64x2
        i32x4.TruncSatUZeroF64x2.code -> i32x4.TruncSatUZeroF64x2
        f64x2.ConvertLowSI32x4.code -> f64x2.ConvertLowSI32x4
        f64x2.ConvertLowUF32x4.code -> f64x2.ConvertLowUF32x4

        // relaxed SIMD
        i8x16.RelaxedLaneselect.code -> i8x16.RelaxedLaneselect
        i16x8.RelaxedSwizzle.code -> i16x8.RelaxedSwizzle
        f32x4.RelaxedMAdd.code -> f32x4.RelaxedMAdd
        f32x4.RelaxedNMAdd.code -> f32x4.RelaxedNMAdd
        f64x2.RelaxedMAdd.code -> f64x2.RelaxedMAdd
        f64x2.RelaxedNMAdd.code -> f64x2.RelaxedNMAdd
        i16x8.RelaxedLaneselect.code -> i16x8.RelaxedLaneselect
        i32x4.RelaxedLaneselect.code -> i32x4.RelaxedLaneselect
        i64x2.RelaxedLaneselect.code -> i64x2.RelaxedLaneselect
        f32x4.RelaxedMin.code -> f32x4.RelaxedMin
        f32x4.RelaxedMax.code -> f32x4.RelaxedMax
        f64x2.RelaxedMin.code -> f64x2.RelaxedMin
        f64x2.RelaxedMax.code -> f64x2.RelaxedMax
        i16x8.RelaxedQ15MulrSatS.code -> i16x8.RelaxedQ15MulrSatS
        i16x8.RelaxedDotSi8x16.code -> i16x8.RelaxedDotSi8x16
        i32x4.RelaxedDotAddSi16x8.code -> i32x4.RelaxedDotAddSi16x8

        // relaxed-SIMD: i32x4.relaxed_trunc (0x101-0x104)
        i32x4.RelaxedTruncF32x4S.code -> i32x4.RelaxedTruncF32x4S
        i32x4.RelaxedTruncF32x4U.code -> i32x4.RelaxedTruncF32x4U
        i32x4.RelaxedTruncF32x4SZero.code -> i32x4.RelaxedTruncF32x4SZero
        i32x4.RelaxedTruncF32x4UZero.code -> i32x4.RelaxedTruncF32x4UZero

        else -> throw UnsupportedOpsException(code, offset)
    }
}

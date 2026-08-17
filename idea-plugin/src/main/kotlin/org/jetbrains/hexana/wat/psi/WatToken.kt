package org.jetbrains.hexana.wat.psi

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.jetbrains.hexana.wat.WatLanguage


class WatToken(val name: String) : IElementType(name, WatLanguage) {
    companion object {
        @JvmField val NUMBER = WatToken("number")
        @JvmField val WHITESPACE = WatToken("whitespace")
        @JvmField val NEWLINE = WatToken("newline")
        @JvmField val LINE_COMMENT = WatToken("line_comment")
        @JvmField val BLOCK_COMMENT = WatToken("block_comment")
        @JvmField val STRING = WatToken("string")
        @JvmField val IDENTIFIER = WatToken("identifier")
        @JvmField val ANNOTATION_ID = WatToken("annotation_id")
        @JvmField val OPEN_PAREN = WatToken("(")
        @JvmField val CLOSE_PAREN = WatToken(")")
        @JvmField val MINUS = WatToken("-")
        @JvmField val PLUS = WatToken("+")
        @JvmField val EQUALS = WatToken("=")

        @JvmField val ACTIVE = WatToken("active")
        @JvmField val ALIGN = WatToken("align")
        @JvmField val ANY = WatToken("any")
        @JvmField val ANY_CONVERT_EXTERN = WatToken("any.convert_extern")
        @JvmField val ANYREF = WatToken("anyref")
        @JvmField val ARRAY = WatToken("array")
        @JvmField val ARRAY_COPY = WatToken("array.copy")
        @JvmField val ARRAY_FILL = WatToken("array.fill")
        @JvmField val ARRAY_GET = WatToken("array.get")
        @JvmField val ARRAY_GET_S = WatToken("array.get_s")
        @JvmField val ARRAY_GET_U = WatToken("array.get_u")
        @JvmField val ARRAY_INIT_DATA = WatToken("array.init_data")
        @JvmField val ARRAY_INIT_ELEM = WatToken("array.init_elem")
        @JvmField val ARRAY_LEN = WatToken("array.len")
        @JvmField val ARRAY_NEW = WatToken("array.new")
        @JvmField val ARRAY_NEW_DATA = WatToken("array.new_data")
        @JvmField val ARRAY_NEW_DEFAULT = WatToken("array.new_default")
        @JvmField val ARRAY_NEW_ELEM = WatToken("array.new_elem")
        @JvmField val ARRAY_NEW_FIXED = WatToken("array.new_fixed")
        @JvmField val ARRAY_SET = WatToken("array.set")
        @JvmField val ARRAYREF = WatToken("arrayref")
        @JvmField val BLOCK = WatToken("block")
        @JvmField val BR = WatToken("br")
        @JvmField val BR_IF = WatToken("br_if")
        @JvmField val BR_ON_CAST = WatToken("br_on_cast")
        @JvmField val BR_ON_CAST_FAIL = WatToken("br_on_cast_fail")
        @JvmField val BR_ON_NON_NULL = WatToken("br_on_non_null")
        @JvmField val BR_ON_NULL = WatToken("br_on_null")
        @JvmField val BR_TABLE = WatToken("br_table")
        @JvmField val CALL = WatToken("call")
        @JvmField val CALL_INDIRECT = WatToken("call_indirect")
        @JvmField val CALL_REF = WatToken("call_ref")
        @JvmField val CANON = WatToken("canon")
        @JvmField val CATCH = WatToken("catch")
        @JvmField val CATCH_ALL = WatToken("catch_all")
        @JvmField val CATCH_ALL_REF = WatToken("catch_all_ref")
        @JvmField val CATCH_REF = WatToken("catch_ref")
        @JvmField val CORE = WatToken("core")
        @JvmField val COMPONENT = WatToken("component")
        @JvmField val DATA = WatToken("data")
        @JvmField val DATA_DROP = WatToken("data.drop")
        @JvmField val DECLARE = WatToken("declare")
        @JvmField val DROP = WatToken("drop")
        @JvmField val ELEM = WatToken("elem")
        @JvmField val ELEM_DROP = WatToken("elem.drop")
        @JvmField val ELSE = WatToken("else")
        @JvmField val END = WatToken("end")
        @JvmField val ENUM = WatToken("enum")
        @JvmField val EQ = WatToken("eq")
        @JvmField val EQREF = WatToken("eqref")
        @JvmField val EXN = WatToken("exn")
        @JvmField val EXNREF = WatToken("exnref")
        @JvmField val EXPORT = WatToken("export")
        @JvmField val EXTERN = WatToken("extern")
        @JvmField val EXTERN_CONVERT_ANY = WatToken("extern.convert_any")
        @JvmField val EXTERNREF = WatToken("externref")
        @JvmField val F32 = WatToken("f32")
        @JvmField val F32_ABS = WatToken("f32.abs")
        @JvmField val F32_ADD = WatToken("f32.add")
        @JvmField val F32_CEIL = WatToken("f32.ceil")
        @JvmField val F32_CONST = WatToken("f32.const")
        @JvmField val F32_CONVERT_I32_S = WatToken("f32.convert_i32_s")
        @JvmField val F32_CONVERT_I32_U = WatToken("f32.convert_i32_u")
        @JvmField val F32_CONVERT_I64_S = WatToken("f32.convert_i64_s")
        @JvmField val F32_CONVERT_I64_U = WatToken("f32.convert_i64_u")
        @JvmField val F32_COPYSIGN = WatToken("f32.copysign")
        @JvmField val F32_DEMOTE_F64 = WatToken("f32.demote_f64")
        @JvmField val F32_DIV = WatToken("f32.div")
        @JvmField val F32_EQ = WatToken("f32.eq")
        @JvmField val F32_FLOOR = WatToken("f32.floor")
        @JvmField val F32_GE = WatToken("f32.ge")
        @JvmField val F32_GT = WatToken("f32.gt")
        @JvmField val F32_LE = WatToken("f32.le")
        @JvmField val F32_LOAD = WatToken("f32.load")
        @JvmField val F32_LT = WatToken("f32.lt")
        @JvmField val F32_MAX = WatToken("f32.max")
        @JvmField val F32_MIN = WatToken("f32.min")
        @JvmField val F32_MUL = WatToken("f32.mul")
        @JvmField val F32_NE = WatToken("f32.ne")
        @JvmField val F32_NEAREST = WatToken("f32.nearest")
        @JvmField val F32_NEG = WatToken("f32.neg")
        @JvmField val F32_REINTERPRET_I32 = WatToken("f32.reinterpret_i32")
        @JvmField val F32_SQRT = WatToken("f32.sqrt")
        @JvmField val F32_STORE = WatToken("f32.store")
        @JvmField val F32_SUB = WatToken("f32.sub")
        @JvmField val F32_TRUNC = WatToken("f32.trunc")
        @JvmField val F32X4_ABS = WatToken("f32x4.abs")
        @JvmField val F32X4_ADD = WatToken("f32x4.add")
        @JvmField val F32X4_CEIL = WatToken("f32x4.ceil")
        @JvmField val F32X4_CONVERT_S_I32X4 = WatToken("f32x4.convert_s_i32x4")
        @JvmField val F32X4_CONVERT_U_I32X4 = WatToken("f32x4.convert_u_i32x4")
        @JvmField val F32X4_DEMOTE_ZERO_F64X2 = WatToken("f32x4.demote_zero_f64x2")
        @JvmField val F32X4_DIV = WatToken("f32x4.div")
        @JvmField val F32X4_EQ = WatToken("f32x4.eq")
        @JvmField val F32X4_EXTRACT_LANE = WatToken("f32x4.extract_lane")
        @JvmField val F32X4_FLOOR = WatToken("f32x4.floor")
        @JvmField val F32X4_GE = WatToken("f32x4.ge")
        @JvmField val F32X4_GT = WatToken("f32x4.gt")
        @JvmField val F32X4_LE = WatToken("f32x4.le")
        @JvmField val F32X4_LT = WatToken("f32x4.lt")
        @JvmField val F32X4_MAX = WatToken("f32x4.max")
        @JvmField val F32X4_MIN = WatToken("f32x4.min")
        @JvmField val F32X4_MUL = WatToken("f32x4.mul")
        @JvmField val F32X4_NE = WatToken("f32x4.ne")
        @JvmField val F32X4_NEAREST = WatToken("f32x4.nearest")
        @JvmField val F32X4_NEG = WatToken("f32x4.neg")
        @JvmField val F32X4_PMAX = WatToken("f32x4.pmax")
        @JvmField val F32X4_PMIN = WatToken("f32x4.pmin")
        @JvmField val F32X4_RELAXED_MADD = WatToken("f32x4.relaxed_madd")
        @JvmField val F32X4_RELAXED_MAX = WatToken("f32x4.relaxed_max")
        @JvmField val F32X4_RELAXED_MIN = WatToken("f32x4.relaxed_min")
        @JvmField val F32X4_RELAXED_NMADD = WatToken("f32x4.relaxed_nmadd")
        @JvmField val F32X4_REPLACE_LANE = WatToken("f32x4.replace_lane")
        @JvmField val F32X4_SPLAT = WatToken("f32x4.splat")
        @JvmField val F32X4_SQRT = WatToken("f32x4.sqrt")
        @JvmField val F32X4_SUB = WatToken("f32x4.sub")
        @JvmField val F32X4_TRUNC = WatToken("f32x4.trunc")
        @JvmField val F64 = WatToken("f64")
        @JvmField val F64_ABS = WatToken("f64.abs")
        @JvmField val F64_ADD = WatToken("f64.add")
        @JvmField val F64_CEIL = WatToken("f64.ceil")
        @JvmField val F64_CONST = WatToken("f64.const")
        @JvmField val F64_CONVERT_I32_S = WatToken("f64.convert_i32_s")
        @JvmField val F64_CONVERT_I32_U = WatToken("f64.convert_i32_u")
        @JvmField val F64_CONVERT_I64_S = WatToken("f64.convert_i64_s")
        @JvmField val F64_CONVERT_I64_U = WatToken("f64.convert_i64_u")
        @JvmField val F64_COPYSIGN = WatToken("f64.copysign")
        @JvmField val F64_DIV = WatToken("f64.div")
        @JvmField val F64_EQ = WatToken("f64.eq")
        @JvmField val F64_FLOOR = WatToken("f64.floor")
        @JvmField val F64_GE = WatToken("f64.ge")
        @JvmField val F64_GT = WatToken("f64.gt")
        @JvmField val F64_LE = WatToken("f64.le")
        @JvmField val F64_LOAD = WatToken("f64.load")
        @JvmField val F64_LT = WatToken("f64.lt")
        @JvmField val F64_MAX = WatToken("f64.max")
        @JvmField val F64_MIN = WatToken("f64.min")
        @JvmField val F64_MUL = WatToken("f64.mul")
        @JvmField val F64_NE = WatToken("f64.ne")
        @JvmField val F64_NEAREST = WatToken("f64.nearest")
        @JvmField val F64_NEG = WatToken("f64.neg")
        @JvmField val F64_PROMOTE_F32 = WatToken("f64.promote_f32")
        @JvmField val F64_REINTERPRET_I64 = WatToken("f64.reinterpret_i64")
        @JvmField val F64_SQRT = WatToken("f64.sqrt")
        @JvmField val F64_STORE = WatToken("f64.store")
        @JvmField val F64_SUB = WatToken("f64.sub")
        @JvmField val F64_TRUNC = WatToken("f64.trunc")
        @JvmField val F64X2_ABS = WatToken("f64x2.abs")
        @JvmField val F64X2_ADD = WatToken("f64x2.add")
        @JvmField val F64X2_CEIL = WatToken("f64x2.ceil")
        @JvmField val F64X2_CONVERT_LOW_S_I32X4 = WatToken("f64x2.convert_low_s_i32x4")
        @JvmField val F64X2_CONVERT_LOW_U_I32X4 = WatToken("f64x2.convert_low_u_i32x4")
        @JvmField val F64X2_DIV = WatToken("f64x2.div")
        @JvmField val F64X2_EQ = WatToken("f64x2.eq")
        @JvmField val F64X2_EXTRACT_LANE = WatToken("f64x2.extract_lane")
        @JvmField val F64X2_FLOOR = WatToken("f64x2.floor")
        @JvmField val F64X2_GE = WatToken("f64x2.ge")
        @JvmField val F64X2_GT = WatToken("f64x2.gt")
        @JvmField val F64X2_LE = WatToken("f64x2.le")
        @JvmField val F64X2_LT = WatToken("f64x2.lt")
        @JvmField val F64X2_MAX = WatToken("f64x2.max")
        @JvmField val F64X2_MIN = WatToken("f64x2.min")
        @JvmField val F64X2_MUL = WatToken("f64x2.mul")
        @JvmField val F64X2_NE = WatToken("f64x2.ne")
        @JvmField val F64X2_NEAREST = WatToken("f64x2.nearest")
        @JvmField val F64X2_NEG = WatToken("f64x2.neg")
        @JvmField val F64X2_PMAX = WatToken("f64x2.pmax")
        @JvmField val F64X2_PMIN = WatToken("f64x2.pmin")
        @JvmField val F64X2_PROMOTE_LOW_F32X4 = WatToken("f64x2.promote_low_f32x4")
        @JvmField val F64X2_RELAXED_MADD = WatToken("f64x2.relaxed_madd")
        @JvmField val F64X2_RELAXED_MAX = WatToken("f64x2.relaxed_max")
        @JvmField val F64X2_RELAXED_MIN = WatToken("f64x2.relaxed_min")
        @JvmField val F64X2_RELAXED_NMADD = WatToken("f64x2.relaxed_nmadd")
        @JvmField val F64X2_REPLACE_LANE = WatToken("f64x2.replace_lane")
        @JvmField val F64X2_SPLAT = WatToken("f64x2.splat")
        @JvmField val F64X2_SQRT = WatToken("f64x2.sqrt")
        @JvmField val F64X2_SUB = WatToken("f64x2.sub")
        @JvmField val F64X2_TRUNC = WatToken("f64x2.trunc")
        @JvmField val FIELD = WatToken("field")
        @JvmField val FINAL = WatToken("final")
        @JvmField val FUNC = WatToken("func")
        @JvmField val FUNCREF = WatToken("funcref")
        @JvmField val GLOBAL = WatToken("global")
        @JvmField val GLOBAL_GET = WatToken("global.get")
        @JvmField val GLOBAL_SET = WatToken("global.set")
        @JvmField val I16 = WatToken("i16")
        @JvmField val I16X8_ABS = WatToken("i16x8.abs")
        @JvmField val I16X8_ADD = WatToken("i16x8.add")
        @JvmField val I16X8_ADD_SAT_S = WatToken("i16x8.add_sat_s")
        @JvmField val I16X8_ADD_SAT_U = WatToken("i16x8.add_sat_u")
        @JvmField val I16X8_ALL_TRUE = WatToken("i16x8.all_true")
        @JvmField val I16X8_AVGR_U = WatToken("i16x8.avgr_u")
        @JvmField val I16X8_BITMASK = WatToken("i16x8.bitmask")
        @JvmField val I16X8_EQ = WatToken("i16x8.eq")
        @JvmField val I16X8_EXTADD_PAIRWISE_I8X16_S = WatToken("i16x8.extadd_pairwise_i8x16_s")
        @JvmField val I16X8_EXTADD_PAIRWISE_I8X16_U = WatToken("i16x8.extadd_pairwise_i8x16_u")
        @JvmField val I16X8_EXTEND_HIGH_S_I8X16 = WatToken("i16x8.extend_high_s_i8x16")
        @JvmField val I16X8_EXTEND_HIGH_U_I8X16 = WatToken("i16x8.extend_high_u_i8x16")
        @JvmField val I16X8_EXTEND_LOW_S_I8X16 = WatToken("i16x8.extend_low_s_i8x16")
        @JvmField val I16X8_EXTEND_LOW_U_I8X16 = WatToken("i16x8.extend_low_u_i8x16")
        @JvmField val I16X8_EXTMUL_HIGH_S_I8X16 = WatToken("i16x8.extmul_high_s_i8x16")
        @JvmField val I16X8_EXTMUL_HIGH_U_I8X16 = WatToken("i16x8.extmul_high_u_i8x16")
        @JvmField val I16X8_EXTMUL_LOW_S_I8X16 = WatToken("i16x8.extmul_low_s_i8x16")
        @JvmField val I16X8_EXTMUL_LOW_U_I8X16 = WatToken("i16x8.extmul_low_u_i8x16")
        @JvmField val I16X8_EXTRACT_LANE_S = WatToken("i16x8.extract_lane_s")
        @JvmField val I16X8_EXTRACT_LANE_U = WatToken("i16x8.extract_lane_u")
        @JvmField val I16X8_GE_S = WatToken("i16x8.ge_s")
        @JvmField val I16X8_GE_U = WatToken("i16x8.ge_u")
        @JvmField val I16X8_GT_S = WatToken("i16x8.gt_s")
        @JvmField val I16X8_GT_U = WatToken("i16x8.gt_u")
        @JvmField val I16X8_LE_S = WatToken("i16x8.le_s")
        @JvmField val I16X8_LE_U = WatToken("i16x8.le_u")
        @JvmField val I16X8_LT_S = WatToken("i16x8.lt_s")
        @JvmField val I16X8_LT_U = WatToken("i16x8.lt_u")
        @JvmField val I16X8_MAX_S = WatToken("i16x8.max_s")
        @JvmField val I16X8_MAX_U = WatToken("i16x8.max_u")
        @JvmField val I16X8_MIN_S = WatToken("i16x8.min_s")
        @JvmField val I16X8_MIN_U = WatToken("i16x8.min_u")
        @JvmField val I16X8_MUL = WatToken("i16x8.mul")
        @JvmField val I16X8_NARROW_I32X4_S = WatToken("i16x8.narrow_i32x4_s")
        @JvmField val I16X8_NARROW_I32X4_U = WatToken("i16x8.narrow_i32x4_u")
        @JvmField val I16X8_NE = WatToken("i16x8.ne")
        @JvmField val I16X8_NEG = WatToken("i16x8.neg")
        @JvmField val I16X8_Q15MULR_SAT_S = WatToken("i16x8.q15mulr_sat_s")
        @JvmField val I16X8_RELAXED_DOT_I8X16_I7X16_ADD_S = WatToken("i16x8.relaxed_dot_i8x16_i7x16_add_s")
        @JvmField val I16X8_RELAXED_DOT_I8X16_I7X16_S = WatToken("i16x8.relaxed_dot_i8x16_i7x16_s")
        @JvmField val I16X8_RELAXED_DOT_S_I8X16 = WatToken("i16x8.relaxed_dot_s_i8x16")
        @JvmField val I16X8_RELAXED_LANESELECT = WatToken("i16x8.relaxed_laneselect")
        @JvmField val I16X8_RELAXED_Q15MULR_S = WatToken("i16x8.relaxed_q15mulr_s")
        @JvmField val I16X8_RELAXED_SWIZZLE = WatToken("i16x8.relaxed_swizzle")
        @JvmField val I16X8_REPLACE_LANE = WatToken("i16x8.replace_lane")
        @JvmField val I16X8_SHL = WatToken("i16x8.shl")
        @JvmField val I16X8_SHR_S = WatToken("i16x8.shr_s")
        @JvmField val I16X8_SHR_U = WatToken("i16x8.shr_u")
        @JvmField val I16X8_SPLAT = WatToken("i16x8.splat")
        @JvmField val I16X8_SUB = WatToken("i16x8.sub")
        @JvmField val I16X8_SUB_SAT_S = WatToken("i16x8.sub_sat_s")
        @JvmField val I16X8_SUB_SAT_U = WatToken("i16x8.sub_sat_u")
        @JvmField val I31 = WatToken("i31")
        @JvmField val I31_GET_S = WatToken("i31.get_s")
        @JvmField val I31_GET_U = WatToken("i31.get_u")
        @JvmField val I31REF = WatToken("i31ref")
        @JvmField val I32 = WatToken("i32")
        @JvmField val I32_ADD = WatToken("i32.add")
        @JvmField val I32_AND = WatToken("i32.and")
        @JvmField val I32_CLZ = WatToken("i32.clz")
        @JvmField val I32_CONST = WatToken("i32.const")
        @JvmField val I32_CTZ = WatToken("i32.ctz")
        @JvmField val I32_DIV_S = WatToken("i32.div_s")
        @JvmField val I32_DIV_U = WatToken("i32.div_u")
        @JvmField val I32_EQ = WatToken("i32.eq")
        @JvmField val I32_EQZ = WatToken("i32.eqz")
        @JvmField val I32_EXTEND16_S = WatToken("i32.extend16_s")
        @JvmField val I32_EXTEND8_S = WatToken("i32.extend8_s")
        @JvmField val I32_GE_S = WatToken("i32.ge_s")
        @JvmField val I32_GE_U = WatToken("i32.ge_u")
        @JvmField val I32_GT_S = WatToken("i32.gt_s")
        @JvmField val I32_GT_U = WatToken("i32.gt_u")
        @JvmField val I32_LE_S = WatToken("i32.le_s")
        @JvmField val I32_LE_U = WatToken("i32.le_u")
        @JvmField val I32_LOAD = WatToken("i32.load")
        @JvmField val I32_LOAD16_S = WatToken("i32.load16_s")
        @JvmField val I32_LOAD16_U = WatToken("i32.load16_u")
        @JvmField val I32_LOAD8_S = WatToken("i32.load8_s")
        @JvmField val I32_LOAD8_U = WatToken("i32.load8_u")
        @JvmField val I32_LT_S = WatToken("i32.lt_s")
        @JvmField val I32_LT_U = WatToken("i32.lt_u")
        @JvmField val I32_MUL = WatToken("i32.mul")
        @JvmField val I32_NE = WatToken("i32.ne")
        @JvmField val I32_OR = WatToken("i32.or")
        @JvmField val I32_POPCNT = WatToken("i32.popcnt")
        @JvmField val I32_REINTERPRET_F32 = WatToken("i32.reinterpret_f32")
        @JvmField val I32_REM_S = WatToken("i32.rem_s")
        @JvmField val I32_REM_U = WatToken("i32.rem_u")
        @JvmField val I32_ROTL = WatToken("i32.rotl")
        @JvmField val I32_ROTR = WatToken("i32.rotr")
        @JvmField val I32_SHL = WatToken("i32.shl")
        @JvmField val I32_SHR_S = WatToken("i32.shr_s")
        @JvmField val I32_SHR_U = WatToken("i32.shr_u")
        @JvmField val I32_STORE = WatToken("i32.store")
        @JvmField val I32_STORE16 = WatToken("i32.store16")
        @JvmField val I32_STORE8 = WatToken("i32.store8")
        @JvmField val I32_SUB = WatToken("i32.sub")
        @JvmField val I32_TRUNC_F32_S = WatToken("i32.trunc_f32_s")
        @JvmField val I32_TRUNC_F32_U = WatToken("i32.trunc_f32_u")
        @JvmField val I32_TRUNC_F64_S = WatToken("i32.trunc_f64_s")
        @JvmField val I32_TRUNC_F64_U = WatToken("i32.trunc_f64_u")
        @JvmField val I32_TRUNC_SAT_F32_S = WatToken("i32.trunc_sat_f32_s")
        @JvmField val I32_TRUNC_SAT_F32_U = WatToken("i32.trunc_sat_f32_u")
        @JvmField val I32_TRUNC_SAT_F64_S = WatToken("i32.trunc_sat_f64_s")
        @JvmField val I32_TRUNC_SAT_F64_U = WatToken("i32.trunc_sat_f64_u")
        @JvmField val I32_WRAP_I64 = WatToken("i32.wrap_i64")
        @JvmField val I32_XOR = WatToken("i32.xor")
        @JvmField val I32X4_ABS = WatToken("i32x4.abs")
        @JvmField val I32X4_ADD = WatToken("i32x4.add")
        @JvmField val I32X4_ALL_TRUE = WatToken("i32x4.all_true")
        @JvmField val I32X4_BITMASK = WatToken("i32x4.bitmask")
        @JvmField val I32X4_DOT_S_I16X8 = WatToken("i32x4.dot_s_i16x8")
        @JvmField val I32X4_EQ = WatToken("i32x4.eq")
        @JvmField val I32X4_EXTADD_PAIRWISE_I16X8_S = WatToken("i32x4.extadd_pairwise_i16x8_s")
        @JvmField val I32X4_EXTADD_PAIRWISE_I16X8_U = WatToken("i32x4.extadd_pairwise_i16x8_u")
        @JvmField val I32X4_EXTEND_HIGH_S_I16X8 = WatToken("i32x4.extend_high_s_i16x8")
        @JvmField val I32X4_EXTEND_HIGH_U_I16X8 = WatToken("i32x4.extend_high_u_i16x8")
        @JvmField val I32X4_EXTEND_LOW_S_I16X8 = WatToken("i32x4.extend_low_s_i16x8")
        @JvmField val I32X4_EXTEND_LOW_U_I16X8 = WatToken("i32x4.extend_low_u_i16x8")
        @JvmField val I32X4_EXTMUL_HIGH_S_I16X8 = WatToken("i32x4.extmul_high_s_i16x8")
        @JvmField val I32X4_EXTMUL_HIGH_U_I16X8 = WatToken("i32x4.extmul_high_u_i16x8")
        @JvmField val I32X4_EXTMUL_LOW_S_I16X8 = WatToken("i32x4.extmul_low_s_i16x8")
        @JvmField val I32X4_EXTMUL_LOW_U_I16X8 = WatToken("i32x4.extmul_low_u_i16x8")
        @JvmField val I32X4_EXTRACT_LANE = WatToken("i32x4.extract_lane")
        @JvmField val I32X4_GE_S = WatToken("i32x4.ge_s")
        @JvmField val I32X4_GE_U = WatToken("i32x4.ge_u")
        @JvmField val I32X4_GT_S = WatToken("i32x4.gt_s")
        @JvmField val I32X4_GT_U = WatToken("i32x4.gt_u")
        @JvmField val I32X4_LE_S = WatToken("i32x4.le_s")
        @JvmField val I32X4_LE_U = WatToken("i32x4.le_u")
        @JvmField val I32X4_LT_S = WatToken("i32x4.lt_s")
        @JvmField val I32X4_LT_U = WatToken("i32x4.lt_u")
        @JvmField val I32X4_MAX_S = WatToken("i32x4.max_s")
        @JvmField val I32X4_MAX_U = WatToken("i32x4.max_u")
        @JvmField val I32X4_MIN_S = WatToken("i32x4.min_s")
        @JvmField val I32X4_MIN_U = WatToken("i32x4.min_u")
        @JvmField val I32X4_MUL = WatToken("i32x4.mul")
        @JvmField val I32X4_NE = WatToken("i32x4.ne")
        @JvmField val I32X4_NEG = WatToken("i32x4.neg")
        @JvmField val I32X4_RELAXED_DOT_ADD_S_I16X8 = WatToken("i32x4.relaxed_dot_add_s_i16x8")
        @JvmField val I32X4_RELAXED_LANESELECT = WatToken("i32x4.relaxed_laneselect")
        @JvmField val I32X4_RELAXED_TRUNC_F32X4_S = WatToken("i32x4.relaxed_trunc_f32x4_s")
        @JvmField val I32X4_RELAXED_TRUNC_F32X4_S_ZERO = WatToken("i32x4.relaxed_trunc_f32x4_s_zero")
        @JvmField val I32X4_RELAXED_TRUNC_F32X4_U = WatToken("i32x4.relaxed_trunc_f32x4_u")
        @JvmField val I32X4_RELAXED_TRUNC_F32X4_U_ZERO = WatToken("i32x4.relaxed_trunc_f32x4_u_zero")
        @JvmField val I32X4_RELAXED_TRUNC_S_F32X4 = WatToken("i32x4.relaxed_trunc_s_f32x4")
        @JvmField val I32X4_RELAXED_TRUNC_S_ZERO_F64X2 = WatToken("i32x4.relaxed_trunc_s_zero_f64x2")
        @JvmField val I32X4_RELAXED_TRUNC_U_F32X4 = WatToken("i32x4.relaxed_trunc_u_f32x4")
        @JvmField val I32X4_RELAXED_TRUNC_U_ZERO_F64X2 = WatToken("i32x4.relaxed_trunc_u_zero_f64x2")
        @JvmField val I32X4_REPLACE_LANE = WatToken("i32x4.replace_lane")
        @JvmField val I32X4_SHL = WatToken("i32x4.shl")
        @JvmField val I32X4_SHR_S = WatToken("i32x4.shr_s")
        @JvmField val I32X4_SHR_U = WatToken("i32x4.shr_u")
        @JvmField val I32X4_SPLAT = WatToken("i32x4.splat")
        @JvmField val I32X4_SUB = WatToken("i32x4.sub")
        @JvmField val I32X4_TRUNC_SAT_F32X4_S = WatToken("i32x4.trunc_sat_f32x4_s")
        @JvmField val I32X4_TRUNC_SAT_F32X4_U = WatToken("i32x4.trunc_sat_f32x4_u")
        @JvmField val I32X4_TRUNC_SAT_S_ZERO_F64X2 = WatToken("i32x4.trunc_sat_s_zero_f64x2")
        @JvmField val I32X4_TRUNC_SAT_U_ZERO_F64X2 = WatToken("i32x4.trunc_sat_u_zero_f64x2")
        @JvmField val I64 = WatToken("i64")
        @JvmField val I64_ADD = WatToken("i64.add")
        @JvmField val I64_AND = WatToken("i64.and")
        @JvmField val I64_CLZ = WatToken("i64.clz")
        @JvmField val I64_CONST = WatToken("i64.const")
        @JvmField val I64_CTZ = WatToken("i64.ctz")
        @JvmField val I64_DIV_S = WatToken("i64.div_s")
        @JvmField val I64_DIV_U = WatToken("i64.div_u")
        @JvmField val I64_EQ = WatToken("i64.eq")
        @JvmField val I64_EQZ = WatToken("i64.eqz")
        @JvmField val I64_EXTEND16_S = WatToken("i64.extend16_s")
        @JvmField val I64_EXTEND32_S = WatToken("i64.extend32_s")
        @JvmField val I64_EXTEND8_S = WatToken("i64.extend8_s")
        @JvmField val I64_EXTEND_I32_S = WatToken("i64.extend_i32_s")
        @JvmField val I64_EXTEND_I32_U = WatToken("i64.extend_i32_u")
        @JvmField val I64_GE_S = WatToken("i64.ge_s")
        @JvmField val I64_GE_U = WatToken("i64.ge_u")
        @JvmField val I64_GT_S = WatToken("i64.gt_s")
        @JvmField val I64_GT_U = WatToken("i64.gt_u")
        @JvmField val I64_LE_S = WatToken("i64.le_s")
        @JvmField val I64_LE_U = WatToken("i64.le_u")
        @JvmField val I64_LOAD = WatToken("i64.load")
        @JvmField val I64_LOAD16_S = WatToken("i64.load16_s")
        @JvmField val I64_LOAD16_U = WatToken("i64.load16_u")
        @JvmField val I64_LOAD32_S = WatToken("i64.load32_s")
        @JvmField val I64_LOAD32_U = WatToken("i64.load32_u")
        @JvmField val I64_LOAD8_S = WatToken("i64.load8_s")
        @JvmField val I64_LOAD8_U = WatToken("i64.load8_u")
        @JvmField val I64_LT_S = WatToken("i64.lt_s")
        @JvmField val I64_LT_U = WatToken("i64.lt_u")
        @JvmField val I64_MUL = WatToken("i64.mul")
        @JvmField val I64_NE = WatToken("i64.ne")
        @JvmField val I64_OR = WatToken("i64.or")
        @JvmField val I64_POPCNT = WatToken("i64.popcnt")
        @JvmField val I64_REINTERPRET_F64 = WatToken("i64.reinterpret_f64")
        @JvmField val I64_REM_S = WatToken("i64.rem_s")
        @JvmField val I64_REM_U = WatToken("i64.rem_u")
        @JvmField val I64_ROTL = WatToken("i64.rotl")
        @JvmField val I64_ROTR = WatToken("i64.rotr")
        @JvmField val I64_SHL = WatToken("i64.shl")
        @JvmField val I64_SHR_S = WatToken("i64.shr_s")
        @JvmField val I64_SHR_U = WatToken("i64.shr_u")
        @JvmField val I64_STORE = WatToken("i64.store")
        @JvmField val I64_STORE16 = WatToken("i64.store16")
        @JvmField val I64_STORE32 = WatToken("i64.store32")
        @JvmField val I64_STORE8 = WatToken("i64.store8")
        @JvmField val I64_SUB = WatToken("i64.sub")
        @JvmField val I64_TRUNC_F32_S = WatToken("i64.trunc_f32_s")
        @JvmField val I64_TRUNC_F32_U = WatToken("i64.trunc_f32_u")
        @JvmField val I64_TRUNC_F64_S = WatToken("i64.trunc_f64_s")
        @JvmField val I64_TRUNC_F64_U = WatToken("i64.trunc_f64_u")
        @JvmField val I64_TRUNC_SAT_F32_S = WatToken("i64.trunc_sat_f32_s")
        @JvmField val I64_TRUNC_SAT_F32_U = WatToken("i64.trunc_sat_f32_u")
        @JvmField val I64_TRUNC_SAT_F64_S = WatToken("i64.trunc_sat_f64_s")
        @JvmField val I64_TRUNC_SAT_F64_U = WatToken("i64.trunc_sat_f64_u")
        @JvmField val I64_XOR = WatToken("i64.xor")
        @JvmField val I64X2_ABS = WatToken("i64x2.abs")
        @JvmField val I64X2_ADD = WatToken("i64x2.add")
        @JvmField val I64X2_ALL_TRUE = WatToken("i64x2.all_true")
        @JvmField val I64X2_BITMASK = WatToken("i64x2.bitmask")
        @JvmField val I64X2_EQ = WatToken("i64x2.eq")
        @JvmField val I64X2_EXTEND_HIGH_S_I32X4 = WatToken("i64x2.extend_high_s_i32x4")
        @JvmField val I64X2_EXTEND_HIGH_U_I32X4 = WatToken("i64x2.extend_high_u_i32x4")
        @JvmField val I64X2_EXTEND_LOW_S_I32X4 = WatToken("i64x2.extend_low_s_i32x4")
        @JvmField val I64X2_EXTEND_LOW_U_I32X4 = WatToken("i64x2.extend_low_u_i32x4")
        @JvmField val I64X2_EXTMUL_HIGH_S_I32X4 = WatToken("i64x2.extmul_high_s_i32x4")
        @JvmField val I64X2_EXTMUL_HIGH_U_I32X4 = WatToken("i64x2.extmul_high_u_i32x4")
        @JvmField val I64X2_EXTMUL_LOW_S_I32X4 = WatToken("i64x2.extmul_low_s_i32x4")
        @JvmField val I64X2_EXTMUL_LOW_U_I32X4 = WatToken("i64x2.extmul_low_u_i32x4")
        @JvmField val I64X2_EXTRACT_LANE = WatToken("i64x2.extract_lane")
        @JvmField val I64X2_GE_S = WatToken("i64x2.ge_s")
        @JvmField val I64X2_GT_S = WatToken("i64x2.gt_s")
        @JvmField val I64X2_LE_S = WatToken("i64x2.le_s")
        @JvmField val I64X2_LT_S = WatToken("i64x2.lt_s")
        @JvmField val I64X2_MUL = WatToken("i64x2.mul")
        @JvmField val I64X2_NE = WatToken("i64x2.ne")
        @JvmField val I64X2_NEG = WatToken("i64x2.neg")
        @JvmField val I64X2_RELAXED_LANESELECT = WatToken("i64x2.relaxed_laneselect")
        @JvmField val I64X2_REPLACE_LANE = WatToken("i64x2.replace_lane")
        @JvmField val I64X2_SHL = WatToken("i64x2.shl")
        @JvmField val I64X2_SHR_S = WatToken("i64x2.shr_s")
        @JvmField val I64X2_SHR_U = WatToken("i64x2.shr_u")
        @JvmField val I64X2_SPLAT = WatToken("i64x2.splat")
        @JvmField val I64X2_SUB = WatToken("i64x2.sub")
        @JvmField val I8 = WatToken("i8")
        @JvmField val I8X16_ABS = WatToken("i8x16.abs")
        @JvmField val I8X16_ADD = WatToken("i8x16.add")
        @JvmField val I8X16_ADD_SAT_S = WatToken("i8x16.add_sat_s")
        @JvmField val I8X16_ADD_SAT_U = WatToken("i8x16.add_sat_u")
        @JvmField val I8X16_ALL_TRUE = WatToken("i8x16.all_true")
        @JvmField val I8X16_AVGR_U = WatToken("i8x16.avgr_u")
        @JvmField val I8X16_BITMASK = WatToken("i8x16.bitmask")
        @JvmField val I8X16_EQ = WatToken("i8x16.eq")
        @JvmField val I8X16_EXTRACT_LANE_S = WatToken("i8x16.extract_lane_s")
        @JvmField val I8X16_EXTRACT_LANE_U = WatToken("i8x16.extract_lane_u")
        @JvmField val I8X16_GE_S = WatToken("i8x16.ge_s")
        @JvmField val I8X16_GE_U = WatToken("i8x16.ge_u")
        @JvmField val I8X16_GT_S = WatToken("i8x16.gt_s")
        @JvmField val I8X16_GT_U = WatToken("i8x16.gt_u")
        @JvmField val I8X16_LE_S = WatToken("i8x16.le_s")
        @JvmField val I8X16_LE_U = WatToken("i8x16.le_u")
        @JvmField val I8X16_LT_S = WatToken("i8x16.lt_s")
        @JvmField val I8X16_LT_U = WatToken("i8x16.lt_u")
        @JvmField val I8X16_MAX_S = WatToken("i8x16.max_s")
        @JvmField val I8X16_MAX_U = WatToken("i8x16.max_u")
        @JvmField val I8X16_MIN_S = WatToken("i8x16.min_s")
        @JvmField val I8X16_MIN_U = WatToken("i8x16.min_u")
        @JvmField val I8X16_NARROW_I16X8_S = WatToken("i8x16.narrow_i16x8_s")
        @JvmField val I8X16_NARROW_I16X8_U = WatToken("i8x16.narrow_i16x8_u")
        @JvmField val I8X16_NE = WatToken("i8x16.ne")
        @JvmField val I8X16_NEG = WatToken("i8x16.neg")
        @JvmField val I8X16_POPCNT = WatToken("i8x16.popcnt")
        @JvmField val I8X16_RELAXED_LANESELECT = WatToken("i8x16.relaxed_laneselect")
        @JvmField val I8X16_RELAXED_SWIZZLE = WatToken("i8x16.relaxed_swizzle")
        @JvmField val I8X16_REPLACE_LANE = WatToken("i8x16.replace_lane")
        @JvmField val I8X16_SHL = WatToken("i8x16.shl")
        @JvmField val I8X16_SHR_S = WatToken("i8x16.shr_s")
        @JvmField val I8X16_SHR_U = WatToken("i8x16.shr_u")
        @JvmField val I8X16_SHUFFLE = WatToken("i8x16.shuffle")
        @JvmField val I8X16_SPLAT = WatToken("i8x16.splat")
        @JvmField val I8X16_SUB = WatToken("i8x16.sub")
        @JvmField val I8X16_SUB_SAT_S = WatToken("i8x16.sub_sat_s")
        @JvmField val I8X16_SUB_SAT_U = WatToken("i8x16.sub_sat_u")
        @JvmField val I8X16_SWIZZLE = WatToken("i8x16.swizzle")
        @JvmField val IF = WatToken("if")
        @JvmField val INSTANCE = WatToken("instance")
        @JvmField val INSTANTIATE = WatToken("instantiate")
        @JvmField val ALIAS = WatToken("alias")

        /** `(alias outer <component> <index> ...)`: the one alias form that leaves the current scope. */
        @JvmField val OUTER = WatToken("outer")
        @JvmField val WITH = WatToken("with")
        @JvmField val IMPORT = WatToken("import")
        @JvmField val ITEM = WatToken("item")
        @JvmField val LIFT = WatToken("lift")
        @JvmField val LOWER = WatToken("lower")
        @JvmField val LOCAL = WatToken("local")
        @JvmField val LOCAL_GET = WatToken("local.get")
        @JvmField val LOCAL_SET = WatToken("local.set")
        @JvmField val LOCAL_TEE = WatToken("local.tee")
        @JvmField val LOOP = WatToken("loop")
        @JvmField val MEMORY = WatToken("memory")
        @JvmField val MEMORY_COPY = WatToken("memory.copy")
        @JvmField val MEMORY_FILL = WatToken("memory.fill")
        @JvmField val MEMORY_GROW = WatToken("memory.grow")
        @JvmField val MEMORY_INIT = WatToken("memory.init")
        @JvmField val MEMORY_SIZE = WatToken("memory.size")
        @JvmField val MODULE = WatToken("module")
        @JvmField val MUT = WatToken("mut")
        @JvmField val NOEXN = WatToken("noexn")
        @JvmField val NOEXTERN = WatToken("noextern")
        @JvmField val NOFUNC = WatToken("nofunc")
        @JvmField val NONE = WatToken("none")
        @JvmField val NOP = WatToken("nop")
        @JvmField val NULL = WatToken("null")
        @JvmField val NULLEXNREF = WatToken("nullexnref")
        @JvmField val NULLEXTERNREF = WatToken("nullexternref")
        @JvmField val NULLFUNCREF = WatToken("nullfuncref")
        @JvmField val NULLREF = WatToken("nullref")
        @JvmField val OFFSET = WatToken("offset")
        @JvmField val PARAM = WatToken("param")
        @JvmField val PASSIVE = WatToken("passive")
        @JvmField val REC = WatToken("rec")
        @JvmField val REF = WatToken("ref")
        @JvmField val REF_AS_NON_NULL = WatToken("ref.as_non_null")
        @JvmField val REF_CAST = WatToken("ref.cast")
        @JvmField val REF_EQ = WatToken("ref.eq")
        @JvmField val REF_FUNC = WatToken("ref.func")
        @JvmField val REF_I31 = WatToken("ref.i31")
        @JvmField val REF_IS_NULL = WatToken("ref.is_null")
        @JvmField val REF_NULL = WatToken("ref.null")
        @JvmField val REF_TEST = WatToken("ref.test")
        @JvmField val RESULT = WatToken("result")
        @JvmField val RETURN = WatToken("return")
        @JvmField val RETURN_CALL = WatToken("return_call")
        @JvmField val RETURN_CALL_INDIRECT = WatToken("return_call_indirect")
        @JvmField val RETURN_CALL_REF = WatToken("return_call_ref")
        @JvmField val SELECT = WatToken("select")
        @JvmField val START = WatToken("start")
        @JvmField val STRUCT = WatToken("struct")
        @JvmField val STRUCT_GET = WatToken("struct.get")
        @JvmField val STRUCT_GET_S = WatToken("struct.get_s")
        @JvmField val STRUCT_GET_U = WatToken("struct.get_u")
        @JvmField val STRUCT_NEW = WatToken("struct.new")
        @JvmField val STRUCT_NEW_DEFAULT = WatToken("struct.new_default")
        @JvmField val STRUCT_SET = WatToken("struct.set")
        @JvmField val STRUCTREF = WatToken("structref")
        @JvmField val SUB = WatToken("sub")
        @JvmField val TABLE = WatToken("table")
        @JvmField val TABLE_COPY = WatToken("table.copy")
        @JvmField val TABLE_FILL = WatToken("table.fill")
        @JvmField val TABLE_GET = WatToken("table.get")
        @JvmField val TABLE_GROW = WatToken("table.grow")
        @JvmField val TABLE_INIT = WatToken("table.init")
        @JvmField val TABLE_SET = WatToken("table.set")
        @JvmField val TABLE_SIZE = WatToken("table.size")
        @JvmField val TAG = WatToken("tag")
        @JvmField val THEN = WatToken("then")
        @JvmField val THROW = WatToken("throw")
        @JvmField val THROW_REF = WatToken("throw_ref")
        @JvmField val TRY_TABLE = WatToken("try_table")
        @JvmField val TYPE = WatToken("type")
        @JvmField val UNREACHABLE = WatToken("unreachable")
        @JvmField val U32 = WatToken("u32")
        @JvmField val V128 = WatToken("v128")
        @JvmField val V128_AND = WatToken("v128.and")
        @JvmField val V128_ANDNOT = WatToken("v128.andnot")
        @JvmField val V128_ANY_TRUE = WatToken("v128.any_true")
        @JvmField val V128_BITSELECT = WatToken("v128.bitselect")
        @JvmField val V128_CONST = WatToken("v128.const")
        @JvmField val V128_LOAD = WatToken("v128.load")
        @JvmField val V128_LOAD16_LANE = WatToken("v128.load16_lane")
        @JvmField val V128_LOAD16_SPLAT = WatToken("v128.load16_splat")
        @JvmField val V128_LOAD16X4_S = WatToken("v128.load16x4_s")
        @JvmField val V128_LOAD16X4_U = WatToken("v128.load16x4_u")
        @JvmField val V128_LOAD32_LANE = WatToken("v128.load32_lane")
        @JvmField val V128_LOAD32_SPLAT = WatToken("v128.load32_splat")
        @JvmField val V128_LOAD32_ZERO = WatToken("v128.load32_zero")
        @JvmField val V128_LOAD32X2_S = WatToken("v128.load32x2_s")
        @JvmField val V128_LOAD32X2_U = WatToken("v128.load32x2_u")
        @JvmField val V128_LOAD64_LANE = WatToken("v128.load64_lane")
        @JvmField val V128_LOAD64_SPLAT = WatToken("v128.load64_splat")
        @JvmField val V128_LOAD64_ZERO = WatToken("v128.load64_zero")
        @JvmField val V128_LOAD8_LANE = WatToken("v128.load8_lane")
        @JvmField val V128_LOAD8_SPLAT = WatToken("v128.load8_splat")
        @JvmField val V128_LOAD8X8_S = WatToken("v128.load8x8_s")
        @JvmField val V128_LOAD8X8_U = WatToken("v128.load8x8_u")
        @JvmField val V128_NOT = WatToken("v128.not")
        @JvmField val V128_OR = WatToken("v128.or")
        @JvmField val V128_STORE = WatToken("v128.store")
        @JvmField val V128_STORE16_LANE = WatToken("v128.store16_lane")
        @JvmField val V128_STORE32_LANE = WatToken("v128.store32_lane")
        @JvmField val V128_STORE64_LANE = WatToken("v128.store64_lane")
        @JvmField val V128_STORE8_LANE = WatToken("v128.store8_lane")
        @JvmField val V128_XOR = WatToken("v128.xor")


        /**
         * Threads: the 67 `0xFE`-prefixed atomic instructions, plus the `shared`
         * attribute on memory/table/global types. Merged into WebAssembly 3.0; the binary
         * side already lives in `wasmParser` `opcodes/atomic/Atomic.kt`.
         */
        @JvmField val MEMORY_ATOMIC_NOTIFY = WatToken("memory.atomic.notify")
        @JvmField val MEMORY_ATOMIC_WAIT32 = WatToken("memory.atomic.wait32")
        @JvmField val MEMORY_ATOMIC_WAIT64 = WatToken("memory.atomic.wait64")
        @JvmField val ATOMIC_FENCE = WatToken("atomic.fence")
        @JvmField val I32_ATOMIC_LOAD = WatToken("i32.atomic.load")
        @JvmField val I32_ATOMIC_LOAD8_U = WatToken("i32.atomic.load8_u")
        @JvmField val I32_ATOMIC_LOAD16_U = WatToken("i32.atomic.load16_u")
        @JvmField val I64_ATOMIC_LOAD = WatToken("i64.atomic.load")
        @JvmField val I64_ATOMIC_LOAD8_U = WatToken("i64.atomic.load8_u")
        @JvmField val I64_ATOMIC_LOAD16_U = WatToken("i64.atomic.load16_u")
        @JvmField val I64_ATOMIC_LOAD32_U = WatToken("i64.atomic.load32_u")
        @JvmField val I32_ATOMIC_STORE = WatToken("i32.atomic.store")
        @JvmField val I32_ATOMIC_STORE8 = WatToken("i32.atomic.store8")
        @JvmField val I32_ATOMIC_STORE16 = WatToken("i32.atomic.store16")
        @JvmField val I64_ATOMIC_STORE = WatToken("i64.atomic.store")
        @JvmField val I64_ATOMIC_STORE8 = WatToken("i64.atomic.store8")
        @JvmField val I64_ATOMIC_STORE16 = WatToken("i64.atomic.store16")
        @JvmField val I64_ATOMIC_STORE32 = WatToken("i64.atomic.store32")
        @JvmField val I32_ATOMIC_RMW_ADD = WatToken("i32.atomic.rmw.add")
        @JvmField val I32_ATOMIC_RMW8_ADD_U = WatToken("i32.atomic.rmw8.add_u")
        @JvmField val I32_ATOMIC_RMW16_ADD_U = WatToken("i32.atomic.rmw16.add_u")
        @JvmField val I64_ATOMIC_RMW_ADD = WatToken("i64.atomic.rmw.add")
        @JvmField val I64_ATOMIC_RMW8_ADD_U = WatToken("i64.atomic.rmw8.add_u")
        @JvmField val I64_ATOMIC_RMW16_ADD_U = WatToken("i64.atomic.rmw16.add_u")
        @JvmField val I64_ATOMIC_RMW32_ADD_U = WatToken("i64.atomic.rmw32.add_u")
        @JvmField val I32_ATOMIC_RMW_SUB = WatToken("i32.atomic.rmw.sub")
        @JvmField val I32_ATOMIC_RMW8_SUB_U = WatToken("i32.atomic.rmw8.sub_u")
        @JvmField val I32_ATOMIC_RMW16_SUB_U = WatToken("i32.atomic.rmw16.sub_u")
        @JvmField val I64_ATOMIC_RMW_SUB = WatToken("i64.atomic.rmw.sub")
        @JvmField val I64_ATOMIC_RMW8_SUB_U = WatToken("i64.atomic.rmw8.sub_u")
        @JvmField val I64_ATOMIC_RMW16_SUB_U = WatToken("i64.atomic.rmw16.sub_u")
        @JvmField val I64_ATOMIC_RMW32_SUB_U = WatToken("i64.atomic.rmw32.sub_u")
        @JvmField val I32_ATOMIC_RMW_AND = WatToken("i32.atomic.rmw.and")
        @JvmField val I32_ATOMIC_RMW8_AND_U = WatToken("i32.atomic.rmw8.and_u")
        @JvmField val I32_ATOMIC_RMW16_AND_U = WatToken("i32.atomic.rmw16.and_u")
        @JvmField val I64_ATOMIC_RMW_AND = WatToken("i64.atomic.rmw.and")
        @JvmField val I64_ATOMIC_RMW8_AND_U = WatToken("i64.atomic.rmw8.and_u")
        @JvmField val I64_ATOMIC_RMW16_AND_U = WatToken("i64.atomic.rmw16.and_u")
        @JvmField val I64_ATOMIC_RMW32_AND_U = WatToken("i64.atomic.rmw32.and_u")
        @JvmField val I32_ATOMIC_RMW_OR = WatToken("i32.atomic.rmw.or")
        @JvmField val I32_ATOMIC_RMW8_OR_U = WatToken("i32.atomic.rmw8.or_u")
        @JvmField val I32_ATOMIC_RMW16_OR_U = WatToken("i32.atomic.rmw16.or_u")
        @JvmField val I64_ATOMIC_RMW_OR = WatToken("i64.atomic.rmw.or")
        @JvmField val I64_ATOMIC_RMW8_OR_U = WatToken("i64.atomic.rmw8.or_u")
        @JvmField val I64_ATOMIC_RMW16_OR_U = WatToken("i64.atomic.rmw16.or_u")
        @JvmField val I64_ATOMIC_RMW32_OR_U = WatToken("i64.atomic.rmw32.or_u")
        @JvmField val I32_ATOMIC_RMW_XOR = WatToken("i32.atomic.rmw.xor")
        @JvmField val I32_ATOMIC_RMW8_XOR_U = WatToken("i32.atomic.rmw8.xor_u")
        @JvmField val I32_ATOMIC_RMW16_XOR_U = WatToken("i32.atomic.rmw16.xor_u")
        @JvmField val I64_ATOMIC_RMW_XOR = WatToken("i64.atomic.rmw.xor")
        @JvmField val I64_ATOMIC_RMW8_XOR_U = WatToken("i64.atomic.rmw8.xor_u")
        @JvmField val I64_ATOMIC_RMW16_XOR_U = WatToken("i64.atomic.rmw16.xor_u")
        @JvmField val I64_ATOMIC_RMW32_XOR_U = WatToken("i64.atomic.rmw32.xor_u")
        @JvmField val I32_ATOMIC_RMW_XCHG = WatToken("i32.atomic.rmw.xchg")
        @JvmField val I32_ATOMIC_RMW8_XCHG_U = WatToken("i32.atomic.rmw8.xchg_u")
        @JvmField val I32_ATOMIC_RMW16_XCHG_U = WatToken("i32.atomic.rmw16.xchg_u")
        @JvmField val I64_ATOMIC_RMW_XCHG = WatToken("i64.atomic.rmw.xchg")
        @JvmField val I64_ATOMIC_RMW8_XCHG_U = WatToken("i64.atomic.rmw8.xchg_u")
        @JvmField val I64_ATOMIC_RMW16_XCHG_U = WatToken("i64.atomic.rmw16.xchg_u")
        @JvmField val I64_ATOMIC_RMW32_XCHG_U = WatToken("i64.atomic.rmw32.xchg_u")
        @JvmField val I32_ATOMIC_RMW_CMPXCHG = WatToken("i32.atomic.rmw.cmpxchg")
        @JvmField val I32_ATOMIC_RMW8_CMPXCHG_U = WatToken("i32.atomic.rmw8.cmpxchg_u")
        @JvmField val I32_ATOMIC_RMW16_CMPXCHG_U = WatToken("i32.atomic.rmw16.cmpxchg_u")
        @JvmField val I64_ATOMIC_RMW_CMPXCHG = WatToken("i64.atomic.rmw.cmpxchg")
        @JvmField val I64_ATOMIC_RMW8_CMPXCHG_U = WatToken("i64.atomic.rmw8.cmpxchg_u")
        @JvmField val I64_ATOMIC_RMW16_CMPXCHG_U = WatToken("i64.atomic.rmw16.cmpxchg_u")
        @JvmField val I64_ATOMIC_RMW32_CMPXCHG_U = WatToken("i64.atomic.rmw32.cmpxchg_u")
        @JvmField val SHARED = WatToken("shared")

        /**
         * Legacy exception handling: the pre-`try_table` form still produced by older
         * toolchains, accepted by wasm-tools behind its `legacy_exceptions` feature.
         */
        @JvmField val TRY = WatToken("try")
        @JvmField val DO = WatToken("do")
        @JvmField val DELEGATE = WatToken("delegate")
        @JvmField val RETHROW = WatToken("rethrow")

        /**
         * Wide arithmetic proposal: 128-bit add/sub and widening multiply.
         */
        @JvmField val I64_ADD128 = WatToken("i64.add128")
        @JvmField val I64_SUB128 = WatToken("i64.sub128")
        @JvmField val I64_MUL_WIDE_S = WatToken("i64.mul_wide_s")
        @JvmField val I64_MUL_WIDE_U = WatToken("i64.mul_wide_u")

        /**
         * Custom page sizes proposal: `(memory $m 1 (pagesize 1))`.
         */
        @JvmField val PAGESIZE = WatToken("pagesize")

        /**
         * Stack switching proposal: continuation types and the resume/suspend family.
         * `on` introduces a handler clause, as in `(resume $ct (on $tag $label))`.
         */
        @JvmField val CONT = WatToken("cont")
        @JvmField val NOCONT = WatToken("nocont")
        @JvmField val CONTREF = WatToken("contref")
        @JvmField val NULLCONTREF = WatToken("nullcontref")
        @JvmField val ON = WatToken("on")
        @JvmField val CONT_NEW = WatToken("cont.new")
        @JvmField val CONT_BIND = WatToken("cont.bind")
        @JvmField val SUSPEND = WatToken("suspend")
        @JvmField val RESUME = WatToken("resume")
        @JvmField val RESUME_THROW = WatToken("resume_throw")
        @JvmField val RESUME_THROW_REF = WatToken("resume_throw_ref")
        @JvmField val SWITCH = WatToken("switch")

        /**
         * Half-precision (FP16) proposal. Not yet in wasm-tools — there is no `fp16`
         * feature flag — so `wat2wasm` still rejects these; they are recognised here only so
         * the editor does not paint them as errors. Both competing spellings are accepted for
         * the fused-multiply ops (`madd` per the proposal Overview vs `relaxed_madd` per
         * clang's `wasm_simd128.h`) and for the truncations (`trunc_sat_f16x8` vs
         * `trunc_f16x8`), because the proposal renamed them and either may appear in the wild.
         */
        @JvmField val F16 = WatToken("f16")
        @JvmField val F32_LOAD_F16 = WatToken("f32.load_f16")
        @JvmField val F32_STORE_F16 = WatToken("f32.store_f16")
        @JvmField val F16X8_SPLAT = WatToken("f16x8.splat")
        @JvmField val F16X8_EXTRACT_LANE = WatToken("f16x8.extract_lane")
        @JvmField val F16X8_REPLACE_LANE = WatToken("f16x8.replace_lane")
        @JvmField val F16X8_EQ = WatToken("f16x8.eq")
        @JvmField val F16X8_NE = WatToken("f16x8.ne")
        @JvmField val F16X8_LT = WatToken("f16x8.lt")
        @JvmField val F16X8_GT = WatToken("f16x8.gt")
        @JvmField val F16X8_LE = WatToken("f16x8.le")
        @JvmField val F16X8_GE = WatToken("f16x8.ge")
        @JvmField val F16X8_ABS = WatToken("f16x8.abs")
        @JvmField val F16X8_NEG = WatToken("f16x8.neg")
        @JvmField val F16X8_SQRT = WatToken("f16x8.sqrt")
        @JvmField val F16X8_CEIL = WatToken("f16x8.ceil")
        @JvmField val F16X8_FLOOR = WatToken("f16x8.floor")
        @JvmField val F16X8_TRUNC = WatToken("f16x8.trunc")
        @JvmField val F16X8_NEAREST = WatToken("f16x8.nearest")
        @JvmField val F16X8_ADD = WatToken("f16x8.add")
        @JvmField val F16X8_SUB = WatToken("f16x8.sub")
        @JvmField val F16X8_MUL = WatToken("f16x8.mul")
        @JvmField val F16X8_DIV = WatToken("f16x8.div")
        @JvmField val F16X8_MIN = WatToken("f16x8.min")
        @JvmField val F16X8_MAX = WatToken("f16x8.max")
        @JvmField val F16X8_PMIN = WatToken("f16x8.pmin")
        @JvmField val F16X8_PMAX = WatToken("f16x8.pmax")
        @JvmField val F16X8_CONVERT_I16X8_S = WatToken("f16x8.convert_i16x8_s")
        @JvmField val F16X8_CONVERT_I16X8_U = WatToken("f16x8.convert_i16x8_u")
        @JvmField val F16X8_DEMOTE_F32X4_ZERO = WatToken("f16x8.demote_f32x4_zero")
        @JvmField val F16X8_DEMOTE_F64X2_ZERO = WatToken("f16x8.demote_f64x2_zero")
        @JvmField val F32X4_PROMOTE_LOW_F16X8 = WatToken("f32x4.promote_low_f16x8")
        @JvmField val F16X8_MADD = WatToken("f16x8.madd")
        @JvmField val F16X8_NMADD = WatToken("f16x8.nmadd")
        @JvmField val F16X8_RELAXED_MADD = WatToken("f16x8.relaxed_madd")
        @JvmField val F16X8_RELAXED_NMADD = WatToken("f16x8.relaxed_nmadd")
        @JvmField val I16X8_TRUNC_SAT_F16X8_S = WatToken("i16x8.trunc_sat_f16x8_s")
        @JvmField val I16X8_TRUNC_SAT_F16X8_U = WatToken("i16x8.trunc_sat_f16x8_u")
        @JvmField val I16X8_TRUNC_F16X8_S = WatToken("i16x8.trunc_f16x8_s")
        @JvmField val I16X8_TRUNC_F16X8_U = WatToken("i16x8.trunc_f16x8_u")

        /**
         * Component model: value types, canonical-ABI options and the stable MVP resource
         * builtins. `result`, `enum`, `field`, `memory`, `core` and the numeric primitives are
         * already declared above and are shared with the core language. The async/threading
         * builtins (`task.return`, `waitable-set.wait`, ...) are deliberately omitted: wasm-tools
         * spells them only as Rust `kw::` idents and their text names are still churning.
         */
        @JvmField val BOOL = WatToken("bool")
        @JvmField val S8 = WatToken("s8")
        @JvmField val U8 = WatToken("u8")
        @JvmField val S16 = WatToken("s16")
        @JvmField val U16 = WatToken("u16")
        @JvmField val S32 = WatToken("s32")
        @JvmField val S64 = WatToken("s64")
        @JvmField val U64 = WatToken("u64")
        @JvmField val CHAR = WatToken("char")
        @JvmField val STRING_TYPE = WatToken("string")
        @JvmField val FLOAT32 = WatToken("float32")
        @JvmField val FLOAT64 = WatToken("float64")
        @JvmField val ERROR_CONTEXT = WatToken("error-context")
        @JvmField val RECORD = WatToken("record")
        @JvmField val VARIANT = WatToken("variant")
        @JvmField val LIST = WatToken("list")
        @JvmField val MAP = WatToken("map")
        @JvmField val TUPLE = WatToken("tuple")
        @JvmField val FLAGS = WatToken("flags")
        @JvmField val OWN = WatToken("own")
        @JvmField val BORROW = WatToken("borrow")
        @JvmField val OPTION = WatToken("option")
        @JvmField val STREAM = WatToken("stream")
        @JvmField val FUTURE = WatToken("future")
        @JvmField val CASE = WatToken("case")
        @JvmField val ERROR = WatToken("error")
        @JvmField val RESOURCE = WatToken("resource")

        /** The component-model `value` sort, as in `(import "x" (value $v (type u32)))`. */
        @JvmField val VALUE = WatToken("value")

        /** Core representation of a resource: `(resource (rep i32))`. */
        @JvmField val REP = WatToken("rep")

        /** Optional resource destructor: `(resource (rep i32) (dtor (func $f)))`. */
        @JvmField val DTOR = WatToken("dtor")
        @JvmField val REALLOC = WatToken("realloc")
        @JvmField val POST_RETURN = WatToken("post-return")
        @JvmField val CALLBACK = WatToken("callback")
        @JvmField val ASYNC = WatToken("async")
        @JvmField val GC = WatToken("gc")
        @JvmField val STRING_ENCODING = WatToken("string-encoding")
        @JvmField val UTF8 = WatToken("utf8")
        @JvmField val UTF16 = WatToken("utf16")
        @JvmField val LATIN1_PLUS_UTF16 = WatToken("latin1+utf16")
        @JvmField val CANCELLABLE = WatToken("cancellable")
        @JvmField val RESOURCE_NEW = WatToken("resource.new")
        @JvmField val RESOURCE_DROP = WatToken("resource.drop")
        @JvmField val RESOURCE_REP = WatToken("resource.rep")

        @JvmField val UNKNOWN = WatToken("unknown")

        /**
         * Every keyword token, i.e. every token the lexer can produce by matching a bare word
         * against [WatLexer]'s keyword map. This is the single source of that map — adding a
         * keyword here is all that is required for the lexer to recognise it.
         *
         * Distinct from [KEYWORDS], which is the much smaller set of *structural* keywords that
         * get keyword colouring; instruction mnemonics deliberately render as plain text.
         */
        @JvmField
        val ALL_KEYWORDS = arrayOf<WatToken>(
            ACTIVE, ALIAS, ALIGN, ANY, ANY_CONVERT_EXTERN, ANYREF, ARRAY, ARRAY_COPY, ARRAY_FILL, ARRAY_GET,
            ARRAY_GET_S, ARRAY_GET_U, ARRAY_INIT_DATA, ARRAY_INIT_ELEM, ARRAY_LEN, ARRAY_NEW, ARRAY_NEW_DATA,
            ARRAY_NEW_DEFAULT, ARRAY_NEW_ELEM, ARRAY_NEW_FIXED, ARRAY_SET, ARRAYREF, BLOCK, BR, BR_IF, BR_ON_CAST,
            BR_ON_CAST_FAIL, BR_ON_NON_NULL, BR_ON_NULL, BR_TABLE, CALL, CALL_INDIRECT, CALL_REF, CANON, CATCH,
            CATCH_ALL, CATCH_ALL_REF, CATCH_REF, CORE, COMPONENT, INSTANCE, INSTANTIATE, DATA, DATA_DROP, DECLARE,
            DROP, ELEM, ELEM_DROP, ELSE, END, ENUM, EQ, EQREF, EXN, EXNREF, EXPORT, EXTERN, EXTERN_CONVERT_ANY,
            EXTERNREF, F32, F32_ABS, F32_ADD, F32_CEIL, F32_CONST, F32_CONVERT_I32_S, F32_CONVERT_I32_U,
            F32_CONVERT_I64_S, F32_CONVERT_I64_U, F32_COPYSIGN, F32_DEMOTE_F64, F32_DIV, F32_EQ, F32_FLOOR, F32_GE,
            F32_GT, F32_LE, F32_LOAD, F32_LT, F32_MAX, F32_MIN, F32_MUL, F32_NE, F32_NEAREST, F32_NEG,
            F32_REINTERPRET_I32, F32_SQRT, F32_STORE, F32_SUB, F32_TRUNC, F32X4_ABS, F32X4_ADD, F32X4_CEIL,
            F32X4_CONVERT_S_I32X4, F32X4_CONVERT_U_I32X4, F32X4_DEMOTE_ZERO_F64X2, F32X4_DIV, F32X4_EQ,
            F32X4_EXTRACT_LANE, F32X4_FLOOR, F32X4_GE, F32X4_GT, F32X4_LE, F32X4_LT, F32X4_MAX, F32X4_MIN, F32X4_MUL,
            F32X4_NE, F32X4_NEAREST, F32X4_NEG, F32X4_PMAX, F32X4_PMIN, F32X4_RELAXED_MADD, F32X4_RELAXED_MAX,
            F32X4_RELAXED_MIN, F32X4_RELAXED_NMADD, F32X4_REPLACE_LANE, F32X4_SPLAT, F32X4_SQRT, F32X4_SUB,
            F32X4_TRUNC, F64, F64_ABS, F64_ADD, F64_CEIL, F64_CONST, F64_CONVERT_I32_S, F64_CONVERT_I32_U,
            F64_CONVERT_I64_S, F64_CONVERT_I64_U, F64_COPYSIGN, F64_DIV, F64_EQ, F64_FLOOR, F64_GE, F64_GT, F64_LE,
            F64_LOAD, F64_LT, F64_MAX, F64_MIN, F64_MUL, F64_NE, F64_NEAREST, F64_NEG, F64_PROMOTE_F32,
            F64_REINTERPRET_I64, F64_SQRT, F64_STORE, F64_SUB, F64_TRUNC, F64X2_ABS, F64X2_ADD, F64X2_CEIL,
            F64X2_CONVERT_LOW_S_I32X4, F64X2_CONVERT_LOW_U_I32X4, F64X2_DIV, F64X2_EQ, F64X2_EXTRACT_LANE,
            F64X2_FLOOR, F64X2_GE, F64X2_GT, F64X2_LE, F64X2_LT, F64X2_MAX, F64X2_MIN, F64X2_MUL, F64X2_NE,
            F64X2_NEAREST, F64X2_NEG, F64X2_PMAX, F64X2_PMIN, F64X2_PROMOTE_LOW_F32X4, F64X2_RELAXED_MADD,
            F64X2_RELAXED_MAX, F64X2_RELAXED_MIN, F64X2_RELAXED_NMADD, F64X2_REPLACE_LANE, F64X2_SPLAT, F64X2_SQRT,
            F64X2_SUB, F64X2_TRUNC, FIELD, FINAL, FUNC, FUNCREF, GLOBAL, GLOBAL_GET, GLOBAL_SET, I16, I16X8_ABS,
            I16X8_ADD, I16X8_ADD_SAT_S, I16X8_ADD_SAT_U, I16X8_ALL_TRUE, I16X8_AVGR_U, I16X8_BITMASK, I16X8_EQ,
            I16X8_EXTADD_PAIRWISE_I8X16_S, I16X8_EXTADD_PAIRWISE_I8X16_U, I16X8_EXTEND_HIGH_S_I8X16,
            I16X8_EXTEND_HIGH_U_I8X16, I16X8_EXTEND_LOW_S_I8X16, I16X8_EXTEND_LOW_U_I8X16, I16X8_EXTMUL_HIGH_S_I8X16,
            I16X8_EXTMUL_HIGH_U_I8X16, I16X8_EXTMUL_LOW_S_I8X16, I16X8_EXTMUL_LOW_U_I8X16, I16X8_EXTRACT_LANE_S,
            I16X8_EXTRACT_LANE_U, I16X8_GE_S, I16X8_GE_U, I16X8_GT_S, I16X8_GT_U, I16X8_LE_S, I16X8_LE_U, I16X8_LT_S,
            I16X8_LT_U, I16X8_MAX_S, I16X8_MAX_U, I16X8_MIN_S, I16X8_MIN_U, I16X8_MUL, I16X8_NARROW_I32X4_S,
            I16X8_NARROW_I32X4_U, I16X8_NE, I16X8_NEG, I16X8_Q15MULR_SAT_S, I16X8_RELAXED_DOT_I8X16_I7X16_ADD_S,
            I16X8_RELAXED_DOT_I8X16_I7X16_S, I16X8_RELAXED_DOT_S_I8X16, I16X8_RELAXED_LANESELECT,
            I16X8_RELAXED_Q15MULR_S, I16X8_RELAXED_SWIZZLE, I16X8_REPLACE_LANE, I16X8_SHL, I16X8_SHR_S, I16X8_SHR_U,
            I16X8_SPLAT, I16X8_SUB, I16X8_SUB_SAT_S, I16X8_SUB_SAT_U, I31, I31_GET_S, I31_GET_U, I31REF, I32, I32_ADD,
            I32_AND, I32_CLZ, I32_CONST, I32_CTZ, I32_DIV_S, I32_DIV_U, I32_EQ, I32_EQZ, I32_EXTEND16_S,
            I32_EXTEND8_S, I32_GE_S, I32_GE_U, I32_GT_S, I32_GT_U, I32_LE_S, I32_LE_U, I32_LOAD, I32_LOAD16_S,
            I32_LOAD16_U, I32_LOAD8_S, I32_LOAD8_U, I32_LT_S, I32_LT_U, I32_MUL, I32_NE, I32_OR, I32_POPCNT,
            I32_REINTERPRET_F32, I32_REM_S, I32_REM_U, I32_ROTL, I32_ROTR, I32_SHL, I32_SHR_S, I32_SHR_U, I32_STORE,
            I32_STORE16, I32_STORE8, I32_SUB, I32_TRUNC_F32_S, I32_TRUNC_F32_U, I32_TRUNC_F64_S, I32_TRUNC_F64_U,
            I32_TRUNC_SAT_F32_S, I32_TRUNC_SAT_F32_U, I32_TRUNC_SAT_F64_S, I32_TRUNC_SAT_F64_U, I32_WRAP_I64, I32_XOR,
            I32X4_ABS, I32X4_ADD, I32X4_ALL_TRUE, I32X4_BITMASK, I32X4_DOT_S_I16X8, I32X4_EQ,
            I32X4_EXTADD_PAIRWISE_I16X8_S, I32X4_EXTADD_PAIRWISE_I16X8_U, I32X4_EXTEND_HIGH_S_I16X8,
            I32X4_EXTEND_HIGH_U_I16X8, I32X4_EXTEND_LOW_S_I16X8, I32X4_EXTEND_LOW_U_I16X8, I32X4_EXTMUL_HIGH_S_I16X8,
            I32X4_EXTMUL_HIGH_U_I16X8, I32X4_EXTMUL_LOW_S_I16X8, I32X4_EXTMUL_LOW_U_I16X8, I32X4_EXTRACT_LANE,
            I32X4_GE_S, I32X4_GE_U, I32X4_GT_S, I32X4_GT_U, I32X4_LE_S, I32X4_LE_U, I32X4_LT_S, I32X4_LT_U,
            I32X4_MAX_S, I32X4_MAX_U, I32X4_MIN_S, I32X4_MIN_U, I32X4_MUL, I32X4_NE, I32X4_NEG,
            I32X4_RELAXED_DOT_ADD_S_I16X8, I32X4_RELAXED_LANESELECT, I32X4_RELAXED_TRUNC_F32X4_S,
            I32X4_RELAXED_TRUNC_F32X4_S_ZERO, I32X4_RELAXED_TRUNC_F32X4_U, I32X4_RELAXED_TRUNC_F32X4_U_ZERO,
            I32X4_RELAXED_TRUNC_S_F32X4, I32X4_RELAXED_TRUNC_S_ZERO_F64X2, I32X4_RELAXED_TRUNC_U_F32X4,
            I32X4_RELAXED_TRUNC_U_ZERO_F64X2, I32X4_REPLACE_LANE, I32X4_SHL, I32X4_SHR_S, I32X4_SHR_U, I32X4_SPLAT,
            I32X4_SUB, I32X4_TRUNC_SAT_F32X4_S, I32X4_TRUNC_SAT_F32X4_U, I32X4_TRUNC_SAT_S_ZERO_F64X2,
            I32X4_TRUNC_SAT_U_ZERO_F64X2, I64, I64_ADD, I64_AND, I64_CLZ, I64_CONST, I64_CTZ, I64_DIV_S, I64_DIV_U,
            I64_EQ, I64_EQZ, I64_EXTEND16_S, I64_EXTEND32_S, I64_EXTEND8_S, I64_EXTEND_I32_S, I64_EXTEND_I32_U,
            I64_GE_S, I64_GE_U, I64_GT_S, I64_GT_U, I64_LE_S, I64_LE_U, I64_LOAD, I64_LOAD16_S, I64_LOAD16_U,
            I64_LOAD32_S, I64_LOAD32_U, I64_LOAD8_S, I64_LOAD8_U, I64_LT_S, I64_LT_U, I64_MUL, I64_NE, I64_OR,
            I64_POPCNT, I64_REINTERPRET_F64, I64_REM_S, I64_REM_U, I64_ROTL, I64_ROTR, I64_SHL, I64_SHR_S, I64_SHR_U,
            I64_STORE, I64_STORE16, I64_STORE32, I64_STORE8, I64_SUB, I64_TRUNC_F32_S, I64_TRUNC_F32_U,
            I64_TRUNC_F64_S, I64_TRUNC_F64_U, I64_TRUNC_SAT_F32_S, I64_TRUNC_SAT_F32_U, I64_TRUNC_SAT_F64_S,
            I64_TRUNC_SAT_F64_U, I64_XOR, I64X2_ABS, I64X2_ADD, I64X2_ALL_TRUE, I64X2_BITMASK, I64X2_EQ,
            I64X2_EXTEND_HIGH_S_I32X4, I64X2_EXTEND_HIGH_U_I32X4, I64X2_EXTEND_LOW_S_I32X4, I64X2_EXTEND_LOW_U_I32X4,
            I64X2_EXTMUL_HIGH_S_I32X4, I64X2_EXTMUL_HIGH_U_I32X4, I64X2_EXTMUL_LOW_S_I32X4, I64X2_EXTMUL_LOW_U_I32X4,
            I64X2_EXTRACT_LANE, I64X2_GE_S, I64X2_GT_S, I64X2_LE_S, I64X2_LT_S, I64X2_MUL, I64X2_NE, I64X2_NEG,
            I64X2_RELAXED_LANESELECT, I64X2_REPLACE_LANE, I64X2_SHL, I64X2_SHR_S, I64X2_SHR_U, I64X2_SPLAT, I64X2_SUB,
            I8, I8X16_ABS, I8X16_ADD, I8X16_ADD_SAT_S, I8X16_ADD_SAT_U, I8X16_ALL_TRUE, I8X16_AVGR_U, I8X16_BITMASK,
            I8X16_EQ, I8X16_EXTRACT_LANE_S, I8X16_EXTRACT_LANE_U, I8X16_GE_S, I8X16_GE_U, I8X16_GT_S, I8X16_GT_U,
            I8X16_LE_S, I8X16_LE_U, I8X16_LT_S, I8X16_LT_U, I8X16_MAX_S, I8X16_MAX_U, I8X16_MIN_S, I8X16_MIN_U,
            I8X16_NARROW_I16X8_S, I8X16_NARROW_I16X8_U, I8X16_NE, I8X16_NEG, I8X16_POPCNT, I8X16_RELAXED_LANESELECT,
            I8X16_RELAXED_SWIZZLE, I8X16_REPLACE_LANE, I8X16_SHL, I8X16_SHR_S, I8X16_SHR_U, I8X16_SHUFFLE,
            I8X16_SPLAT, I8X16_SUB, I8X16_SUB_SAT_S, I8X16_SUB_SAT_U, I8X16_SWIZZLE, IF, IMPORT, ITEM, LIFT, LOWER,
            LOCAL, LOCAL_GET, LOCAL_SET, LOCAL_TEE, LOOP, MEMORY, MEMORY_COPY, MEMORY_FILL, MEMORY_GROW, MEMORY_INIT,
            MEMORY_SIZE, MODULE, MUT, NOEXN, NOEXTERN, NOFUNC, NONE, NOP, NULL, NULLEXNREF, NULLEXTERNREF,
            NULLFUNCREF, NULLREF, OFFSET, PARAM, PASSIVE, REC, REF, REF_AS_NON_NULL, REF_CAST, REF_EQ, REF_FUNC,
            REF_I31, REF_IS_NULL, REF_NULL, REF_TEST, RESULT, RETURN, RETURN_CALL, RETURN_CALL_INDIRECT,
            RETURN_CALL_REF, SELECT, START, STRUCT, STRUCT_GET, STRUCT_GET_S, STRUCT_GET_U, STRUCT_NEW,
            STRUCT_NEW_DEFAULT, STRUCT_SET, STRUCTREF, SUB, TABLE, TABLE_COPY, TABLE_FILL, TABLE_GET, TABLE_GROW,
            TABLE_INIT, TABLE_SET, TABLE_SIZE, TAG, THEN, THROW, THROW_REF, TRY_TABLE, TYPE, UNREACHABLE, U32, V128,
            V128_AND, V128_ANDNOT, V128_ANY_TRUE, V128_BITSELECT, V128_CONST, V128_LOAD, V128_LOAD16_LANE,
            V128_LOAD16_SPLAT, V128_LOAD16X4_S, V128_LOAD16X4_U, V128_LOAD32_LANE, V128_LOAD32_SPLAT,
            V128_LOAD32_ZERO, V128_LOAD32X2_S, V128_LOAD32X2_U, V128_LOAD64_LANE, V128_LOAD64_SPLAT, V128_LOAD64_ZERO,
            V128_LOAD8_LANE, V128_LOAD8_SPLAT, V128_LOAD8X8_S, V128_LOAD8X8_U, V128_NOT, V128_OR, V128_STORE,
            V128_STORE16_LANE, V128_STORE32_LANE, V128_STORE64_LANE, V128_STORE8_LANE, V128_XOR, WITH,

            // Threads
            MEMORY_ATOMIC_NOTIFY, MEMORY_ATOMIC_WAIT32, MEMORY_ATOMIC_WAIT64, ATOMIC_FENCE, I32_ATOMIC_LOAD,
            I32_ATOMIC_LOAD8_U, I32_ATOMIC_LOAD16_U, I64_ATOMIC_LOAD, I64_ATOMIC_LOAD8_U, I64_ATOMIC_LOAD16_U,
            I64_ATOMIC_LOAD32_U, I32_ATOMIC_STORE, I32_ATOMIC_STORE8, I32_ATOMIC_STORE16, I64_ATOMIC_STORE,
            I64_ATOMIC_STORE8, I64_ATOMIC_STORE16, I64_ATOMIC_STORE32, I32_ATOMIC_RMW_ADD, I32_ATOMIC_RMW8_ADD_U,
            I32_ATOMIC_RMW16_ADD_U, I64_ATOMIC_RMW_ADD, I64_ATOMIC_RMW8_ADD_U, I64_ATOMIC_RMW16_ADD_U,
            I64_ATOMIC_RMW32_ADD_U, I32_ATOMIC_RMW_SUB, I32_ATOMIC_RMW8_SUB_U, I32_ATOMIC_RMW16_SUB_U,
            I64_ATOMIC_RMW_SUB, I64_ATOMIC_RMW8_SUB_U, I64_ATOMIC_RMW16_SUB_U, I64_ATOMIC_RMW32_SUB_U,
            I32_ATOMIC_RMW_AND, I32_ATOMIC_RMW8_AND_U, I32_ATOMIC_RMW16_AND_U, I64_ATOMIC_RMW_AND,
            I64_ATOMIC_RMW8_AND_U, I64_ATOMIC_RMW16_AND_U, I64_ATOMIC_RMW32_AND_U, I32_ATOMIC_RMW_OR,
            I32_ATOMIC_RMW8_OR_U, I32_ATOMIC_RMW16_OR_U, I64_ATOMIC_RMW_OR, I64_ATOMIC_RMW8_OR_U,
            I64_ATOMIC_RMW16_OR_U, I64_ATOMIC_RMW32_OR_U, I32_ATOMIC_RMW_XOR, I32_ATOMIC_RMW8_XOR_U,
            I32_ATOMIC_RMW16_XOR_U, I64_ATOMIC_RMW_XOR, I64_ATOMIC_RMW8_XOR_U, I64_ATOMIC_RMW16_XOR_U,
            I64_ATOMIC_RMW32_XOR_U, I32_ATOMIC_RMW_XCHG, I32_ATOMIC_RMW8_XCHG_U, I32_ATOMIC_RMW16_XCHG_U,
            I64_ATOMIC_RMW_XCHG, I64_ATOMIC_RMW8_XCHG_U, I64_ATOMIC_RMW16_XCHG_U, I64_ATOMIC_RMW32_XCHG_U,
            I32_ATOMIC_RMW_CMPXCHG, I32_ATOMIC_RMW8_CMPXCHG_U, I32_ATOMIC_RMW16_CMPXCHG_U, I64_ATOMIC_RMW_CMPXCHG,
            I64_ATOMIC_RMW8_CMPXCHG_U, I64_ATOMIC_RMW16_CMPXCHG_U, I64_ATOMIC_RMW32_CMPXCHG_U, SHARED,
            // Legacy exception handling
            TRY, DO, DELEGATE, RETHROW,
            // Wide arithmetic
            I64_ADD128, I64_SUB128, I64_MUL_WIDE_S, I64_MUL_WIDE_U,
            // Custom page sizes
            PAGESIZE,
            // Stack switching
            CONT, NOCONT, CONTREF, NULLCONTREF, ON, CONT_NEW, CONT_BIND, SUSPEND, RESUME, RESUME_THROW,
            RESUME_THROW_REF, SWITCH,
            // Half-precision (FP16)
            F16, F32_LOAD_F16, F32_STORE_F16, F16X8_SPLAT, F16X8_EXTRACT_LANE, F16X8_REPLACE_LANE, F16X8_EQ, F16X8_NE,
            F16X8_LT, F16X8_GT, F16X8_LE, F16X8_GE, F16X8_ABS, F16X8_NEG, F16X8_SQRT, F16X8_CEIL, F16X8_FLOOR,
            F16X8_TRUNC, F16X8_NEAREST, F16X8_ADD, F16X8_SUB, F16X8_MUL, F16X8_DIV, F16X8_MIN, F16X8_MAX, F16X8_PMIN,
            F16X8_PMAX, F16X8_CONVERT_I16X8_S, F16X8_CONVERT_I16X8_U, F16X8_DEMOTE_F32X4_ZERO,
            F16X8_DEMOTE_F64X2_ZERO, F32X4_PROMOTE_LOW_F16X8, F16X8_MADD, F16X8_NMADD, F16X8_RELAXED_MADD,
            F16X8_RELAXED_NMADD, I16X8_TRUNC_SAT_F16X8_S, I16X8_TRUNC_SAT_F16X8_U, I16X8_TRUNC_F16X8_S,
            I16X8_TRUNC_F16X8_U,
            // Component model
            BOOL, S8, U8, S16, U16, S32, S64, U64, CHAR, STRING_TYPE, FLOAT32, FLOAT64, ERROR_CONTEXT, RECORD,
            VARIANT, LIST, MAP, TUPLE, FLAGS, OWN, BORROW, OPTION, STREAM, FUTURE, CASE, ERROR, RESOURCE, REP, DTOR,
            REALLOC, POST_RETURN, CALLBACK, ASYNC, GC, STRING_ENCODING, UTF8, UTF16, LATIN1_PLUS_UTF16, CANCELLABLE,
            RESOURCE_NEW, RESOURCE_DROP, RESOURCE_REP, OUTER, VALUE,
        )

        /**
         * Keywords that get [com.intellij.openapi.editor.DefaultLanguageHighlighterColors.KEYWORD]
         * colouring: module structure, types and control flow. Numeric/vector primitives (`i32`,
         * `f64`, `v128`, and the component-model `s8`/`char`/`string`/... analogues) and plain
         * instruction mnemonics are intentionally excluded so they render as ordinary text.
         */
        @JvmField
        val KEYWORDS = arrayOf<WatToken>(
            ALIGN, ANY, ALIAS, ARRAY, BLOCK, BR, BR_IF, BR_ON_CAST, BR_ON_CAST_FAIL, BR_ON_NON_NULL, BR_ON_NULL,
            BR_TABLE, CATCH, CATCH_ALL, CATCH_ALL_REF, CATCH_REF, CALL, CALL_INDIRECT, CALL_REF, CANON, CORE, COMPONENT,
            DATA, ELEM, ELSE, END, ENUM, EXN, EXPORT, EXTERN, FIELD, FUNC, GLOBAL, IF, IMPORT, ITEM, INSTANCE, INSTANTIATE,
            LIFT, LOOP, LOWER, MUT, MODULE, MEMORY, NOEXN, NOEXTERN, OFFSET, PARAM, REC, RESULT, RETURN,
            RETURN_CALL, SELECT, START, STRUCT, SUB, TABLE, TAG, THEN, THROW, THROW_REF, TRY_TABLE, TYPE, WITH,
            // Threads
            SHARED,
            // Legacy exception handling
            TRY, DO, DELEGATE, RETHROW,
            // Custom page sizes
            PAGESIZE,
            // Stack switching
            CONT, NOCONT, ON, SUSPEND, RESUME, RESUME_THROW, RESUME_THROW_REF, SWITCH,
            // Component model
            RECORD, VARIANT, CASE, LIST, MAP, TUPLE, FLAGS, OPTION, OWN, BORROW, RESOURCE, REP, DTOR, STREAM, FUTURE,
            ERROR, ASYNC, CALLBACK, REALLOC, POST_RETURN, STRING_ENCODING, CANCELLABLE, GC, OUTER, VALUE
        )

        @JvmField
        val COMMENTS_SET = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)

        @JvmField
        val WHITESPACES_SET = TokenSet.create(WHITESPACE, NEWLINE)

    }
}

package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer

/**
 * A single decoded DWARF expression operation with its operands.
 */
sealed class DwarfExprOp {
    /** `DW_OP_WASM_location 0x00 <index>` — value is in the i-th Wasm local. */
    data class WasmLocal(val index: Long) : DwarfExprOp()

    /** `DW_OP_WASM_location 0x01 <index>` — value is in the i-th Wasm global (ULEB128). */
    data class WasmGlobal(val index: Long) : DwarfExprOp()

    /** `DW_OP_WASM_location 0x03 <index>` — value is in the i-th Wasm global (U32). */
    data class WasmGlobalU32(val index: Long) : DwarfExprOp()

    /** `DW_OP_WASM_location 0x02 <index>` — value is on the i-th Wasm operand stack entry. */
    data class WasmOperandStack(val index: Long) : DwarfExprOp()

    /** `DW_OP_fbreg <offset>` — value is at offset from the frame base. */
    data class FrameBaseOffset(val offset: Long) : DwarfExprOp()

    /** `DW_OP_addr <address>` — value is at a fixed address in linear memory. */
    data class Address(val address: Long) : DwarfExprOp()

    /** `DW_OP_stack_value` — the value on the DWARF stack IS the variable value (not a memory address). */
    data object StackValue : DwarfExprOp()

    /** `DW_OP_constu <value>` — push an unsigned constant onto the DWARF stack. */
    data class ConstUnsigned(val value: Long) : DwarfExprOp()

    /** `DW_OP_consts <value>` — push a signed constant onto the DWARF stack. */
    data class ConstSigned(val value: Long) : DwarfExprOp()

    /** `DW_OP_plus_uconst <value>` — add an unsigned constant to the top of the DWARF stack. */
    data class PlusUConst(val value: Long) : DwarfExprOp()

    /** `DW_OP_regN` — value is in DWARF register N (0x50..0x6f encode regs 0..31). */
    data class Register(val regNumber: Int) : DwarfExprOp()

    /** `DW_OP_regx <register>` — value is in the given DWARF register. */
    data class RegisterX(val regNumber: Long) : DwarfExprOp()

    /** `DW_OP_bregN <offset>` — value is at offset from DWARF register N (0x70..0x8f). */
    data class BaseRegisterOffset(val regNumber: Int, val offset: Long) : DwarfExprOp()

    /** `DW_OP_bregx <register> <offset>` — value is at offset from the given DWARF register. */
    data class BaseRegisterOffsetX(val regNumber: Long, val offset: Long) : DwarfExprOp()

    /** `DW_OP_deref` — dereference the address on top of the DWARF stack. */
    data object Deref : DwarfExprOp()

    /** `DW_OP_deref_size <size>` — dereference with an explicit byte size. */
    data class DerefSize(val size: Int) : DwarfExprOp()

    /** `DW_OP_piece <size>` — the preceding location describes <size> bytes of the value. */
    data class Piece(val byteSize: Long) : DwarfExprOp()

    /** `DW_OP_bit_piece <bit_size> <bit_offset>` — the preceding location describes a bit range. */
    data class BitPiece(val bitSize: Long, val bitOffset: Long) : DwarfExprOp()

    /** `DW_OP_call_frame_cfa` — push the CFA (Canonical Frame Address) onto the DWARF stack. */
    data object CallFrameCfa : DwarfExprOp()

    /** `DW_OP_implicit_value <length> <bytes>` — the value is directly encoded in the expression. */
    data class ImplicitValue(val bytes: ByteArray) : DwarfExprOp() {
        override fun equals(other: Any?): Boolean =
            other is ImplicitValue && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** `DW_OP_const1u` through `DW_OP_const8s` — push a fixed-size constant. */
    data class ConstFixed(val value: Long, val byteSize: Int, val signed: Boolean) : DwarfExprOp()

    /** Any DWARF operation we recognize but don't model with a dedicated subclass. */
    data class Other(val opcode: UByte) : DwarfExprOp()
}

/**
 * A decoded DWARF location description: a sequence of [DwarfExprOp] operations
 * that together describe where a variable's value can be found at runtime.
 */
data class DwarfLocationDescription(val operations: List<DwarfExprOp>) {

    /** True when the location is empty (the variable is optimized away / unavailable). */
    val isEmpty: Boolean get() = operations.isEmpty()

    /** Returns the first Wasm-specific location operation, if any. */
    val wasmLocation: DwarfExprOp?
        get() = operations.firstOrNull {
            it is DwarfExprOp.WasmLocal ||
                it is DwarfExprOp.WasmGlobal ||
                it is DwarfExprOp.WasmGlobalU32 ||
                it is DwarfExprOp.WasmOperandStack
        }

    /** True when the location uses only a Wasm-specific virtual register. */
    val isWasmLocation: Boolean get() = wasmLocation != null

    /** True when the expression ends with `DW_OP_stack_value`. */
    val isStackValue: Boolean get() = operations.lastOrNull() is DwarfExprOp.StackValue

    /** Returns a human-readable summary of this location description. */
    fun describe(): String {
        if (isEmpty) return "<empty location>"
        return operations.joinToString(", ") { op ->
            when (op) {
                is DwarfExprOp.WasmLocal -> "wasm-local[${op.index}]"
                is DwarfExprOp.WasmGlobal -> "wasm-global[${op.index}]"
                is DwarfExprOp.WasmGlobalU32 -> "wasm-global[${op.index}]"
                is DwarfExprOp.WasmOperandStack -> "wasm-stack[${op.index}]"
                is DwarfExprOp.FrameBaseOffset -> "DW_OP_fbreg(${op.offset})"
                is DwarfExprOp.Address -> "DW_OP_addr(0x${op.address.toString(16)})"
                is DwarfExprOp.StackValue -> "DW_OP_stack_value"
                is DwarfExprOp.ConstUnsigned -> "DW_OP_constu(${op.value})"
                is DwarfExprOp.ConstSigned -> "DW_OP_consts(${op.value})"
                is DwarfExprOp.PlusUConst -> "DW_OP_plus_uconst(${op.value})"
                is DwarfExprOp.Register -> "DW_OP_reg${op.regNumber}"
                is DwarfExprOp.RegisterX -> "DW_OP_regx(${op.regNumber})"
                is DwarfExprOp.BaseRegisterOffset -> "DW_OP_breg${op.regNumber}(${op.offset})"
                is DwarfExprOp.BaseRegisterOffsetX -> "DW_OP_bregx(${op.regNumber}, ${op.offset})"
                is DwarfExprOp.Deref -> "DW_OP_deref"
                is DwarfExprOp.DerefSize -> "DW_OP_deref_size(${op.size})"
                is DwarfExprOp.Piece -> "DW_OP_piece(${op.byteSize})"
                is DwarfExprOp.BitPiece -> "DW_OP_bit_piece(${op.bitSize}, ${op.bitOffset})"
                is DwarfExprOp.CallFrameCfa -> "DW_OP_call_frame_cfa"
                is DwarfExprOp.ImplicitValue -> "DW_OP_implicit_value(${op.bytes.size})"
                is DwarfExprOp.ConstFixed -> "const(${op.value})"
                is DwarfExprOp.Other -> "op(0x${op.opcode.toString(16)})"
            }
        }
    }
}

/**
 * Parses a DWARF location expression encoded as a `DW_FORM_exprloc` byte array
 * (or a `DW_FORM_block*` byte array) into a structured [DwarfLocationDescription].
 *
 * The parser handles the Wasm-specific `DW_OP_WASM_location` (0xED) extension
 * defined in the [DWARF for WebAssembly](https://yurydelendik.github.io/webassembly-dwarf/)
 * specification, as well as common standard DWARF location operations.
 */
object DwarfExpressionParser {

    /**
     * Parses the given [exprBytes] as a DWARF location expression.
     *
     * @param exprBytes raw bytes of a `DW_FORM_exprloc` or `DW_FORM_block*` attribute value
     * @param addressSize the target address size in bytes (typically 4 for wasm32)
     * @return a [DwarfLocationDescription] with decoded operations
     */
    fun parse(exprBytes: ByteArray, addressSize: Int = 4): DwarfLocationDescription {
        if (exprBytes.isEmpty()) return DwarfLocationDescription(emptyList())
        val buffer = exprBytes.toCommonByteBuffer()
        val ops = mutableListOf<DwarfExprOp>()
        while (buffer.position() < exprBytes.size) {
            ops.add(readOperation(buffer, addressSize))
        }
        return DwarfLocationDescription(ops)
    }

    private fun readOperation(buffer: CommonByteBuffer, addressSize: Int): DwarfExprOp {
        val opByte = buffer.get().toUByte()
        return when {
            // DW_OP_litN: 0x30..0x4f → push literal N (0..31)
            opByte in 0x30u..0x4fu -> DwarfExprOp.ConstFixed(
                (opByte - 0x30u).toLong(), 0, false,
            )
            // DW_OP_regN: 0x50..0x6f → registers 0..31
            opByte in 0x50u..0x6fu -> DwarfExprOp.Register((opByte - 0x50u).toInt())
            // DW_OP_bregN: 0x70..0x8f → base register + SLEB128 offset
            opByte in 0x70u..0x8fu -> DwarfExprOp.BaseRegisterOffset(
                (opByte - 0x70u).toInt(),
                buffer.getSLEB128()
            )
            else -> when (opByte) {
                Op.DW_OP_addr.value -> {
                    val addr = when (addressSize) {
                        4 -> buffer.getIntLE().toLong() and 0xFFFFFFFFL
                        8 -> buffer.getLongLE()
                        else -> buffer.getIntLE().toLong() and 0xFFFFFFFFL
                    }
                    DwarfExprOp.Address(addr)
                }

                Op.DW_OP_deref.value -> DwarfExprOp.Deref
                Op.DW_OP_deref_size.value -> DwarfExprOp.DerefSize(buffer.get().toInt() and 0xFF)

                Op.DW_OP_const1u.value -> DwarfExprOp.ConstFixed(
                    (buffer.get().toLong() and 0xFF), 1, false
                )
                Op.DW_OP_const1s.value -> DwarfExprOp.ConstFixed(
                    buffer.get().toLong(), 1, true
                )
                Op.DW_OP_const2u.value -> DwarfExprOp.ConstFixed(
                    buffer.getShortLE().toUShort().toLong(), 2, false
                )
                Op.DW_OP_const2s.value -> DwarfExprOp.ConstFixed(
                    buffer.getShortLE().toLong(), 2, true
                )
                Op.DW_OP_const4u.value -> DwarfExprOp.ConstFixed(
                    buffer.getIntLE().toLong() and 0xFFFFFFFFL, 4, false
                )
                Op.DW_OP_const4s.value -> DwarfExprOp.ConstFixed(
                    buffer.getIntLE().toLong(), 4, true
                )
                Op.DW_OP_const8u.value -> DwarfExprOp.ConstFixed(
                    buffer.getLongLE(), 8, false
                )
                Op.DW_OP_const8s.value -> DwarfExprOp.ConstFixed(
                    buffer.getLongLE(), 8, true
                )
                Op.DW_OP_constu.value -> DwarfExprOp.ConstUnsigned(buffer.getULEB128())
                Op.DW_OP_consts.value -> DwarfExprOp.ConstSigned(buffer.getSLEB128())

                Op.DW_OP_plus_uconst.value -> DwarfExprOp.PlusUConst(buffer.getULEB128())

                Op.DW_OP_fbreg.value -> DwarfExprOp.FrameBaseOffset(buffer.getSLEB128())

                Op.DW_OP_regx.value -> DwarfExprOp.RegisterX(buffer.getULEB128())
                Op.DW_OP_bregx.value -> DwarfExprOp.BaseRegisterOffsetX(
                    buffer.getULEB128(), buffer.getSLEB128()
                )

                Op.DW_OP_piece.value -> DwarfExprOp.Piece(buffer.getULEB128())
                Op.DW_OP_bit_piece.value -> DwarfExprOp.BitPiece(
                    buffer.getULEB128(), buffer.getULEB128()
                )

                Op.DW_OP_stack_value.value -> DwarfExprOp.StackValue
                Op.DW_OP_call_frame_cfa.value -> DwarfExprOp.CallFrameCfa

                Op.DW_OP_implicit_value.value -> {
                    val length = buffer.getULEB128().toInt()
                    val bytes = ByteArray(length)
                    buffer.get(bytes, 0, length)
                    DwarfExprOp.ImplicitValue(bytes)
                }

                // Wasm-specific extension: DW_OP_WASM_location
                Op.DW_OP_WASM_location.value -> {
                    val wasmOp = buffer.get().toUByte()
                    when (wasmOp.toInt()) {
                        0x00 -> DwarfExprOp.WasmLocal(buffer.getULEB128())
                        0x01 -> DwarfExprOp.WasmGlobal(buffer.getULEB128())
                        0x02 -> DwarfExprOp.WasmOperandStack(buffer.getULEB128())
                        0x03 -> DwarfExprOp.WasmGlobalU32(buffer.getIntLE().toLong() and 0xFFFFFFFFL)
                        else -> DwarfExprOp.Other(opByte)
                    }
                }

                else -> DwarfExprOp.Other(opByte)
            }
        }
    }
}

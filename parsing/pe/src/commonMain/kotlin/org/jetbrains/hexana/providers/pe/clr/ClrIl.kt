package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.pe.byteOrNull
import org.jetbrains.hexana.providers.pe.u16LeOrNull
import org.jetbrains.hexana.providers.pe.u32LeOrNull
import org.jetbrains.hexana.providers.pe.u64LeOrNull

/** Inline operand encodings of ECMA-335 Partition III instructions. */
enum class ClrOperandKind(val size: Int) {
    None(0),
    Int8(1),
    UInt8(1),
    /** Argument or local index, one byte (`ldarg.s`, `stloc.s`, ...). */
    ShortVariable(1),
    /** Argument or local index, two bytes (`ldarg`, `stloc`, ...). */
    Variable(2),
    Int32(4),
    Int64(8),
    Float32(4),
    Float64(8),
    ShortBranch(1),
    Branch(4),
    /** `switch`: a u32 count followed by that many i32 targets. */
    Switch(4),
    Method(4),
    Field(4),
    Type(4),
    String(4),
    Signature(4),
    /** `ldtoken`: any of method, field or type. */
    Token(4),
}

/** One IL opcode; [code] is the single byte, or `0xFExx` for two-byte opcodes. */
class ClrOpcode(val code: Int, val mnemonic: String, val operand: ClrOperandKind) {
    val size: Int get() = if (code > 0xFF) 2 else 1

    /** True for `ret`, `throw`, unconditional branches and the like, after which a blank line reads well. */
    val endsBlock: Boolean get() = mnemonic in BLOCK_ENDING

    override fun toString(): String = mnemonic

    companion object {
        private val BLOCK_ENDING = setOf("ret", "throw", "rethrow", "br", "br.s", "leave", "leave.s", "endfinally", "endfilter", "jmp")
    }
}

sealed interface ClrIlOperand {
    data object None : ClrIlOperand
    data class Integer(val value: Long) : ClrIlOperand
    data class Real(val value: Double) : ClrIlOperand
    data class Variable(val index: Int) : ClrIlOperand
    /** Branch destination as an IL offset from the start of the method's code. */
    data class Target(val offset: Int) : ClrIlOperand
    data class Switch(val targets: List<Int>) : ClrIlOperand
    data class Token(val token: UInt) : ClrIlOperand
}

data class ClrIlInstruction(
    /** Offset from the start of the method's IL code (the `IL_xxxx` label). */
    val offset: Int,
    /** `null` for a byte that is not a defined opcode. */
    val opcode: ClrOpcode?,
    val operand: ClrIlOperand,
    /** Instruction bytes, opcode included. */
    val range: NativeByteRange,
)

data class ClrIlListing(
    val instructions: List<ClrIlInstruction>,
    /** IL offset where decoding stopped because an operand ran past the end of the code, or null. */
    val truncatedAt: Int?,
)

/** Decodes ECMA-335 Partition III instruction streams. */
object ClrIlDecoder {
    fun decode(bytes: CommonByteBuffer, code: NativeByteRange): ClrIlListing {
        val instructions = ArrayList<ClrIlInstruction>()
        val end = code.offset + code.size
        var position = code.offset
        while (position < end) {
            val start = position
            val ilOffset = start - code.offset
            val first = bytes.byteOrNull(position) ?: break
            val opcode = if (first == 0xFE) {
                val second = bytes.byteOrNull(position + 1).takeIf { position + 1 < end } ?: return ClrIlListing(instructions, ilOffset)
                TWO_BYTE_OPCODES.getOrNull(second)
            } else {
                ONE_BYTE_OPCODES[first]
            }
            if (opcode == null) {
                // Not a defined opcode: show the byte and keep going, so one bad byte does not hide the rest.
                position += if (first == 0xFE) 2 else 1
                instructions += ClrIlInstruction(ilOffset, null, ClrIlOperand.Integer(first.toLong()), NativeByteRange(start, position - start))
                continue
            }
            position += opcode.size
            val operandEnd = position + opcode.operand.size
            if (operandEnd > end) return ClrIlListing(instructions, ilOffset)
            var next = operandEnd
            val operand = when (opcode.operand) {
                ClrOperandKind.None -> ClrIlOperand.None
                ClrOperandKind.Int8 -> ClrIlOperand.Integer(bytes.byteOrNull(position)!!.toByte().toLong())
                ClrOperandKind.UInt8 -> ClrIlOperand.Integer(bytes.byteOrNull(position)!!.toLong())
                ClrOperandKind.ShortVariable -> ClrIlOperand.Variable(bytes.byteOrNull(position)!!)
                ClrOperandKind.Variable -> ClrIlOperand.Variable(bytes.u16LeOrNull(position)!!)
                ClrOperandKind.Int32 -> ClrIlOperand.Integer(bytes.u32LeOrNull(position)!!.toInt().toLong())
                ClrOperandKind.Int64 -> ClrIlOperand.Integer(bytes.u64LeOrNull(position)!!.toLong())
                ClrOperandKind.Float32 -> ClrIlOperand.Real(Float.fromBits(bytes.u32LeOrNull(position)!!.toInt()).toDouble())
                ClrOperandKind.Float64 -> ClrIlOperand.Real(Double.fromBits(bytes.u64LeOrNull(position)!!.toLong()))
                ClrOperandKind.ShortBranch -> ClrIlOperand.Target(operandEnd - code.offset + bytes.byteOrNull(position)!!.toByte())
                ClrOperandKind.Branch -> ClrIlOperand.Target(operandEnd - code.offset + bytes.u32LeOrNull(position)!!.toInt())
                ClrOperandKind.Switch -> {
                    val count = bytes.u32LeOrNull(position)!!.toLong()
                    if (count > (end - operandEnd) / 4) return ClrIlListing(instructions, ilOffset)
                    val tableEnd = operandEnd + count.toInt() * 4
                    val targets = List(count.toInt()) { index ->
                        tableEnd - code.offset + bytes.u32LeOrNull(operandEnd + index * 4)!!.toInt()
                    }
                    next = tableEnd
                    ClrIlOperand.Switch(targets)
                }
                ClrOperandKind.Method, ClrOperandKind.Field, ClrOperandKind.Type, ClrOperandKind.String,
                ClrOperandKind.Signature, ClrOperandKind.Token,
                -> ClrIlOperand.Token(bytes.u32LeOrNull(position)!!)
            }
            position = next
            instructions += ClrIlInstruction(ilOffset, opcode, operand, NativeByteRange(start, position - start))
        }
        return ClrIlListing(instructions, truncatedAt = null)
    }

    private val ONE_BYTE_OPCODES: Array<ClrOpcode?> = arrayOfNulls(256)
    private val TWO_BYTE_OPCODES: Array<ClrOpcode?> = arrayOfNulls(256)

    init {
        fun op(code: Int, mnemonic: String, operand: ClrOperandKind = ClrOperandKind.None) {
            val opcode = ClrOpcode(code, mnemonic, operand)
            if (code > 0xFF) TWO_BYTE_OPCODES[code and 0xFF] = opcode else ONE_BYTE_OPCODES[code] = opcode
        }
        fun ops(firstCode: Int, operand: ClrOperandKind, vararg mnemonics: String) {
            mnemonics.forEachIndexed { index, mnemonic -> op(firstCode + index, mnemonic, operand) }
        }
        val none = ClrOperandKind.None
        ops(0x00, none, "nop", "break", "ldarg.0", "ldarg.1", "ldarg.2", "ldarg.3", "ldloc.0", "ldloc.1", "ldloc.2", "ldloc.3",
            "stloc.0", "stloc.1", "stloc.2", "stloc.3")
        ops(0x0E, ClrOperandKind.ShortVariable, "ldarg.s", "ldarga.s", "starg.s", "ldloc.s", "ldloca.s", "stloc.s")
        ops(0x14, none, "ldnull", "ldc.i4.m1", "ldc.i4.0", "ldc.i4.1", "ldc.i4.2", "ldc.i4.3", "ldc.i4.4", "ldc.i4.5",
            "ldc.i4.6", "ldc.i4.7", "ldc.i4.8")
        op(0x1F, "ldc.i4.s", ClrOperandKind.Int8)
        op(0x20, "ldc.i4", ClrOperandKind.Int32)
        op(0x21, "ldc.i8", ClrOperandKind.Int64)
        op(0x22, "ldc.r4", ClrOperandKind.Float32)
        op(0x23, "ldc.r8", ClrOperandKind.Float64)
        ops(0x25, none, "dup", "pop")
        op(0x27, "jmp", ClrOperandKind.Method)
        op(0x28, "call", ClrOperandKind.Method)
        op(0x29, "calli", ClrOperandKind.Signature)
        op(0x2A, "ret")
        val branches = arrayOf("br", "brfalse", "brtrue", "beq", "bge", "bgt", "ble", "blt", "bne.un", "bge.un", "bgt.un", "ble.un", "blt.un")
        ops(0x2B, ClrOperandKind.ShortBranch, *Array(branches.size) { branches[it] + ".s" })
        ops(0x38, ClrOperandKind.Branch, *branches)
        op(0x45, "switch", ClrOperandKind.Switch)
        ops(0x46, none, "ldind.i1", "ldind.u1", "ldind.i2", "ldind.u2", "ldind.i4", "ldind.u4", "ldind.i8", "ldind.i",
            "ldind.r4", "ldind.r8", "ldind.ref", "stind.ref", "stind.i1", "stind.i2", "stind.i4", "stind.i8", "stind.r4",
            "stind.r8", "add", "sub", "mul", "div", "div.un", "rem", "rem.un", "and", "or", "xor", "shl", "shr", "shr.un",
            "neg", "not", "conv.i1", "conv.i2", "conv.i4", "conv.i8", "conv.r4", "conv.r8", "conv.u4", "conv.u8")
        op(0x6F, "callvirt", ClrOperandKind.Method)
        ops(0x70, ClrOperandKind.Type, "cpobj", "ldobj")
        op(0x72, "ldstr", ClrOperandKind.String)
        op(0x73, "newobj", ClrOperandKind.Method)
        ops(0x74, ClrOperandKind.Type, "castclass", "isinst")
        op(0x76, "conv.r.un")
        op(0x79, "unbox", ClrOperandKind.Type)
        op(0x7A, "throw")
        ops(0x7B, ClrOperandKind.Field, "ldfld", "ldflda", "stfld", "ldsfld", "ldsflda", "stsfld")
        op(0x81, "stobj", ClrOperandKind.Type)
        ops(0x82, none, "conv.ovf.i1.un", "conv.ovf.i2.un", "conv.ovf.i4.un", "conv.ovf.i8.un", "conv.ovf.u1.un",
            "conv.ovf.u2.un", "conv.ovf.u4.un", "conv.ovf.u8.un", "conv.ovf.i.un", "conv.ovf.u.un")
        ops(0x8C, ClrOperandKind.Type, "box", "newarr")
        op(0x8E, "ldlen")
        op(0x8F, "ldelema", ClrOperandKind.Type)
        ops(0x90, none, "ldelem.i1", "ldelem.u1", "ldelem.i2", "ldelem.u2", "ldelem.i4", "ldelem.u4", "ldelem.i8",
            "ldelem.i", "ldelem.r4", "ldelem.r8", "ldelem.ref", "stelem.i", "stelem.i1", "stelem.i2", "stelem.i4",
            "stelem.i8", "stelem.r4", "stelem.r8", "stelem.ref")
        ops(0xA3, ClrOperandKind.Type, "ldelem", "stelem", "unbox.any")
        ops(0xB3, none, "conv.ovf.i1", "conv.ovf.u1", "conv.ovf.i2", "conv.ovf.u2", "conv.ovf.i4", "conv.ovf.u4",
            "conv.ovf.i8", "conv.ovf.u8")
        op(0xC2, "refanyval", ClrOperandKind.Type)
        op(0xC3, "ckfinite")
        op(0xC6, "mkrefany", ClrOperandKind.Type)
        op(0xD0, "ldtoken", ClrOperandKind.Token)
        ops(0xD1, none, "conv.u2", "conv.u1", "conv.i", "conv.ovf.i", "conv.ovf.u", "add.ovf", "add.ovf.un", "mul.ovf",
            "mul.ovf.un", "sub.ovf", "sub.ovf.un", "endfinally")
        op(0xDD, "leave", ClrOperandKind.Branch)
        op(0xDE, "leave.s", ClrOperandKind.ShortBranch)
        ops(0xDF, none, "stind.i", "conv.u")

        ops(0xFE00, none, "arglist", "ceq", "cgt", "cgt.un", "clt", "clt.un")
        ops(0xFE06, ClrOperandKind.Method, "ldftn", "ldvirtftn")
        ops(0xFE09, ClrOperandKind.Variable, "ldarg", "ldarga", "starg", "ldloc", "ldloca", "stloc")
        op(0xFE0F, "localloc")
        op(0xFE11, "endfilter")
        op(0xFE12, "unaligned.", ClrOperandKind.UInt8)
        ops(0xFE13, none, "volatile.", "tail.")
        ops(0xFE15, ClrOperandKind.Type, "initobj", "constrained.")
        ops(0xFE17, none, "cpblk", "initblk")
        op(0xFE19, "no.", ClrOperandKind.UInt8)
        op(0xFE1A, "rethrow")
        op(0xFE1C, "sizeof", ClrOperandKind.Type)
        ops(0xFE1D, none, "refanytype", "readonly.")
    }
}

package org.jetbrains.hexana.javabytecode

/**
 * One decoded JVM bytecode instruction (JVMS §6). The [bytecodeOffset] is the
 * instruction's offset within its enclosing `Code` attribute's `code[]` array
 * (i.e. the value that branch / switch operands use).
 *
 * [operands] is the structured operand interpretation; the raw operand bytes
 * remain reachable via [JavaCodeAttribute.code] sliced over `bytecodeOffset
 * until bytecodeOffset + size`.
 */
data class JavaBytecodeInstruction(
    val bytecodeOffset: Int,
    val opcode: JavaOpcode,
    val size: Int,
    val operands: JavaBytecodeOperands,
)

/**
 * Structured operand payload per JVMS §6.5 — variable shape by opcode family.
 * The interpreter (or UI renderer) switches on the subtype.
 */
sealed interface JavaBytecodeOperands {
    /** No operands beyond the 1-byte opcode (e.g. `nop`, `iconst_0`, `aload_0`). */
    data object None : JavaBytecodeOperands

    /** Single byte operand: `bipush`, `ldc`, `newarray`. */
    data class Byte1(val value: Int) : JavaBytecodeOperands

    /** Two-byte operand: `sipush`, `ldc_w`, `ldc2_w`, `getstatic`, …, `goto`, `if_*`. */
    data class Short1(val value: Int) : JavaBytecodeOperands

    /** Four-byte operand: `goto_w`, `jsr_w`. */
    data class Wide1(val value: Int) : JavaBytecodeOperands

    /** `iinc` (and `wide iinc`): local-variable index + signed constant. */
    data class IInc(val varIndex: Int, val delta: Int) : JavaBytecodeOperands

    /** `invokeinterface`: cp index + count + zero. */
    data class InvokeInterface(val cpIndex: Int, val count: Int) : JavaBytecodeOperands

    /** `invokedynamic`: cp index + two zero bytes. */
    data class InvokeDynamic(val cpIndex: Int) : JavaBytecodeOperands

    /** `multianewarray`: cp index + dimensions. */
    data class MultiANewArray(val cpIndex: Int, val dimensions: Int) : JavaBytecodeOperands

    /** `tableswitch`: default offset + low + high + jump offsets table. */
    data class TableSwitch(
        val defaultOffset: Int,
        val low: Int,
        val high: Int,
        val jumpOffsets: List<Int>,
    ) : JavaBytecodeOperands

    /** `lookupswitch`: default offset + (match, offset) pairs. */
    data class LookupSwitch(
        val defaultOffset: Int,
        val pairs: List<Pair<Int, Int>>,
    ) : JavaBytecodeOperands

    /** `wide`-prefixed instructions (variable shape; carries the inner opcode). */
    data class Wide(val innerOpcode: Int, val varIndex: Int, val constant: Int? = null) : JavaBytecodeOperands

    /** Anything else we don't have a typed shape for yet — raw bytes preserved. */
    data class Raw(val bytes: ByteArray) : JavaBytecodeOperands {
        override fun equals(other: Any?): Boolean = other is Raw && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

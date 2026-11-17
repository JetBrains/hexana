package org.jetbrains.hexana.dex

/**
 * One decoded Dalvik instruction. [address] and [codeUnits] are counted in
 * 16-bit code units from the start of the enclosing `code_item`'s `insns[]`,
 * which is the unit branch and payload operands use.
 *
 * The raw bytes stay reachable as `insns[address * 2 until (address + codeUnits) * 2]`.
 */
data class DexInstruction(
    val address: Int,
    val opcode: DexOpcode,
    val codeUnits: Int,
    val operands: DexOperands,
) {
    /** `true` for the three payload pseudo-instructions, which carry no executable code. */
    val isPayload: Boolean get() = operands.payload != null
}

/**
 * Operands of a [DexInstruction], in one uniform shape rather than one type per
 * format.
 *
 * Dalvik has 26 instruction formats against the JVM's 14 operand families, so a
 * sealed hierarchy in the style of `JavaBytecodeOperands` would force every
 * renderer into a 26-arm `when` for what is really the same handful of operand
 * roles. [DexOpcode.format] already says which fields are populated; this
 * matches how smali/dexlib model the same encoding.
 */
data class DexOperands(
    /** Register operands in source order (`vA, vB, vC, …`), or the expanded range for `3rc`/`4rcc`. */
    val registers: List<Int> = emptyList(),
    /** Literal operand (`#+B`), already sign-extended and, for `21h`, shifted into place. */
    val literal: Long? = null,
    /** Absolute branch target, in code units from the start of `insns[]`. */
    val branchTarget: Int? = null,
    /** Pool index (`string@`, `type@`, `field@`, `meth@`, …); interpret via [DexOpcode.indexKind]. */
    val index: Int? = null,
    /** Second pool index: the `proto@HHHH` of `45cc` / `4rcc`. */
    val secondaryIndex: Int? = null,
    /** `true` when [registers] came from a range form, which renders as `{vC .. vN}`. */
    val isRange: Boolean = false,
    /** Set only on the payload pseudo-instructions. */
    val payload: DexPayload? = null,
)

/**
 * A variable-length data payload embedded in `insns[]`. Payloads hide behind
 * opcode `0x00`, with the high byte of the first code unit identifying the kind,
 * and are referenced by the [DexOpcode.PACKED_SWITCH], [DexOpcode.SPARSE_SWITCH]
 * and [DexOpcode.FILL_ARRAY_DATA] branch operands.
 */
sealed interface DexPayload {

    /** `packed-switch-payload`: consecutive keys from [firstKey], one target per key. */
    data class PackedSwitch(
        val firstKey: Int,
        /** Branch offsets, relative to the address of the referring `packed-switch`. */
        val targets: List<Int>,
    ) : DexPayload

    /** `sparse-switch-payload`: sorted key/target pairs of equal length. */
    data class SparseSwitch(
        val keys: List<Int>,
        /** Branch offsets, relative to the address of the referring `sparse-switch`. */
        val targets: List<Int>,
    ) : DexPayload

    /** `fill-array-data-payload`: [size] elements of [elementWidth] bytes each. */
    data class FillArrayData(
        val elementWidth: Int,
        val size: Int,
        val data: ByteArray,
    ) : DexPayload {
        override fun equals(other: Any?): Boolean =
            other is FillArrayData &&
                elementWidth == other.elementWidth &&
                size == other.size &&
                data.contentEquals(other.data)

        override fun hashCode(): Int {
            var result = elementWidth
            result = 31 * result + size
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
}

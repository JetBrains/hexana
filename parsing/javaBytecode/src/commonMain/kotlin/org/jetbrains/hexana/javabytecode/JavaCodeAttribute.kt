package org.jetbrains.hexana.javabytecode

/**
 * Parsed `Code` attribute body (JVMS §4.7.3). The raw [code] byte array is
 * preserved alongside the decoded [instructions] so callers that want byte
 * ranges (hex view, edits) don't need to re-decode.
 */
data class JavaCodeAttribute(
    val maxStack: Int,
    val maxLocals: Int,
    val code: ByteArray,
    val instructions: List<JavaBytecodeInstruction>,
    val exceptionTable: List<JavaExceptionHandler>,
    val attributes: List<AttributeRecord>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JavaCodeAttribute) return false
        return maxStack == other.maxStack &&
            maxLocals == other.maxLocals &&
            code.contentEquals(other.code) &&
            instructions == other.instructions &&
            exceptionTable == other.exceptionTable &&
            attributes == other.attributes
    }

    override fun hashCode(): Int {
        var result = maxStack
        result = 31 * result + maxLocals
        result = 31 * result + code.contentHashCode()
        result = 31 * result + instructions.hashCode()
        result = 31 * result + exceptionTable.hashCode()
        result = 31 * result + attributes.hashCode()
        return result
    }
}

/** Single row of the Code attribute's exception table (JVMS §4.7.3). */
data class JavaExceptionHandler(
    val startPc: Int,
    val endPc: Int,
    val handlerPc: Int,
    /** Constant-pool index of the caught exception's [JavaConstantPoolEntry.ClassRef]; 0 = catch-all (finally). */
    val catchTypeCpIndex: Int,
)

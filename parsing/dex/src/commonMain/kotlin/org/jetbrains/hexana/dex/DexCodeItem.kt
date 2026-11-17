package org.jetbrains.hexana.dex

/**
 * Parsed `code_item`: one method's register frame, decoded instruction stream
 * and exception table.
 *
 * [insnsFileOffset] is the absolute file offset of `insns[]`, kept for the same
 * reason as `JavaCodeAttribute.codeBaseFileOffset`: it lets a UI turn an
 * instruction's code-unit [DexInstruction.address] into a clickable file offset
 * (`insnsFileOffset + address * 2`).
 */
data class DexCodeItem(
    val registersSize: Int,
    /** Number of incoming argument registers, which occupy the top of the frame. */
    val insSize: Int,
    val outsSize: Int,
    val debugInfoOff: Long,
    /** Size of `insns[]` in 16-bit code units. */
    val insnsSize: Int,
    val insnsFileOffset: Int,
    val instructions: List<DexInstruction>,
    val tries: List<DexTry>,
    /** Catch handlers, addressed by [DexTry.handlerOff] via [handlerFor]. */
    val handlers: List<DexCatchHandler>,
) {
    /**
     * The first register that holds an incoming argument. Smali names these
     * `p0..pN`; everything below is a local `v0..vN`.
     */
    val firstParameterRegister: Int get() = registersSize - insSize

    /** Resolves the handler a [DexTry] points at, or `null` when the offset is unknown. */
    fun handlerFor(tryItem: DexTry): DexCatchHandler? =
        handlers.firstOrNull { it.offset == tryItem.handlerOff }
}

/** `try_item`: a code range covered by one catch handler list. */
data class DexTry(
    /** Start of the covered range, in code units. */
    val startAddr: Int,
    /** Length of the covered range, in code units. */
    val insnCount: Int,
    /** Byte offset of the handler within the `encoded_catch_handler_list`. */
    val handlerOff: Int,
) {
    /** First code unit after the covered range. */
    val endAddr: Int get() = startAddr + insnCount
}

/** `encoded_catch_handler`: typed handlers plus an optional catch-all. */
data class DexCatchHandler(
    /** This handler's byte offset within the `encoded_catch_handler_list`. */
    val offset: Int,
    val typeHandlers: List<DexTypeAddrPair>,
    /** Handler address for any exception, in code units, or `null` when absent. */
    val catchAllAddr: Int?,
)

/** `encoded_type_addr_pair`: one exception type and where it is handled. */
data class DexTypeAddrPair(
    /** Index into [DexFile.typeIds]. */
    val typeIdx: Int,
    /** Handler address, in code units. */
    val addr: Int,
)

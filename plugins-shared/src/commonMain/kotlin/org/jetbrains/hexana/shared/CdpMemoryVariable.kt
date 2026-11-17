package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dwarf.DwarfTypeInfo

/**
 * A DWARF-described variable whose value lives in Wasm linear memory at runtime.
 *
 * The location is expressed relative to the function's frame base (shadow-stack pointer):
 * the actual linear-memory address = frameBaseLocalIndex wasm-local value + [fbregOffset].
 * For static/global variables ([DW_OP_addr][org.jetbrains.hexana.dwarf.DwarfExprOp.Address])
 * use [absoluteAddress] instead; [fbregOffset] and [frameBaseLocalIndex] will both be `null`.
 *
 * @property name the DWARF source-level variable name
 * @property type the resolved type (drives rendering and byte count)
 * @property fbregOffset signed byte offset from the frame-base pointer into linear memory;
 *           `null` for absolute-address variables
 * @property frameBaseLocalIndex the wasm local index whose value is the frame-base pointer
 *           (shadow stack pointer loaded in the function prologue); `null` for absolute-address
 *           variables
 * @property absoluteAddress the fixed linear-memory address for `DW_OP_addr` variables;
 *           `null` for frame-relative variables
 * @property byteSize the number of bytes to read from linear memory (derived from [type]);
 *           capped at [MAX_SLAB_BYTES] for safety
 */
data class CdpMemoryVariable(
    val name: String,
    val type: DwarfTypeInfo,
    val fbregOffset: Long?,
    val frameBaseLocalIndex: Long?,
    val absoluteAddress: Long?,
    val byteSize: Int,
) {
    companion object {
        /** Maximum bytes read per variable in one CDP round-trip. */
        const val MAX_SLAB_BYTES: Int = 4096
    }
}

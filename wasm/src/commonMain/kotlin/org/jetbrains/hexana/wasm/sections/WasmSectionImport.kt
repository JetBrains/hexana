package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer

class WasmSectionImport(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 2
    }

    fun CommonByteBuffer.getImportRecords(): MutableList<WasmImportRecord> {
        position(offset)

        val records = mutableListOf<WasmImportRecord>()
        consumeArray {
            val prefix = consumeString()
            val name = consumeString()
            val rawType = get()
            val type = when (rawType) {
                0x00.toByte() -> WasmTypeIndexExternalType(getULEB128())
                0x01.toByte() -> {
                    val refType = referenceType()
                    val limits = limits()
                    WasmTableExternalType(refType, limits)
                }
                0x02.toByte() -> WasmMemType(limits())
                0x03.toByte() -> {
                    WasmGlobalType(valueType(), get() != 0x00.toByte())
                }
                0x04.toByte() -> WasmTagType(WasmTypeIndexExternalType(getULEB128()))
                else -> error("unknown import type: $rawType")
            }
            records.add(
                WasmImportRecord(prefix, name, type)
            )
        }

        return records
    }

}

sealed class WasmExternalType(val type: Byte)
class WasmTypeIndexExternalType(val index: Long): WasmExternalType(0) {
    override fun equals(other: Any?): Boolean {
        val b = other as? WasmTypeIndexExternalType ?: return false
        return b.index == index && b.type == type
    }

    override fun hashCode(): Int {
        return index.hashCode()
    }
}
class WasmTableExternalType(val referenceType: WasmReferenceType, val limits: WasmLimits<*>): WasmExternalType(1)

/**
 *
 */
sealed class WasmLimits<T>(val kind: Byte, val min: T, val max: T?)

/**
 * [min, infinite)
 */
class WasmOpenI32Limits(min: Int): WasmLimits<Int>(0, min, null)
/**
 * [min, max]
 */
class WasmFixedI32Limits(min: Int, max: Int): WasmLimits<Int>(1, min, max)
/**
 * [min, infinite)
 */
class WasmOpenI64Limits(min: Long): WasmLimits<Long>(4, min, null)

/**
 * [min, max]
 */
class WasmFixedI64Limits(min: Long, max: Long): WasmLimits<Long>(5, min, max)

/**
 * spec 5.3.12
 */
class WasmMemType(val limits: WasmLimits<*>): WasmExternalType(0x2)

/**
 * spec 5.3.11
 */
class WasmGlobalType(val valueType: WasmValueType<*>, val mutable: Boolean): WasmExternalType(0x3)

/**
 * Represents an import record in a WebAssembly module.
 * externtype ::=
 * Spec 5.3.14
 * 0x00 𝑥:typeidx ⇒ func 𝑥
 * 0x01 tt:tabletype ⇒ table tt
 * 0x02 mt:memtype ⇒ mem mt
 * 0x03 gt:globaltype ⇒ global gt
 * 0x04 jt:tagtype ⇒ tag jt.
 */
data class WasmImportRecord(val prefix: String, val name: String, val type: WasmExternalType)
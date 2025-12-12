package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange

class WasmSectionImport(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Imports", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 2
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with(buffer) {
            getImportRecords().mapIndexed { index, record ->
                val name = when {
                    record.name.isBlank() -> "import $index"
                    else -> "import $index: ${record.name}"
                }
                WasmNamedRange(record, name)
            }
        }
    }

    fun CommonByteBuffer.getImportRecords(): MutableList<WasmImportRecord> {
        position(contentOffset)

        val records = mutableListOf<WasmImportRecord>()
        consumeArray {
            val offset = position()
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
                WasmImportRecord(offset, sizeFromOffset(offset), prefix, name, type)
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
data class WasmImportRecord(
    override val offset: Int,
    override val size: Int,
    val prefix: String,
    val name: String,
    val type: WasmExternalType
) : WasmRange
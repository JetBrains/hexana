package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.limits
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.referenceType
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.valueType

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

    /**
     * Parses the import section, degrading to the decoded prefix when an entry
     * cannot be decoded (unknown extern type, truncated entry). Use
     * [getImportRecordsPartial] when the failure offset is needed for reporting.
     */
    fun CommonByteBuffer.getImportRecords(): MutableList<WasmImportRecord> = getImportRecordsPartial().first

    /**
     * Parses as many import records as possible. On the first undecodable entry,
     * stops and returns the partial list paired with the byte offset of that
     * entry (null second means the section parsed fully).
     */
    fun CommonByteBuffer.getImportRecordsPartial(): Pair<MutableList<WasmImportRecord>, Int?> = run {
        position(contentOffset)
        consumeArrayPartial(contentOffset + contentSize) { importRecord() }.let { it.values to it.failureOffset }
    }

}

fun CommonByteBuffer.importRecord(): WasmImportRecord {
    val offset = position()
    val prefix = consumeString()
    val name = consumeString()
    val rawType = get()
    val type = when (rawType) {
        0x00.toByte() -> WasmTypeIndexExternalType(consumeUnsignedUInt().toLong())
        0x01.toByte() -> {
            val refType = referenceType()!!
            val limits = limits()
            WasmTableExternalType(refType, limits)
        }

        0x02.toByte() -> WasmMemType(limits())
        0x03.toByte() -> {
            WasmGlobalType(valueType()!!, get() != 0x00.toByte())
        }

        0x04.toByte() -> {
            get() // 0x00 prefix
            WasmTagType(WasmTypeIndexExternalType(consumeUnsignedUInt().toLong()))
        }

        else -> error("unknown import type: $rawType")
    }
    val element = WasmImportRecord(offset, sizeFromOffset(offset), prefix, name, type)
    return element
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
class WasmTableExternalType(val referenceType: WasmReferenceType, val limits: WasmLimits<*>): WasmExternalType(1) {
    override fun equals(other: Any?): Boolean {
        val b = other as? WasmTableExternalType ?: return false
        return referenceType == b.referenceType && limits == b.limits
    }

    override fun hashCode(): Int {
        var result = referenceType.hashCode()
        result = 31 * result + limits.hashCode()
        return result
    }
}

/**
 * @param pageSizeLog2 the explicit page size, as a base-2 logarithm, when the limits
 *   carry the custom-page-sizes flag (bit 3); `null` for the default 64 KiB page. Named
 *   after wasm-tools' `page_size_log2`.
 */
sealed class WasmLimits<T>(val kind: Byte, val min: T, val max: T?, val pageSizeLog2: Int? = null) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val b = other as? WasmLimits<*> ?: return false
        return kind == b.kind && min == b.min && max == b.max && pageSizeLog2 == b.pageSizeLog2
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + (min?.hashCode() ?: 0)
        result = 31 * result + (max?.hashCode() ?: 0)
        result = 31 * result + (pageSizeLog2?.hashCode() ?: 0)
        return result
    }
}

/**
 * [min, infinite)
 */
class WasmOpenI32Limits(min: Int, val shared: Boolean = false, pageSizeLog2: Int? = null):
    WasmLimits<Int>(0, min, null, pageSizeLog2)
/**
 * [min, max]
 */
class WasmFixedI32Limits(min: Int, max: Int, val shared:Boolean = false, pageSizeLog2: Int? = null):
    WasmLimits<Int>(1, min, max, pageSizeLog2) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val b = other as? WasmFixedI32Limits ?: return false
        return min == b.min && max == b.max && shared == b.shared && pageSizeLog2 == b.pageSizeLog2
    }

    override fun hashCode(): Int {
        var result = min.hashCode()
        result = 31 * result + max.hashCode()
        result = 31 * result + shared.hashCode()
        result = 31 * result + (pageSizeLog2?.hashCode() ?: 0)
        return result
    }
}
/**
 * [min, infinite)
 */
class WasmOpenI64Limits(min: Long, val shared: Boolean = false, pageSizeLog2: Int? = null):
    WasmLimits<Long>(4, min, null, pageSizeLog2)

/**
 * [min, max]
 */
class WasmFixedI64Limits(min: Long, max: Long, val shared: Boolean = false, pageSizeLog2: Int? = null):
    WasmLimits<Long>(5, min, max, pageSizeLog2)

/**
 * spec 5.3.12
 */
class WasmMemType(val limits: WasmLimits<*>): WasmExternalType(0x2) {
    override fun equals(other: Any?): Boolean {
        val b = other as? WasmMemType ?: return false
        return b.limits == limits
    }

    override fun hashCode(): Int = limits.hashCode()
}

/**
 * spec 5.3.11
 */
class WasmGlobalType(val valueType: WasmValueType<*>, val mutable: Boolean): WasmExternalType(0x3) {
    override fun equals(other: Any?): Boolean {
        val b = other as? WasmGlobalType ?: return false
        return valueType == b.valueType && mutable == b.mutable
    }

    override fun hashCode(): Int {
        var result = valueType.hashCode()
        result = 31 * result + mutable.hashCode()
        return result
    }
}

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

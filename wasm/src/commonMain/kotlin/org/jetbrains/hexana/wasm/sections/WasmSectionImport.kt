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

    private fun CommonByteBuffer.referenceType(): WasmReferenceType {
        return when (get()) {
            0x63.toByte() -> {
                WasmNullableReferenceType(heapType())
            }

            0x64.toByte() -> {
                WasmNonNullableReferenceType(heapType())
            }

            else -> {
                position(position() - 1)
                WasmReferenceHeapType(heapType())
            }
        }

    }

    private fun CommonByteBuffer.limits(): WasmLimits<*> {
        return when (get()) {
            0x00.toByte() -> WasmOpenI32Limits(getULEB128().toInt())
            0x01.toByte() -> WasmFixedI32Limits(getULEB128().toInt(), getULEB128().toInt())
            0x02.toByte() -> WasmOpenI64Limits(getULEB128())
            0x03.toByte() -> WasmFixedI64Limits(getULEB128(), getULEB128())
            else -> error("unknown limits type")
        }
    }

    private fun CommonByteBuffer.valueType(): WasmValueType<*> {
        return when (get()) {
            0x7c.toByte() -> WasmF32Type()
            0x7d.toByte() -> WasmF64Type()
            0x7e.toByte() -> WasmI32Type()
            0x7f.toByte() -> WasmI64Type()
            0x7b.toByte() -> WasmVecType()

            else -> {
                position(position() - 1)
                WasmRefType(referenceType())
            }
        }
    }

    private fun CommonByteBuffer.heapType(): WasmHeapType {
        return when (get()) {
            0x69.toByte() -> WasmExnRefHeapType()
            0x6a.toByte() -> WasmArrayRefHeapType()
            0x6b.toByte() -> WasmStructRefHeapType()
            0x6c.toByte() -> WasmI31RefHeapType()
            0x6d.toByte() -> WasmEqRefHeapType()
            0x6e.toByte() -> WasmAnyRefHeapType()
            0x6f.toByte() -> WasmExtRefHeapType()
            0x70.toByte() -> WasmFuncHeapType()
            0x71.toByte() -> WasmNoneHeapType()
            0x72.toByte() -> WasmNoExternRefHeapType()
            0x73.toByte() -> WasmNoFuncHeapType()
            0x74.toByte() -> WasmNoExnHeapType()
            else -> {
                position(position() - 1)
                WasmIndexHeapType(getULEB128())
            }
        }
    }
}

sealed class WasmExternalType(val type: Byte)
class WasmTypeIndexExternalType(val index: Long): WasmExternalType(0)
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

sealed class WasmReferenceType(val heapType: WasmHeapType)

/**
 * spec 5.3.3
 */
sealed class WasmHeapType(val type: Long)
class WasmExnRefHeapType: WasmHeapType(0x69)
class WasmArrayRefHeapType: WasmHeapType(0x6a)
class WasmStructRefHeapType: WasmHeapType(0x6b)
class WasmI31RefHeapType: WasmHeapType(0x6c)
class WasmEqRefHeapType: WasmHeapType(0x6d)
class WasmAnyRefHeapType: WasmHeapType(0x6e)
class WasmExtRefHeapType: WasmHeapType(0x6f)
class WasmFuncHeapType: WasmHeapType(0x70)
class WasmNoneHeapType: WasmHeapType(0x71)
class WasmNoExternRefHeapType: WasmHeapType(0x72)
class WasmNoFuncHeapType: WasmHeapType(0x73)
class WasmNoExnHeapType: WasmHeapType(0x74)
class WasmIndexHeapType(type: Long): WasmHeapType(type)



/**
 * spec 5.3.4
 */
class WasmNonNullableReferenceType(heapType: WasmHeapType): WasmReferenceType(heapType)
class WasmNullableReferenceType(heapType: WasmHeapType): WasmReferenceType(heapType)
class WasmReferenceHeapType(heapType: WasmHeapType): WasmReferenceType(heapType)

/**
 * spec 5.3.12
 */
class WasmMemType(val limits: WasmLimits<*>): WasmExternalType(0x2)

/**
 * spec 5.3.11
 */
class WasmGlobalType(val valueType: WasmValueType<*>, val mutable: Boolean): WasmExternalType(0x3)

sealed class  WasmValueType<T>(val code: T)

sealed class WasmNumType(code: Byte) : WasmValueType<Byte>(code)
class WasmF32Type : WasmNumType(0x7c)
class WasmF64Type : WasmNumType(0x7d)
class WasmI32Type : WasmNumType(0x7f)
class WasmI64Type : WasmNumType(0x7e)
class WasmVecType : WasmValueType<Byte>(0x7b)
class WasmRefType(code: WasmReferenceType) : WasmValueType<WasmReferenceType>(code)

class WasmTagType(val index: WasmTypeIndexExternalType) : WasmExternalType(0x4)

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
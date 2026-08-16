package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.limits
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.referenceType

class WasmSectionTable(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Tables", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 4
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with(buffer) {
            scanTable().mapIndexed { index, entry ->
                WasmNamedRange(entry, "table $index")
            }
        }
    }

    /**
     * Parses as many table entries as possible. On the first undecodable entry,
     * stops and returns the partial list paired with the byte offset of that
     * entry (null second means the section parsed fully).
     */
    fun CommonByteBuffer.scanTablePartial(): Pair<MutableList<WasmTableEntry>, Int?> {
        position(contentOffset)
        val entries = mutableListOf<WasmTableEntry>()
        var failedOffset: Int? = null

        val count = getULEB128().toInt()
        for (i in 0 until count) {
            val offset = position()
            try {
                if (get() == 0x40.toByte()) {
                    if (get() == 0x00.toByte()) {
                        val tableType = tableType()
                        val opcodes = parseOpCodes()
                        entries.add(WasmTableEntry(offset, sizeFromOffset(offset), tableType, opcodes))
                        continue
                    } else {
                        position(position() - 1)
                    }
                } else {
                    position(position() - 1)
                }
                val tableType = tableType()
                entries.add(WasmTableEntry(offset, sizeFromOffset(offset), tableType))
            } catch (e: Exception) {
                failedOffset = offset
                break
            }
        }
        return entries to failedOffset
    }

    fun CommonByteBuffer.scanTable(): List<WasmTableEntry> = scanTablePartial().first

    private fun CommonByteBuffer.tableType(): WasmTableType {
        return WasmTableType(referenceType()!!, limits())
    }
}

data class WasmTableType(val referenceType: WasmReferenceType, val limits: WasmLimits<*> )

class WasmTableEntry(
    override val offset: Int,
    override val size: Int,
    val tableType: WasmTableType,
    val expression: List<OpCode>? = null
) : WasmRange

package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation


data class LineRow(val offset: Int, val line: Int, val column: Int, val directory: Int, val file: Int)

class DwarfSourceLocationResolver(val dwarfAccessor: DwarfAccessor) {

    private class LineProgramExecutionState(
        val header: DwarfLineUnitHeader<*>,
        var address: ULong = 0UL,
        var line: Int = 1,
        var column: Int = 0,
        var file: Int = 0,
        var directory: Int = 0) {

        fun reset() {
            address = 0UL
            line = 1
            column = 0

            when(header) {
                is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4,
                is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V4 -> {
                    file = 1
                    directory = 1
                }
                is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V5,
                is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V5 -> {
                    file = 0
                    directory = 0
                }
            }
        }

        fun toLineRow() = LineRow(address.toInt(), line, column, directory, file)
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun executeProgram(
        header: DwarfLineUnitHeader<*>,
        program: List<LineProgramOpcode>
    ): List<LineRow> {
        val state = LineProgramExecutionState(header)
        state.reset()

        val opcodeBase = header.standardOpcodeLengths.size + 1
        val minInstLength = header.maximumInstructionsLength.toInt()
        val lineBase = header.lineBase.toInt()
        val lineRange = header.lineRange.toInt()

        val range = mutableListOf<LineRow>()
        program.forEach { op ->
            when(op) {
                is LnsSpecialOpcode -> {
                    val adjusted = op.value.toInt() - opcodeBase
                    state.address += ((adjusted / lineRange) * minInstLength).toULong()
                    state.line += lineBase + (adjusted % lineRange)
                    range.add(state.toLineRow())

                }
                is LnsOpcode.DW_LNS_copy -> {
                    range.add(state.toLineRow())
                }
                is LnsOpcode.DW_LNS_advance_pc -> {
                    state.address += op.params[0] * minInstLength.toULong()
                }
                is LnsOpcode.DW_LNS_advance_line -> {
                    state.line += op.params[0].toInt()
                }
                is LnsOpcode.DW_LNS_set_file -> {
                    state.file = op.params[0].toInt()
                }
                is LnsOpcode.DW_LNS_set_column -> {
                    state.column = op.params[0].toInt()
                }
                is LnsOpcode.DW_LNS_const_add_pc -> {
                    val adjusted = 255 - opcodeBase
                    state.address += ((adjusted / lineRange) * minInstLength).toULong()
                }
                is LneOpcode.DW_LNE_set_address -> {
                    state.address = op.params[0]
                }
                is LneOpcode.DW_LNE_end_sequence -> {
                    range.add(state.toLineRow())
                }
                else -> { /* ignore */ }
            }
        }
        return range
    }

    data class SourceLocation(val file: String, val line: Int, val column: Int)
    fun CommonByteBuffer.resolveState(header: DwarfLineUnitHeader<*>, state: LineRow, compDir: String? = null): SourceLocation? {
        return when(header) {
            is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4 -> {
                header.includeFiles[state.file - 1].let { fileEntry ->
                    val file = fileEntry.fileName
                    val directory = when(val dirIndex = fileEntry.dirIndex) {
                        0 -> compDir ?: ""
                        else -> header.includeDirectories[dirIndex - 1]
                    }
                    SourceLocation("${directory}/${file}" , state.line, state.column)
                }
            }
            is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V4 -> {
                header.includeFiles[state.file - 1].let { fileEntry ->
                    val file = fileEntry.fileName
                    val directory = when (val dirIndex = fileEntry.dirIndex) {
                        0 -> compDir ?: ""
                        else -> header.includeDirectories[dirIndex - 1]
                    }
                    SourceLocation("${directory}/${file}" , state.line, state.column)
                }

            }
            is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V5 -> {
                val fileEntry = header.fileNames[state.file]
                val directoryIndex = fileEntry.find { it.formatName == LineNumberHeaderEntryFormatName.DW_LNCT_directory_index }?.let {
                    it.value as Long
                } ?: return null
                val fileNameOff = fileEntry.find { it.formatName == LineNumberHeaderEntryFormatName.DW_LNCT_path }?.let { pathEntry ->
                    pathEntry.value as Int
                } ?: return null
                val directoryEntry = header.directories[directoryIndex.toInt()]
                val directoryNameIndex = directoryEntry.find { it.formatName == LineNumberHeaderEntryFormatName.DW_LNCT_path }?.let { it.value as Int } ?: return null
                val debugLineStr = dwarfAccessor.section(".debug_line_str") ?: return null
                val (directoryName, fileName) = with(debugLineStr) {
                    crossSectionOperation {
                        position(this@with.contentOffset + directoryNameIndex)
                        val a = readStringZ()
                        position(this@with.contentOffset + fileNameOff)
                        val b = readStringZ()
                        a to b
                    }
                }
                SourceLocation("${directoryName}/${fileName}" , state.line, state.column)
            }
            is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V5 -> {
                TODO()
            }
        }
    }

    fun CommonByteBuffer.resolveLocation(offset: Int): SourceLocation? {
        val infos = with(dwarfAccessor.debugInfoSection()!!){
            scanDwarfInfo()
        }
        val targetDie = infos.find {
            val low = (it.children[0].attributes.find { it.attribute.name == Attribute.DW_AT_low_pc }?.value as Long).toInt()
            val hi = (it.children[0].attributes.find { it.attribute.name == Attribute.DW_AT_high_pc }?.value as UInt).toInt()
            offset in low..hi
        } ?: return null

        val targetCu = targetDie.children[0]
        val debugLineOffset = (targetCu.attributes.find { it.attribute.name == Attribute.DW_AT_stmt_list }?.value as? UInt)?.toInt() ?: return null

        val debugLineSection = dwarfAccessor.debugLineSection() ?: return null
        val lineUnits = with(debugLineSection) {
            scanLineTable()
        }
        val lineUnitHeader = lineUnits.find { it.contentOffset - debugLineSection.contentOffset == debugLineOffset } ?: return null
        val program = with(lineUnitHeader) {
           lineProgram()
        }
        val result = executeProgram(lineUnitHeader, program)
        val entry = result.findLast { it.offset <= offset } ?: return null
        val compDir = when(targetCu) {
            is CompilationUnitHeader.Dwarf32Header.V4,
            is CompilationUnitHeader.Dwarf64Header.V4 -> {
                targetCu.attributes.find { it.attribute.name == Attribute.DW_AT_comp_dir }?.value as? String
            }
            else -> null
        }
        return resolveState(lineUnitHeader, entry, compDir)
    }
}
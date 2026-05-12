package org.jetbrains.hexana.parsing.common

import org.jetbrains.hexana.io.CommonByteBuffer

enum class NativeBinaryEndian {
    Little,
    Big,
    Unknown,
}

enum class NativeBinaryWordSize {
    Bits32,
    Bits64,
    Unknown,
}

sealed interface NativeBinaryFormat {
    val displayName: String

    data class Elf(
        val wordSize: NativeBinaryWordSize,
        val endian: NativeBinaryEndian,
        val fileType: Int?,
        val machine: Int?,
    ) : NativeBinaryFormat {
        override val displayName: String = buildString {
            append("ELF")
            when (wordSize) {
                NativeBinaryWordSize.Bits32 -> append("32")
                NativeBinaryWordSize.Bits64 -> append("64")
                NativeBinaryWordSize.Unknown -> Unit
            }
        }
    }

    data class Pe(
        val wordSize: NativeBinaryWordSize,
        val machine: Int?,
    ) : NativeBinaryFormat {
        override val displayName: String = when (wordSize) {
            NativeBinaryWordSize.Bits32 -> "PE32"
            NativeBinaryWordSize.Bits64 -> "PE32+"
            NativeBinaryWordSize.Unknown -> "PE"
        }
    }

    data class MachO(
        val wordSize: NativeBinaryWordSize,
        val endian: NativeBinaryEndian,
        val cpuType: Int?,
    ) : NativeBinaryFormat {
        override val displayName: String = buildString {
            append("Mach-O")
            when (wordSize) {
                NativeBinaryWordSize.Bits32 -> append(" 32")
                NativeBinaryWordSize.Bits64 -> append(" 64")
                NativeBinaryWordSize.Unknown -> Unit
            }
        }
    }

    data class FatMachO(
        val wordSize: NativeBinaryWordSize,
        val endian: NativeBinaryEndian,
    ) : NativeBinaryFormat {
        override val displayName: String = buildString {
            append("Mach-O Universal")
            if (wordSize == NativeBinaryWordSize.Bits64) append(" 64")
        }
    }

    data object Archive : NativeBinaryFormat {
        override val displayName: String = "Archive"
    }

    data class Coff(
        val machine: Int,
        val sectionCount: Int,
    ) : NativeBinaryFormat {
        override val displayName: String = "COFF"
    }
}

object NativeBinaryFormatDetector {
    fun detect(bytes: CommonByteBuffer): NativeBinaryFormat? {
        val reader = NativeBinaryReader(bytes)
        detectArchive(reader)?.let { return it }
        detectElf(reader)?.let { return it }
        detectPe(reader)?.let { return it }
        detectMachO(reader)?.let { return it }
        detectCoff(reader)?.let { return it }
        return null
    }

    private fun detectArchive(reader: NativeBinaryReader): NativeBinaryFormat.Archive? {
        if (!reader.hasPrefix(0, 0x21, 0x3C, 0x61, 0x72, 0x63, 0x68, 0x3E, 0x0A)) return null
        return NativeBinaryFormat.Archive
    }

    private fun detectCoff(reader: NativeBinaryReader): NativeBinaryFormat.Coff? {
        if (reader.size < COFF_HEADER_SIZE) return null

        val machine = reader.u16OrNull(0) ?: return null
        if (machine !in coffMachineTypes) return null

        val sectionCount = reader.u16OrNull(2) ?: return null
        if (sectionCount !in 1..96) return null
        val sectionTableSize = sectionCount * COFF_SECTION_HEADER_SIZE
        if (sectionTableSize > reader.size - COFF_HEADER_SIZE) return null

        val symbolTableOffset = reader.u32OrNull(8)?.takeIf { it <= Int.MAX_VALUE.toUInt() }?.toInt() ?: return null
        val symbolCount = reader.u32OrNull(12) ?: return null
        val optionalHeaderSize = reader.u16OrNull(16) ?: return null
        if (optionalHeaderSize != 0) return null

        if (symbolCount == 0u) {
            if (symbolTableOffset != 0) return null
        } else {
            if (symbolTableOffset < COFF_HEADER_SIZE || symbolTableOffset >= reader.size) return null
            if (symbolCount > (Int.MAX_VALUE / COFF_SYMBOL_SIZE).toUInt()) return null
            val symbolTableSize = symbolCount.toInt() * COFF_SYMBOL_SIZE
            if (symbolTableSize > reader.size - symbolTableOffset) return null
        }

        return NativeBinaryFormat.Coff(machine, sectionCount)
    }

    private const val COFF_HEADER_SIZE = 20
    private const val COFF_SECTION_HEADER_SIZE = 40
    private const val COFF_SYMBOL_SIZE = 18

    private val coffMachineTypes = setOf(
        0x014Cu, // IMAGE_FILE_MACHINE_I386
        0x01C0u, // IMAGE_FILE_MACHINE_ARM
        0x01C4u, // IMAGE_FILE_MACHINE_ARMNT
        0x0200u, // IMAGE_FILE_MACHINE_IA64
        0x8664u, // IMAGE_FILE_MACHINE_AMD64
        0xAA64u, // IMAGE_FILE_MACHINE_ARM64
    ).map { it.toInt() }.toSet()

    private fun detectElf(reader: NativeBinaryReader): NativeBinaryFormat.Elf? {
        if (!reader.hasPrefix(0, 0x7F, 0x45, 0x4C, 0x46)) return null

        val wordSize = when (reader.byteOrNull(4)) {
            1 -> NativeBinaryWordSize.Bits32
            2 -> NativeBinaryWordSize.Bits64
            else -> NativeBinaryWordSize.Unknown
        }
        val endian = when (reader.byteOrNull(5)) {
            1 -> NativeBinaryEndian.Little
            2 -> NativeBinaryEndian.Big
            else -> NativeBinaryEndian.Unknown
        }

        return NativeBinaryFormat.Elf(
            wordSize = wordSize,
            endian = endian,
            fileType = reader.u16OrNull(16, endian),
            machine = reader.u16OrNull(18, endian),
        )
    }

    private fun detectPe(reader: NativeBinaryReader): NativeBinaryFormat.Pe? {
        if (!reader.hasPrefix(0, 0x4D, 0x5A)) return null

        val peOffset = reader.u32OrNull(0x3C)?.takeIf { it <= Int.MAX_VALUE.toUInt() }?.toInt() ?: return null
        if (!reader.hasPrefix(peOffset, 0x50, 0x45, 0x00, 0x00)) return null

        val machine = reader.u16OrNull(peOffset + 4)
        val optionalHeaderMagic = reader.u16OrNull(peOffset + 24)
        val wordSize = when (optionalHeaderMagic) {
            0x010B -> NativeBinaryWordSize.Bits32
            0x020B -> NativeBinaryWordSize.Bits64
            else -> NativeBinaryWordSize.Unknown
        }

        return NativeBinaryFormat.Pe(wordSize, machine)
    }

    private fun detectMachO(reader: NativeBinaryReader): NativeBinaryFormat? {
        if (reader.size < 4) return null

        return when (reader.u32OrNull(0, NativeBinaryEndian.Big)) {
            0xFEEDFACEu -> NativeBinaryFormat.MachO(
                wordSize = NativeBinaryWordSize.Bits32,
                endian = NativeBinaryEndian.Big,
                cpuType = reader.i32OrNull(4, NativeBinaryEndian.Big),
            )
            0xCEFAEDFEu -> NativeBinaryFormat.MachO(
                wordSize = NativeBinaryWordSize.Bits32,
                endian = NativeBinaryEndian.Little,
                cpuType = reader.i32OrNull(4, NativeBinaryEndian.Little),
            )
            0xFEEDFACFu -> NativeBinaryFormat.MachO(
                wordSize = NativeBinaryWordSize.Bits64,
                endian = NativeBinaryEndian.Big,
                cpuType = reader.i32OrNull(4, NativeBinaryEndian.Big),
            )
            0xCFFAEDFEu -> NativeBinaryFormat.MachO(
                wordSize = NativeBinaryWordSize.Bits64,
                endian = NativeBinaryEndian.Little,
                cpuType = reader.i32OrNull(4, NativeBinaryEndian.Little),
            )
            0xCAFEBABEu -> NativeBinaryFormat.FatMachO(
                wordSize = NativeBinaryWordSize.Bits32,
                endian = NativeBinaryEndian.Big,
            )
            0xBEBAFECAu -> NativeBinaryFormat.FatMachO(
                wordSize = NativeBinaryWordSize.Bits32,
                endian = NativeBinaryEndian.Little,
            )
            0xCAFEBABFu -> NativeBinaryFormat.FatMachO(
                wordSize = NativeBinaryWordSize.Bits64,
                endian = NativeBinaryEndian.Big,
            )
            0xBFBAFECAu -> NativeBinaryFormat.FatMachO(
                wordSize = NativeBinaryWordSize.Bits64,
                endian = NativeBinaryEndian.Little,
            )
            else -> null
        }
    }
}

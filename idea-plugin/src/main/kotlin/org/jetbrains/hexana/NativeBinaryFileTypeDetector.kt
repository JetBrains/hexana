package org.jetbrains.hexana

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.io.ByteSequence

/**
 * Magic-byte detector that routes extensionless (or ambiguously-named) binaries to the
 * correct [NativeBinaryFileType] subtype without relying on file extensions alone.
 *
 * Disambiguation note — fat Mach-O vs Java `.class`:
 *   Both begin with `CA FE BA BE`. Java `.class` files store their major version number
 *   in the next 2 bytes (0x0000 bytes 4-5) and minor version in bytes 6-7; fat Mach-O
 *   stores `nfat_arch` there as a big-endian u32. In practice, `nfat_arch` values up to
 *   ~8 are common and Java major versions are also small (e.g. 65 for Java 21), so the
 *   numeric ranges overlap. The safest guard is to reject the match entirely when the
 *   file extension is `.class`, letting IntelliJ's built-in class-file handler win.
 */
class NativeBinaryFileTypeDetector : FileTypeRegistry.FileTypeDetector {

    override fun getDesiredContentPrefixLength(): Int = 0x100

    override fun detect(file: VirtualFile, firstBytes: ByteSequence, firstCharsIfText: CharSequence?): FileType? {
        val len = firstBytes.length()
        if (len < 4) return null

        val b0 = firstBytes.byteAt(0).toInt() and 0xFF
        val b1 = firstBytes.byteAt(1).toInt() and 0xFF
        val b2 = firstBytes.byteAt(2).toInt() and 0xFF
        val b3 = firstBytes.byteAt(3).toInt() and 0xFF

        // ELF: 7F 45 4C 46
        if (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) {
            return ElfFileType.INSTANCE
        }

        // Mach-O thin: FE ED FA CE (big-endian 32-bit), CE FA ED FE (little-endian 32-bit),
        //              FE ED FA CF (big-endian 64-bit), CF FA ED FE (little-endian 64-bit)
        if ((b0 == 0xFE && b1 == 0xED && b2 == 0xFA && (b3 == 0xCE || b3 == 0xCF)) ||
            (b3 == 0xFE && b2 == 0xED && b1 == 0xFA && (b0 == 0xCE || b0 == 0xCF))
        ) {
            return MachOFileType.INSTANCE
        }

        // Mach-O fat/universal: CA FE BA BE / BE BA FE CA / CA FE BA BF / BF BA FE CA
        // Guard against the CA FE BA BE collision with Java .class files (see KDoc above).
        if ((b0 == 0xCA && b1 == 0xFE && b2 == 0xBA && (b3 == 0xBE || b3 == 0xBF)) ||
            (b3 == 0xCA && b2 == 0xFE && b1 == 0xBA && (b0 == 0xBE || b0 == 0xBF))
        ) {
            if (file.extension.equals("class", ignoreCase = true)) return null
            return MachOFileType.INSTANCE
        }

        // PE: 4D 5A ("MZ"), then check e_lfanew at offset 0x3C and PE signature at that offset
        if (b0 == 0x4D && b1 == 0x5A && len >= 0x40) {
            val eLfanew = readU32LE(firstBytes, 0x3C)
            if (eLfanew in 0x40 until len - 3) {
                val pe0 = firstBytes.byteAt(eLfanew).toInt() and 0xFF
                val pe1 = firstBytes.byteAt(eLfanew + 1).toInt() and 0xFF
                val pe2 = firstBytes.byteAt(eLfanew + 2).toInt() and 0xFF
                val pe3 = firstBytes.byteAt(eLfanew + 3).toInt() and 0xFF
                if (pe0 == 0x50 && pe1 == 0x45 && pe2 == 0x00 && pe3 == 0x00) {
                    return PeFileType.INSTANCE
                }
            }
        }

        return null
    }

    private fun readU32LE(bytes: ByteSequence, offset: Int): Int {
        val b0 = bytes.byteAt(offset).toInt() and 0xFF
        val b1 = bytes.byteAt(offset + 1).toInt() and 0xFF
        val b2 = bytes.byteAt(offset + 2).toInt() and 0xFF
        val b3 = bytes.byteAt(offset + 3).toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}

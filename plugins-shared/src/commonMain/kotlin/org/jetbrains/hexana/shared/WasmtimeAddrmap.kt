package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.elf.ElfParser

/**
 * Decoded `.wasmtime.addrmap` section of a wasmtime compilation artifact —
 * either a `.cwasm` file or the in-memory ELF image wasmtime registers with
 * the GDB JIT interface (`__jit_debug_descriptor`).
 *
 * The section maps native code offsets (relative to the artifact's `.text`
 * section) back to byte offsets in the original wasm file. Encoding, per
 * wasmtime `crates/environ/src/address_map.rs`: a little-endian u32 entry
 * count, then `count` sorted u32 code offsets, then `count` u32 wasm file
 * positions where `0xFFFFFFFF` means "no source position" (prologue,
 * epilogue and other glue code).
 *
 * In the registered JIT image wasmtime patches the `.text` section address
 * to the real load address of the compiled code, so a stopped thread's pc
 * can be translated with plain subtraction.
 */
class WasmtimeAddrmap private constructor(
    /** Load address of the `.text` section as recorded in the image. */
    val textLoadAddress: ULong,
    private val textSize: ULong,
    /** Sorted `.text`-relative native code offsets (u32 stored as Int bits). */
    private val codeOffsets: IntArray,
    /** Wasm file byte offset per entry; -1 (0xFFFFFFFF) = no source position. */
    private val filePositions: IntArray,
) {

    /** Number of decoded addrmap entries. */
    val entryCount: Int get() = codeOffsets.size

    /** Returns true when [pc] falls inside this artifact's `.text` mapping. */
    fun containsPc(pc: ULong): Boolean =
        pc >= textLoadAddress && pc - textLoadAddress < textSize

    /**
     * Returns the wasm file byte offset for the native [pc], or null when
     * [pc] is outside `.text`, precedes the first mapped instruction, or the
     * covering entry has no source position.
     *
     * The lookup mirrors wasmtime's `lookup_file_pos`: a binary search for
     * the greatest code offset not exceeding the pc's `.text`-relative
     * offset (native pcs between two entries belong to the earlier bucket).
     */
    fun wasmOffsetForPc(pc: ULong): Int? {
        if (!containsPc(pc)) return null
        val target = (pc - textLoadAddress).toUInt()
        var lo = 0
        var hi = codeOffsets.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (codeOffsets[mid].toUInt() <= target) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return null
        val pos = filePositions[best]
        return if (pos == -1) null else pos
    }

    companion object {
        private const val SECTION_NAME = ".wasmtime.addrmap"
        private const val TEXT_SECTION_NAME = ".text"

        /**
         * Parses a wasmtime ELF artifact and decodes its addrmap section.
         *
         * @param imageBytes the full artifact bytes (a `.cwasm` file or a
         *        JIT image dumped from the debuggee's memory)
         * @return the decoded map, or null when [imageBytes] is not an ELF
         *         image or has no well-formed `.wasmtime.addrmap` section
         */
        fun parse(imageBytes: ByteArray): WasmtimeAddrmap? {
            val elf = ElfParser.parse(imageBytes.toCommonByteBuffer()).valueOrNull ?: return null
            val text = elf.sections.firstOrNull { it.name == TEXT_SECTION_NAME } ?: return null
            val section = elf.sections.firstOrNull { it.name == SECTION_NAME } ?: return null

            val start = section.fileOffset.toLong()
            val size = section.size.toLong()
            if (start < 0 || size < 4 || start + size > imageBytes.size) return null

            fun u32(pos: Long): Int {
                val i = pos.toInt()
                return (imageBytes[i].toInt() and 0xFF) or
                    ((imageBytes[i + 1].toInt() and 0xFF) shl 8) or
                    ((imageBytes[i + 2].toInt() and 0xFF) shl 16) or
                    ((imageBytes[i + 3].toInt() and 0xFF) shl 24)
            }

            val count = u32(start)
            if (count < 0 || 4L + 8L * count > size) return null

            val codeOffsets = IntArray(count) { u32(start + 4 + 4L * it) }
            val filePositions = IntArray(count) { u32(start + 4 + 4L * count + 4L * it) }
            return WasmtimeAddrmap(text.address, text.size, codeOffsets, filePositions)
        }
    }
}

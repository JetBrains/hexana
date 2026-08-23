package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Minimal parser for the ELF `.ARM.attributes` section (SHT_ARM_ATTRIBUTES = 0x70000003).
 *
 * Extracts the aeabi Tag_CPU_arch_profile (tag 7) value to distinguish CPU profiles:
 * - 'A' (0x41) — Application profile (Cortex-A)
 * - 'R' (0x52) — Real-time profile (Cortex-R)
 * - 'M' (0x4D) — Microcontroller profile (Cortex-M / Thumb-2)
 * - 'S' (0x53) — Classic profile
 * - 0          — Architecture-independent or pre-v7
 *
 * Returns null on any malformed or absent data. Never throws.
 */
object ArmAttributesParser {

    private const val SECTION_FORMAT_VERSION: Int = 0x41 // 'A'
    private const val VENDOR_AEABI: String = "aeabi"
    private const val TAG_FILE: Long = 1L
    private const val TAG_CPU_ARCH_PROFILE: Long = 7L

    /** Reads the aeabi Tag_CPU_arch_profile from the given section content slice. */
    fun parseCpuArchProfile(bytes: CommonByteBuffer, section: ElfSectionHeader): Char? {
        val content = section.contentRange ?: return null
        if (content.size < 1) return null
        return try {
            parseSection(bytes, content.offset, content.offset + content.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSection(buf: CommonByteBuffer, start: Int, end: Int): Char? {
        var pos = start
        if (pos >= end) return null

        // First byte: format version, must be 'A' (0x41)
        val version = buf[pos].toInt() and 0xFF
        pos += 1
        if (version != SECTION_FORMAT_VERSION) return null

        // Sections: u32LE length (includes the 4-byte length field itself) + NUL-terminated vendor name
        while (pos + 4 < end) {
            val sectionLen = buf.readU32LE(pos)
            if (sectionLen < 5u) return null  // length + at least one char + NUL
            val sectionEnd = pos + sectionLen.toInt()
            if (sectionEnd > end || sectionEnd < pos) return null
            pos += 4

            // NUL-terminated vendor name
            val nameStart = pos
            while (pos < sectionEnd && buf[pos] != 0.toByte()) pos++
            if (pos >= sectionEnd) return null
            val vendor = buf.readString(nameStart, pos - nameStart)
            pos += 1 // skip NUL

            if (vendor == VENDOR_AEABI) {
                val result = parseAeabiSubsections(buf, pos, sectionEnd)
                if (result != null) return result
            }
            pos = sectionEnd
        }
        return null
    }

    private fun parseAeabiSubsections(buf: CommonByteBuffer, start: Int, end: Int): Char? {
        var pos = start
        while (pos + 5 < end) {
            // ULEB128 subsection tag
            val (tag, tagLen) = buf.readUleb128At(pos)
            if (tagLen <= 0) return null
            pos += tagLen

            if (pos + 4 > end) return null
            val subLen = buf.readU32LE(pos)  // includes the 4-byte size field
            if (subLen < 4u) return null
            val subEnd = pos + subLen.toInt()
            if (subEnd > end || subEnd < pos) return null
            pos += 4

            if (tag == TAG_FILE) {
                val result = parseTagFileAttributes(buf, pos, subEnd)
                if (result != null) return result
            }
            pos = subEnd
        }
        return null
    }

    private fun parseTagFileAttributes(buf: CommonByteBuffer, start: Int, end: Int): Char? {
        var pos = start
        while (pos < end) {
            val (tag, tagLen) = buf.readUleb128At(pos)
            if (tagLen <= 0) return null
            pos += tagLen
            if (pos >= end) return null

            if (tag == TAG_CPU_ARCH_PROFILE) {
                // ULEB128 value
                val (value, valueLen) = buf.readUleb128At(pos)
                if (valueLen <= 0) return null
                return if (value == 0L) '0' else value.toInt().toChar()
            }

            // Skip the value for other tags
            pos = skipAttributeValue(buf, pos, end, tag) ?: return null
        }
        return null
    }

    /**
     * Skips the value of an attribute at [pos] and returns the position after it, or null if
     * the buffer is malformed. Value encoding rules per ARM IHI0045:
     * - Tags 4 and 5: NUL-terminated string
     * - Tag 32 (Tag_compatibility): ULEB128 followed by NUL-terminated string
     * - Other tags < 32: ULEB128
     * - Tags >= 32: even -> ULEB128, odd -> NTBS
     */
    private fun skipAttributeValue(buf: CommonByteBuffer, pos: Int, end: Int, tag: Long): Int? {
        val isString = when {
            tag == 4L || tag == 5L -> true
            tag == 32L -> {
                // ULEB128 then NTBS
                val (_, ulLen) = buf.readUleb128At(pos)
                if (ulLen <= 0) return null
                val afterUl = pos + ulLen
                return skipNtbs(buf, afterUl, end)
            }
            tag > 32L -> (tag % 2L) == 1L // odd = NTBS
            else -> false // all remaining tags below 32 are ULEB128
        }
        return if (isString) skipNtbs(buf, pos, end) else skipUleb128(buf, pos, end)
    }

    private fun skipNtbs(buf: CommonByteBuffer, pos: Int, end: Int): Int? {
        var i = pos
        while (i < end) {
            if (buf[i] == 0.toByte()) return i + 1
            i++
        }
        return null
    }

    private fun skipUleb128(buf: CommonByteBuffer, pos: Int, end: Int): Int? {
        val (_, len) = buf.readUleb128At(pos)
        if (len <= 0) return null
        val next = pos + len
        return if (next <= end) next else null
    }

    // ---- helpers ----

    private fun CommonByteBuffer.readU32LE(offset: Int): UInt {
        val b0 = get(offset).toInt() and 0xFF
        val b1 = get(offset + 1).toInt() and 0xFF
        val b2 = get(offset + 2).toInt() and 0xFF
        val b3 = get(offset + 3).toInt() and 0xFF
        return b0.toUInt() or (b1.toUInt() shl 8) or (b2.toUInt() shl 16) or (b3.toUInt() shl 24)
    }

    private fun CommonByteBuffer.readString(offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) sb.append(get(offset + i).toInt().toChar())
        return sb.toString()
    }

    /**
     * Reads a ULEB128 value at [offset]. Returns (value, bytesConsumed).
     * bytesConsumed is 0 if the buffer ran out before a terminal byte was found.
     */
    private fun CommonByteBuffer.readUleb128At(offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < size) {
            val b = get(i).toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if ((b and 0x80) == 0) return result to (i - offset)
            if (shift >= 63) return result to 0  // overflow guard
        }
        return result to 0
    }
}

package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeParserSeverity
import org.jetbrains.hexana.providers.pe.byteOrNull
import org.jetbrains.hexana.providers.pe.containsRange
import org.jetbrains.hexana.providers.pe.u16LeOrNull
import org.jetbrains.hexana.providers.pe.u32LeOrNull
import org.jetbrains.hexana.providers.pe.u64LeOrNull

/**
 * A .NET `.resources` file: the binary resource set `ResourceWriter` produces and
 * `ResourceManager` reads, either standalone or embedded in an assembly.
 *
 * Layout, as `System.Resources.ResourceReader` reads it: a ResourceManager header (magic
 * `0xBEEFCACE`, reader and resource set type names), a reader header (version, counts, the type
 * table, padding to 8 bytes, name hashes and name offsets, the data section offset), the name
 * section (UTF-16 names, each followed by its value's offset) and the data section.
 */
data class ClrResourcesFile(
    val managerHeaderVersion: Int,
    /** Assembly-qualified type of the reader, e.g. `System.Resources.ResourceReader, mscorlib, …`. */
    val readerType: String,
    val resourceSetType: String,
    /** Reader format version: 2 since .NET 2.0; version 1 values are not decoded. */
    val version: Int,
    /** Types of user-defined values, assembly-qualified, indexed from type code `0x40`. */
    val types: List<String>,
    /** In file order, which is name-hash order. */
    val resources: List<ClrResource>,
    val headerRange: NativeByteRange,
    val nameSectionRange: NativeByteRange?,
    val dataSectionRange: NativeByteRange?,
    val diagnostics: List<NativeParserDiagnostic>,
)

enum class ClrResourceKind { Null, String, Primitive, ByteArray, Stream, Serialized, Unreadable }

data class ClrResource(
    val name: String,
    /** C#-style name for built-in types (`string`, `int`, `byte[]`), else the assembly-qualified type. */
    val type: String,
    val kind: ClrResourceKind,
    /** Display text: the string, the formatted number, or a summary for binary and serialized data. */
    val value: String,
    /** The raw string for [ClrResourceKind.String] values. */
    val stringValue: String?,
    /** The name entry: its length prefix, UTF-16 characters and data offset. */
    val nameRange: NativeByteRange,
    /** The value's bytes, type code included; null when its offset is out of range. */
    val dataRange: NativeByteRange?,
    /** For byte arrays, streams and serialized values: the payload without type code and length. */
    val payloadRange: NativeByteRange?,
)

object ClrResourcesParser {
    const val MAGIC: UInt = 0xBEEFCACEu

    fun isResources(bytes: CommonByteBuffer): Boolean = bytes.u32LeOrNull(0) == MAGIC

    /** Returns null when the bytes are not a resource set; damaged entries degrade to [ClrResourceKind.Unreadable]. */
    fun parse(bytes: CommonByteBuffer, range: NativeByteRange = NativeByteRange(0, bytes.size)): ClrResourcesFile? {
        val reader = Reader(bytes, range.offset, range.offset + range.size)
        return try {
            reader.parse()
        } catch (_: Malformed) {
            null
        }
    }

    private class Malformed : RuntimeException()

    private class Reader(private val bytes: CommonByteBuffer, private val start: Int, private val end: Int) {
        private val diagnostics = mutableListOf<NativeParserDiagnostic>()
        var position = start

        fun parse(): ClrResourcesFile? {
            if (u32() != MAGIC) return null
            val managerVersion = u32().toInt()
            val skip = u32().toInt()
            if (skip < 0 || skip > end - position) throw Malformed()
            val managerHeaderEnd = position + skip
            // Version 1 headers hold the two type names; later versions are skipped by size.
            val readerType = if (managerVersion == 1) string() else ""
            val resourceSetType = if (managerVersion == 1) string() else ""
            position = managerHeaderEnd

            val version = u32().toInt()
            val count = u32().toInt()
            val typeCount = u32().toInt()
            if (count < 0 || count > (end - position) / 8 || typeCount < 0 || typeCount > end - position) throw Malformed()
            val types = List(typeCount) { string() }
            // The writer pads with "PAD" bytes to align the hash table on 8 bytes from the start.
            while ((position - start) and 7 != 0) position++
            position += 4 * count // name hashes
            val namePositions = IntArray(count) { u32().toInt() }
            val dataSectionOffset = u32().toInt()
            val nameSection = position
            val dataSection = start + dataSectionOffset
            if (dataSection < nameSection || dataSection > end) throw Malformed()

            val entries = (0 until count).map { index -> readName(index, nameSection, dataSection, namePositions[index]) }
            // A value's extent is only implicit for serialized data: it runs to the next value.
            val valueStarts = entries.mapNotNull { it?.dataOffset }.filter { it in dataSection until end }.distinct().sorted().toIntArray()
            val resources = entries.mapNotNull { entry ->
                entry ?: return@mapNotNull null
                val next = valueStarts.firstAfter(entry.dataOffset) ?: end
                readValue(entry, version, types, next)
            }
            return ClrResourcesFile(
                managerHeaderVersion = managerVersion,
                readerType = readerType,
                resourceSetType = resourceSetType,
                version = version,
                types = types,
                resources = resources,
                headerRange = NativeByteRange(start, nameSection - start),
                nameSectionRange = NativeByteRange(nameSection, dataSection - nameSection),
                dataSectionRange = NativeByteRange(dataSection, end - dataSection),
                diagnostics = diagnostics,
            )
        }

        private class NameEntry(val name: String, val range: NativeByteRange, val dataOffset: Int)

        private fun readName(index: Int, nameSection: Int, dataSection: Int, relative: Int): NameEntry? {
            val at = nameSection + relative
            if (relative < 0 || at >= dataSection) return invalid("Resource #$index points outside the name section.")
            position = at
            val length = sevenBitInt()
            if (length < 0 || length % 2 != 0 || length > dataSection - position - 4) return invalid("Resource #$index has a malformed name.")
            val name = CharArray(length / 2) { (u16(position + it * 2)).toChar() }.concatToString()
            position += length
            val dataOffset = u32().toInt()
            return NameEntry(name, NativeByteRange(at, position - at), dataSection + dataOffset)
        }

        private fun readValue(entry: NameEntry, version: Int, types: List<String>, next: Int): ClrResource {
            fun resource(type: String, kind: ClrResourceKind, value: String, string: String? = null, payload: NativeByteRange? = null) =
                ClrResource(entry.name, type, kind, value, string, entry.range, NativeByteRange(entry.dataOffset, position - entry.dataOffset), payload)

            if (entry.dataOffset !in start until end) {
                return ClrResource(entry.name, "?", ClrResourceKind.Unreadable, "<value outside the file>", null, entry.range, null, null)
            }
            return try {
                position = entry.dataOffset
                val code = sevenBitInt()
                if (version != 2) {
                    // Version 1 tags values with a type table index and serializes them all.
                    val type = types.getOrNull(code) ?: if (code == -1) "null" else "?"
                    position = next
                    return resource(type, ClrResourceKind.Serialized, "${next - entry.dataOffset} bytes (version 1 encoding)")
                }
                when (code) {
                    TYPE_NULL -> resource("null", ClrResourceKind.Null, "null")
                    TYPE_STRING -> string().let { resource("string", ClrResourceKind.String, it, it) }
                    TYPE_BOOLEAN -> resource("bool", ClrResourceKind.Primitive, (byte() != 0).toString())
                    TYPE_CHAR -> resource("char", ClrResourceKind.Primitive, "'${u16(position).toChar()}'".also { position += 2 })
                    TYPE_BYTE -> resource("byte", ClrResourceKind.Primitive, byte().toString())
                    TYPE_SBYTE -> resource("sbyte", ClrResourceKind.Primitive, byte().toByte().toString())
                    TYPE_INT16 -> resource("short", ClrResourceKind.Primitive, u16(position).toShort().toString().also { position += 2 })
                    TYPE_UINT16 -> resource("ushort", ClrResourceKind.Primitive, u16(position).toString().also { position += 2 })
                    TYPE_INT32 -> resource("int", ClrResourceKind.Primitive, u32().toInt().toString())
                    TYPE_UINT32 -> resource("uint", ClrResourceKind.Primitive, u32().toString())
                    TYPE_INT64 -> resource("long", ClrResourceKind.Primitive, u64().toLong().toString())
                    TYPE_UINT64 -> resource("ulong", ClrResourceKind.Primitive, u64().toString())
                    TYPE_SINGLE -> resource("float", ClrResourceKind.Primitive, Float.fromBits(u32().toInt()).toString())
                    TYPE_DOUBLE -> resource("double", ClrResourceKind.Primitive, Double.fromBits(u64().toLong()).toString())
                    TYPE_DECIMAL -> resource("decimal", ClrResourceKind.Primitive, decimal())
                    TYPE_DATE_TIME -> resource("DateTime", ClrResourceKind.Primitive, formatDateTime(u64().toLong()))
                    TYPE_TIME_SPAN -> resource("TimeSpan", ClrResourceKind.Primitive, formatTimeSpan(u64().toLong()))
                    TYPE_BYTE_ARRAY, TYPE_STREAM -> {
                        val length = u32().toInt()
                        if (length < 0 || length > end - position) throw Malformed()
                        val payload = NativeByteRange(position, length)
                        position += length
                        val stream = code == TYPE_STREAM
                        resource(
                            if (stream) "Stream" else "byte[]",
                            if (stream) ClrResourceKind.Stream else ClrResourceKind.ByteArray,
                            describeBytes(payload),
                            payload = payload,
                        )
                    }
                    else -> {
                        val type = types.getOrNull(code - TYPE_USER_TYPES)
                            ?: return ClrResource(entry.name, "?", ClrResourceKind.Unreadable, "<unknown type code 0x${code.toString(16)}>", null, entry.range, null, null)
                        val payload = NativeByteRange(position, (next - position).coerceAtLeast(0))
                        position = next
                        resource(type, ClrResourceKind.Serialized, "${payload.size} bytes, serialized", payload = payload)
                    }
                }
            } catch (_: Malformed) {
                ClrResource(entry.name, "?", ClrResourceKind.Unreadable, "<truncated value>", null, entry.range, null, null)
            }
        }

        private fun describeBytes(range: NativeByteRange): String {
            val sniffed = sniff(range)
            return "${range.size} bytes" + (sniffed?.let { " ($it)" } ?: "")
        }

        /** Content type of common embedded files, from their leading bytes. */
        private fun sniff(range: NativeByteRange): String? {
            fun at(index: Int) = if (index < range.size) bytes.byteOrNull(range.offset + index) else null
            return when {
                at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "PNG image"
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "JPEG image"
                at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> "GIF image"
                at(0) == 0x42 && at(1) == 0x4D -> "BMP image"
                at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x01 && at(3) == 0x00 -> "icon"
                at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x02 && at(3) == 0x00 -> "cursor"
                at(0) == 0x50 && at(1) == 0x4B && at(2) == 0x03 && at(3) == 0x04 -> "ZIP archive"
                at(0) == 0x4D && at(1) == 0x5A -> "PE image"
                else -> null
            }
        }

        /** `System.Decimal`: flags (scale in bits 16-23, sign in bit 31) after a 96-bit integer, as `lo, mid, hi, flags`. */
        private fun decimal(): String {
            val lo = u32()
            val mid = u32()
            val hi = u32()
            val flags = u32()
            val scale = ((flags shr 16) and 0xFFu).toInt().coerceAtMost(28)
            val parts = uintArrayOf(hi, mid, lo)
            val digits = StringBuilder()
            while (parts.any { it != 0u }) {
                var remainder = 0uL
                for (index in parts.indices) {
                    val current = (remainder shl 32) or parts[index].toULong()
                    parts[index] = (current / 10uL).toUInt()
                    remainder = current % 10uL
                }
                digits.append(remainder.toInt())
            }
            var text = digits.reverse().toString().ifEmpty { "0" }.padStart(scale + 1, '0')
            if (scale > 0) text = text.dropLast(scale) + "." + text.takeLast(scale)
            return if (flags and 0x80000000u != 0u) "-$text" else text
        }

        private fun invalid(message: String): NameEntry? {
            diagnostics += NativeParserDiagnostic("clr.resources.invalid_name", message, severity = NativeParserSeverity.Warning)
            return null
        }

        private fun byte(): Int = (if (position < end) bytes.byteOrNull(position) else null)?.also { position++ } ?: throw Malformed()

        private fun u16(offset: Int): Int = (if (offset + 2 <= end) bytes.u16LeOrNull(offset) else null) ?: throw Malformed()

        private fun u32(): UInt = (if (position + 4 <= end) bytes.u32LeOrNull(position) else null)?.also { position += 4 } ?: throw Malformed()

        private fun u64(): ULong = (if (position + 8 <= end) bytes.u64LeOrNull(position) else null)?.also { position += 8 } ?: throw Malformed()

        /** `BinaryReader.Read7BitEncodedInt`: little-endian base-128, at most five bytes. */
        private fun sevenBitInt(): Int {
            var value = 0
            for (shift in 0 until 35 step 7) {
                val byte = byte()
                value = value or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return value
            }
            throw Malformed()
        }

        /** `BinaryWriter` string: a 7-bit encoded byte length, then UTF-8. */
        private fun string(): String {
            val length = sevenBitInt()
            if (length < 0 || length > end - position || !bytes.containsRange(position, length)) throw Malformed()
            val text = ByteArray(length) { bytes[position + it] }.decodeToString()
            position += length
            return text
        }
    }
}

/** `DateTime.ToBinary()`: ticks since 0001-01-01 in the low 62 bits, the kind in the top two. */
internal fun formatDateTime(binary: Long): String {
    val kind = (binary ushr 62).toInt()
    val ticks = binary and 0x3FFFFFFFFFFFFFFFL
    val days = ticks / TICKS_PER_DAY
    val timeOfDay = ticks % TICKS_PER_DAY
    // Civil date from a day count (H. Hinnant's algorithm), shifted from 0001-01-01 to 0000-03-01.
    val z = days + 306
    val era = z / 146097
    val dayOfEra = z - era * 146097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthIndex = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
    val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    val date = "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}"
    val suffix = when (kind) {
        1 -> "Z"
        2, 3 -> " (local)"
        else -> ""
    }
    return date + "T" + clock(timeOfDay) + suffix
}

/** `TimeSpan` in its constant ("c") format: `[-][d.]hh:mm:ss[.fffffff]`. */
internal fun formatTimeSpan(ticks: Long): String {
    val negative = ticks < 0
    val magnitude = if (negative) -ticks else ticks
    val days = magnitude / TICKS_PER_DAY
    return (if (negative) "-" else "") + (if (days > 0) "$days." else "") + clock(magnitude % TICKS_PER_DAY)
}

private fun clock(ticksOfDay: Long): String {
    val seconds = ticksOfDay / TICKS_PER_SECOND
    val fraction = ticksOfDay % TICKS_PER_SECOND
    val time = "${two(seconds / 3600)}:${two(seconds / 60 % 60)}:${two(seconds % 60)}"
    return if (fraction == 0L) time else "$time.${fraction.toString().padStart(7, '0')}"
}

private fun two(value: Long): String = value.toString().padStart(2, '0')

private const val TICKS_PER_SECOND = 10_000_000L
private const val TICKS_PER_DAY = 86_400L * TICKS_PER_SECOND

// `ResourceTypeCode`.
private const val TYPE_NULL = 0x00
private const val TYPE_STRING = 0x01
private const val TYPE_BOOLEAN = 0x02
private const val TYPE_CHAR = 0x03
private const val TYPE_BYTE = 0x04
private const val TYPE_SBYTE = 0x05
private const val TYPE_INT16 = 0x06
private const val TYPE_UINT16 = 0x07
private const val TYPE_INT32 = 0x08
private const val TYPE_UINT32 = 0x09
private const val TYPE_INT64 = 0x0A
private const val TYPE_UINT64 = 0x0B
private const val TYPE_SINGLE = 0x0C
private const val TYPE_DOUBLE = 0x0D
private const val TYPE_DECIMAL = 0x0E
private const val TYPE_DATE_TIME = 0x0F
private const val TYPE_TIME_SPAN = 0x10
private const val TYPE_BYTE_ARRAY = 0x20
private const val TYPE_STREAM = 0x21
private const val TYPE_USER_TYPES = 0x40

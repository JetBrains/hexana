package org.jetbrains.hexana.protobuf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer

/**
 * A single field decoded from an opaque protobuf message with no schema.
 *
 * The decoder infers structure purely from the wire format, so [fieldNumber],
 * [wireType], and [typeGuess] are best-effort guesses rather than schema facts.
 *
 * @property path dotted positional path (per level, in document order), e.g. `"1"`, `"1.3"`, `"1.3.2"`.
 * @property fieldNumber raw field number from the tag; `-1` marks a sentinel error row.
 * @property wireType `"varint"`, `"i64"`, `"i32"`, `"len:message"`, `"len:string"`, `"len:bytes"`, or `"error"`.
 * @property typeGuess `"varint"`, `"i64"`, `"i32"`, `"message"`, `"string"`, `"bytes"`, or `"error"`.
 * @property value formatted, human-readable display string for this field.
 * @property offset absolute byte offset of this field's TAG in the original buffer.
 * @property length bytes from the tag start through the end of the payload.
 * @property depth nesting depth; `0` is the top level.
 */
data class RawProtoField(
    val path: String,
    val fieldNumber: Int,
    val wireType: String,
    val typeGuess: String,
    val value: String,
    val offset: Int,
    val length: Int,
    val depth: Int,
)

/**
 * Byte range of one field in a wire-encoded message: the tag, the length prefix when
 * there is one, and the payload. Produced by [RawWireDecoder.topLevelFieldRanges].
 */
data class ProtoFieldRange(
    val fieldNumber: Int,
    /** Position of the field's tag in the inspected buffer. */
    val offset: Int,
    val size: Int,
    /** Wire type as [RawProtoField.wireType] spells it, minus the LEN sub-classification. */
    val wireType: String,
)

/**
 * Best-effort, schema-less decoder for opaque protobuf (`.pb`) messages.
 *
 * Unlike [FileDescriptorSetParser], this decoder never throws on malformed input:
 * it degrades gracefully, emitting an `"error"` sentinel row and stopping the
 * current level when it cannot make progress.
 */
object RawWireDecoder {

    private const val MAX_DEPTH = MAX_PROTO_DEPTH
    private const val MAX_FIELD_NUMBER = 536870911 // 2^29 - 1, protobuf's max field number
    private const val STRING_PREVIEW_LIMIT = 120
    private const val BYTES_PREVIEW_LIMIT = 16

    /**
     * Decodes [buf] from its current position to its limit, returning a flat,
     * document-order list of fields. Nested message fields are interleaved
     * directly after their parent row with greater [RawProtoField.depth].
     *
     * Never throws: arbitrary/hostile input yields a partial list, possibly
     * terminated by an `"error"` sentinel row.
     */
    fun decode(buf: CommonByteBuffer): List<RawProtoField> =
        decodeLevel(buf, parentPath = "", depth = 0, baseOffset = 0)

    /**
     * Byte ranges of the outermost fields of [buf], from its current position to its
     * limit, without recursing into submessages or materializing any payload.
     *
     * This is the cheap alternative to [decode] for callers that only need the layout
     * of the top level — mapping file regions to fields in a hex view, say. [decode]
     * would classify and format every nested field of a multi-megabyte message to
     * answer the same question.
     *
     * Never throws: like [decode], it stops at the first field it cannot read and
     * returns the ranges found so far.
     */
    fun topLevelFieldRanges(buf: CommonByteBuffer): List<ProtoFieldRange> {
        val ranges = mutableListOf<ProtoFieldRange>()
        val baseOffset = buf.position()
        while (buf.hasRemaining()) {
            val tagPos = buf.position()
            val tag = tryReadULEB128(buf) ?: break
            val fn = fieldNumber(tag)
            if (fn < 1 || fn > MAX_FIELD_NUMBER) break
            val skipped = when (wireType(tag)) {
                WIRETYPE_VARINT -> tryReadULEB128(buf) != null
                WIRETYPE_I64 -> advance(buf, 8)
                WIRETYPE_I32 -> advance(buf, 4)
                WIRETYPE_LEN -> {
                    val len = tryReadULEB128(buf)
                    len != null && len >= 0 && len <= buf.remaining() && advance(buf, len.toInt())
                }
                else -> false
            }
            if (!skipped) break
            ranges.add(
                ProtoFieldRange(
                    fieldNumber = fn,
                    offset = tagPos,
                    size = buf.position() - tagPos,
                    wireType = wireTypeLabel(wireType(tag)),
                ),
            )
        }
        // Leave the cursor where the caller had it; this is a read-only inspection and
        // `buf` may be a duplicate the caller keeps using.
        buf.position(baseOffset)
        return ranges
    }

    /**
     * Names a wire type the way [decode] does. LEN stays `"len"` here: telling a
     * string from a submessage means reading the payload, which is exactly the work
     * [topLevelFieldRanges] exists to avoid.
     */
    private fun wireTypeLabel(wireType: Int): String = when (wireType) {
        WIRETYPE_VARINT -> "varint"
        WIRETYPE_I64 -> "i64"
        WIRETYPE_I32 -> "i32"
        WIRETYPE_LEN -> "len"
        else -> "unknown"
    }

    /**
     * Decodes one nesting level. [baseOffset] is the absolute offset (in the
     * original top-level buffer) of position 0 of [buf]; it is threaded through
     * recursion because [CommonByteBuffer.slice] does NOT preserve absolute
     * offsets (a slice's `position()` is slice-relative).
     */
    private fun decodeLevel(
        buf: CommonByteBuffer,
        parentPath: String,
        depth: Int,
        baseOffset: Int,
    ): List<RawProtoField> {
        val rows = mutableListOf<RawProtoField>()
        var positional = 0

        while (buf.hasRemaining()) {
            val tagPos = buf.position()
            val absOffset = baseOffset + tagPos
            positional++
            val path = if (parentPath.isEmpty()) "$positional" else "$parentPath.$positional"

            // Fault tolerance: any failure in a single field iteration is caught,
            // recorded as a sentinel row, and terminates this level. The buffer
            // position is undefined after a failed skip/read, so we cannot safely
            // continue scanning at this level; already-decoded rows above survive.
            try {
                val tag = tryReadULEB128(buf) ?: error("truncated tag")
                val fn = fieldNumber(tag)
                val wt = wireType(tag)
                when (wt) {
                    WIRETYPE_VARINT -> rows += decodeVarint(buf, path, fn, absOffset, depth, tagPos)
                    WIRETYPE_I64 -> rows += decodeI64(buf, path, fn, absOffset, depth, tagPos)
                    WIRETYPE_I32 -> rows += decodeI32(buf, path, fn, absOffset, depth, tagPos)
                    WIRETYPE_LEN -> decodeLen(buf, path, fn, absOffset, depth, tagPos, baseOffset, rows)
                    else -> error("unsupported wire type $wt")
                }
            } catch (e: Exception) {
                rows += RawProtoField(
                    path = path,
                    fieldNumber = -1,
                    wireType = "error",
                    typeGuess = "error",
                    value = "malformed at offset $absOffset: ${e.message}",
                    offset = absOffset,
                    length = 0,
                    depth = depth,
                )
                break
            }
        }
        return rows
    }

    // -------------------------------------------------------------------------
    // Per-wire-type decoders
    // -------------------------------------------------------------------------

    private fun decodeVarint(
        buf: CommonByteBuffer,
        path: String,
        fn: Int,
        absOffset: Int,
        depth: Int,
        tagPos: Int,
    ): RawProtoField {
        val v = tryReadULEB128(buf) ?: error("truncated varint")
        return RawProtoField(
            path = path,
            fieldNumber = fn,
            wireType = "varint",
            typeGuess = "varint",
            value = v.toULong().toString(),
            offset = absOffset,
            length = buf.position() - tagPos,
            depth = depth,
        )
    }

    private fun decodeI64(
        buf: CommonByteBuffer,
        path: String,
        fn: Int,
        absOffset: Int,
        depth: Int,
        tagPos: Int,
    ): RawProtoField {
        require(buf.remaining() >= 8) { "truncated i64" }
        val bits = buf.getLongLE()
        val d = Double.fromBits(bits)
        val hex = "0x" + bits.toULong().toString(16).uppercase().padStart(16, '0')
        val fpart = when {
            d.isNaN() -> "(NaN)"
            d.isInfinite() -> "(Inf)"
            else -> "($d f64)"
        }
        return RawProtoField(
            path = path,
            fieldNumber = fn,
            wireType = "i64",
            typeGuess = "i64",
            value = "$hex  $fpart",
            offset = absOffset,
            length = buf.position() - tagPos,
            depth = depth,
        )
    }

    private fun decodeI32(
        buf: CommonByteBuffer,
        path: String,
        fn: Int,
        absOffset: Int,
        depth: Int,
        tagPos: Int,
    ): RawProtoField {
        require(buf.remaining() >= 4) { "truncated i32" }
        val bits = buf.getIntLE()
        val f = Float.fromBits(bits)
        val hex = "0x" + bits.toUInt().toString(16).uppercase().padStart(8, '0')
        val fpart = when {
            f.isNaN() -> "(NaN)"
            f.isInfinite() -> "(Inf)"
            else -> "($f f32)"
        }
        return RawProtoField(
            path = path,
            fieldNumber = fn,
            wireType = "i32",
            typeGuess = "i32",
            value = "$hex  $fpart",
            offset = absOffset,
            length = buf.position() - tagPos,
            depth = depth,
        )
    }

    /**
     * Decodes a LEN-delimited field and appends its row (plus any child rows for
     * a message) to [rows]. Disambiguates message/string/bytes per the documented
     * order.
     */
    private fun decodeLen(
        buf: CommonByteBuffer,
        path: String,
        fn: Int,
        absOffset: Int,
        depth: Int,
        tagPos: Int,
        baseOffset: Int,
        rows: MutableList<RawProtoField>,
    ) {
        val len = (tryReadULEB128(buf) ?: error("truncated length")).toInt()
        require(len >= 0 && len <= buf.remaining()) { "length $len exceeds remaining ${buf.remaining()}" }
        val payloadStart = buf.position()
        val payload = ByteArray(len)
        if (len > 0) buf.get(payload, 0, len)
        val totalLength = buf.position() - tagPos

        when {
            len == 0 -> rows += lenRow(path, fn, "len:bytes", "bytes", "(empty)", absOffset, totalLength, depth)
            len == 1 -> rows += classifyStringOrBytes(payload, path, fn, absOffset, totalLength, depth)
            depth < MAX_DEPTH && looksLikeMessage(payload, depth) -> {
                // Recurse: child baseOffset is where the payload starts absolutely.
                val childBase = baseOffset + payloadStart
                val childBuf = payload.toCommonByteBuffer()
                val children = decodeLevel(childBuf, path, depth + 1, childBase)
                val directCount = children.count { it.depth == depth + 1 }
                rows += lenRow(path, fn, "len:message", "message", "{ $directCount fields }", absOffset, totalLength, depth)
                rows += children
            }
            else -> rows += classifyStringOrBytes(payload, path, fn, absOffset, totalLength, depth)
        }
    }

    private fun classifyStringOrBytes(
        payload: ByteArray,
        path: String,
        fn: Int,
        absOffset: Int,
        totalLength: Int,
        depth: Int,
    ): RawProtoField =
        if (isPrintableUtf8(payload)) {
            lenRow(path, fn, "len:string", "string", formatString(payload), absOffset, totalLength, depth)
        } else {
            lenRow(path, fn, "len:bytes", "bytes", formatBytes(payload), absOffset, totalLength, depth)
        }

    private fun lenRow(
        path: String,
        fn: Int,
        wireType: String,
        typeGuess: String,
        value: String,
        absOffset: Int,
        totalLength: Int,
        depth: Int,
    ) = RawProtoField(path, fn, wireType, typeGuess, value, absOffset, totalLength, depth)

    // -------------------------------------------------------------------------
    // Message probe
    // -------------------------------------------------------------------------

    /**
     * Returns true if [payload] parses cleanly as a complete protobuf message:
     * every field has a plausible tag (valid field number and wire type), all
     * skips stay in bounds, at least one field is present, and parsing consumes
     * exactly to the end with no leftover bytes.
     */
    private fun looksLikeMessage(payload: ByteArray, depth: Int): Boolean {
        if (depth >= MAX_DEPTH) return false
        val probe = payload.toCommonByteBuffer()
        var fieldCount = 0
        while (probe.hasRemaining()) {
            val tag = tryReadULEB128(probe) ?: return false
            if (tag == 0L) return false
            val fn = (tag ushr 3)
            if (fn < 1 || fn > MAX_FIELD_NUMBER) return false
            val wt = (tag and 7L).toInt()
            val ok = when (wt) {
                WIRETYPE_VARINT -> tryReadULEB128(probe) != null
                WIRETYPE_I64 -> advance(probe, 8)
                WIRETYPE_I32 -> advance(probe, 4)
                WIRETYPE_LEN -> {
                    val len = tryReadULEB128(probe)
                    len != null && len >= 0 && len <= probe.remaining() && advance(probe, len.toInt())
                }
                else -> false
            }
            if (!ok) return false
            fieldCount++
        }
        return fieldCount >= 1
    }

    /** Advances [buf] by [n] bytes if in bounds, returning success. */
    private fun advance(buf: CommonByteBuffer, n: Int): Boolean {
        if (n < 0 || n > buf.remaining()) return false
        buf.position(buf.position() + n)
        return true
    }

    // -------------------------------------------------------------------------
    // Bounds-checked varint (getULEB128 has NO bounds check and can overrun)
    // -------------------------------------------------------------------------

    /**
     * Reads an unsigned LEB128 varint with bounds checking. Returns null on
     * underflow (buffer exhausted mid-varint) or an overlong encoding (>10 bytes).
     */
    private fun tryReadULEB128(buf: CommonByteBuffer): Long? {
        var result = 0L
        var shift = 0
        var count = 0
        while (true) {
            if (!buf.hasRemaining()) return null
            if (count >= 10) return null
            val byte = buf.get().toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            count++
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    // -------------------------------------------------------------------------
    // UTF-8 validity + formatting
    // -------------------------------------------------------------------------

    /**
     * Returns true if [bytes] is valid UTF-8 whose every code point is printable.
     *
     * Validity is checked by a decode/re-encode round trip: `decodeToString`
     * substitutes U+FFFD for malformed sequences, so re-encoding a decoded copy
     * only matches the original when the input was already valid UTF-8. This
     * avoids depending on the `decodeToString(throwOnInvalidSequence=…)` overload.
     */
    private fun isPrintableUtf8(bytes: ByteArray): Boolean {
        val decoded = bytes.decodeToString()
        if (!decoded.encodeToByteArray().contentEquals(bytes)) return false
        for (cp in decoded.codePoints()) {
            val printable = cp >= 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0D
            if (!printable || cp == 0x7F) return false
        }
        return true
    }

    /** Iterates Unicode code points of a [String] in a multiplatform-safe way. */
    private fun String.codePoints(): Sequence<Int> = sequence {
        var i = 0
        while (i < length) {
            val c = this@codePoints[i]
            if (c.isHighSurrogate() && i + 1 < length && this@codePoints[i + 1].isLowSurrogate()) {
                val low = this@codePoints[i + 1]
                yield(0x10000 + ((c.code - 0xD800) shl 10) + (low.code - 0xDC00))
                i += 2
            } else {
                yield(c.code)
                i++
            }
        }
    }

    private fun formatString(payload: ByteArray): String {
        val text = payload.decodeToString()
        val escaped = escapeControlChars(text)
        return if (text.length <= STRING_PREVIEW_LIMIT) {
            escaped
        } else {
            val head = escapeControlChars(text.substring(0, STRING_PREVIEW_LIMIT))
            "$head … (+${text.length - STRING_PREVIEW_LIMIT} chars)"
        }
    }

    /** Local control-char escaper (avoids a cross-module dep on plugins-shared). */
    private fun escapeControlChars(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                // U+007F is already excluded by isPrintableUtf8; this branch is defensive only.
                else -> if (c.code < 0x20 || c.code == 0x7F) {
                    sb.append("\\x").append(c.code.toString(16).uppercase().padStart(2, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    private fun formatBytes(payload: ByteArray): String {
        val shown = payload.take(BYTES_PREVIEW_LIMIT)
        val hex = shown.joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
        return if (payload.size <= BYTES_PREVIEW_LIMIT) hex else "$hex … (${payload.size} bytes)"
    }
}

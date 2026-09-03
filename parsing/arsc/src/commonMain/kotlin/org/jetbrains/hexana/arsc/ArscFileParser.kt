package org.jetbrains.hexana.arsc

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Parses Android binary resource tables (`resources.arsc`) on top of
 * [CommonByteBuffer].
 *
 * The format is a tree of little-endian `ResChunk_header` chunks. Parsing walks
 * that tree and keeps the chunks needed to list resources; unknown chunk types
 * are skipped by their declared size, which is also how the platform loader
 * stays forward compatible.
 *
 * Malformed input is handled in two ways. The root chunk must be a resource
 * table — without it there is nothing to show, so [parse] throws. Everything
 * below it is best effort: a chunk that declares more bytes than its parent
 * actually holds is parsed against the bytes that are present, and the walk
 * stops there. A truncated file therefore still lists the resources that
 * survived, which is what a partially downloaded or repacked APK looks like.
 *
 * Reference: [ResourceTypes.h](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h)
 */
object ArscFileParser {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202

    /** Every chunk starts with `u16 type, u16 headerSize, u32 size`. */
    private const val CHUNK_HEADER_SIZE = 8

    /** `ResTable_package::name` is a fixed-width UTF-16 buffer of 128 code units. */
    private const val PACKAGE_NAME_CHARS = 128

    /** `ResChunk_header` + id + `name[128]` + the four pool/public offsets. */
    private const val PACKAGE_HEADER_MIN_SIZE = CHUNK_HEADER_SIZE + 4 + PACKAGE_NAME_CHARS * 2 + 16

    /** `ResTable_type::flags`: entry offsets are `u16 idx, u16 offset` pairs instead of a dense array. */
    private const val TYPE_FLAG_SPARSE = 0x01

    /** `ResTable_type::flags`: entry offsets are `u16`s scaled by 4 instead of `u32`s. */
    private const val TYPE_FLAG_OFFSET16 = 0x02

    /** Dense `u32` entry-offset value meaning "this configuration does not define the entry". */
    private const val NO_ENTRY = 0xFFFFFFFFL

    /** [TYPE_FLAG_OFFSET16] equivalent of [NO_ENTRY]. */
    private const val NO_ENTRY_OFFSET16 = 0xFFFF

    /**
     * Detects a resource table by its root chunk: type `RES_TABLE_TYPE`, a
     * header and total size consistent with the buffer, and a string pool as
     * the first child chunk.
     *
     * `resources.arsc` has no magic number, so this checks structure instead.
     * The type word alone (`02 00`) is far too common to be trusted, hence the
     * child-chunk check — every writer emits the global value pool first, even
     * when it is empty.
     */
    fun isArsc(buffer: CommonByteBuffer): Boolean {
        if (buffer.size < CHUNK_HEADER_SIZE + 4) return false
        val saved = buffer.position()
        try {
            buffer.position(0)
            if (buffer.readU2() != RES_TABLE_TYPE) return false
            val headerSize = buffer.readU2()
            val size = buffer.readU4()
            if (headerSize < CHUNK_HEADER_SIZE + 4) return false
            if (size < headerSize || size > buffer.size) return false
            if (headerSize + CHUNK_HEADER_SIZE > buffer.size) return false
            buffer.position(headerSize)
            return buffer.readU2() == RES_STRING_POOL_TYPE
        } finally {
            buffer.position(saved)
        }
    }

    fun parse(buffer: CommonByteBuffer): ArscFile {
        buffer.position(0)
        // `readChunk` clamps the declared size to the bytes present, so a file
        // whose root chunk claims more than it has still parses.
        val table = readChunk(buffer, offset = 0, limit = buffer.size)
            ?: throw IllegalArgumentException("not a resource table: truncated root chunk")
        require(table.type == RES_TABLE_TYPE) {
            "not a resource table: root chunk type 0x${table.type.toString(16)}"
        }
        require(table.headerSize >= CHUNK_HEADER_SIZE + 4) {
            "resource table header is ${table.headerSize} bytes, expected at least ${CHUNK_HEADER_SIZE + 4}"
        }

        buffer.position(table.offset + CHUNK_HEADER_SIZE)
        val packageCount = buffer.readU4().toInt()

        var valueStrings = ArscStringPool.EMPTY
        var seenValuePool = false
        val packages = mutableListOf<ArscPackage>()
        forEachChild(buffer, table) { child ->
            when (child.type) {
                // The first string pool under the table is the global value pool.
                RES_STRING_POOL_TYPE -> if (!seenValuePool) {
                    valueStrings = parseStringPool(buffer, child)
                    seenValuePool = true
                }
                RES_TABLE_PACKAGE_TYPE -> parsePackage(buffer, child)?.let(packages::add)
            }
        }

        return ArscFile(packageCount = packageCount, valueStrings = valueStrings, packages = packages)
    }

    private fun parsePackage(buffer: CommonByteBuffer, chunk: ArscChunk): ArscPackage? {
        // Newer platform versions append fields to the header, but never fewer
        // than the ones read below.
        if (chunk.headerSize < PACKAGE_HEADER_MIN_SIZE) return null
        buffer.position(chunk.offset + CHUNK_HEADER_SIZE)
        val id = buffer.readU4().toInt()
        val name = readFixedUtf16String(buffer, PACKAGE_NAME_CHARS)
        val typeStringsOff = buffer.readU4().toInt()
        buffer.readU4() // lastPublicType — unused, the table lists every entry
        val keyStringsOff = buffer.readU4().toInt()

        // The type and key pools are located by offset from the package chunk
        // start; they also appear in the child chunk walk, which skips them.
        val typeStrings = parseStringPoolAt(buffer, chunk, typeStringsOff)
        val keyStrings = parseStringPoolAt(buffer, chunk, keyStringsOff)

        val types = mutableListOf<ArscTypeChunk>()
        val typeSpecs = mutableListOf<ArscTypeSpec>()
        forEachChild(buffer, chunk) { child ->
            when (child.type) {
                RES_TABLE_TYPE_SPEC_TYPE -> parseTypeSpec(buffer, child, typeStrings)?.let(typeSpecs::add)
                RES_TABLE_TYPE_TYPE -> parseType(buffer, child, typeStrings, keyStrings)?.let(types::add)
            }
        }

        return ArscPackage(
            id = id,
            name = name,
            typeStrings = typeStrings,
            keyStrings = keyStrings,
            types = types,
            typeSpecs = typeSpecs,
        )
    }

    private fun parseTypeSpec(
        buffer: CommonByteBuffer,
        chunk: ArscChunk,
        typeStrings: ArscStringPool,
    ): ArscTypeSpec? {
        if (chunk.headerSize < CHUNK_HEADER_SIZE + 8) return null
        buffer.position(chunk.offset + CHUNK_HEADER_SIZE)
        val typeId = buffer.readU1()
        buffer.readU1() // res0
        buffer.readU2() // res1
        val entryCount = buffer.readU4().toInt()
        return ArscTypeSpec(
            typeId = typeId,
            typeName = typeName(typeStrings, typeId),
            entryCount = entryCount,
        )
    }

    private fun parseType(
        buffer: CommonByteBuffer,
        chunk: ArscChunk,
        typeStrings: ArscStringPool,
        keyStrings: ArscStringPool,
    ): ArscTypeChunk? {
        // header = ResChunk_header + id/res0/res1 + entryCount + entriesStart + ResTable_config
        if (chunk.headerSize < CHUNK_HEADER_SIZE + 12 + 4) return null
        buffer.position(chunk.offset + CHUNK_HEADER_SIZE)
        val typeId = buffer.readU1()
        val flags = buffer.readU1()
        buffer.readU2() // res1
        val declaredEntryCount = buffer.readU4().toInt()
        val entriesStart = buffer.readU4().toInt()
        val configuration = parseConfiguration(buffer, limit = chunk.offset + chunk.headerSize)

        val sparse = flags and TYPE_FLAG_SPARSE != 0
        val offset16 = flags and TYPE_FLAG_OFFSET16 != 0
        val bytesPerOffset = when {
            sparse -> 4 // u16 idx + u16 offset
            offset16 -> 2
            else -> 4
        }
        val indexOffset = chunk.offset + chunk.headerSize
        val indexBytes = chunk.end - indexOffset
        if (declaredEntryCount < 0 || indexBytes < 0) return null

        // A count that does not fit the chunk can only come from a damaged file;
        // clamp instead of asking the allocator for it.
        val entryCount = minOf(declaredEntryCount.toLong(), (indexBytes / bytesPerOffset).toLong()).toInt()
        val entriesBase = chunk.offset + entriesStart

        buffer.position(indexOffset)
        val entries = mutableListOf<ArscEntry>()
        for (index in 0 until entryCount) {
            val entryId: Int
            val entryOffset: Long
            when {
                sparse -> {
                    entryId = buffer.readU2()
                    entryOffset = buffer.readU2().toLong() * 4
                }
                offset16 -> {
                    entryId = index
                    val raw = buffer.readU2()
                    entryOffset = if (raw == NO_ENTRY_OFFSET16) NO_ENTRY else raw.toLong() * 4
                }
                else -> {
                    entryId = index
                    entryOffset = buffer.readU4()
                }
            }
            if (entryOffset == NO_ENTRY) continue

            // Reading an entry moves the cursor away from the index array, so the
            // index position is restored for the next iteration.
            val indexPosition = buffer.position()
            val entryAt = entriesBase + entryOffset
            if (entryAt >= 0 && entryAt + 8 <= chunk.end && entryAt <= buffer.size - 8) {
                parseEntry(buffer, entryAt.toInt(), entryId, chunk.end, keyStrings)?.let(entries::add)
            }
            buffer.position(indexPosition)
        }

        return ArscTypeChunk(
            typeId = typeId,
            typeName = typeName(typeStrings, typeId),
            configuration = configuration,
            entryCount = declaredEntryCount,
            entries = entries,
        )
    }

    private fun parseEntry(
        buffer: CommonByteBuffer,
        offset: Int,
        entryId: Int,
        limit: Int,
        keyStrings: ArscStringPool,
    ): ArscEntry? {
        buffer.position(offset)
        val sizeOrKey = buffer.readU2()
        val flags = buffer.readU2()

        // Compact entries (Android 14+) inline the key index in place of the
        // size field and pack the value type into the high byte of the flags,
        // which makes the whole entry 8 bytes with no separate Res_value.
        if (flags and ArscEntry.FLAG_COMPACT != 0) {
            if (buffer.remaining() < 4) return null
            val data = buffer.readU4().toInt()
            return ArscEntry(
                entryId = entryId,
                keyIndex = sizeOrKey,
                key = keyStrings[sizeOrKey] ?: "<key@$sizeOrKey>",
                flags = flags,
                value = ArscValue(dataType = flags ushr 8, data = data),
            )
        }

        if (buffer.remaining() < 4) return null
        val keyIndex = buffer.readU4().toInt()
        val key = keyStrings[keyIndex] ?: "<key@$keyIndex>"

        if (flags and ArscEntry.FLAG_COMPLEX == 0) {
            // A Res_value is 8 bytes; a file truncated mid-entry yields a keyed
            // entry with no value rather than a read past the end.
            return ArscEntry(
                entryId = entryId,
                keyIndex = keyIndex,
                key = key,
                flags = flags,
                value = if (buffer.remaining() >= 8) parseValue(buffer) else null,
            )
        }

        if (buffer.remaining() < 8) return null
        val parentRef = buffer.readU4().toInt()
        val declaredCount = buffer.readU4()
        // ResTable_map is a 4-byte name plus an 8-byte Res_value.
        val available = (limit - buffer.position()).coerceAtLeast(0) / 12
        val count = minOf(declaredCount, available.toLong()).toInt()
        val mapValues = List(count) {
            ArscMapValue(nameRef = buffer.readU4().toInt(), value = parseValue(buffer))
        }
        return ArscEntry(
            entryId = entryId,
            keyIndex = keyIndex,
            key = key,
            flags = flags,
            value = null,
            parentRef = parentRef,
            mapValues = mapValues,
        )
    }

    /** Reads a `Res_value` at the current position: `u16 size, u8 res0, u8 dataType, u32 data`. */
    private fun parseValue(buffer: CommonByteBuffer): ArscValue {
        buffer.readU2() // size — always 8 in practice
        buffer.readU1() // res0
        val dataType = buffer.readU1()
        return ArscValue(dataType = dataType, data = buffer.readU4().toInt())
    }

    /**
     * Reads the leading, stable part of `ResTable_config` at the current
     * position and skips the rest by its self-declared size, bounded by [limit].
     *
     * The struct has gained a new trailing block in most Android releases, so
     * every field is read by its fixed offset and only when the declared size
     * actually covers it.
     */
    private fun parseConfiguration(buffer: CommonByteBuffer, limit: Int): ArscConfiguration {
        val start = buffer.position()
        val declaredSize = buffer.readU4()
        // The struct cannot extend past the header that contains it.
        val size = minOf(declaredSize, (limit - start).coerceAtLeast(0).toLong()).toInt()
        if (size < 4 || start + size > buffer.size) return ArscConfiguration.DEFAULT

        fun seek(fieldOffset: Int, fieldSize: Int): Boolean {
            if (fieldOffset + fieldSize > size) return false
            buffer.position(start + fieldOffset)
            return true
        }

        val mcc = if (seek(4, 2)) buffer.readU2() else 0
        val mnc = if (seek(6, 2)) buffer.readU2() else 0
        val language = if (seek(8, 2)) readLocaleCode(buffer, base = 'a') else ""
        val country = if (seek(10, 2)) readLocaleCode(buffer, base = '0') else ""
        val orientation = if (seek(12, 1)) buffer.readU1() else 0
        val density = if (seek(14, 2)) buffer.readU2() else 0
        val sdkVersion = if (seek(24, 2)) buffer.readU2() else 0

        buffer.position(start + size)
        return ArscConfiguration(
            size = size,
            mcc = mcc,
            mnc = mnc,
            language = language,
            country = country,
            orientation = orientation,
            density = density,
            sdkVersion = sdkVersion,
        )
    }

    /**
     * Reads a two-byte locale code. `0x00 0x00` means "any" and a plain
     * two-letter code is stored as ASCII.
     *
     * A set high bit marks the packed three-letter form, where the letters are
     * 5-bit offsets from [base] spread across both bytes — see
     * `packLanguageOrRegion` in `ResourceTypes.cpp`. [base] is `'a'` for
     * languages and `'0'` for regions.
     */
    private fun readLocaleCode(buffer: CommonByteBuffer, base: Char): String {
        val first = buffer.readU1()
        val second = buffer.readU1()
        if (first == 0 && second == 0) return ""
        if (first and 0x80 == 0) return charArrayOf(first.toChar(), second.toChar()).concatToString()

        val letters = charArrayOf(
            base + (second and 0x1F),
            base + (((second and 0xE0) shr 5) or ((first and 0x03) shl 3)),
            base + ((first and 0x7C) shr 2),
        )
        return letters.concatToString()
    }

    /** Type names are indexed by `typeId - 1`, and `typeId` is 1-based. */
    private fun typeName(typeStrings: ArscStringPool, typeId: Int): String =
        typeStrings[typeId - 1] ?: "<type@$typeId>"

    private fun parseStringPoolAt(
        buffer: CommonByteBuffer,
        packageChunk: ArscChunk,
        relativeOffset: Int,
    ): ArscStringPool {
        if (relativeOffset <= 0) return ArscStringPool.EMPTY
        val chunk = readChunk(buffer, packageChunk.offset + relativeOffset, packageChunk.end)
            ?: return ArscStringPool.EMPTY
        if (chunk.type != RES_STRING_POOL_TYPE) return ArscStringPool.EMPTY
        return parseStringPool(buffer, chunk)
    }

    /**
     * Parses a `ResStringPool` chunk: a header, an array of string offsets, an
     * array of style offsets, then the string data.
     */
    private fun parseStringPool(buffer: CommonByteBuffer, chunk: ArscChunk): ArscStringPool {
        if (chunk.headerSize < CHUNK_HEADER_SIZE + 20) return ArscStringPool.EMPTY
        buffer.position(chunk.offset + CHUNK_HEADER_SIZE)
        val declaredStringCount = buffer.readU4()
        buffer.readU4() // styleCount — span markup, not needed for listing
        val flags = buffer.readU4().toInt()
        val stringsStart = buffer.readU4().toInt()

        val offsetsAt = chunk.offset + chunk.headerSize
        val offsetsAvailable = ((chunk.end - offsetsAt).coerceAtLeast(0) / 4).toLong()
        val stringCount = minOf(declaredStringCount, offsetsAvailable).toInt()
        if (stringCount <= 0) return ArscStringPool(flags, emptyList())

        buffer.position(offsetsAt)
        val offsets = LongArray(stringCount) { buffer.readU4() }

        val utf8 = flags and ArscStringPool.FLAG_UTF8 != 0
        val dataStart = chunk.offset + stringsStart
        val strings = offsets.map { relative ->
            val at = dataStart + relative
            if (relative < 0 || at >= chunk.end || at >= buffer.size) {
                ""
            } else {
                buffer.position(at.toInt())
                if (utf8) readUtf8String(buffer, chunk.end) else readUtf16String(buffer, chunk.end)
            }
        }
        return ArscStringPool(flags = flags, strings = strings)
    }

    /**
     * Iterates the chunks contained in [parent], skipping each one by its
     * declared size. The walk stops at the first header that cannot be read,
     * which is what keeps a truncated file from failing the whole parse.
     */
    private inline fun forEachChild(
        buffer: CommonByteBuffer,
        parent: ArscChunk,
        action: (ArscChunk) -> Unit,
    ) {
        val limit = minOf(parent.end, buffer.size)
        var offset = parent.offset + parent.headerSize
        while (offset + CHUNK_HEADER_SIZE <= limit) {
            val child = readChunk(buffer, offset, limit) ?: break
            action(child)
            offset = child.end
        }
    }

    /**
     * Reads and validates a chunk header at [offset].
     *
     * A declared size that overruns [limit] (or the buffer) is clamped to the
     * bytes actually available rather than rejected, so a truncated tail still
     * yields whatever it holds. The result is `null` only when even the header
     * cannot be trusted; since the clamped size is never below `headerSize`,
     * [forEachChild] is always guaranteed to make progress.
     */
    private fun readChunk(buffer: CommonByteBuffer, offset: Int, limit: Int): ArscChunk? {
        val end = minOf(limit, buffer.size)
        if (offset < 0 || offset + CHUNK_HEADER_SIZE > end) return null
        buffer.position(offset)
        val type = buffer.readU2()
        val headerSize = buffer.readU2()
        val size = buffer.readU4()
        val available = end - offset
        if (headerSize < CHUNK_HEADER_SIZE || headerSize > available) return null
        if (size < headerSize) return null
        return ArscChunk(
            type = type,
            headerSize = headerSize,
            size = minOf(size, available.toLong()).toInt(),
            offset = offset,
        )
    }
}

/** A validated `ResChunk_header` plus its absolute position in the file. */
private class ArscChunk(
    val type: Int,
    val headerSize: Int,
    val size: Int,
    val offset: Int,
) {
    /** Absolute offset one past this chunk's last byte. */
    val end: Int get() = offset + size
}

// -----------------------------------------------------------------------------
// Little-endian readers over CommonByteBuffer
// -----------------------------------------------------------------------------

private fun CommonByteBuffer.readU1(): Int = get().toInt() and 0xFF

private fun CommonByteBuffer.readU2(): Int = getShortLE().toInt() and 0xFFFF

private fun CommonByteBuffer.readU4(): Long = getIntLE().toLong() and 0xFFFFFFFFL

/**
 * Reads a fixed-width, null-padded UTF-16 string of [chars] code units and
 * leaves the cursor after the whole field, as `ResTable_package::name` requires.
 */
private fun readFixedUtf16String(buffer: CommonByteBuffer, chars: Int): String {
    val start = buffer.position()
    val decoded = CharArray(chars)
    var length = 0
    while (length < chars) {
        val unit = buffer.readU2()
        if (unit == 0) break
        decoded[length++] = unit.toChar()
    }
    buffer.position(start + chars * 2)
    return decoded.concatToString(0, length)
}

/**
 * Reads a UTF-16 pool string: a length in code units — extended to 31 bits when
 * the high bit is set — followed by the units and a `0x0000` terminator.
 */
private fun readUtf16String(buffer: CommonByteBuffer, limit: Int): String {
    var length = buffer.readU2()
    if (length and 0x8000 != 0) {
        length = ((length and 0x7FFF) shl 16) or buffer.readU2()
    }
    val available = (limit - buffer.position()).coerceAtLeast(0) / 2
    val units = minOf(length, available)
    val decoded = CharArray(units)
    for (index in 0 until units) {
        decoded[index] = buffer.readU2().toChar()
    }
    return decoded.concatToString()
}

/**
 * Reads a UTF-8 pool string: the UTF-16 length, then the byte length — each
 * extended to two bytes when the high bit is set — then the bytes.
 */
private fun readUtf8String(buffer: CommonByteBuffer, limit: Int): String {
    fun readLength(): Int {
        val first = buffer.readU1()
        return if (first and 0x80 != 0) ((first and 0x7F) shl 8) or buffer.readU1() else first
    }

    readLength() // UTF-16 length, needed only for span math
    val declaredBytes = readLength()
    val available = (limit - buffer.position()).coerceAtLeast(0)
    val bytes = ByteArray(minOf(declaredBytes, available))
    buffer.get(bytes, 0, bytes.size)
    return bytes.decodeToString()
}

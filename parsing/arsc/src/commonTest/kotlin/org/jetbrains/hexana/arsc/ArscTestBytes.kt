package org.jetbrains.hexana.arsc

/**
 * Builds synthetic `resources.arsc` bytes for the parser tests.
 *
 * Chunk sizes, header sizes and the string-pool data offset are back-patched
 * after the fact, exactly as `aapt2` writes them, so the fixtures exercise the
 * real offset arithmetic instead of hard-coded numbers.
 */
internal object ArscTestBytes {

    const val RES_STRING_POOL_TYPE = 0x0001
    const val RES_TABLE_TYPE = 0x0002
    const val RES_TABLE_PACKAGE_TYPE = 0x0200
    const val RES_TABLE_TYPE_TYPE = 0x0201
    const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202

    const val PACKAGE_ID = 0x7f
    const val PACKAGE_NAME = "com.example.app"

    val VALUE_STRINGS = listOf("Hello world", "Hi there", "Bonjour")
    val TYPE_NAMES = listOf("attr", "string", "dimen", "bool", "color", "style")
    val KEY_NAMES = listOf("app_name", "greeting", "margin", "enabled", "accent", "AppTheme")

    const val TYPE_STRING_ID = 2
    const val TYPE_DIMEN_ID = 3
    const val TYPE_BOOL_ID = 4
    const val TYPE_COLOR_ID = 5
    const val TYPE_STYLE_ID = 6

    /** `16dp`: mantissa 16, radix 0, `COMPLEX_UNIT_DIP`. */
    const val DIMENSION_16DP = (16 shl 8) or 0x1

    const val COLOR_ACCENT = 0xFF3DDC84.toInt()

    /** `50%`: mantissa 0.5 at radix `0p23`, `COMPLEX_UNIT_FRACTION`. */
    const val FRACTION_50_PERCENT = (0x400000 shl 8) or (3 shl 4)

    /** A framework style ID, i.e. one this table cannot resolve. */
    const val FRAMEWORK_PARENT = 0x01030005

    /**
     * A table with one package, a UTF-16 value pool, two `string`
     * configurations (default and `fr`), scalar entries of several value types,
     * and one complex `style` entry.
     */
    fun sampleTable(utf8Strings: Boolean = false, typeIdOffset: Int = 0): ByteArray =
        chunk(RES_TABLE_TYPE, header = {
            u4(1) // packageCount
        }) {
            raw(stringPool(VALUE_STRINGS, utf8 = utf8Strings))
            raw(samplePackage(utf8Strings, typeIdOffset))
        }

    /**
     * The sample package. A non-zero [typeIdOffset] shifts every type id by it
     * and records the field in the header, as a feature split is built; the type
     * pool is unchanged, so the names only resolve if the reader applies the
     * offset.
     */
    private fun samplePackage(utf8Strings: Boolean, typeIdOffset: Int = 0): ByteArray {
        val typePool = stringPool(TYPE_NAMES, utf8 = utf8Strings)
        val keyPool = stringPool(KEY_NAMES, utf8 = utf8Strings)
        // Offsets to both pools are relative to the start of the package chunk.
        val headerSize = if (typeIdOffset == 0) PACKAGE_HEADER_SIZE else PACKAGE_HEADER_SIZE_WITH_TYPE_ID_OFFSET
        return chunk(RES_TABLE_PACKAGE_TYPE, header = {
            u4(PACKAGE_ID)
            utf16Fixed(PACKAGE_NAME, 128)
            u4(headerSize) // typeStrings
            u4(TYPE_NAMES.size) // lastPublicType
            u4(headerSize + typePool.size) // keyStrings
            u4(KEY_NAMES.size) // lastPublicKey
            // Written only when the header is long enough to hold it, exactly
            // as the platform loader expects to find it.
            if (typeIdOffset != 0) u4(typeIdOffset)
        }) {
            raw(typePool)
            raw(keyPool)
            raw(typeSpec(TYPE_STRING_ID + typeIdOffset, entryCount = 2))
            raw(
                type(TYPE_STRING_ID + typeIdOffset, config = defaultConfig()) {
                    entry(key = 0, dataType = ArscValue.TYPE_STRING, data = 0)
                    entry(key = 1, dataType = ArscValue.TYPE_STRING, data = 1)
                }
            )
            raw(
                type(TYPE_STRING_ID + typeIdOffset, config = config(language = "fr")) {
                    hole()
                    entry(key = 1, dataType = ArscValue.TYPE_STRING, data = 2)
                }
            )
            raw(
                type(TYPE_DIMEN_ID + typeIdOffset, config = defaultConfig()) {
                    entry(key = 2, dataType = ArscValue.TYPE_DIMENSION, data = DIMENSION_16DP)
                }
            )
            raw(
                type(TYPE_BOOL_ID + typeIdOffset, config = defaultConfig()) {
                    entry(key = 3, dataType = ArscValue.TYPE_INT_BOOLEAN, data = -1)
                }
            )
            raw(
                type(TYPE_COLOR_ID + typeIdOffset, config = defaultConfig()) {
                    entry(key = 4, dataType = ArscValue.TYPE_INT_COLOR_ARGB8, data = COLOR_ACCENT)
                }
            )
            raw(
                // Sparse index + compact entries, as written for Android 14+.
                type(TYPE_BOOL_ID + typeIdOffset, config = config(language = "fil"), index = EntryIndex.SPARSE) {
                    hole()
                    hole()
                    compactEntry(key = 3, dataType = ArscValue.TYPE_INT_BOOLEAN, data = 0)
                }
            )
            raw(
                type(TYPE_DIMEN_ID + typeIdOffset, config = config(density = 240), index = EntryIndex.OFFSET16) {
                    hole()
                    entry(key = 2, dataType = ArscValue.TYPE_FRACTION, data = FRACTION_50_PERCENT)
                }
            )
            raw(
                type(TYPE_STYLE_ID + typeIdOffset, config = defaultConfig()) {
                    bagEntry(
                        key = 5,
                        parent = FRAMEWORK_PARENT,
                        items = listOf(
                            resourceId(PACKAGE_ID, 1, 0) to
                                ArscValue(ArscValue.TYPE_REFERENCE, resourceId(PACKAGE_ID, TYPE_COLOR_ID, 0)),
                            resourceId(PACKAGE_ID, 1, 1) to ArscValue(ArscValue.TYPE_INT_DEC, 42),
                        ),
                    )
                }
            )
        }
    }

    /**
     * A table whose `bool` type chunk ends right after an entry header, with no
     * room for that entry's `Res_value`, and which is followed by a `color`
     * type chunk. A reader that bounds entry reads by the file rather than by
     * the owning chunk decodes the next chunk's header bytes as the value.
     */
    fun tableWithEntryCutOffBeforeValue(): ByteArray = chunk(RES_TABLE_TYPE, header = {
        u4(1) // packageCount
    }) {
        raw(stringPool(VALUE_STRINGS))
        val typePool = stringPool(TYPE_NAMES)
        val keyPool = stringPool(KEY_NAMES)
        raw(
            chunk(RES_TABLE_PACKAGE_TYPE, header = {
                u4(PACKAGE_ID)
                utf16Fixed(PACKAGE_NAME, 128)
                u4(PACKAGE_HEADER_SIZE) // typeStrings
                u4(TYPE_NAMES.size) // lastPublicType
                u4(PACKAGE_HEADER_SIZE + typePool.size) // keyStrings
                u4(KEY_NAMES.size) // lastPublicKey
            }) {
                raw(typePool)
                raw(keyPool)
                raw(
                    type(TYPE_BOOL_ID, config = defaultConfig()) {
                        entryWithoutValue(key = 3)
                    }
                )
                raw(
                    type(TYPE_COLOR_ID, config = defaultConfig()) {
                        entry(key = 4, dataType = ArscValue.TYPE_INT_COLOR_ARGB8, data = COLOR_ACCENT)
                    }
                )
            }
        )
    }

    /** `ResStringPool` with a UTF-16 or UTF-8 data block. */
    fun stringPool(strings: List<String>, utf8: Boolean = false): ByteArray {
        val data = Writer()
        val offsets = strings.map { value ->
            val offset = data.size
            if (utf8) data.utf8Pooled(value) else data.utf16Pooled(value)
            offset
        }
        data.alignTo4()

        val writer = Writer()
        writer.u2(RES_STRING_POOL_TYPE)
        writer.u2(0) // headerSize, patched below
        writer.u4(0) // size, patched below
        writer.u4(strings.size)
        writer.u4(0) // styleCount
        writer.u4(if (utf8) ArscStringPool.FLAG_UTF8 else 0)
        writer.u4(0) // stringsStart, patched below
        writer.u4(0) // stylesStart
        val headerSize = writer.size
        offsets.forEach(writer::u4)
        val stringsStart = writer.size
        writer.raw(data.toByteArray())
        writer.patchU2(2, headerSize)
        writer.patchU4(4, writer.size)
        writer.patchU4(20, stringsStart)
        return writer.toByteArray()
    }

    private fun typeSpec(typeId: Int, entryCount: Int): ByteArray =
        chunk(RES_TABLE_TYPE_SPEC_TYPE, header = {
            u1(typeId)
            u1(0) // res0
            u2(0) // res1
            u4(entryCount)
        }) {
            repeat(entryCount) { u4(0) } // per-entry config flags
        }

    /** How a `ResTable_type` chunk encodes its entry-offset index. */
    enum class EntryIndex(val flags: Int, val bytesPerOffset: Int) {
        /** `u32` offsets, one per declared entry. */
        DENSE(0x00, 4),

        /** `u16` offsets scaled by 4, one per declared entry. */
        OFFSET16(0x02, 2),

        /** `u16 idx, u16 offset` pairs, only for the entries that exist. */
        SPARSE(0x01, 4),
    }

    /**
     * `ResTable_type`. Entries are appended by the [build] block;
     * [EntriesBuilder.hole] leaves an entry undefined.
     */
    private fun type(
        typeId: Int,
        config: ByteArray,
        index: EntryIndex = EntryIndex.DENSE,
        build: EntriesBuilder.() -> Unit,
    ): ByteArray {
        val entries = EntriesBuilder().apply(build)
        val defined = entries.offsets.withIndex().filter { it.value != null }
        // Sparse chunks declare only the entries they carry.
        val declaredCount = if (index == EntryIndex.SPARSE) defined.size else entries.offsets.size
        val headerSize = CHUNK_HEADER_SIZE + 12 + config.size
        val indexBytes = declaredCount * index.bytesPerOffset
        // Real writers keep the entry data 4-byte aligned.
        val padding = (4 - indexBytes % 4) % 4
        val entriesStart = headerSize + indexBytes + padding
        return chunk(RES_TABLE_TYPE_TYPE, header = {
            u1(typeId)
            u1(index.flags)
            u2(0) // res1
            u4(declaredCount)
            u4(entriesStart)
            raw(config)
        }) {
            when (index) {
                EntryIndex.DENSE -> entries.offsets.forEach { offset ->
                    if (offset == null) u4(NO_ENTRY) else u4(offset)
                }
                EntryIndex.OFFSET16 -> entries.offsets.forEach { offset ->
                    if (offset == null) u2(NO_ENTRY_OFFSET16) else u2(offset / 4)
                }
                EntryIndex.SPARSE -> defined.forEach { (entryId, offset) ->
                    u2(entryId)
                    u2(offset!! / 4)
                }
            }
            repeat(padding) { u1(0) }
            raw(entries.data.toByteArray())
        }
    }

    fun defaultConfig(): ByteArray = config()

    /** `ResTable_config`, written at the modern 64-byte size. */
    fun config(
        mcc: Int = 0,
        mnc: Int = 0,
        language: String = "",
        country: String = "",
        orientation: Int = 0,
        density: Int = 0,
        sdkVersion: Int = 0,
    ): ByteArray {
        val writer = Writer()
        writer.u4(CONFIG_SIZE)
        writer.u2(mcc)
        writer.u2(mnc)
        writer.localeCode(language, base = 'a')
        writer.localeCode(country, base = '0')
        writer.u1(orientation)
        writer.u1(0) // touchscreen
        writer.u2(density)
        writer.u4(0) // keyboard, navigation, inputFlags, inputPad0
        writer.u2(0) // screenWidth
        writer.u2(0) // screenHeight
        writer.u2(sdkVersion)
        writer.u2(0) // minorVersion
        while (writer.size < CONFIG_SIZE) writer.u1(0)
        return writer.toByteArray()
    }

    internal class EntriesBuilder {
        /** One slot per declared entry; `null` marks `NO_ENTRY`. */
        val offsets = mutableListOf<Int?>()
        val data = Writer()

        fun hole() {
            offsets += null
        }

        fun entry(key: Int, dataType: Int, data: Int, flags: Int = 0) {
            offsets += this.data.size
            this.data.u2(8) // entry size
            this.data.u2(flags)
            this.data.u4(key)
            this.data.resValue(ArscValue(dataType, data))
        }

        /**
         * An entry header with its `Res_value` missing, i.e. what the tail of a
         * chunk truncated mid-entry holds.
         */
        fun entryWithoutValue(key: Int) {
            offsets += this.data.size
            this.data.u2(8) // entry size
            this.data.u2(0) // flags
            this.data.u4(key)
        }

        /**
         * Android 14+ 8-byte entry: the key index replaces the size field and
         * the value type is packed into the high byte of the flags.
         */
        fun compactEntry(key: Int, dataType: Int, data: Int) {
            offsets += this.data.size
            this.data.u2(key)
            this.data.u2(ArscEntry.FLAG_COMPACT or (dataType shl 8))
            this.data.u4(data)
        }

        fun bagEntry(key: Int, parent: Int, items: List<Pair<Int, ArscValue>>) {
            offsets += data.size
            data.u2(16) // ResTable_map_entry size
            data.u2(ArscEntry.FLAG_COMPLEX)
            data.u4(key)
            data.u4(parent)
            data.u4(items.size)
            items.forEach { (name, value) ->
                data.u4(name)
                data.resValue(value)
            }
        }
    }

    /**
     * Writes a chunk, patching `headerSize` and `size` once the [header] and
     * [body] blocks have run.
     */
    private fun chunk(type: Int, header: Writer.() -> Unit = {}, body: Writer.() -> Unit): ByteArray {
        val writer = Writer()
        writer.u2(type)
        writer.u2(0) // headerSize, patched below
        writer.u4(0) // size, patched below
        writer.header()
        val headerSize = writer.size
        writer.body()
        writer.patchU2(2, headerSize)
        writer.patchU4(4, writer.size)
        return writer.toByteArray()
    }

    internal class Writer {
        private val bytes = mutableListOf<Byte>()

        val size: Int get() = bytes.size

        fun u1(value: Int) {
            bytes += (value and 0xFF).toByte()
        }

        fun u2(value: Int) {
            u1(value)
            u1(value shr 8)
        }

        fun u4(value: Int) {
            u2(value)
            u2(value shr 16)
        }

        fun u4(value: Long) = u4(value.toInt())

        fun raw(value: ByteArray) {
            value.forEach { bytes += it }
        }

        fun alignTo4() {
            while (bytes.size % 4 != 0) u1(0)
        }

        fun resValue(value: ArscValue) {
            u2(8) // Res_value size
            u1(0) // res0
            u1(value.dataType)
            u4(value.data)
        }

        /** Fixed-width, null-padded UTF-16 field as used by `ResTable_package::name`. */
        fun utf16Fixed(value: String, chars: Int) {
            for (index in 0 until chars) u2(value.getOrNull(index)?.code ?: 0)
        }

        /** UTF-16 pool string: length in code units, the units, then a terminator. */
        fun utf16Pooled(value: String) {
            u2(value.length)
            value.forEach { u2(it.code) }
            u2(0)
        }

        /** UTF-8 pool string: UTF-16 length, byte length, the bytes, then a terminator. */
        fun utf8Pooled(value: String) {
            val encoded = value.encodeToByteArray()
            u1(value.length)
            u1(encoded.size)
            raw(encoded)
            u1(0)
        }

        /**
         * Locale code: `0x0000` for "any", ASCII for a two-letter code, and the
         * packed form for a three-letter one — see `packLanguageOrRegion` in
         * `ResourceTypes.cpp`.
         */
        fun localeCode(value: String, base: Char = 'a') {
            when (value.length) {
                0 -> u2(0)
                2 -> {
                    u1(value[0].code)
                    u1(value[1].code)
                }
                3 -> {
                    val first = value[0] - base
                    val second = value[1] - base
                    val third = value[2] - base
                    u1(0x80 or (third shl 2) or (second shr 3))
                    u1((second shl 5) or first)
                }
                else -> error("unsupported locale code: $value")
            }
        }

        fun patchU2(at: Int, value: Int) {
            bytes[at] = (value and 0xFF).toByte()
            bytes[at + 1] = ((value shr 8) and 0xFF).toByte()
        }

        fun patchU4(at: Int, value: Int) {
            patchU2(at, value)
            patchU2(at + 2, value shr 16)
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private const val CHUNK_HEADER_SIZE = 8

    /** `ResChunk_header` + id + name[128] + four pool/public offsets. */
    private const val PACKAGE_HEADER_SIZE = CHUNK_HEADER_SIZE + 4 + 256 + 16

    /** [PACKAGE_HEADER_SIZE] plus `typeIdOffset`. */
    private const val PACKAGE_HEADER_SIZE_WITH_TYPE_ID_OFFSET = PACKAGE_HEADER_SIZE + 4

    private const val CONFIG_SIZE = 64

    private const val NO_ENTRY = 0xFFFFFFFFL

    private const val NO_ENTRY_OFFSET16 = 0xFFFF
}

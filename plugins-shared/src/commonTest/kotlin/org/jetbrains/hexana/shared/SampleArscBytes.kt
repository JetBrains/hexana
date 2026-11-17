package org.jetbrains.hexana.shared

/**
 * Hand-built `resources.arsc` for the resource-table tab tests. Like
 * [buildSampleDex] there is no checked-in Android fixture in this repository,
 * and the tab wiring has to be driven from real bytes so that format detection
 * is covered too.
 *
 * The table describes one application package:
 *
 * ```
 * com.example.app (0x7f)
 *     string/app_name  = "Hello world"   // default config
 *     string/app_name  = "Bonjour"       // fr config
 * ```
 */
internal fun buildSampleArsc(): ByteArray {
    val valuePool = arscStringPool(listOf("Hello world", "Bonjour"))
    val typePool = arscStringPool(listOf("attr", "string"))
    val keyPool = arscStringPool(listOf("app_name"))

    val packageHeaderSize = 8 + 4 + 256 + 16
    val packageBody = ArscWriter().apply {
        raw(typePool)
        raw(keyPool)
        raw(arscStringType(config = arscConfig(), valueStringIndex = 0))
        raw(arscStringType(config = arscConfig(language = "fr"), valueStringIndex = 1))
    }

    val packageChunk = arscChunk(type = 0x0200, header = {
        u4(0x7f) // package id
        repeat(128) { index -> u2("com.example.app".getOrNull(index)?.code ?: 0) }
        u4(packageHeaderSize) // typeStrings offset
        u4(2) // lastPublicType
        u4(packageHeaderSize + typePool.size) // keyStrings offset
        u4(1) // lastPublicKey
    }) {
        raw(packageBody.toByteArray())
    }

    return arscChunk(type = 0x0002, header = { u4(1) /* packageCount */ }) {
        raw(valuePool)
        raw(packageChunk)
    }
}

/** One `ResTable_type` chunk holding a single `string` entry. */
private fun arscStringType(config: ByteArray, valueStringIndex: Int): ByteArray {
    val headerSize = 8 + 12 + config.size
    return arscChunk(type = 0x0201, header = {
        u1(2) // type id: "string"
        u1(0) // flags: dense u32 entry offsets
        u2(0) // res1
        u4(1) // entryCount
        u4(headerSize + 4) // entriesStart, past the single entry offset
        raw(config)
    }) {
        u4(0) // offset of entry 0
        u2(8) // entry size
        u2(0) // flags
        u4(0) // key index: "app_name"
        u2(8) // Res_value size
        u1(0) // res0
        u1(0x03) // TYPE_STRING
        u4(valueStringIndex)
    }
}

/** `ResTable_config` at the modern 64-byte size, with an optional language. */
private fun arscConfig(language: String = ""): ByteArray = ArscWriter().apply {
    u4(64)
    u2(0) // mcc
    u2(0) // mnc
    if (language.isEmpty()) u2(0) else { u1(language[0].code); u1(language[1].code) }
    u2(0) // country
    while (size < 64) u1(0)
}.toByteArray()

/** `ResStringPool` with a UTF-16 data block. */
private fun arscStringPool(strings: List<String>): ByteArray {
    val data = ArscWriter()
    val offsets = strings.map { value ->
        val offset = data.size
        data.u2(value.length)
        value.forEach { data.u2(it.code) }
        data.u2(0)
        offset
    }
    while (data.size % 4 != 0) data.u1(0)

    val writer = ArscWriter()
    writer.u2(0x0001)
    writer.u2(0) // headerSize, patched below
    writer.u4(0) // size, patched below
    writer.u4(strings.size)
    writer.u4(0) // styleCount
    writer.u4(0) // flags: UTF-16
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

/** Writes a chunk, patching `headerSize` and `size` once both blocks have run. */
private fun arscChunk(
    type: Int,
    header: ArscWriter.() -> Unit,
    body: ArscWriter.() -> Unit,
): ByteArray {
    val writer = ArscWriter()
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

internal class ArscWriter {
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

    fun raw(value: ByteArray) {
        value.forEach { bytes += it }
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

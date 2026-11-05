package org.jetbrains.hexana.mlmodel

/** Builds GGUF and safetensors files for tests. */
class GgufWriter(private val version: Int = 3, private val bigEndian: Boolean = false) {
    private val out = ArrayList<Byte>()
    private val wide get() = version >= 2

    val size: Int get() = out.size

    fun bytes(): ByteArray = out.toByteArray()

    fun raw(vararg values: Int) = apply { values.forEach { out += it.toByte() } }

    private fun unsigned(value: Long, width: Int) = apply {
        val bytes = ByteArray(width) { i -> (value ushr (8 * i)).toByte() }
        if (bigEndian) bytes.reverse()
        bytes.forEach { out += it }
    }

    fun u8(value: Int) = unsigned(value.toLong(), 1)
    fun u32(value: Long) = unsigned(value, 4)
    fun u64(value: Long) = unsigned(value, 8)
    fun count(value: Long) = if (wide) u64(value) else u32(value)

    fun string(value: String) = apply {
        val bytes = value.encodeToByteArray()
        count(bytes.size.toLong())
        bytes.forEach { out += it }
    }

    fun header(tensorCount: Long, metadataCount: Long) = apply {
        "GGUF".encodeToByteArray().forEach { out += it }
        u32(version.toLong())
        count(tensorCount)
        count(metadataCount)
    }

    fun kvString(key: String, value: String) = apply { string(key); u32(8); string(value) }
    fun kvU32(key: String, value: Long) = apply { string(key); u32(4); u32(value) }
    fun kvF32(key: String, value: Float) = apply { string(key); u32(6); u32(value.toRawBits().toLong() and 0xFFFFFFFFL) }
    fun kvBool(key: String, value: Boolean) = apply { string(key); u32(7); u8(if (value) 1 else 0) }

    fun kvStringArray(key: String, values: List<String>) = apply {
        string(key); u32(9); u32(8); count(values.size.toLong())
        values.forEach { string(it) }
    }

    fun kvI32Array(key: String, values: List<Int>) = apply {
        string(key); u32(9); u32(5); count(values.size.toLong())
        values.forEach { u32(it.toLong() and 0xFFFFFFFFL) }
    }

    fun tensor(name: String, dims: List<Long>, type: Int, offset: Long) = apply {
        string(name)
        u32(dims.size.toLong())
        dims.forEach { count(it) }
        u32(type.toLong())
        u64(offset)
    }

    fun padTo(alignment: Int) = apply { while (out.size % alignment != 0) out += 0 }

    fun zeros(count: Int) = apply { repeat(count) { out += 0 } }
}

/** A safetensors file whose header is [header] (padded with [padding] spaces) followed by [dataSize] zero bytes. */
fun safetensorsBytes(header: String, dataSize: Int, padding: Int = 0): ByteArray {
    val json = (header + " ".repeat(padding)).encodeToByteArray()
    val n = json.size.toLong()
    return ByteArray(8) { i -> (n ushr (8 * i)).toByte() } + json + ByteArray(dataSize)
}

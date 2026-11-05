package org.jetbrains.hexana.mlmodel

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mlmodel.gguf.GgmlType
import org.jetbrains.hexana.mlmodel.gguf.GgufParser
import org.jetbrains.hexana.mlmodel.gguf.GgufValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GgufParserTest {

    /** Two tensors: a 64×2 Q8_0 matrix (2 blocks × 2 rows × 34 bytes) and a 4-element F32 vector. */
    private fun sampleModel(version: Int = 3, bigEndian: Boolean = false, alignment: Long? = null): ByteArray {
        val w = GgufWriter(version, bigEndian)
        w.header(tensorCount = 2, metadataCount = if (alignment != null) 6 else 5)
        w.kvString("general.architecture", "llama")
        w.kvString("general.name", "Tiny Test")
        w.kvU32("general.file_type", 7)
        w.kvStringArray("tokenizer.ggml.tokens", listOf("<s>", "</s>", "hello"))
        w.kvF32("llama.rope.freq_base", 10000f)
        if (alignment != null) w.kvU32("general.alignment", alignment)
        w.tensor("blk.0.attn_q.weight", listOf(64, 2), type = 8, offset = 0)
        w.tensor("output_norm.weight", listOf(4), type = 0, offset = if (alignment == 64L) 192 else 160)
        w.padTo(alignment?.toInt() ?: 32)
        w.zeros(if (alignment == 64L) 208 else 176)
        return w.bytes()
    }

    @Test
    fun `parses header, metadata and tensor infos`() {
        val bytes = sampleModel()
        assertTrue(GgufParser.isGguf(bytes.toCommonByteBuffer()))
        val file = assertNotNull(GgufParser.parse(bytes.toCommonByteBuffer()))

        assertEquals(3, file.version)
        assertFalse(file.bigEndian)
        assertEquals(emptyList(), file.diagnostics)
        assertEquals("llama", file.architecture)
        assertEquals("Tiny Test", file.name)
        assertEquals("Q8_0", file.fileTypeLabel)
        assertEquals(32, file.alignment)

        val tokens = assertNotNull(file.metadataValue("tokenizer.ggml.tokens"))
        assertEquals(GgufValueType.ARRAY, tokens.type)
        assertEquals(GgufValueType.STRING, tokens.elementType)
        assertEquals(listOf("<s>", "</s>", "hello"), tokens.elements!!.map { it.string })
        assertEquals(10000.0, file.metadataValue("llama.rope.freq_base")!!.real)

        val (q, norm) = file.tensors
        assertEquals(GgmlType.Q8_0, q.type)
        assertEquals(listOf(64L, 2L), q.dims)
        assertEquals(128L, q.elementCount)
        assertEquals(136L, q.dataSize)
        val dataOffset = assertNotNull(file.tensorDataOffset)
        assertEquals(0L, dataOffset % 32)
        assertEquals(ModelByteRange(dataOffset, 136), q.dataRange)
        assertEquals(ModelByteRange(dataOffset + 160, 16), norm.dataRange)
        assertEquals(132L, file.parameterCount)
    }

    @Test
    fun `metadata entry ranges cover key, type and value`() {
        val file = assertNotNull(GgufParser.parse(sampleModel().toCommonByteBuffer()))
        val first = file.metadata.first()
        // 24-byte header, then u64 key length + "general.architecture" (20) + u32 type + u64 length + "llama" (5).
        assertEquals(ModelByteRange(24, 8 + 20 + 4 + 8 + 5), first.range)
        assertEquals(file.metadata.last().range.endExclusive, file.tensors.first().infoRange.offset)
    }

    @Test
    fun `honours general alignment`() {
        val file = assertNotNull(GgufParser.parse(sampleModel(alignment = 64).toCommonByteBuffer()))
        assertEquals(64, file.alignment)
        assertEquals(0L, file.tensorDataOffset!! % 64)
        assertEquals(emptyList(), file.diagnostics)
    }

    @Test
    fun `reads big-endian files`() {
        val file = assertNotNull(GgufParser.parse(sampleModel(bigEndian = true).toCommonByteBuffer()))
        assertTrue(file.bigEndian)
        assertEquals("llama", file.architecture)
        assertEquals(listOf(64L, 2L), file.tensors.first().dims)
        assertEquals(emptyList(), file.diagnostics)
    }

    @Test
    fun `reads version 1 with 32-bit counts`() {
        val file = assertNotNull(GgufParser.parse(sampleModel(version = 1).toCommonByteBuffer()))
        assertEquals(1, file.version)
        assertEquals("Tiny Test", file.name)
        assertEquals(2, file.tensors.size)
        assertEquals(emptyList(), file.diagnostics)
    }

    @Test
    fun `rejects other magic and unknown versions`() {
        assertFalse(GgufParser.isGguf("GGML\u0003\u0000\u0000\u0000".encodeToByteArray().toCommonByteBuffer()))
        assertFalse(GgufParser.isGguf(GgufWriter(version = 9).header(0, 0).bytes().toCommonByteBuffer()))
        assertNull(GgufParser.parse(ByteArray(4).toCommonByteBuffer()))
    }

    @Test
    fun `truncated metadata keeps what was read`() {
        val bytes = sampleModel()
        val cut = bytes.copyOf(70) // inside the second metadata entry
        val file = assertNotNull(GgufParser.parse(cut.toCommonByteBuffer()))
        assertEquals(1, file.metadata.size)
        assertEquals(emptyList(), file.tensors)
        assertNull(file.tensorDataOffset)
        assertTrue(file.diagnostics.any { it.code == "gguf.metadata" })
    }

    @Test
    fun `absurd counts are reported and do not allocate`() {
        val bytes = GgufWriter().header(tensorCount = Long.MAX_VALUE / 2, metadataCount = Long.MAX_VALUE / 2).bytes()
        val file = assertNotNull(GgufParser.parse(bytes.toCommonByteBuffer()))
        assertTrue(file.diagnostics.any { it.code == "gguf.metadata-count" })
        assertEquals(emptyList(), file.metadata)
    }

    @Test
    fun `huge array length is rejected before allocating`() {
        val w = GgufWriter().header(0, 1)
        w.string("k"); w.u32(9); w.u32(8); w.u64(Long.MAX_VALUE / 4)
        val file = assertNotNull(GgufParser.parse(w.bytes().toCommonByteBuffer()))
        assertEquals("gguf.metadata", file.diagnostics.single().code)
    }

    @Test
    fun `tensor data past the end of the file is reported`() {
        val w = GgufWriter().header(1, 0)
        w.tensor("big", listOf(1024, 1024), type = 0, offset = 0)
        w.padTo(32)
        val file = assertNotNull(GgufParser.parse(w.bytes().toCommonByteBuffer()))
        val tensor = file.tensors.single()
        assertEquals(4L * 1024 * 1024, tensor.dataSize)
        assertNull(tensor.dataRange)
        assertEquals("gguf.tensor-bounds", file.diagnostics.single().code)
    }

    @Test
    fun `head-only buffer resolves ranges against the real file size`() {
        val w = GgufWriter().header(1, 0)
        w.tensor("big", listOf(1024, 1024), type = 0, offset = 0)
        w.padTo(32)
        val head = w.bytes()
        val file = assertNotNull(GgufParser.parse(head.toCommonByteBuffer(), fileSize = head.size + 4L * 1024 * 1024))
        assertEquals(ModelByteRange(head.size.toLong(), 4L * 1024 * 1024), file.tensors.single().dataRange)
        assertEquals(emptyList(), file.diagnostics)
    }

    @Test
    fun `unknown tensor type keeps the tensor without a size`() {
        val w = GgufWriter().header(1, 0)
        w.tensor("odd", listOf(8), type = 4, offset = 0)
        w.padTo(32)
        val tensor = assertNotNull(GgufParser.parse(w.bytes().toCommonByteBuffer())).tensors.single()
        assertNull(tensor.type)
        assertEquals("type 4", tensor.typeLabel)
        assertNull(tensor.dataSize)
    }

    @Test
    fun `quantized byte size requires whole blocks`() {
        assertEquals(144L, GgmlType.Q4_K.byteSize(256))
        assertNull(GgmlType.Q4_K.byteSize(100))
        assertEquals(8L, GgmlType.F16.byteSize(4))
    }

    @Test
    fun `display truncates long arrays`() {
        val w = GgufWriter().header(0, 1).kvI32Array("ids", (1..20).toList())
        val value = assertNotNull(GgufParser.parse(w.bytes().toCommonByteBuffer())).metadata.single().value
        assertEquals("int32[20] [1, 2, 3, 4, 5, 6, 7, 8, …]", value.display())
    }
}

package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.asLargeBytes
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MlModelBinaryProvidersTest {

    @Test
    fun `GGUF is resolved by content whatever the name`() {
        val bytes = mlTestGguf()
        for (name in listOf("model.gguf", "ggml-model-q4_0.bin", null)) {
            assertIs<GgufBinaryProvider>(createBinaryProvider(bytes.toCommonByteBuffer(), name), "name $name")
        }
    }

    @Test
    fun `safetensors is resolved by content whatever the name`() {
        val bytes = mlTestSafetensors()
        for (name in listOf("model.safetensors", "weights.bin", null)) {
            assertIs<SafetensorsBinaryProvider>(createBinaryProvider(bytes.toCommonByteBuffer(), name), "name $name")
        }
    }

    @Test
    fun `parser picker round-trips both formats`() {
        val gguf = assertNotNull(createBinaryProvider(BinaryParser.GGUF, mlTestGguf().toCommonByteBuffer()))
        assertEquals(BinaryParser.GGUF, binaryParserOf(gguf))
        val st = assertNotNull(createBinaryProvider(BinaryParser.SAFETENSORS, mlTestSafetensors().toCommonByteBuffer()))
        assertEquals(BinaryParser.SAFETENSORS, binaryParserOf(st))
    }

    @Test
    fun `GGUF structure ranges and size tree`() {
        val provider = GgufBinaryProvider(mlTestGguf().toCommonByteBuffer())
        assertEquals(listOf("Header", "Metadata", "Tensor Infos", "Tensor Data"), provider.structureRanges.map { it.label })
        val tree = assertNotNull(provider.sizeAnalysis)
        assertEquals(provider.bytes.size, tree.size)
        val tensors = tree.children.single { it.name == "Tensors" }
        // blk.0.* tensors group under their layer; a lone tensor stays a leaf.
        assertEquals(setOf("blk.0", "output_norm.weight"), tensors.children.map { it.name }.toSet())
        assertEquals(2, tensors.children.single { it.name == "blk.0" }.children.size)
    }

    @Test
    fun `safetensors structure ranges`() {
        val provider = SafetensorsBinaryProvider(mlTestSafetensors().toCommonByteBuffer())
        assertEquals(listOf("Header Length", "Header (JSON)", "Tensor Data"), provider.structureRanges.map { it.label })
        assertTrue(provider.parsedSafetensors!!.diagnostics.isEmpty())
    }

    @Test
    fun `a GGUF header cut off by the loaded head is a prefix`() {
        val bytes = mlTestGguf()
        val far = 8L shl 30
        val whole = GgufBinaryProvider(bytes.toCommonByteBuffer(), far)
        val headerEnd = assertNotNull(whole.parsedGguf?.tensorDataOffset)
        // Everything past the tensor infos is tensor data: nothing is missing.
        assertEquals(ParseCoverage.WholeFile, whole.parseCoverage(far))
        val cut = GgufBinaryProvider(bytes.copyOf(headerEnd.toInt() - 1).toCommonByteBuffer(), far)
        assertEquals(ParseCoverage.Prefix(headerEnd - 1, "tensor infos"), cut.parseCoverage(far))
    }

    @Test
    fun `a safetensors header past the loaded head is read from the whole file`() {
        val bytes = mlTestSafetensors()
        val head = bytes.copyOf(16).toCommonByteBuffer()
        assertIs<GenericBinaryProvider>(createBinaryProvider(head, "model.safetensors"), "the head alone cannot tell")
        val provider = assertIs<SafetensorsBinaryProvider>(
            createBinaryProvider(head, "model.safetensors", source = bytes.toCommonByteBuffer().asLargeBytes()),
        )
        assertEquals(mlTestSafetensors().let { SafetensorsBinaryProvider(it.toCommonByteBuffer()) }.parsedSafetensors!!.tensors.map { it.name },
            provider.parsedSafetensors!!.tensors.map { it.name })
        assertEquals(ParseCoverage.WholeFile, provider.parseCoverage(bytes.size.toLong()))
    }

    @Test
    fun `tensor groups follow the layer number`() {
        assertEquals("blk.12", tensorGroupOf("blk.12.attn_q.weight"))
        assertEquals("model.layers.3", tensorGroupOf("model.layers.3.mlp.up_proj.weight"))
        assertEquals("token_embd", tensorGroupOf("token_embd.weight"))
    }
}

/** A little-endian GGUF v3 file: architecture metadata and three F32 tensors (two in `blk.0`). */
internal fun mlTestGguf(): ByteArray {
    val out = ArrayList<Byte>()
    fun le(value: Long, width: Int) = repeat(width) { out += (value ushr (8 * it)).toByte() }
    fun str(value: String) { val b = value.encodeToByteArray(); le(b.size.toLong(), 8); b.forEach { out += it } }
    "GGUF".encodeToByteArray().forEach { out += it }
    le(3, 4); le(3, 8); le(1, 8)
    str("general.architecture"); le(8, 4); str("llama")
    fun tensor(name: String, elements: Long, offset: Long) { str(name); le(1, 4); le(elements, 8); le(0, 4); le(offset, 8) }
    tensor("blk.0.attn_q.weight", 8, 0)
    tensor("blk.0.attn_k.weight", 8, 32)
    tensor("output_norm.weight", 4, 64)
    while (out.size % 32 != 0) out += 0
    repeat(80) { out += 0 }
    return out.toByteArray()
}

/** A safetensors file with one F32 tensor of 4 elements. */
internal fun mlTestSafetensors(): ByteArray {
    val json = """{"w":{"dtype":"F32","shape":[4],"data_offsets":[0,16]}}""".encodeToByteArray()
    return ByteArray(8) { (json.size.toLong() ushr (8 * it)).toByte() } + json + ByteArray(16)
}

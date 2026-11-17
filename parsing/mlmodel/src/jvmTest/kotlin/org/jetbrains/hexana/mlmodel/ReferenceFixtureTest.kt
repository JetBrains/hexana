package org.jetbrains.hexana.mlmodel

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mlmodel.gguf.GgmlType
import org.jetbrains.hexana.mlmodel.gguf.GgufParser
import org.jetbrains.hexana.mlmodel.gguf.GgufValueType
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsMetadataEntry
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Files written by the reference implementations (llama.cpp's gguf-py, Hugging Face's
 * safetensors) via `test-data/generate.py`. Expected values are what those libraries'
 * own readers report for the same files.
 */
class ReferenceFixtureTest {
    private fun bytes(name: String) = File("test-data/$name").readBytes()

    @Test
    fun `gguf-py file`() {
        val file = assertNotNull(GgufParser.parse(bytes("tiny.gguf").toCommonByteBuffer()))
        assertEquals(emptyList(), file.diagnostics)
        assertEquals(3, file.version)
        assertEquals("llama", file.architecture)
        assertEquals("Tiny Hexana Fixture", file.name)
        assertEquals("Q8_0", file.fileTypeLabel)
        assertEquals(20, file.metadata.size)

        assertEquals(200L, file.metadataInteger("test.u8"))
        assertEquals(-5L, file.metadataInteger("test.i8"))
        assertEquals(65000L, file.metadataInteger("test.u16"))
        assertEquals(-30000L, file.metadataInteger("test.i16"))
        assertEquals(-2_000_000_000L, file.metadataInteger("test.i32"))
        assertEquals("9223372036854775813", file.metadataValue("test.u64")!!.display())
        assertEquals(-(1L shl 40), file.metadataInteger("test.i64"))
        assertEquals(2.5, file.metadataValue("test.f64")!!.real)
        assertEquals(true, file.metadataValue("test.bool")!!.bool)
        assertEquals(10000.0, file.metadataValue("llama.rope.freq_base")!!.real)
        assertEquals(listOf(1L, 2L, 3L), file.metadataValue("test.ints")!!.elements!!.map { it.integer })
        val tokens = file.metadataValue("tokenizer.ggml.tokens")!!
        assertEquals(GgufValueType.STRING, tokens.elementType)
        assertEquals(listOf("<unk>", "<s>", "</s>", "hello", "world"), tokens.elements!!.map { it.string })
        assertEquals(-1.5, file.metadataValue("tokenizer.ggml.scores")!!.elements!![3].real)

        // Names, types, ggml-order shapes, sizes and absolute data offsets as GGUFReader reports them.
        assertEquals(
            listOf(
                listOf("token_embd.weight", GgmlType.Q8_0, listOf(64L, 5L), 340L, 992L),
                listOf("blk.0.attn_norm.weight", GgmlType.F32, listOf(64L), 256L, 1344L),
                listOf("blk.0.attn_q.weight", GgmlType.F16, listOf(64L, 64L), 8192L, 1600L),
                listOf("output_norm.weight", GgmlType.F32, listOf(64L), 256L, 9792L),
            ),
            file.tensors.map { listOf(it.name, it.type, it.dims, it.dataSize, it.dataRange?.offset) },
        )
        assertEquals(64L * 5 + 64 + 64 * 64 + 64, file.parameterCount)
        assertEquals(file.fileSize, file.tensors.last().dataRange!!.endExclusive)
    }

    @Test
    fun `safetensors library file`() {
        val file = assertNotNull(SafetensorsParser.parse(bytes("tiny.safetensors").toCommonByteBuffer()))
        assertEquals(emptyList(), file.diagnostics)
        assertEquals(
            listOf(SafetensorsMetadataEntry("producer", "hexana-fixture"), SafetensorsMetadataEntry("format", "pt")),
            file.metadata,
        )
        assertEquals(
            listOf(
                listOf("lm_head.bias", "I64", listOf(5L), 0L, 40L),
                listOf("model.layers.0.input_layernorm.weight", "F32", listOf(8L), 40L, 72L),
                listOf("model.layers.0.mlp.up_proj.weight", "F32", listOf(16L, 8L), 72L, 584L),
                listOf("model.embed_tokens.weight", "F16", listOf(5L, 8L), 584L, 664L),
            ),
            file.tensors.map { listOf(it.name, it.dtype, it.shape, it.begin, it.end) },
        )
        assertEquals(file.fileSize, file.dataOffset + 664)
        assertEquals(0L, file.dataOffset % 8)
    }
}

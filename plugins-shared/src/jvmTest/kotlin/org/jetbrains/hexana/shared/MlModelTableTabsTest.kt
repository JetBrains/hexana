package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GgufBinaryProvider
import org.jetbrains.hexana.providers.SafetensorsBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** GGUF and safetensors tabs over the reference-writer fixtures in `parsing/mlmodel/test-data`. */
class MlModelTableTabsTest {
    private fun bytes(name: String) = File("../parsing/mlmodel/test-data/$name").readBytes()

    private fun TableTabDescriptor.cell(row: Int, column: String) = columns.single { it.name == column }.cellProvider(row).representation
    private fun TableTabDescriptor.rowWhere(column: String, value: String) = (0 until rowCount).single { cell(it, column) == value }

    private val gguf = assertIs<GgufBinaryProvider>(createBinaryProvider(bytes("tiny.gguf").toCommonByteBuffer(), "tiny.gguf"))
    private val safetensors =
        assertIs<SafetensorsBinaryProvider>(createBinaryProvider(bytes("tiny.safetensors").toCommonByteBuffer(), "tiny.safetensors"))

    @Test
    fun `GGUF tabs`() {
        val tabs = GgufTableTabsProvider()
        assertEquals("GGUF Model", fileTypeDescription(gguf))
        assertEquals("gguf", binaryFormatId(gguf))
        assertEquals(listOf("Header", "Metadata", "Tensors") to emptyList<String>(), tabs.availableTabNames(gguf, TableTabsContext()))

        val header = tabs.tableTabs(gguf).single { it.name == "Header" }
        assertEquals("GGUF v3", header.cell(header.rowWhere("Field", "Format"), "Value"))
        assertEquals("llama", header.cell(header.rowWhere("Field", "Architecture"), "Value"))
        assertEquals("Q8_0 (7)", header.cell(header.rowWhere("Field", "File Type"), "Value"))
        assertEquals("4.54 K (4,544)", header.cell(header.rowWhere("Field", "Parameters"), "Value"))
        assertEquals("2 tensors, 512 B", header.cell(header.rowWhere("Field", "Type F32"), "Value"))

        val metadata = tabs.tableTabs(gguf).single { it.name == "Metadata" }
        val tokens = metadata.rowWhere("Key", "tokenizer.ggml.tokens")
        assertEquals("string[]", metadata.cell(tokens, "Type"))
        assertEquals("string[5] [\"<unk>\", \"<s>\", \"</s>\", \"hello\", \"world\"]", metadata.cell(tokens, "Value"))
        val children = assertNotNull(metadata.childrenProvider?.invoke(tokens))
        assertEquals(5, children.rowCount)
        assertEquals("hello", children.columns.single { it.name == "Value" }.cellProvider(3).representation)
        assertNull(metadata.childrenProvider?.invoke(metadata.rowWhere("Key", "general.name")))

        val tensors = tabs.tableTabs(gguf).single { it.name == "Tensors" }
        val embd = tensors.rowWhere("Name", "token_embd.weight")
        assertEquals("Q8_0", tensors.cell(embd, "Type"))
        assertEquals("64 × 5", tensors.cell(embd, "Shape"))
        assertEquals("340 B", tensors.cell(embd, "Size"))
        val selected = assertNotNull(tensors.rowSelectionProvider(embd))
        assertEquals(992 to 340, selected.offset to selected.size)
    }

    @Test
    fun `safetensors tabs`() {
        val tabs = SafetensorsTableTabsProvider()
        assertEquals("Safetensors Model", fileTypeDescription(safetensors))
        assertEquals("safetensors", binaryFormatId(safetensors))

        val header = tabs.tableTabs(safetensors).single { it.name == "Header" }
        assertEquals("pt", header.cell(header.rowWhere("Field", "Format"), "Value"))
        assertEquals("hexana-fixture", header.cell(header.rowWhere("Field", "Metadata: producer"), "Value"))
        assertEquals("1 tensor, 80 B", header.cell(header.rowWhere("Field", "Type F16"), "Value"))

        val tensors = tabs.tableTabs(safetensors).single { it.name == "Tensors" }
        assertEquals(4, tensors.rowCount)
        val up = tensors.rowWhere("Name", "model.layers.0.mlp.up_proj.weight")
        assertEquals("16 × 8", tensors.cell(up, "Shape"))
        assertEquals("128", tensors.cell(up, "Elements"))
        val file = safetensors.parsedSafetensors!!
        val selected = assertNotNull(tensors.rowSelectionProvider(up))
        assertEquals((file.dataOffset + 72).toInt() to 512, selected.offset to selected.size)
    }

    @Test
    fun `parameter counts are abbreviated`() {
        assertEquals("999", formatParameterCount(999))
        assertEquals("7.24 B (7,241,732,096)", formatParameterCount(7_241_732_096))
        assertEquals("1.00 M (1,000,000)", formatParameterCount(1_000_000))
    }
}

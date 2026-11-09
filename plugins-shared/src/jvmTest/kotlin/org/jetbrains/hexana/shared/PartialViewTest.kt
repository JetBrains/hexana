package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GgufBinaryProvider
import org.jetbrains.hexana.providers.ParseCoverage
import org.jetbrains.hexana.providers.SafetensorsBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file larger than a host can load is opened from its head, with the real size passed to the
 * factory. Simulated by cutting the reference fixtures short and declaring their full size.
 */
class PartialViewTest {
    private fun bytes(name: String) = File("../parsing/mlmodel/test-data/$name").readBytes()

    private fun TableTabDescriptor.rowWhere(column: String, value: String) =
        (0 until rowCount).single { columns.single { it.name == column }.cellProvider(it).representation == value }

    @Test
    fun `GGUF head lists every tensor and selects only what was loaded`() {
        val full = bytes("tiny.gguf")
        val head = full.copyOf(1500) // past the header and the first tensor, before blk.0.attn_q
        val provider = assertIs<GgufBinaryProvider>(
            createBinaryProvider(head.toCommonByteBuffer(), "tiny.gguf", fileSize = full.size.toLong()),
        )
        assertEquals(full.size.toLong(), provider.fileSize)
        val file = assertNotNull(provider.parsedGguf)
        assertEquals(emptyList(), file.diagnostics)
        assertEquals(4, file.tensors.size)
        assertNull(provider.sizeAnalysis, "an Int size tree cannot describe the whole file")

        val tensors = GgufTableTabsProvider().tableTabs(provider).single { it.name == "Tensors" }
        val first = assertNotNull(tensors.rowSelectionProvider(tensors.rowWhere("Name", "token_embd.weight")))
        assertEquals(992 to 340, first.offset.toInt() to first.size.toInt())
        // Data past the loaded head falls back to the info record, which is in the head.
        val late = assertNotNull(tensors.rowSelectionProvider(tensors.rowWhere("Name", "output_norm.weight")))
        assertTrue(late.offset + late.size <= head.size)
        assertEquals(file.tensors.last().infoRange.offset, late.offset)
    }

    @Test
    fun `safetensors head is still recognized and parsed`() {
        val full = bytes("tiny.safetensors")
        val dataOffset = 8 + full.copyOf(8).foldIndexed(0L) { i, acc, b -> acc or ((b.toLong() and 0xFF) shl (8 * i)) }
        val head = full.copyOf(dataOffset.toInt() + 16)
        val provider = assertIs<SafetensorsBinaryProvider>(
            createBinaryProvider(head.toCommonByteBuffer(), "tiny.safetensors", fileSize = full.size.toLong()),
        )
        val file = assertNotNull(provider.parsedSafetensors)
        assertEquals(emptyList(), file.diagnostics)
        assertEquals(4, file.tensors.size)
        val tensors = SafetensorsTableTabsProvider().tableTabs(provider).single { it.name == "Tensors" }
        val late = assertNotNull(tensors.rowSelectionProvider(tensors.rowWhere("Name", "model.embed_tokens.weight")))
        assertEquals(file.headerRange.offset.toInt() to file.headerRange.size.toInt(), late.offset.toInt() to late.size.toInt())
    }

    @Test
    fun `without the real size a cut file is diagnosed, not misread`() {
        val head = bytes("tiny.gguf").copyOf(1500)
        val provider = assertIs<GgufBinaryProvider>(createBinaryProvider(head.toCommonByteBuffer(), "tiny.gguf"))
        assertTrue(provider.parsedGguf!!.diagnostics.any { it.code == "gguf.tensor-bounds" })
    }

    @Test
    fun `notice text`() {
        assertTrue(isPartialView(512, 1024L))
        assertFalse(isPartialView(512, 512L))
        assertFalse(isPartialView(512, null))
        val gb5 = 5L * 1024 * 1024 * 1024
        val mb512 = 512 * 1024 * 1024
        val prefix = ParseCoverage.Prefix(mb512.toLong(), "packets")
        assertEquals(
            "Showing the first 512.0 MB of this 5.0 GB file. Tabs leave out packets past the first 512.0 MB. " +
                "Editing is turned off.",
            partialViewNotice(mb512, gb5, prefix),
        )
        assertEquals(
            "Showing the first 512.0 MB of this 5.0 GB file. The tabs list every structure. Editing is turned off.",
            partialViewNotice(mb512, gb5, ParseCoverage.WholeFile),
        )
        assertEquals(
            "This 5.0 GB file is shown in full. Tabs leave out packets past the first 512.0 MB. " +
                "Editing is turned off for files this large.",
            largeFileNotice(gb5, prefix),
        )
        assertEquals(
            "This 5.0 GB file is shown and parsed in full. Editing is turned off for files this large.",
            largeFileNotice(gb5, ParseCoverage.WholeFile),
        )
        assertEquals("5.0 GB (first 512.0 MB parsed)", largeFileSizeLabel(gb5, prefix))
        assertEquals("5.0 GB", largeFileSizeLabel(gb5, ParseCoverage.WholeFile))
    }

    @Test
    fun `a model head covers the whole file`() {
        val full = bytes("tiny.gguf")
        val provider = createBinaryProvider(full.copyOf(1500).toCommonByteBuffer(), "tiny.gguf", fileSize = full.size.toLong())
        assertEquals(ParseCoverage.WholeFile, provider.parseCoverage(full.size.toLong()))
    }
}

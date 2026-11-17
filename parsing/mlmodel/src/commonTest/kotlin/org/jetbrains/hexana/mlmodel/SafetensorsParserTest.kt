package org.jetbrains.hexana.mlmodel

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsMetadataEntry
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetensorsParserTest {

    private val header = """{"__metadata__":{"format":"pt"},""" +
        """"lm_head.weight":{"dtype":"F16","shape":[2,3],"data_offsets":[16,28]},""" +
        """"embed.weight":{"dtype":"F32","shape":[4],"data_offsets":[0,16]}}"""

    @Test
    fun `parses tensors and metadata`() {
        val bytes = safetensorsBytes(header, dataSize = 28, padding = 3)
        assertTrue(SafetensorsParser.isSafetensors(bytes.toCommonByteBuffer()))
        val file = assertNotNull(SafetensorsParser.parse(bytes.toCommonByteBuffer()))

        assertEquals(emptyList(), file.diagnostics)
        assertEquals(listOf(SafetensorsMetadataEntry("format", "pt")), file.metadata)
        assertEquals("pt", file.formatTag)
        // Ordered by data offset, not by JSON order.
        assertEquals(listOf("embed.weight", "lm_head.weight"), file.tensors.map { it.name })
        val head = file.tensors[1]
        assertEquals("F16", head.dtype)
        assertEquals(listOf(2L, 3L), head.shape)
        assertEquals(6L, head.elementCount)
        assertEquals(ModelByteRange(file.dataOffset + 16, 12), head.dataRange)
        assertEquals(10L, file.parameterCount)
        assertEquals(28L, file.tensorDataSize)
        assertEquals(8L + header.length + 3, file.dataOffset)
    }

    @Test
    fun `detection rejects other binaries`() {
        assertFalse(SafetensorsParser.isSafetensors(ByteArray(64).toCommonByteBuffer()))
        // Plausible length but no JSON object.
        assertFalse(SafetensorsParser.isSafetensors(safetensorsBytes("[1,2,3]", 0).toCommonByteBuffer()))
        // Opening brace but the header does not end in one.
        assertFalse(SafetensorsParser.isSafetensors(safetensorsBytes("{\"a\":1} x", 0).toCommonByteBuffer()))
        // Length beyond the file.
        val bytes = safetensorsBytes("{}", 0)
        bytes[0] = 0x7F
        assertFalse(SafetensorsParser.isSafetensors(bytes.toCommonByteBuffer()))
        assertTrue(SafetensorsParser.isSafetensors(safetensorsBytes("{}", 0).toCommonByteBuffer()))
    }

    @Test
    fun `size mismatch, gap and bounds are diagnosed`() {
        val bad = """{"a":{"dtype":"F32","shape":[2],"data_offsets":[0,4]},""" +
            """"b":{"dtype":"U8","shape":[4],"data_offsets":[8,12]},""" +
            """"c":{"dtype":"U8","shape":[4],"data_offsets":[12,100]}}"""
        val file = assertNotNull(SafetensorsParser.parse(safetensorsBytes(bad, dataSize = 12).toCommonByteBuffer()))
        val codes = file.diagnostics.map { it.code }.toSet()
        assertTrue("safetensors.tensor-size" in codes)
        assertTrue("safetensors.gap" in codes)
        assertTrue("safetensors.tensor-bounds" in codes)
        assertNull(file.tensors.single { it.name == "c" }.dataRange)
    }

    @Test
    fun `malformed entries are skipped with a diagnostic`() {
        val bad = """{"a":{"dtype":"F32","shape":[1]},"b":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}"""
        val file = assertNotNull(SafetensorsParser.parse(safetensorsBytes(bad, dataSize = 4).toCommonByteBuffer()))
        assertEquals(listOf("b"), file.tensors.map { it.name })
        assertEquals("safetensors.tensor", file.diagnostics.single().code)
    }

    @Test
    fun `invalid JSON yields an empty file with a diagnostic`() {
        val file = assertNotNull(SafetensorsParser.parse(safetensorsBytes("{\"a\":}", 0).toCommonByteBuffer()))
        assertEquals(emptyList(), file.tensors)
        assertEquals("safetensors.json", file.diagnostics.single().code)
    }

    @Test
    fun `head-only buffer resolves ranges against the real file size`() {
        val h = """{"w":{"dtype":"BF16","shape":[1024,1024],"data_offsets":[0,2097152]}}"""
        val head = safetensorsBytes(h, 0)
        val file = assertNotNull(SafetensorsParser.parse(head.toCommonByteBuffer(), fileSize = head.size + 2097152L))
        assertEquals(emptyList(), file.diagnostics)
        assertEquals(ModelByteRange(head.size.toLong(), 2097152), file.tensors.single().dataRange)
    }
}

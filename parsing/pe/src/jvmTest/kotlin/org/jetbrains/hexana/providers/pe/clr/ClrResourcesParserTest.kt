package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `sample.resources` (written by `src/clr/resources/MakeResources.cs`) against the dump the runtime's
 * own `ResourceReader` produces for it (`DumpResources.cs`): name, type code, data length, value.
 */
class ClrResourcesParserTest {
    private val dir = Path.of(System.getProperty("user.dir")).resolve("../native-fixtures/test-data/pe").normalize()
    private val bytes = dir.resolve("sample.resources").readBytes()
    private val file = assertNotNull(ClrResourcesParser.parse(bytes.toCommonByteBuffer()))

    private class Expected(val type: String, val dataLength: Int, val value: String)

    /** `name | type | data length | value`, as `ResourceReader.GetResourceData` and the enumerator report them. */
    private val expected: Map<String, Expected> = dir.resolve("sample.resources.dump.txt").readLines().associate { line ->
        val (name, type, length, value) = line.split(" | ", limit = 4)
        name to Expected(type, length.toInt(), value)
    }

    @Test
    fun `reads the headers`() {
        assertTrue(file.readerType.startsWith("System.Resources.ResourceReader, mscorlib"))
        assertTrue(file.resourceSetType.startsWith("System.Resources.RuntimeResourceSet"))
        assertEquals(2, file.version)
        assertEquals(listOf("Margin, MakeResources, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null"), file.types)
        assertEquals(expected.keys, file.resources.map { it.name }.toSet())
        assertEquals(emptyList(), file.diagnostics)
    }

    @Test
    fun `data ranges match what ResourceReader reads`() {
        for (resource in file.resources) {
            val want = expected.getValue(resource.name)
            // GetResourceData returns the value's bytes without its leading type code (one byte here).
            assertEquals(want.dataLength, assertNotNull(resource.dataRange).size - 1, resource.name)
        }
    }

    @Test
    fun `decodes every built-in type`() {
        fun value(name: String) = file.resources.single { it.name == name }
        val decoded = mapOf(
            "Greeting" to "Hello from Hexana", "Multiline" to "line one\\nline two", "Empty" to "",
            "Answer" to "42", "Negative" to "-7", "Big" to "1234567890123456789", "Unsigned" to "4000000000",
            "Byte" to "255", "Flag" to "true", "Letter" to "'Z'", "Ratio" to "0.25", "Single" to "1.5",
            "Price" to "12345.6789", "Released" to "2026-10-20T13:45:30Z", "Timeout" to "1.02:03:04.5000000",
            "Nothing" to "null",
        )
        for ((name, text) in decoded) {
            // The runtime prints booleans capitalized, DateTime with seven fraction digits; compare to ours.
            assertEquals(text, value(name).value.replace("\n", "\\n"), name)
        }
        assertEquals("True", expected.getValue("Flag").value)
        assertEquals("1.02:03:04.5000000", expected.getValue("Timeout").value)
        assertEquals("12345.6789", expected.getValue("Price").value)

        assertEquals("byte[]", value("Pixel").type)
        assertEquals("12 bytes (PNG image)", value("Pixel").value)
        assertEquals(NativeByteRange(value("Pixel").dataRange!!.offset + 5, 12), value("Pixel").payloadRange)
        assertEquals("Stream", value("Blob").type)
        assertEquals("5 bytes", value("Blob").value)
        assertEquals(ClrResourceKind.Serialized, value("Margin").kind)
        assertEquals(133, value("Margin").payloadRange!!.size)
        assertEquals("string", value("Greeting").type)
        assertEquals("Hello from Hexana", value("Greeting").stringValue)
    }

    @Test
    fun `formats dates and time spans like the runtime`() {
        assertEquals("0001-01-01T00:00:00", formatDateTime(0))
        assertEquals("2000-02-29T12:00:00Z", formatDateTime((1L shl 62) or 630_874_224_000_000_000L))
        assertEquals("-00:00:01.5000000", formatTimeSpan(-15_000_000))
        assertEquals("00:00:00", formatTimeSpan(0))
    }

    @Test
    fun `non-resource bytes and truncations are rejected or degrade`() {
        assertNull(ClrResourcesParser.parse(ByteArray(64).toCommonByteBuffer()))
        for (length in bytes.size downTo 0 step 3) ClrResourcesParser.parse(bytes.copyOf(length).toCommonByteBuffer())
        val random = kotlin.random.Random(1007)
        repeat(3_000) {
            val corrupted = bytes.copyOf()
            repeat(1 + random.nextInt(8)) { corrupted[random.nextInt(4, corrupted.size)] = random.nextInt(256).toByte() }
            ClrResourcesParser.parse(corrupted.toCommonByteBuffer())
        }
    }
}

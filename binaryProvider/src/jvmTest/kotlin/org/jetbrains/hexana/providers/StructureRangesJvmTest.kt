package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural regions checked against real binaries rather than synthesized ones: a ZIP
 * written by `java.util.zip`, and a JVM class file taken off this test's own classpath.
 * Both formats derive their region extents from several header fields at once, so a
 * real writer's layout is what actually exercises the arithmetic.
 */
class StructureRangesJvmTest {

    @Test
    fun zipRangesCoverEveryEntryRecord() {
        val entries = mapOf(
            "a.txt" to "hello".repeat(100).toByteArray(),
            "dir/" to ByteArray(0),
            "dir/b.bin" to ByteArray(2048) { it.toByte() },
            "empty.txt" to ByteArray(0),
        )
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, content) in entries) {
                    // One stored and one deflated entry, so both the compressed and the
                    // uncompressed size paths are covered.
                    zip.setMethod(if (name.endsWith(".bin")) ZipOutputStream.STORED else ZipOutputStream.DEFLATED)
                    if (name.endsWith(".bin")) {
                        zip.putNextEntry(
                            ZipEntry(name).apply {
                                size = content.size.toLong()
                                compressedSize = content.size.toLong()
                                crc = java.util.zip.CRC32().apply { update(content) }.value
                            },
                        )
                    } else {
                        zip.putNextEntry(ZipEntry(name))
                    }
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        val provider = ZipBinaryProvider(bytes.toCommonByteBuffer())
        val ranges = provider.structureRanges

        assertEquals(entries.keys.toList(), ranges.map { it.label })
        assertEquals(entries.keys.map { "Entries" }, ranges.map { it.tabName })
        assertRangeInvariants(ranges, bytes.size)
        // Every range starts at a local file header, PK\x03\x04.
        for (range in ranges) {
            val start = range.offset.toInt()
            assertEquals(
                listOf(0x50, 0x4B, 0x03, 0x04),
                (0 until 4).map { bytes[start + it].toInt() and 0xFF },
                "${range.label} does not start at a local file header",
            )
        }
        // The records are followed by the central directory, which is not an entry.
        assertTrue(
            ranges.last().let { it.offset + it.size } < bytes.size,
            "entry ranges must stop before the central directory",
        )
    }

    @Test
    fun classFileRangesCoverPoolMembersAndAttributes() {
        val bytes = checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "org/jetbrains/hexana/providers/StructureRangesJvmTest.class",
            ),
        ).use { it.readBytes() }

        val provider = ClassFileBinaryProvider(bytes.toCommonByteBuffer())
        assertNotNull(provider.parsedClassFile)
        val ranges = provider.structureRanges

        assertRangeInvariants(ranges, bytes.size)
        // The pool comes first and takes up most of a class file.
        val pool = ranges.first()
        assertEquals("Constant pool", pool.label)
        assertEquals(10L, pool.offset)
        // Both test methods show up under their own names, wired to the Methods tab.
        for (name in listOf("zipRangesCoverEveryEntryRecord", "classFileRangesCoverPoolMembersAndAttributes")) {
            val method = assertNotNull(
                ranges.firstOrNull { it.label == name },
                "no range for method $name; got ${ranges.map { it.label }}",
            )
            assertEquals("Methods", method.tabName)
        }
        // The last region is a class-level attribute reaching the end of the file.
        assertEquals(bytes.size.toLong(), ranges.last().let { it.offset + it.size })
    }
}

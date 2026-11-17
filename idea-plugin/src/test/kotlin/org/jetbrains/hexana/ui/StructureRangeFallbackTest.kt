package org.jetbrains.hexana.ui

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the structure view's fallback tree: formats without a hand-written tree
 * (everything but Wasm and ELF) list their [org.jetbrains.hexana.providers.BinaryProvider.structureRanges]
 * instead of leaving the tool window empty. ZIP stands in for all of them - its regions
 * carry real labels, and it is the cheapest format to write byte-exactly here.
 */
class StructureRangeFallbackTest : UsefulTestCase() {

    fun testRegionsBecomeNavigableLeavesInFileOrder() {
        val names = listOf("beta.txt", "alpha/nested.bin", "gamma.txt")
        val provider = ZipBinaryProvider(zipOf(names).toCommonByteBuffer())

        val elements = buildStructureRangeElements(provider, editor = null)

        assertEquals(names, elements.map { it.presentation.presentableText })
        // In file order, and each element spans exactly its region.
        val regions = provider.structureRanges.sortedBy { it.offset }
        assertEquals(
            regions.map { it.offset.toInt()..(it.offset + it.size - 1).toInt() },
            elements.map { it.range },
        )
        // Navigation needs an editor; without one the element stays inert rather than
        // claiming it can navigate.
        assertFalse(elements.first().canNavigate())
    }

    fun testRegionsBeyondTheCapAreSummarized() {
        val cap = 10_000
        val extra = 5
        val provider = ZipBinaryProvider(zipOf((0 until cap + extra).map { "f$it" }).toCommonByteBuffer())
        assertEquals(cap + extra, provider.structureRanges.size)

        val elements = buildStructureRangeElements(provider, editor = null)

        assertEquals(cap + 1, elements.size)
        val summary = elements.last()
        assertEquals("... $extra more regions", summary.presentation.presentableText)
        assertFalse("the summary node must not be navigable", summary.canNavigate())
        assertTrue(elements.dropLast(1).all { it.range.first >= 0 })
    }

    fun testFormatWithoutRegionsHasNoElements() {
        val provider = GenericBinaryProvider(ByteArray(64) { it.toByte() }.toCommonByteBuffer())

        assertEmpty(buildStructureRangeElements(provider, editor = null))
    }

    private fun zipOf(names: List<String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}

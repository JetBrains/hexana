package org.jetbrains.hexana.diff

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.StructureRange

class HexDiffSectionsTest : UsefulTestCase() {

    fun testChangedBytesAreAttributedToTheirSection() {
        val left = ByteArray(16 * 20)
        val right = left.copyOf()
        right[40] = 1 // .a
        right[41] = 1 // .a
        right[200] = 1 // .c
        val diff = diff(left, right)
        val ranges = listOf(
            StructureRange(160, 160, ".c"), // unsorted on purpose
            StructureRange(0, 64, ".a"),
            StructureRange(64, 96, ".b"),
        )

        val sections = diffSections(diff, ranges, left.size)

        assertEquals(listOf(".a", ".b", ".c"), sections.map { it.label })
        assertEquals(listOf(0, 1, 2), sections.map { it.colorIndex })
        assertEquals(listOf(2L, 0L, 1L), sections.map { it.changedBytes })
        assertEquals(listOf(40L, null, 200L), sections.map { it.firstChangedOffset })
    }

    fun testBytesPastTheShorterSideCountAsChanged() {
        val left = ByteArray(40) { 7 }
        val right = ByteArray(100) { 7 }
        val diff = diff(left, right)
        // Straddles the end of the left side, and runs past the right side's end too.
        val sections = diffSections(diff, listOf(StructureRange(32, 200, ".tail")), right.size)

        assertEquals(68L, sections.single().size)
        assertEquals(60L, sections.single().changedBytes)
        assertEquals(40L, sections.single().firstChangedOffset)
    }

    fun testLookupsUseOffsetOrder() {
        val diff = diff(ByteArray(64), ByteArray(64))
        val sections = diffSections(
            diff,
            listOf(StructureRange(0, 16, ".a"), StructureRange(32, 16, ".b"), StructureRange(48, 16, ".c")),
            64,
        )

        assertEquals(".a", sectionAt(sections, 15)?.label)
        assertNull(sectionAt(sections, 20)) // gap
        assertEquals(listOf(".a", ".b"), sectionLabelsIn(sections, 8, 40))
        assertEquals(listOf(".b", ".c"), sectionLabelsIn(sections, 16, 64))
    }

    fun testMergePairsByNameAndPlacesOneSidedSectionsInOrder() {
        val left = listOf(section(".a", 0), section(".b", 1, changed = 5), section(".gone", 2), section(".c", 3))
        val right = listOf(section(".new", 0), section(".a", 1), section(".b", 2), section(".extra", 3), section(".c", 4))

        val merged = mergeSections(left, right)

        assertEquals(listOf(".new", ".a", ".b", ".extra", ".gone", ".c"), merged.merged.map { it.label })
        assertEquals(
            listOf(
                SectionStatus.RIGHT_ONLY, SectionStatus.UNCHANGED, SectionStatus.MODIFIED,
                SectionStatus.RIGHT_ONLY, SectionStatus.LEFT_ONLY, SectionStatus.UNCHANGED,
            ),
            merged.merged.map { it.status },
        )
        assertEquals(merged.merged.indices.toList(), merged.merged.map { it.colorIndex })
        // A pair shares one tint on both sides.
        assertEquals(merged.left.first { it.label == ".c" }.colorIndex, merged.right.first { it.label == ".c" }.colorIndex)
        assertEquals(5L, merged.merged.first { it.label == ".b" }.changedBytes)
    }

    fun testMergePairsRepeatedNamesByOccurrence() {
        val merged = mergeSections(
            listOf(section("x", 0), section("x", 1)),
            listOf(section("x", 0), section("x", 1), section("x", 2)),
        )
        assertEquals(
            listOf(SectionStatus.UNCHANGED, SectionStatus.UNCHANGED, SectionStatus.RIGHT_ONLY),
            merged.merged.map { it.status },
        )
    }

    private fun section(label: String, index: Int, changed: Long = 0) =
        DiffSection(index * 16L, 16, label, index, changed, if (changed > 0) index * 16L else null)

    private fun diff(left: ByteArray, right: ByteArray) =
        HexDiff.compute(left.toCommonByteBuffer(), right.toCommonByteBuffer())
}

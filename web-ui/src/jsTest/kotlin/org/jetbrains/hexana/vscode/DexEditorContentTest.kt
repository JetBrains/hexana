package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.shared.DexSmaliClass
import org.jetbrains.hexana.shared.DexSmaliRow
import org.jetbrains.hexana.shared.DEX_SMALI_TAB_NAME
import org.jetbrains.hexana.shared.DexSmaliSearchEntry
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DexEditorContentTest {

    private val classes = List(3) { i ->
        DexSmaliClass(
            index = i,
            descriptor = "Lcom/example/C$i;",
            accessFlags = 0x1,
            superDescriptor = "Ljava/lang/Object;",
            byteRange = (100L + i * 32) until (132L + i * 32),
            classDataOff = 0x1000L + i,
        )
    }

    private fun rows(count: Int, label: String): List<DexSmaliRow> = List(count) { i ->
        DexSmaliRow(null, listOf(RenderToken("$label-$i", RenderTokenKind.IDENTIFIER)))
    }

    @Test
    fun collapsedListHasOneItemPerClass() {
        val index = DexSmaliItemIndex(classes, emptyMap())

        assertEquals(3, index.size)
        for (i in 0 until 3) {
            val item = index.at(i)
            assertTrue(item is DexSmaliItem.Header, "item $i is $item")
            assertEquals(classes[i].descriptor, item.cls.descriptor)
            assertEquals(false, item.expanded)
        }
        assertNull(index.at(3))
        assertNull(index.at(-1))
    }

    @Test
    fun headerIndexOfAccountsForExpandedClassesAndFilteredOnes() {
        val index = DexSmaliItemIndex(classes, mapOf(0 to rows(4, "c0")))
        assertEquals(0, index.headerIndexOf(0))
        assertEquals(5, index.headerIndexOf(1))
        assertEquals(6, index.headerIndexOf(2))

        // A class a search filtered out is not listed, so it has no header.
        val filtered = DexSmaliItemIndex(listOf(classes[0], classes[2]), emptyMap())
        assertNull(filtered.headerIndexOf(1))
        assertEquals(1, filtered.headerIndexOf(2))
    }

    @Test
    fun expandingAClassInsertsItsRowsAfterItsHeader() {
        val index = DexSmaliItemIndex(classes, mapOf(1 to rows(2, "c1")))

        assertEquals(5, index.size)
        assertEquals("Lcom/example/C0;", (index.at(0) as DexSmaliItem.Header).cls.descriptor)
        val header = index.at(1) as DexSmaliItem.Header
        assertEquals("Lcom/example/C1;", header.cls.descriptor)
        assertEquals(true, header.expanded)
        assertEquals("c1-0", (index.at(2) as DexSmaliItem.Row).row.text)
        assertEquals("c1-1", (index.at(3) as DexSmaliItem.Row).row.text)
        assertEquals(1, (index.at(3) as DexSmaliItem.Row).classIndex)
        // The next class's header comes straight after the expanded body.
        assertEquals("Lcom/example/C2;", (index.at(4) as DexSmaliItem.Header).cls.descriptor)
    }

    @Test
    fun laterHeadersShiftByExactlyTheRowsAddedBeforeThem() {
        val collapsed = DexSmaliItemIndex(classes, emptyMap())
        val expandedFirst = DexSmaliItemIndex(classes, mapOf(0 to rows(7, "c0")))

        fun headerIndexOf(index: DexSmaliItemIndex, descriptor: String): Int =
            (0 until index.size).first {
                (index.at(it) as? DexSmaliItem.Header)?.cls?.descriptor == descriptor
            }

        assertEquals(
            headerIndexOf(collapsed, "Lcom/example/C2;") + 7,
            headerIndexOf(expandedFirst, "Lcom/example/C2;"),
        )
    }

    @Test
    fun everyItemIndexResolvesWithSeveralClassesExpanded() {
        val index = DexSmaliItemIndex(
            classes,
            mapOf(0 to rows(3, "c0"), 2 to rows(1, "c2")),
        )

        assertEquals(3 + 3 + 1, index.size)
        val kinds = (0 until index.size).map { index.at(it) }
        assertEquals(3, kinds.count { it is DexSmaliItem.Header })
        assertEquals(4, kinds.count { it is DexSmaliItem.Row })
        assertTrue(kinds.none { it == null })
    }

    @Test
    fun emptyQueryKeepsEveryClass() {
        assertEquals(classes, filterDexSmaliClasses(classes, emptyList(), "   "))
    }

    @Test
    fun filterMatchesClassDescriptorsAndIndexedMethodSignatures() {
        val searchIndex = listOf(
            DexSmaliSearchEntry("Lcom/example/C0;", null, 0),
            DexSmaliSearchEntry("Lcom/example/C1;", null, 1),
            DexSmaliSearchEntry("Lcom/example/C2;", null, 2),
            DexSmaliSearchEntry("parseInt(Ljava/lang/String;)I", "Lcom/example/C2;", 2),
        )

        assertEquals(listOf(1), filterDexSmaliClasses(classes, searchIndex, "C1").map { it.index })
        // Case-insensitive, and a method hit selects its declaring class.
        assertEquals(listOf(2), filterDexSmaliClasses(classes, searchIndex, "parseint").map { it.index })
        assertEquals(listOf(0, 1, 2), filterDexSmaliClasses(classes, searchIndex, "com/example").map { it.index })
        assertEquals(emptyList(), filterDexSmaliClasses(classes, searchIndex, "nothing").map { it.index })
    }

    @Test
    fun aDexFileGetsTheSmaliTab() {
        // The webview picks extra tabs off the raw buffer, so this is the whole
        // wiring: magic detection, parse, tab label.
        val tabs = computeExtraTabs(minimalDex().toCommonByteBuffer())
        assertEquals(listOf(DEX_SMALI_TAB_NAME), tabs.map { it.label })
    }

    @Test
    fun aDexFileChosenAsGenericGetsNoSmaliTab() {
        // A parser chosen in `.hexana/file_associations.json` decides; the magic must not
        // bring the bytecode tabs back for a file the user asked to see unparsed.
        val bytes = minimalDex().toCommonByteBuffer()
        assertEquals(emptyList(), computeExtraTabs(bytes, GenericBinaryProvider(bytes)).map { it.label })
        assertEquals(listOf(DEX_SMALI_TAB_NAME), computeExtraTabs(bytes, DexBinaryProvider(bytes)).map { it.label })
    }

    @Test
    fun anUnrecognizedBufferGetsNoExtraTabs() {
        assertEquals(emptyList(), computeExtraTabs(ByteArray(128).toCommonByteBuffer()).map { it.label })
    }
}

/** A header-only DEX file: valid magic and endian tag, zero of everything else. */
private fun minimalDex(): ByteArray {
    val bytes = ByteArray(112)
    "dex\n035".encodeToByteArray().copyInto(bytes)
    putU32Le(bytes, 32, 112) // file_size
    putU32Le(bytes, 36, 112) // header_size
    putU32Le(bytes, 40, 0x12345678) // endian_tag
    return bytes
}

private fun putU32Le(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

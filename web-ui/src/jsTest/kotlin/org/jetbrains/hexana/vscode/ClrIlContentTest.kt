package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.shared.ClrIlRow
import org.jetbrains.hexana.shared.ClrIlType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ClrIlContentTest {
    private val types = List(3) { index ->
        ClrIlType(index, "Ns.T$index", "public class", "System.Object", memberCount = 1, byteRange = (index * 14L) until (index * 14L + 14))
    }

    private fun rows(count: Int, prefix: String) = List(count) { ClrIlRow(null, 0, listOf(RenderToken("$prefix-$it", RenderTokenKind.KEYWORD))) }

    @Test
    fun collapsedTypesAreOneHeaderEach() {
        val index = ClrIlItemIndex(types, emptyMap())
        assertEquals(3, index.size)
        for (i in 0 until 3) {
            val item = assertIs<ClrIlItem.Header>(index.at(i))
            assertEquals("Ns.T$i", item.type.name)
            assertEquals(false, item.expanded)
        }
        assertNull(index.at(3))
        assertNull(index.at(-1))
    }

    @Test
    fun expandingATypeInsertsItsRowsAfterItsHeader() {
        val index = ClrIlItemIndex(types, mapOf(1 to rows(2, "t1")))
        assertEquals(5, index.size)
        assertEquals("Ns.T0", assertIs<ClrIlItem.Header>(index.at(0)).type.name)
        assertEquals(true, assertIs<ClrIlItem.Header>(index.at(1)).expanded)
        assertEquals("t1-0", assertIs<ClrIlItem.Row>(index.at(2)).row.text)
        assertEquals(1, assertIs<ClrIlItem.Row>(index.at(3)).typeIndex)
        assertEquals("Ns.T2", assertIs<ClrIlItem.Header>(index.at(4)).type.name)
    }
}

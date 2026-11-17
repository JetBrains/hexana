package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DexRevealInSmaliTest {

    private val columns = listOf(ColumnDescriptor("Class") { CellValue("Lcom/example/C$it;") })

    @Test
    fun classesTabGetsRevealInSmaliPassingTheClassDefIndex() {
        val revealed = mutableListOf<Int>()
        val tab = TableTabDescriptor(
            name = DEX_CLASSES_TAB_NAME,
            columns = columns,
            rowCount = 3,
            alwaysShowSearch = true,
            searchPlaceholder = "Search classes",
            childrenProvider = { TableTabChildRows(columns, rowCount = 1) },
        ).withDexRevealInSmali { revealed += it }

        val item = tab.contextMenuItems.single()
        assertEquals(DEX_REVEAL_IN_SMALI_LABEL, item.labelProvider(2))
        item.action(2)
        assertEquals(listOf(2), revealed)

        // Everything else about the tab survives.
        assertNotNull(tab.childrenProvider)
        assertTrue(tab.alwaysShowSearch)
        assertEquals("Search classes", tab.searchPlaceholder)
        assertEquals(3, tab.rowCount)
    }

    @Test
    fun otherTabsAndAnEmptyClassesTabAreReturnedAsIs() {
        val header = TableTabDescriptor(name = "Header", columns = columns, rowCount = 1)
        val emptyClasses = TableTabDescriptor(name = DEX_CLASSES_TAB_NAME, columns = emptyList(), rowCount = 0)

        assertSame(header, header.withDexRevealInSmali {})
        assertSame(emptyClasses, emptyClasses.withDexRevealInSmali {})
    }
}

package org.jetbrains.hexana.ui.panel

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.DEX_SMALI_TAB_NAME
import org.jetbrains.hexana.shared.TableTabChildRows
import org.jetbrains.hexana.shared.TableTabDescriptor

class DexTabEnricherTest : BasePlatformTestCase() {

    private val columns = listOf(
        ColumnDescriptor("Class", ColumnWidth.Fill()) { CellValue("Lcom/example/Foo$it;") },
    )

    private fun classesTab() = TableTabDescriptor(
        name = "Classes",
        columns = columns,
        rowCount = 3,
        alwaysShowSearch = true,
        searchPlaceholder = "Search classes",
        childrenProvider = { TableTabChildRows(columns, rowCount = 1) },
    )

    fun testClassesTabGetsRevealInSmaliThatRequestsTheClass() {
        val state = HexViewStateHolder(project)
        val enriched = listOf(classesTab()).enrichDexTabs(state).single()

        val item = enriched.contextMenuItems.single { it.labelProvider(2) == "Reveal in Smali" }
        item.action(2)

        assertEquals(2, state.dexSmaliClassNav.value)
        assertEquals(DEX_SMALI_TAB_NAME, state.pendingTabSelection.replayCache.lastOrNull())
    }

    fun testClassesTabKeepsItsTreeAndSearch() {
        val enriched = listOf(classesTab()).enrichDexTabs(HexViewStateHolder(project)).single()

        assertNotNull(enriched.childrenProvider)
        assertTrue(enriched.alwaysShowSearch)
        assertEquals("Search classes", enriched.searchPlaceholder)
        assertEquals(3, enriched.rowCount)
    }

    fun testOtherTabsAreUntouched() {
        val header = TableTabDescriptor(name = "Header", columns = columns, rowCount = 1)
        val emptyClasses = TableTabDescriptor(name = "Classes", columns = emptyList(), rowCount = 0)
        val enriched = listOf(header, emptyClasses).enrichDexTabs(HexViewStateHolder(project))

        assertSame(header, enriched[0])
        assertSame(emptyClasses, enriched[1])
    }
}

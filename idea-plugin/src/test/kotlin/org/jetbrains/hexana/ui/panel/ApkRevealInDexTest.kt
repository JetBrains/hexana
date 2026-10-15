package org.jetbrains.hexana.ui.panel

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.TableRowSelectionRequest
import org.jetbrains.hexana.shared.DEX_CLASSES_TAB_NAME

class ApkRevealInDexTest : BasePlatformTestCase() {

    fun testRequestSelectTableRowSelectsTheTabAndQueuesTheRow() {
        val state = HexViewStateHolder(project)

        state.requestSelectTableRow(DEX_CLASSES_TAB_NAME, 7)

        assertEquals(TableRowSelectionRequest(DEX_CLASSES_TAB_NAME, 7), state.pendingTableRowSelection.value)
        assertEquals(DEX_CLASSES_TAB_NAME, state.pendingTabSelection.replayCache.lastOrNull())
    }
}

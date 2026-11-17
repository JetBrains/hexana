package org.jetbrains.hexana.actions

import com.intellij.diff.DiffManagerEx
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.impl.DiffSettingsHolder
import org.jetbrains.hexana.diff.HexDiffTool
import org.jetbrains.hexana.diff.HexanaStructuralDiffTool
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Which files the Project-view "Compare Binaries" entry is offered for: every binary a diff
 * provider compares, recognized by content — including extensionless ones — and nothing else.
 */
class CompareFilesActionTest : BasePlatformTestCase() {

    private val elf = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0) + ByteArray(56)
    private val wasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    private val machO = byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()) + ByteArray(28)

    private fun file(name: String, bytes: ByteArray): VirtualFile = WriteAction.computeAndWait<VirtualFile, Throwable> {
        myFixture.tempDirFixture.createFile(name).also { it.setBinaryContent(bytes) }
    }

    private fun contextMenuVisible(vararg files: VirtualFile): Boolean {
        val action = CompareFilesAction()
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, files.first())
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files))
            .build()
        val event = AnActionEvent.createEvent(
            context, action.templatePresentation.clone(), ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, null,
        )
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    fun `test hexana compare opens on the structural viewer and compare files on the hex diff`() {
        val tools = DiffManagerEx.getInstance().diffTools
        val hexanaPlace = DiffSettingsHolder.DiffSettings.getSettings(CompareFilesAction.DIFF_PLACE)
        hexanaPlace.diffToolsOrder = emptyList()

        CompareFilesAction.preferStructuralViewer()
        val hexanaOrder = DiffRequestProcessor.getToolOrderFromSettings(hexanaPlace, tools)
        assertInstanceOf(hexanaOrder.first(), HexanaStructuralDiffTool::class.java)
        assertTrue(hexanaOrder.indexOfFirst { it is HexDiffTool } > 0)

        // The IDE's own Compare Files uses the default place, where the hex diff comes first.
        val defaultOrder = DiffRequestProcessor.getToolOrderFromSettings(DiffSettingsHolder.DiffSettings.getSettings(null), tools)
        assertTrue(defaultOrder.indexOfFirst { it is HexDiffTool } < defaultOrder.indexOfFirst { it is HexanaStructuralDiffTool })

        // Seeded once: a viewer the user picked afterwards is kept.
        hexanaPlace.diffToolsOrder = listOf(HexDiffTool::class.java.canonicalName)
        CompareFilesAction.preferStructuralViewer()
        assertEquals(listOf(HexDiffTool::class.java.canonicalName), hexanaPlace.diffToolsOrder)
        hexanaPlace.diffToolsOrder = emptyList()
    }

    fun `test only pairs a diff provider accepts are comparable pairs`() {
        val executable = file("server", elf)
        val module = file("module.wasm", wasm)
        assertTrue(CompareFilesAction.isComparablePair(executable, file("server-old", elf)))
        assertTrue(CompareFilesAction.isComparablePair(module, file("module-old.wasm", wasm)))
        // Native formats share one provider, so ELF against Mach-O is compared.
        assertTrue(CompareFilesAction.isComparablePair(executable, file("tool", machO)))
        assertFalse(CompareFilesAction.isComparablePair(module, executable))
        assertFalse(CompareFilesAction.isComparablePair(executable, module))
        // A module and a component share the WASM file type; only the header tells them apart.
        val component = file("component.wasm", byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00))
        assertFalse(CompareFilesAction.isComparablePair(module, component))
        assertTrue(CompareFilesAction.isComparablePair(component, file("component-old.wasm", byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00))))
    }

    fun `test extensionless binaries are comparable by content`() {
        val executable = file("server", elf)
        val module = file("module", wasm)
        assertTrue(CompareFilesAction.isComparable(executable))
        assertTrue(CompareFilesAction.isComparable(module))
        assertTrue(contextMenuVisible(executable))
        assertTrue(contextMenuVisible(executable, file("server-old", elf)))
    }

    fun `test named binaries of every compared format are comparable`() {
        assertTrue(CompareFilesAction.isComparable(file("a.wasm", wasm)))
        assertTrue(CompareFilesAction.isComparable(file("libfoo.dylib", machO)))
        assertTrue(CompareFilesAction.isComparable(file("main.o", elf)))
    }

    fun `test files no provider compares are not offered`() {
        val headerless = file("firmware.bin", ByteArray(64) { 0x5A })
        val text = file("notes", "just some text\n".toByteArray())
        assertFalse(CompareFilesAction.isComparable(headerless))
        assertFalse(CompareFilesAction.isComparable(text))
        assertFalse(contextMenuVisible(headerless))
    }

    fun `test the pair title reflects two selected files`() {
        val action = CompareFilesAction()
        val a = file("a", elf)
        val b = file("b", elf)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, a)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(a, b))
            .build()
        val event = AnActionEvent.createEvent(
            context, action.templatePresentation.clone(), ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, null,
        )
        action.update(event)
        assertEquals("Compare Binary Files", event.presentation.text)
    }
}

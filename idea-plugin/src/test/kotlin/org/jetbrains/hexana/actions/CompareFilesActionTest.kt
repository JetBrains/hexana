package org.jetbrains.hexana.actions

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

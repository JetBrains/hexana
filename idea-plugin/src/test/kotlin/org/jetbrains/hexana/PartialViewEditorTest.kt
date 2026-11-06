package org.jetbrains.hexana

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.hexana.providers.GgufBinaryProvider
import org.jetbrains.hexana.providers.MAX_BINARY_WINDOW_BYTES
import org.jetbrains.hexana.ui.PartialViewNotification
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * A file over 2 GiB opens as its head instead of failing. The file is sparse: a GGUF header,
 * then a length set far past the window, so no gigabytes are written to disk.
 */
class PartialViewEditorTest : BasePlatformTestCase() {
    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("hexana-partial-view")
    }

    override fun tearDown() {
        try {
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun sparseModel(size: Long): VirtualFile {
        val path = dir.resolve("big.gguf")
        RandomAccessFile(path.toFile(), "rw").use { raf ->
            raf.write(File("../parsing/mlmodel/test-data/tiny.gguf").readBytes())
            raf.setLength(size)
        }
        return runWriteAction { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) } ?: error("no VFS file for $path")
    }

    fun testFileOverTheWindowOpensAsItsHead() {
        val size = 3L * 1024 * 1024 * 1024
        val file = sparseModel(size)

        val window = file.toBinaryWindow()!!
        assertTrue(window.isPartial)
        assertEquals(MAX_BINARY_WINDOW_BYTES, window.buffer.size)
        assertEquals(size, window.fileSize)
        assertThrows(FileTooLargeToMapException::class.java) { file.toMappedBuffer() }

        val editor = createEditor(file) as HexanaFileEditor
        try {
            assertEquals(size, editor.partialFileSize)
            val provider = editor.binaryProvider as GgufBinaryProvider
            assertEquals(size, provider.fileSize)
            val gguf = provider.parsedGguf!!
            assertEquals(emptyList<Any>(), gguf.diagnostics)
            assertEquals(4, gguf.tensors.size)
            assertNotNull(PartialViewNotification().collectNotificationData(project, file)?.apply(editor))
        } finally {
            ApplicationManager.getApplication().invokeAndWait { editor.dispose() }
        }
    }

    fun testFileWithinTheWindowIsWhole() {
        val file = sparseModel(64L * 1024)
        val window = file.toBinaryWindow()!!
        assertFalse(window.isPartial)
        assertNull(PartialViewNotification().collectNotificationData(project, file))
        val editor = createEditor(file) as HexanaFileEditor
        try {
            assertNull(editor.partialFileSize)
        } finally {
            ApplicationManager.getApplication().invokeAndWait { editor.dispose() }
        }
    }

    private fun createEditor(file: VirtualFile): FileEditor {
        val editorRef = arrayOfNulls<FileEditor>(1)
        ApplicationManager.getApplication().invokeAndWait {
            editorRef[0] = HexanaFileEditorProvider().createEditor(project, file)
        }
        return editorRef[0] ?: error("Hexana editor was not created")
    }
}

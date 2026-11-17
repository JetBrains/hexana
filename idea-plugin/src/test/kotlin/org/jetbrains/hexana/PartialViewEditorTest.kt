package org.jetbrains.hexana

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.io.readBytes
import org.jetbrains.hexana.providers.GgufBinaryProvider
import org.jetbrains.hexana.providers.MAX_BINARY_WINDOW_BYTES
import org.jetbrains.hexana.ui.performSearch
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * A file over 2 GiB opens instead of failing: the parsers get its head, while the hex view and
 * search read all of it. The file is sparse — a GGUF header, a marker past 2 GiB, and a length set
 * far beyond — so no gigabytes are written to disk.
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

    private val marker = "HEXANA-PAST-2GB".encodeToByteArray()
    private val markerOffset = 2L * 1024 * 1024 * 1024 + 512L * 1024 * 1024 + 3

    private fun sparseModel(size: Long): VirtualFile {
        val path = dir.resolve("big.gguf")
        RandomAccessFile(path.toFile(), "rw").use { raf ->
            raf.write(File("../parsing/mlmodel/test-data/tiny.gguf").readBytes())
            raf.setLength(size)
            if (size > markerOffset + marker.size) {
                raf.seek(markerOffset)
                raf.write(marker)
            }
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

            // The hex view reads the whole file, past the head the parsers see.
            val source = editor.fileSource!!
            assertEquals(size, source.size)
            assertEquals(marker.toList(), source.readBytes(markerOffset, marker.size).toList())

            // Search reaches it too, and leaves the cursor on it.
            val state = HexViewStateHolder(project)
            runBlocking { performSearch(String(marker), reverse = false, state, source) }
            assertEquals(markerOffset, state.cursorPosition.getByteOffset(state.bytesPerRow.value))
            assertEquals(listOf(markerOffset until markerOffset + marker.size), state.searchResults.value)
        } finally {
            ApplicationManager.getApplication().invokeAndWait { editor.dispose() }
        }
    }

    fun testFileWithinTheWindowIsWhole() {
        val file = sparseModel(64L * 1024)
        val window = file.toBinaryWindow()!!
        assertFalse(window.isPartial)
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

class SearchHighlightLookupTest : com.intellij.testFramework.UsefulTestCase() {
    fun testBinarySearchOverSortedResults() {
        val results = listOf(0L..1L, 5L..9L, (3L shl 30)..(3L shl 30) + 3)
        assertTrue(org.jetbrains.hexana.ui.isInSearchResult(results, 7))
        assertTrue(org.jetbrains.hexana.ui.isInSearchResult(results, (3L shl 30) + 2))
        assertFalse(org.jetbrains.hexana.ui.isInSearchResult(results, 3))
        assertFalse(org.jetbrains.hexana.ui.isInSearchResult(emptyList(), 0))
        // Overlapping matches ("aa" in "aaa"): both halves of the middle byte are found.
        assertTrue(org.jetbrains.hexana.ui.isInSearchResult(listOf(0L..1L, 1L..2L), 1))
    }
}

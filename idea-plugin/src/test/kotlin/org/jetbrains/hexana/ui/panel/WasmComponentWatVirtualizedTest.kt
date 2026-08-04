package org.jetbrains.hexana.ui.panel

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

class WasmComponentWatVirtualizedTest : UsefulTestCase() {

    // Real artifact (wasm-tools parse): a glue-only component with one func
    // import, a record type, a func type, and one export — no core modules.
    private val glueComponent = (
        "0061736d0d000100070501400001000a17010012696d706f72742d66756e632d68616e646c6501000" +
            "71d02720207726571756573747908726573706f6e736573400101787900730b0c01000668616e646c65030100"
        ).hexToByteArray()

    private fun chunkData(): ComponentWatChunkData =
        buildComponentWatChunks(WasmComponentBinaryProvider(glueComponent.toCommonByteBuffer()))

    private fun ComponentWatChunkData.lineText(chunk: BinaryChunk): String =
        linesByChunk[chunk.id].orEmpty().joinToString("") { it.text }

    fun testSectionsAndRecordsAreDecoded() {
        val data = chunkData()
        val tree = data.chunkTree
        assertTrue(tree.totalChunks > 0)

        val headers = (0 until tree.totalChunks).map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.SectionHeader>()
            .map { ComponentSectionKinds.displayName(it.kind) }
        assertContainsElements(headers, "Import", "Types", "Export")

        val lines = (0 until tree.totalChunks).map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .map { data.lineText(it) }
        assertTrue(lines.any { it.contains("(component") })
        assertTrue(lines.any { it.contains("(import \"import-func-handle\"") })
        assertTrue(lines.any { it.contains("(record (field \"request\" u32) (field \"response\" string))") })
        assertTrue(lines.any { it.contains("(func (param \"x\" u32) (result string))") })
        assertTrue(lines.any { it.contains("(export \"handle\"") })
    }

    fun testChunkIdsAreUnique() {
        val tree = chunkData().chunkTree
        val ids = (0 until tree.totalChunks).map { tree.chunkAt(it).id }
        assertEquals(ids.size, ids.toSet().size)
    }

    fun testImportElementCarriesClickableOffset() {
        val data = chunkData()
        val tree = data.chunkTree
        val importElement = (0 until tree.totalChunks).map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .single { it.displayName == "import-func-handle" }
        val offsetToken = data.linesByChunk[importElement.id].orEmpty()
            .single { it.kind == RenderTokenKind.OFFSET_ANNOTATION }
        assertEquals(importElement.byteRange.first, offsetToken.sourceOffset)
        assertTrue(importElement.byteRange.first > 0L)
    }

    fun testWatLineLexerSplitsStringsCommentsAndIdentifiers() {
        val tokens = watLineTokens("(type (own \$#type4) (; 4 ;)) \"name\"")
        assertTrue(tokens.any { it.kind == RenderTokenKind.IDENTIFIER && it.text == "\$#type4" })
        assertTrue(tokens.any { it.kind == RenderTokenKind.COMMENT && it.text == "(; 4 ;)" })
        assertTrue(tokens.any { it.kind == RenderTokenKind.STRING && it.text == "\"name\"" })
        assertTrue(tokens.any { it.kind == RenderTokenKind.KEYWORD && it.text == "type" })
    }
}
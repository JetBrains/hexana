package org.jetbrains.hexana.mcp

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WasmInputBytesLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectsFileThatExceedsLimitAfterRead() {
        val wasm = temporaryFolder.newFile("growing.wasm").toPath()
        Files.write(wasm, byteArrayOf(0, 1, 2))
        val loader = WasmInputBytesLoader(
            maxInlineInputBytes = 1024,
            maxFileInputBytes = 3,
            allowedFilePolicy = AllowedFilePolicy.allowAll,
            fileSizeReader = { 3L },
            fileBytesReader = { byteArrayOf(0, 1, 2, 3) },
        )

        val error = assertFailsWith<HexanaAnalysisException> {
            loader.load(WasmInput.FilePath(wasm.toString()))
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertEquals(
            "File exceeds maximum size of 3 bytes. Use a smaller file or increase the configured MCP file-size limit.",
            error.message
        )
    }
}

package org.jetbrains.hexana

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import java.io.File

class DwarfIndexTest : UsefulTestCase() {

    private fun loadWasmResource(relativePath: String): ByteArray {
        val resourceDir = File("../wasmParser/src/commonTest/resources")
        val file = File(resourceDir, relativePath)
        assertTrue("Test resource must exist: ${file.absolutePath}", file.exists())
        return file.readBytes()
    }

    private fun extractAllFileNames(bytes: ByteArray): Set<String> {
        val buffer = bytes.toCommonByteBuffer()
        val parseResult = WasmParser().parse(buffer) as? WasmParseSuccess ?: return emptySet()
        val module = parseResult.wasmModule as? BaseModule ?: return emptySet()
        val accessor = WasmDwarfAccessor(module)
        val lineSection = accessor.debugLineSection() ?: return emptySet()
        val headers = with(lineSection) { buffer.scanLineTable() }
        val result = mutableSetOf<String>()
        for (header in headers) {
            result.addAll(buffer.extractFileNames(header, lineSection))
        }
        return result
    }

    fun testV4DwarfFileNames() {
        val fileNames = extractAllFileNames(loadWasmResource("c/utf8-c.wasm"))
        assertTrue("Should extract file names from DWARF V4 data", fileNames.isNotEmpty())
        assertTrue("Should contain utf8.c", fileNames.any { it.endsWith("utf8.c") })
    }

    fun testV5DwarfFileNames() {
        val fileNames = extractAllFileNames(loadWasmResource("c/utf8-c.dwarf5.wasm"))
        assertTrue("Should extract file names from DWARF V5 data", fileNames.isNotEmpty())
        assertTrue("Should contain utf8.c", fileNames.any { it.endsWith("utf8.c") })
    }

    fun testNoDwarfSections() {
        val fileNames = extractAllFileNames(loadWasmResource("string.wasm"))
        assertTrue("Module without DWARF should produce no file names", fileNames.isEmpty())
    }
}

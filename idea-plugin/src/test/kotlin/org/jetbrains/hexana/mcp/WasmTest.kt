package org.jetbrains.hexana.mcp

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import java.io.FileInputStream
import kotlin.io.path.Path

abstract class WasmTest: UsefulTestCase() {
    val dataPath: String = "src/test/data/mcp/"

    private fun loadTestDataFile(section: String): String {
        return FileInputStream(getPathToTestDataFile(section)).bufferedReader().use { it.readText() }
    }

    open fun doTest(section:String, body: CommonByteBuffer.(WasmModule?) -> Unit) {
        val bytes = wat2wasm(loadTestDataFile(section))
        val commonByteBuffer = bytes.toCommonByteBuffer()
        val wasmModule = WasmParser().parse(commonByteBuffer)
        commonByteBuffer.body(wasmModule)

    }
    protected fun getPathToTestDataFile(section: String): String {
        return Path("${dataPath}/$section/${getTestName(true)}.wat").toAbsolutePath().toString()
    }
}
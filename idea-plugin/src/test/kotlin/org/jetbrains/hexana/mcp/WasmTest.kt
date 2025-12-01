package org.jetbrains.hexana.mcp

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.sections.WasmCodeLocalRecord
import org.jetbrains.hexana.wasm.sections.WasmElementSegment
import org.jetbrains.hexana.wasm.sections.WasmTableEntry
import java.io.FileInputStream
import kotlin.io.path.Path

abstract class WasmTest: UsefulTestCase() {
    open val dataPath: String = "src/test/data/mcp/"

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
    internal inline fun doMemTest(crossinline body: (List<MemoryRecord>) -> Unit) = doTest("memory") {
        listMemory(it).let(body)
    }
    internal inline fun doGlobalTest(crossinline body: (List<GlobalRecord>) -> Unit) = doTest("global") {
        listGlobals(it).let(body)
    }
    internal inline fun doExportTest(crossinline body: (List<ExportRecord>) -> Unit) = doTest("exports") {
        listExports(it).let(body)
    }
    internal inline fun doListDataSegments(crossinline body: (McpDataSegmentsReport) -> Unit) = doTest("listDataSegments") {
        listDataSegments(it).let(body)
    }
    internal inline fun doFunctionTest(crossinline body: (List<GeneralFunctionEntry>) -> Unit) = doTest("function") {
        listFunctions(it).let(body)
    }

    internal inline fun doOpcodeInFunctionTest(index: Int, crossinline body: (List<OpCode>?) -> Unit) = doTest("function") { wasmModule ->
        with(wasmModule!!.codeSection!!) {
            body(scanOpcodesForIndices(listOf(index))[index])
        }
    }

    internal inline fun doLocalsInFunctionTest(index: Int, crossinline body: (List<WasmCodeLocalRecord>?) -> Unit) = doTest("function") { wasmModule ->
        with(wasmModule!!.codeSection!!) {
            body(scanLocalsEntries(listOf(index))[index])
        }
    }

    internal inline fun doElementTest(crossinline body: (List<WasmElementSegment>) -> Unit) = doTest("element") {wasmModule ->
        with(wasmModule!!.elementSection!!) {
            body(scanElements())
        }
    }

    internal inline fun doTableTest(crossinline body: (List<WasmTableEntry>) -> Unit) = doTest("table") { wasmModule ->
        with(wasmModule!!.tableSection!!) {
            body(scanTable(wasmModule))
        }
    }

}
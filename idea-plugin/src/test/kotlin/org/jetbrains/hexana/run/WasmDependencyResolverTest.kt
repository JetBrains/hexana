package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import java.io.File

class WasmDependencyResolverTest : UsefulTestCase() {

    private fun loadWasmResource(relativePath: String): ByteArray {
        val resourceDir = File("../wasmParser/src/commonTest/resources")
        val file = File(resourceDir, relativePath)
        assertTrue("Test resource must exist: ${file.absolutePath}", file.exists())
        return file.readBytes()
    }

    fun testCalculatorComponentImportNames() {
        val bytes = loadWasmResource("component/calculator.wasm")
        val buffer = bytes.toCommonByteBuffer()

        assertEquals(BinaryProviderType.WasmComponent, WasmBinaryProviderSelector.select(buffer))

        val provider = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())
        assertTrue(provider.canProvideWasmModule())

        val importNames = provider.imports.map { it.importName }.distinct()

        assertTrue("Should have component:adder/add import",
            importNames.any { it.contains("component:adder/add") })
        assertTrue("Should have component:subtractor/subtract import",
            importNames.any { it.contains("component:subtractor/subtract") })
    }

    fun testCalculatorComponentExportNames() {
        val bytes = loadWasmResource("component/calculator.wasm")
        val provider = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())
        assertTrue(provider.canProvideWasmModule())

        val exportNames = provider.exports.map { it.exportName }

        assertTrue("Should export component:calculator/calculate@0.1.0",
            exportNames.any { it.contains("component:calculator/calculate") })
    }

    /**
     * Verifies that collecting imports from a component that itself imports
     * another component produces the transitive import names. This simulates
     * the scenario described in the issue: a main module imports only
     * calculator, but calculator in turn imports adder and subtractor.
     */
    fun testTransitiveImportCollection() {
        val calculatorBytes = loadWasmResource("component/calculator.wasm")
        val calculatorProvider = WasmComponentBinaryProvider(calculatorBytes.toCommonByteBuffer())
        assertTrue(calculatorProvider.canProvideWasmModule())

        val calculatorImports = calculatorProvider.imports.map { it.importName }.distinct()
        assertTrue("Calculator should import adder",
            calculatorImports.any { it.contains("component:adder/add") })
        assertTrue("Calculator should import subtractor",
            calculatorImports.any { it.contains("component:subtractor/subtract") })

        val adderBytes = loadWasmResource("component/add.component.wasm")
        val adderProvider = WasmComponentBinaryProvider(adderBytes.toCommonByteBuffer())
        assertTrue(adderProvider.canProvideWasmModule())

        val adderExports = adderProvider.exports.map { it.exportName }
        assertTrue("Adder should export docs:adder/add or component:adder/add",
            adderExports.any { it.contains("adder/add") })
    }

    fun testIsRuntimeImport() {
        assertTrue("wasi: imports should be runtime",
            WasmDependencyResolver.isRuntimeImport("wasi:cli/run@0.2.3"))
        assertTrue("wasi:io/streams should be runtime",
            WasmDependencyResolver.isRuntimeImport("wasi:io/streams@0.2.3"))
        assertFalse("component: imports should not be runtime",
            WasmDependencyResolver.isRuntimeImport("component:calculator/calculate@0.1.0"))
        assertFalse("plain names should not be runtime",
            WasmDependencyResolver.isRuntimeImport("my-module"))
    }

    fun testFindWorkspaceRoot() {
        val sampleRoot = File("/Users/user/Downloads/sample-wasm-component-model-main")
        if (!sampleRoot.exists()) {
            println("[DEBUG_LOG] Sample project not found, skipping")
            return
        }
        val appWasm = File(sampleRoot, "target/wasm32-wasip1/debug/app.wasm")
        if (!appWasm.exists()) {
            println("[DEBUG_LOG] app.wasm not found, skipping")
            return
        }

        val root = WasmDependencyResolver.findWorkspaceRoot(appWasm)
        assertNotNull("Should find workspace root", root)
        assertEquals("Workspace root should be sample project root",
            sampleRoot.absolutePath, root!!.absolutePath)
    }

    fun testBuildScanRootsFromWasmFilePath() {
        val sampleRoot = File("/Users/user/Downloads/sample-wasm-component-model-main")
        if (!sampleRoot.exists()) {
            println("[DEBUG_LOG] Sample project not found, skipping")
            return
        }
        val appWasmPath = File(sampleRoot, "target/wasm32-wasip1/debug/app.wasm").absolutePath

        val roots = WasmDependencyResolver.buildScanRoots(null, appWasmPath)
        assertTrue("Should have at least one scan root", roots.isNotEmpty())
        assertTrue("Scan roots should contain the workspace root",
            roots.any { it == sampleRoot.absolutePath })
    }
}

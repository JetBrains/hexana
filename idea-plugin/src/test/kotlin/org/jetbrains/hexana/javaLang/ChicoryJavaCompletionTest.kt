package org.jetbrains.hexana.javaLang

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

/**
 * Completion tests for Chicory WebAssembly runtime API patterns.
 *
 * Tests verify that the completion contributor provides WASM export names
 * inside `instance.export("...")` and WASM import module/function names
 * inside `new HostFunction("module", "func", ...)` when a `.wasm` file is
 * referenced via `Parser.parse(...)`.
 *
 * Test data:
 * - `simple.wasm`: exports `exported_func` (0 params), imports `my_namespace.imported_func` (1 i32 param)
 * - `add.wasm`: exports `add` (2 i32 params -> i32)
 */
class ChicoryJavaCompletionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String {
        return Path.of("src/test/data/javaLang/chicory").toAbsolutePath().toString()
    }

    // -- Export completion: instance.export() --

    fun testExportCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ExportCall.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "exported_func")
    }

    fun testExportCompletionDoesNotSuggestImports() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ExportCall.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertDoesNotContain(lookupStrings!!, "imported_func")
    }

    fun testExportCompletionForAddWasm() {
        myFixture.copyFileToProject("add.wasm")
        myFixture.configureByFile("ExportCallAdd.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "add")
    }

    // -- Export completion: Parser.parse(new File(...)) --

    fun testExportCompletionFromFileConstructor() {
        myFixture.copyFileToProject("add.wasm")
        myFixture.configureByFile("ExportFromFile.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "add")
    }

    // -- Import completion: HostFunction module name --

    fun testHostFunctionModuleNameCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("HostFunctionImport.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "my_namespace")
    }

    fun testHostFunctionModuleNameDoesNotSuggestExports() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("HostFunctionImport.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertDoesNotContain(lookupStrings!!, "exported_func")
    }

    // -- Import completion: HostFunction function name --

    fun testHostFunctionFuncNameCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("HostFunctionImportFuncName.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "imported_func")
    }

    fun testHostFunctionFuncNameDoesNotSuggestModuleNames() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("HostFunctionImportFuncName.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertDoesNotContain(lookupStrings!!, "my_namespace")
    }

    // -- Negative: no WASM reference --

    fun testNoCompletionWithoutWasmReference() {
        myFixture.configureByFile("NoWasmReference.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        if (lookupStrings != null) {
            assertDoesNotContain(lookupStrings, "exported_func")
            assertDoesNotContain(lookupStrings, "my_namespace")
        }
    }

    private fun <T> assertDoesNotContain(collection: Collection<T>, vararg elements: T) {
        for (element in elements) {
            assertFalse(
                "Collection should not contain '$element' but does: $collection",
                collection.contains(element)
            )
        }
    }
}

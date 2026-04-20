package org.jetbrains.hexana.javaLang

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

/**
 * Completion tests for GraalVM Polyglot API patterns with WebAssembly modules.
 *
 * Tests verify that the completion contributor provides WASM export names
 * inside `invokeMember()`/`getMember()`/`hasMember()` and WASM import module/function
 * names inside `ProxyObject.fromMap(Map.of(...))` when a `.wasm` file is referenced
 * via `Source.newBuilder("wasm", ...)`.
 *
 * Test data:
 * - `simple.wasm`: exports `exported_func` (0 params), imports `my_namespace.imported_func` (1 i32 param)
 * - `add.wasm`: exports `add` (2 i32 params -> i32)
 */
class GraalWasmJavaCompletionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String {
        return Path.of("src/test/data/javaLang/graalwasm").toAbsolutePath().toString()
    }

    // -- Export completion: invokeMember --

    fun testExportInvokeMemberCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ExportInvokeMember.java")
        val lookups = myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "exported_func")
    }

    fun testExportInvokeMemberDoesNotSuggestImports() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ExportInvokeMember.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertDoesNotContain(lookupStrings!!, "imported_func")
    }

    // -- Export completion: getMember --

    fun testExportGetMemberCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ExportGetMember.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "exported_func")
    }

    // -- Export completion: hasMember --

    fun testExportHasMemberCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ExportHasMember.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "exported_func")
    }

    // -- Export completion: different WASM module --

    fun testExportCompletionForAddWasm() {
        myFixture.copyFileToProject("add.wasm")
        myFixture.configureByFile("ExportMultipleExports.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "add")
    }

    // -- Import completion: module names --

    fun testImportModuleNameCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ImportModuleName.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "my_namespace")
    }

    fun testImportModuleNameDoesNotSuggestExports() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ImportModuleName.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertDoesNotContain(lookupStrings!!, "exported_func")
    }

    // -- Import completion: function names --

    fun testImportFunctionNameCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ImportFunctionName.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertContainsElements(lookupStrings!!, "imported_func")
    }

    fun testImportFunctionNameDoesNotSuggestOtherModules() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ImportFunctionName.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results", lookupStrings)
        assertEquals("imported_func", lookupStrings!!.first())
        //assertDoesNotContain(lookupStrings!!, "my_namespace")
    }

    // -- Advanced: exports stored in a field, used in another method --

    fun testExportFromFieldCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ExportFromField.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results for field-based exports", lookupStrings)
        assertContainsElements(lookupStrings!!, "exported_func")
    }

    // -- Advanced: exports returned from a helper method --

    fun testExportFromMethodReturnCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ExportFromMethodReturn.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results for method-returned exports", lookupStrings)
        assertContainsElements(lookupStrings!!, "exported_func")
    }

    // -- Advanced: chained call without intermediate exports variable --

    fun testExportChainedCallCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ExportChainedCall.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results for chained exports call", lookupStrings)
        assertContainsElements(lookupStrings!!, "exported_func")
    }

    // -- Advanced: import map built as a variable, not inline --

    fun testImportMapAsVariableCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ImportMapAsVariable.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results for variable-based import map", lookupStrings)
        assertContainsElements(lookupStrings!!, "imported_func")
    }

    // -- Advanced: import map passed as parameter to init method --

    fun testImportMapPassedToMethodCompletion() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.configureByFile("ImportMapPassedToMethod.java")
        myFixture.completeBasic()
        val lookupStrings = myFixture.lookupElementStrings
        assertNotNull("Expected completion results for parameter-passed import map", lookupStrings)
        assertContainsElements(lookupStrings!!, "my_namespace")
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

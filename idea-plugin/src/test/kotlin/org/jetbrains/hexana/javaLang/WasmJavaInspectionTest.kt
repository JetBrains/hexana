package org.jetbrains.hexana.javaLang

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

/**
 * Inspection tests for Java code working with WebAssembly modules.
 *
 * Since GraalVM and Chicory libraries are not on the test classpath, we cannot
 * use `testHighlighting` (it would fail on "Cannot resolve symbol" errors for
 * the library classes). Instead, we enable only our inspection, run highlighting,
 * and filter the results for our specific warning descriptions.
 *
 * Test data:
 * - `simple.wasm`: exports `exported_func` (0 params), imports `my_namespace.imported_func` (1 i32 param)
 * - `add.wasm`: exports `add` (2 i32 params -> i32)
 */
class WasmJavaInspectionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String {
        return Path.of("src/test/data/javaLang/inspections").toAbsolutePath().toString()
    }

    private fun getWarnings(inspection: LocalInspectionTool, fileName: String): List<String> {
        myFixture.enableInspections(inspection)
        myFixture.configureByFile(fileName)
        val highlights = myFixture.doHighlighting()
        return highlights
            .filter { it.description != null && it.severity.name == "WARNING" }
            .mapNotNull { it.description }
    }

    // -- GraalWasm: unresolved export --

    fun testGraalWasmUnresolvedExport() {
        myFixture.copyFileToProject("simple.wasm")
        val warnings = getWarnings(WasmExportInspection(), "GraalWasmUnresolvedExport.java")

        assertContainsElements(warnings, "Export 'nonexistent_func' not found in WebAssembly module")
        assertContainsElements(warnings, "Export 'bad_export' not found in WebAssembly module")
        // "exported_func" is valid — should NOT produce a warning
        assertFalse(
            "Valid export 'exported_func' should not be flagged",
            warnings.any { it.contains("exported_func") }
        )
    }

    fun testGraalWasmUnresolvedExportSkipsHasMember() {
        myFixture.copyFileToProject("simple.wasm")
        val warnings = getWarnings(WasmExportInspection(), "GraalWasmUnresolvedExport.java")

        // hasMember("nonexistent_func") should NOT produce a warning
        val hasMemberWarnings = warnings.count { it.contains("nonexistent_func") }
        assertEquals("Only invokeMember should warn for 'nonexistent_func', not hasMember", 1, hasMemberWarnings)
    }

    // -- GraalWasm: argument count mismatch --

    fun testGraalWasmArgCountMismatch() {
        myFixture.copyFileToProject("add.wasm")
        val warnings = getWarnings(WasmExportArgCountInspection(), "GraalWasmArgCountMismatch.java")

        assertContainsElements(warnings, "Function 'add' expects 2 argument(s), but 0 provided")
        assertContainsElements(warnings, "Function 'add' expects 2 argument(s), but 3 provided")
        // Correct call with 2 args should NOT produce a warning
        assertFalse(
            "Correct 2-arg call should not be flagged",
            warnings.any { it.contains("but 2 provided") }
        )
    }

    // -- GraalWasm: unresolved import --

    fun testGraalWasmUnresolvedImport() {
        myFixture.copyFileToProject("simple.wasm")
        val warnings = getWarnings(WasmImportInspection(), "GraalWasmUnresolvedImport.java")

        assertContainsElements(warnings, "Import module 'bad_module' not found in WebAssembly module")
        assertContainsElements(warnings, "Import function 'bad_func' not found in module 'my_namespace'")
        // Valid names should NOT produce warnings
        assertFalse(
            "Valid module 'my_namespace' should not be flagged",
            warnings.any { it.contains("'my_namespace' not found") }
        )
        assertFalse(
            "Valid function 'imported_func' should not be flagged",
            warnings.any { it.contains("'imported_func' not found") }
        )
    }

    // -- Chicory: unresolved export --

    fun testChicoryUnresolvedExport() {
        myFixture.copyFileToProject("simple.wasm")
        val warnings = getWarnings(WasmExportInspection(), "ChicoryUnresolvedExport.java")

        assertContainsElements(warnings, "Export 'nonexistent_func' not found in WebAssembly module")
        assertFalse(
            "Valid export 'exported_func' should not be flagged",
            warnings.any { it.contains("exported_func") }
        )
    }

    // -- Chicory: argument count mismatch --

    fun testChicoryArgCountMismatch() {
        myFixture.copyFileToProject("add.wasm")
        val warnings = getWarnings(WasmExportArgCountInspection(), "ChicoryArgCountMismatch.java")

        assertContainsElements(warnings, "Function 'add' expects 2 argument(s), but 0 provided")
        assertContainsElements(warnings, "Function 'add' expects 2 argument(s), but 3 provided")
    }

    // -- GraalWasm: argument type mismatch --

    fun testGraalWasmArgTypeMismatchStringForI32() {
        myFixture.copyFileToProject("add.wasm")
        val warnings = getWarnings(WasmExportArgTypeInspection(), "GraalWasmArgTypeMismatch.java")

        // String arg for i32 param
        assertTrue(
            "String arg should be flagged as incompatible with i32",
            warnings.any { it.contains("String") && it.contains("i32") }
        )
    }

    fun testGraalWasmArgTypeMismatchDoubleForI32() {
        myFixture.copyFileToProject("add.wasm")
        val warnings = getWarnings(WasmExportArgTypeInspection(), "GraalWasmArgTypeMismatch.java")

        // double arg for i32 param
        assertTrue(
            "double arg should be flagged as incompatible with i32",
            warnings.any { it.contains("double") && it.contains("i32") }
        )
    }

    fun testGraalWasmArgTypeMismatchFloatForI32() {
        myFixture.copyFileToProject("add.wasm")
        val warnings = getWarnings(WasmExportArgTypeInspection(), "GraalWasmArgTypeMismatch.java")

        // float arg for i32 param
        assertTrue(
            "float arg should be flagged as incompatible with i32",
            warnings.any { it.contains("float") && it.contains("i32") }
        )
    }

    fun testGraalWasmArgTypeMismatchLongForI32() {
        myFixture.copyFileToProject("add.wasm")
        val warnings = getWarnings(WasmExportArgTypeInspection(), "GraalWasmArgTypeMismatch.java")

        // long arg for i32 param
        assertTrue(
            "long arg should be flagged as incompatible with i32",
            warnings.any { it.contains("long") && it.contains("i32") }
        )
    }

    fun testGraalWasmArgTypeCorrectIntForI32() {
        myFixture.copyFileToProject("add.wasm")
        val warnings = getWarnings(WasmExportArgTypeInspection(), "GraalWasmArgTypeMismatch.java")

        // Correct int args — should NOT flag the first invokeMember("add", 1, 2)
        // and short/byte are also compatible with i32
        assertFalse(
            "int args should not be flagged for i32 params",
            warnings.any { it.contains("'int'") && it.contains("i32") }
        )
        assertFalse(
            "short args should not be flagged for i32 params",
            warnings.any { it.contains("'short'") && it.contains("i32") }
        )
        assertFalse(
            "byte args should not be flagged for i32 params",
            warnings.any { it.contains("'byte'") && it.contains("i32") }
        )
    }
}

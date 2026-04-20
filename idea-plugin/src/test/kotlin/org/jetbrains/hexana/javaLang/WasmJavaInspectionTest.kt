package org.jetbrains.hexana.javaLang

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

/**
 * Inspection tests for Java code working with WebAssembly modules.
 *
 * Tests verify that inspections flag:
 * - Unresolved export names (invokeMember/getMember/instance.export with a name not in the WASM exports)
 * - Argument count mismatches (wrong number of args for a known WASM function signature)
 * - Unresolved import module/function names (ProxyObject.fromMap / HostFunction with bad names)
 *
 * Test data uses `<warning descr="...">` markers in .java files for expected highlights.
 *
 * Test data:
 * - `simple.wasm`: exports `exported_func` (0 params), imports `my_namespace.imported_func` (1 i32 param)
 * - `add.wasm`: exports `add` (2 i32 params -> i32)
 */
class WasmJavaInspectionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String {
        return Path.of("src/test/data/javaLang/inspections").toAbsolutePath().toString()
    }

    // -- GraalWasm: unresolved export --

    fun testGraalWasmUnresolvedExport() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.enableInspections(WasmExportInspection())
        myFixture.testHighlighting(true, false, false, "GraalWasmUnresolvedExport.java")
    }

    // -- GraalWasm: argument count mismatch --

    fun testGraalWasmArgCountMismatch() {
        myFixture.copyFileToProject("add.wasm")
        myFixture.enableInspections(WasmExportArgCountInspection())
        myFixture.testHighlighting(true, false, false, "GraalWasmArgCountMismatch.java")
    }

    // -- GraalWasm: unresolved import --

    fun testGraalWasmUnresolvedImport() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.enableInspections(WasmImportInspection())
        myFixture.testHighlighting(true, false, false, "GraalWasmUnresolvedImport.java")
    }

    // -- Chicory: unresolved export --

    fun testChicoryUnresolvedExport() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.enableInspections(WasmExportInspection())
        myFixture.testHighlighting(true, false, false, "ChicoryUnresolvedExport.java")
    }

    // -- Chicory: argument count mismatch --

    fun testChicoryArgCountMismatch() {
        myFixture.copyFileToProject("add.wasm")
        myFixture.enableInspections(WasmExportArgCountInspection())
        myFixture.testHighlighting(true, false, false, "ChicoryArgCountMismatch.java")
    }
}

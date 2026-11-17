package org.jetbrains.hexana.javaLang

import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

/**
 * Tests for [JavaWasmReferenceIndex] — the file-based index that tracks
 * `.wasm` module references in Java source files.
 *
 * Covers:
 * - Reverse lookup: given a Java file, find indexed WASM filenames
 * - Forward lookup: given a WASM filename, find Java files referencing it
 * - Resolution: [findReferencedWasmFile] / [findAllReferencedWasmFiles] resolve to VirtualFiles
 * - Edge cases: no references, comments, path-based references, multiple modules
 *
 * Test data:
 * - `simple.wasm`: exports `exported_func`, imports `my_namespace.imported_func`
 * - `add.wasm`: exports `add` (2 i32 params -> i32)
 */
class JavaWasmReferenceIndexTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String {
        return Path.of("src/test/data/javaLang/index").toAbsolutePath().toString()
    }

    // -- Reverse lookup: Java file → WASM filenames --

    fun testGraalWasmSingleModuleIsIndexed() {
        val javaFile = myFixture.copyFileToProject("GraalWasmSingleModule.java")
        val names = getIndexedWasmFileNames(javaFile, project)
        assertEquals(setOf("simple.wasm"), names)
    }

    fun testChicorySingleModuleIsIndexed() {
        val javaFile = myFixture.copyFileToProject("ChicorySingleModule.java")
        val names = getIndexedWasmFileNames(javaFile, project)
        assertEquals(setOf("simple.wasm"), names)
    }

    fun testChicoryFileConstructorIsIndexed() {
        val javaFile = myFixture.copyFileToProject("ChicoryFileConstructor.java")
        val names = getIndexedWasmFileNames(javaFile, project)
        assertEquals(setOf("add.wasm"), names)
    }

    fun testMultipleModulesAreIndexed() {
        val javaFile = myFixture.copyFileToProject("MultipleModules.java")
        val names = getIndexedWasmFileNames(javaFile, project)
        assertEquals(setOf("simple.wasm", "add.wasm"), names)
    }

    fun testNoWasmReferenceProducesEmptyIndex() {
        val javaFile = myFixture.copyFileToProject("NoWasmReference.java")
        val names = getIndexedWasmFileNames(javaFile, project)
        assertTrue("Expected no WASM references, got: $names", names.isEmpty())
    }

    fun testResourcePathIsIndexed() {
        val javaFile = myFixture.copyFileToProject("ResourcePath.java")
        val names = getIndexedWasmFileNames(javaFile, project)
        assertEquals(setOf("modules/floyd.wasm"), names)
    }

    fun testWasmInCommentIsAlsoIndexed() {
        // The regex-based indexer picks up "commented.wasm" from comments too.
        // This is intentional: false positives are cheap and filtered by resolution.
        // The real .wasm reference ("real.wasm") must be indexed.
        val javaFile = myFixture.copyFileToProject("WasmInComment.java")
        val names = getIndexedWasmFileNames(javaFile, project)
        assertContainsElements(names, "real.wasm")
    }

    // -- Forward lookup: WASM filename → Java files --

    fun testForwardLookupFindsSingleFile() {
        myFixture.copyFileToProject("GraalWasmSingleModule.java")
        val files = findJavaFilesReferencingWasm("simple.wasm", project)
        assertEquals(1, files.size)
        assertEquals("GraalWasmSingleModule.java", files.first().name)
    }

    fun testForwardLookupFindsMultipleFiles() {
        myFixture.copyFileToProject("TwoFilesReferSameModule_A.java")
        myFixture.copyFileToProject("TwoFilesReferSameModule_B.java")
        val files = findJavaFilesReferencingWasm("simple.wasm", project)
        assertEquals(2, files.size)
        val names = files.map { it.name }.toSet()
        assertContainsElements(names, "TwoFilesReferSameModule_A.java", "TwoFilesReferSameModule_B.java")
    }

    fun testForwardLookupReturnsEmptyForUnknownModule() {
        myFixture.copyFileToProject("GraalWasmSingleModule.java")
        val files = findJavaFilesReferencingWasm("nonexistent.wasm", project)
        assertTrue("Expected no files for unknown module, got: ${files.map { it.name }}", files.isEmpty())
    }

    // -- Resolution: findReferencedWasmFile / findAllReferencedWasmFiles --

    fun testFindReferencedWasmFileResolvesToVirtualFile() {
        myFixture.copyFileToProject("simple.wasm")
        val javaVf = myFixture.copyFileToProject("GraalWasmSingleModule.java")
        val psiFile = PsiManager.getInstance(project).findFile(javaVf) as PsiJavaFile

        val wasmFile = findReferencedWasmFile(psiFile)
        assertNotNull("Expected resolved WASM VirtualFile", wasmFile)
        assertEquals("simple.wasm", wasmFile!!.name)
    }

    fun testFindReferencedWasmFileReturnsNullWhenModuleMissing() {
        // Java file references "simple.wasm" but we don't add it to the project
        val javaVf = myFixture.copyFileToProject("GraalWasmSingleModule.java")
        val psiFile = PsiManager.getInstance(project).findFile(javaVf) as PsiJavaFile

        val wasmFile = findReferencedWasmFile(psiFile)
        assertNull("Expected null when WASM file is not in project", wasmFile)
    }

    fun testFindAllReferencedWasmFilesResolvesMultiple() {
        myFixture.copyFileToProject("simple.wasm")
        myFixture.copyFileToProject("add.wasm")
        val javaVf = myFixture.copyFileToProject("MultipleModules.java")
        val psiFile = PsiManager.getInstance(project).findFile(javaVf) as PsiJavaFile

        val wasmFiles = findAllReferencedWasmFiles(psiFile)
        assertEquals(2, wasmFiles.size)
        val names = wasmFiles.map { it.name }.toSet()
        assertContainsElements(names, "simple.wasm", "add.wasm")
    }

    fun testFindAllReferencedWasmFilesReturnsEmptyForNoReferences() {
        val javaVf = myFixture.copyFileToProject("NoWasmReference.java")
        val psiFile = PsiManager.getInstance(project).findFile(javaVf) as PsiJavaFile

        val wasmFiles = findAllReferencedWasmFiles(psiFile)
        assertTrue("Expected empty list, got: ${wasmFiles.map { it.name }}", wasmFiles.isEmpty())
    }

    fun testFindReferencedWasmFileChicoryPattern() {
        myFixture.copyFileToProject("simple.wasm")
        val javaVf = myFixture.copyFileToProject("ChicorySingleModule.java")
        val psiFile = PsiManager.getInstance(project).findFile(javaVf) as PsiJavaFile

        val wasmFile = findReferencedWasmFile(psiFile)
        assertNotNull("Expected resolved WASM VirtualFile for Chicory pattern", wasmFile)
        assertEquals("simple.wasm", wasmFile!!.name)
    }

    fun testFindReferencedWasmFileChicoryFileConstructor() {
        myFixture.copyFileToProject("add.wasm")
        val javaVf = myFixture.copyFileToProject("ChicoryFileConstructor.java")
        val psiFile = PsiManager.getInstance(project).findFile(javaVf) as PsiJavaFile

        val wasmFile = findReferencedWasmFile(psiFile)
        assertNotNull("Expected resolved WASM VirtualFile for Chicory File pattern", wasmFile)
        assertEquals("add.wasm", wasmFile!!.name)
    }

    // -- Index version --

    fun testIndexVersionIsStable() {
        val index = JavaWasmReferenceIndex()
        assertEquals("Index version should be 1", 1, index.version)
    }
}

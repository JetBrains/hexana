package org.jetbrains.hexana.javaLang

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor

val JavaWasmReferenceIndexId: ID<String, Void?> = ID.create("hexana.JavaWasmReferenceIndex")

private val WASM_LITERAL_PATTERN = Regex(""""([^"]*\.wasm)"""")

/**
 * File-based index that tracks `.wasm` module references in Java source files.
 *
 * Scans Java files for string literals ending with `.wasm` and indexes them,
 * enabling fast lookup in both directions:
 * - **Forward**: given a WASM filename, find all Java files that reference it
 *   via [findJavaFilesReferencingWasm]
 * - **Reverse**: given a Java file, find all WASM filenames it references
 *   via [getIndexedWasmFileNames]
 *
 * Detected patterns include:
 * - GraalVM:  `Source.newBuilder("wasm", Foo.class.getResource("module.wasm"))`
 * - Chicory:  `Parser.parse(Foo.class.getResourceAsStream("module.wasm"))`
 * - Any other Java string literal containing a `.wasm` filename
 */
class JavaWasmReferenceIndex : ScalarIndexExtension<String>() {
    override fun getName() = JavaWasmReferenceIndexId

    override fun getIndexer() = DataIndexer<String, Void?, FileContent> { inputData ->
        val content = inputData.contentAsText
        val result = mutableMapOf<String, Void?>()
        for (match in WASM_LITERAL_PATTERN.findAll(content)) {
            result[match.groupValues[1]] = null
        }
        result
    }

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType == JavaFileType.INSTANCE }

    override fun dependsOnFileContent() = true

    override fun getVersion(): Int = 1
}

/**
 * Returns all `.wasm` filenames indexed from the given Java [file].
 */
internal fun getIndexedWasmFileNames(file: VirtualFile, project: com.intellij.openapi.project.Project): Set<String> {
    return FileBasedIndex.getInstance()
        .getFileData(JavaWasmReferenceIndexId, file, project)
        .keys
}

/**
 * Finds all Java files in the project that reference the given [wasmFileName].
 */
internal fun findJavaFilesReferencingWasm(
    wasmFileName: String,
    project: com.intellij.openapi.project.Project
): Collection<VirtualFile> {
    return FileBasedIndex.getInstance()
        .getContainingFiles(JavaWasmReferenceIndexId, wasmFileName, GlobalSearchScope.projectScope(project))
}

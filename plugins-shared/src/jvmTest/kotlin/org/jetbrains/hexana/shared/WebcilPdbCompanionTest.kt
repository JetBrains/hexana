package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PdbMatch
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WebcilAssembly
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding the Portable PDB next to a Blazor WebAssembly assembly, including SDK-fingerprinted names. */
class WebcilPdbCompanionTest {
    private val dir = File("../parsing/native-fixtures/test-data/pe").canonicalFile

    /** The fixtures directory, optionally seen as [renamed] (shown name → real fixture file). */
    private class FixtureFileSystem(private val dir: File, private val renamed: Map<String, String> = emptyMap()) : FileSystem {
        val reads = mutableListOf<String>()
        private fun real(path: String): File = renamed[File(path).name]?.let { dir.resolve(it) } ?: File(path)
        override fun listEntries(dir: String): List<FileEntry> =
            (File(dir).list().orEmpty().toList() + renamed.keys).map { FileEntry(it, isDirectory = false, isFile = true) }
        override fun readFile(path: String): ByteArray? { reads += path; return real(path).takeIf { it.isFile }?.readBytes() }
        override fun fileExists(path: String): Boolean = real(path).isFile
        override fun parentDir(path: String): String = File(path).parent
        override fun joinPath(base: String, child: String): String = File(base, child).path
        override fun pathRoot(path: String): String = "/"
        override fun regularFileSize(path: String): Long? = real(path).takeIf { it.isFile }?.length()
    }

    private fun assembly(name: String): WebcilAssembly =
        assertNotNull(assertIs<WasmBinaryProvider>(createBinaryProvider(dir.resolve(name).readBytes().toCommonByteBuffer(), name)).webcil)

    @Test
    fun `loads the PDB the CodeView record names`() {
        val assembly = assembly("sample-clr-portable.webcil.wasm")
        val path = dir.resolve("sample-clr-portable.webcil.wasm").path
        assertEquals(dir.resolve("sample-clr-portable.pdb").path, PdbCompanionLoader.candidatePaths(assembly, path).first())
        assertEquals(PdbMatch.Matches, PdbCompanionLoader.load(assembly, path, FixtureFileSystem(dir)))
        assertNotNull(assembly.portablePdb)
        // Attached: nothing more to look for.
        assertEquals(emptyList(), PdbCompanionLoader.candidatePaths(assembly, path))
    }

    @Test
    fun `finds the fingerprinted PDB of a fingerprinted module through the directory listing`() {
        // As published: `sample-clr-portable.ab12cd34ef.wasm` next to `sample-clr-portable.zz98yy76xx.pdb`.
        val fs = FixtureFileSystem(
            dir,
            mapOf(
                "sample-clr-portable.ab12cd34ef.wasm" to "sample-clr-portable.webcil.wasm",
                "sample-clr-portable.zz98yy76xx.pdb" to "sample-clr-portable.pdb",
                "sample-clr-portable.Extra.zz98yy76xx.pdb" to "sample-clr-portable.pdb",
            ),
        )
        val assembly = assembly("sample-clr-portable.webcil.wasm")
        val path = dir.resolve("sample-clr-portable.ab12cd34ef.wasm").path
        val siblings = fs.listEntries(dir.path).map { it.name }
        val candidates = PdbCompanionLoader.candidatePaths(assembly, path, siblings).map { File(it).name }
        assertTrue("sample-clr-portable.zz98yy76xx.pdb" in candidates, candidates.toString())
        assertTrue("sample-clr-portable.Extra.zz98yy76xx.pdb" !in candidates, candidates.toString())
        assertEquals(PdbMatch.Matches, PdbCompanionLoader.load(assembly, path, fs))
    }

    @Test
    fun `modules without a CodeView record or with an embedded PDB look for nothing`() {
        val fs = FixtureFileSystem(dir)
        val plain = assembly("sample-clr.webcil.wasm")
        assertEquals(emptyList(), PdbCompanionLoader.candidatePaths(plain, dir.resolve("sample-clr.webcil.wasm").path))
        assertNull(PdbCompanionLoader.load(plain, dir.resolve("sample-clr.webcil.wasm").path, fs))
        val embedded = assembly("sample-clr-embedded.webcil.wasm")
        assertNull(PdbCompanionLoader.load(embedded, dir.resolve("sample-clr-embedded.webcil.wasm").path, fs))
        assertEquals(emptyList(), fs.reads)
    }

    @Test
    fun `a PDB of another build is rejected`() {
        val assembly = assembly("sample-clr-portable.webcil.wasm")
        assertEquals(PdbMatch.Unreadable, PdbCompanionLoader.attach(assembly, ByteArray(64)))
        assertEquals(PdbMatch.WrongKind, PdbCompanionLoader.attach(assembly, dir.resolve("sample.pdb").readBytes()))
        assertNull(assembly.portablePdb)
    }
}

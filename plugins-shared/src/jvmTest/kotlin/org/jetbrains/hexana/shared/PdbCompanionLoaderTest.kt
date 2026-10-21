package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PdbMatch
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Loading the PDB next to a PE fixture, and what the tabs show with it. */
class PdbCompanionLoaderTest {
    private val dir = File("../parsing/native-fixtures/test-data/pe").canonicalFile

    /** A file system over the fixtures directory, with optional replacement file contents. */
    private class FixtureFileSystem(
        private val overrides: Map<String, ByteArray> = emptyMap(),
        private val sizes: Map<String, Long?> = emptyMap(),
    ) : FileSystem {
        val reads = mutableListOf<String>()
        override fun listEntries(dir: String): List<FileEntry> = emptyList()
        override fun readFile(path: String): ByteArray? { reads += path; return overrides[path] ?: File(path).takeIf { it.isFile }?.readBytes() }
        override fun fileExists(path: String): Boolean = path in overrides || File(path).isFile
        override fun parentDir(path: String): String = File(path).parent
        override fun joinPath(base: String, child: String): String = File(base, child).path
        override fun pathRoot(path: String): String = "/"
        override fun regularFileSize(path: String): Long? =
            if (path in sizes) sizes[path] else overrides[path]?.size?.toLong() ?: File(path).takeIf { it.isFile }?.length()
    }

    private fun pe(name: String, bytes: ByteArray = dir.resolve(name).readBytes()): PeBinaryProvider =
        assertIs<PeBinaryProvider>(createBinaryProvider(bytes.toCommonByteBuffer(), dir.resolve(name).path, name))

    private fun TableTabDescriptor.pdbRow(): Int = (0 until rowCount).single { cell(it, "Kind") == "PDB symbols" }

    private val tabs = NativeTableTabsProvider()
    private fun PeBinaryProvider.tab(name: String, fs: FileSystem) =
        tabs.singleTab(name, this, TableTabsContext(nativeDebugData = NativeDebugTabData.scan(this, fs)))

    private fun TableTabDescriptor.cell(row: Int, column: String) = columns.single { it.name == column }.cellProvider(row).representation
    private fun TableTabDescriptor.value(field: String) = cell((0 until rowCount).single { cell(it, "Field") == field }, "Value")

    @Test
    fun `candidate paths put the recorded name next to the image first`() {
        val exe = pe("sample.exe")
        assertEquals(
            // The recorded `/out/pe/sample.pdb` contributes its file name only.
            listOf(dir.resolve("sample.pdb").path, dir.resolve("sample.exe.pdb").path),
            PdbCompanionLoader.candidatePaths(exe),
        )
        // Nothing to look for: no CodeView record, or the PDB is embedded.
        assertEquals(emptyList(), PdbCompanionLoader.candidatePaths(pe("sample-clr.exe")))
        assertEquals(emptyList(), PdbCompanionLoader.candidatePaths(pe("sample-clr-embedded.exe")))
    }

    @Test
    fun `windows PDB next to the image is loaded and shown`() {
        val exe = pe("sample.exe")
        val fs = FixtureFileSystem()
        assertEquals(PdbMatch.Matches, PdbCompanionLoader.load(exe, fs))
        assertEquals(listOf(dir.resolve("sample.pdb").path), fs.reads)
        assertTrue(PdbCompanionLoader.candidatePaths(exe).isEmpty(), "nothing left to load once attached")

        val context = assertNotNull(exe.tab("Debug Context", fs))
        val row = (0 until context.rowCount).single { context.cell(it, "Kind") == "PDB symbols" }
        assertEquals("found", context.cell(row, "Status"))
        assertEquals(dir.resolve("sample.pdb").path, context.cell(row, "Location"))
        assertEquals("Loaded: the PDB matches the image (AEBD2BE3-8B00-6D1E-4C4C-44205044422E, age 1).", context.cell(row, "Notes"))

        val info = assertNotNull(exe.tab("Debug Info", fs))
        assertEquals("Windows PDB: ${dir.resolve("sample.pdb").path}", info.value("Symbols"))
        assertEquals("12", info.value("Line rows"))
        assertEquals("0x140001000", info.value("First VA"))
        assertTrue("Debug Info" in tabs.availableTabNames(exe, TableTabsContext())!!.first)
    }

    @Test
    fun `a rebuilt PDB is reported, not loaded`() {
        val pdbPath = dir.resolve("sample.pdb").path
        val rebuilt = dir.resolve("sample.pdb").readBytes().also { it[12 * 4096 + 8] = 2 } // DBI stream (block 12) age
        val exe = pe("sample.exe")
        val fs = FixtureFileSystem(mapOf(pdbPath to rebuilt))
        assertEquals(PdbMatch.AgeMismatch, PdbCompanionLoader.load(exe, fs))
        assertNull(exe.debugSymbols)
        assertNull(exe.nativeSourceLocationProvider)

        val context = assertNotNull(exe.tab("Debug Context", fs))
        val row = (0 until context.rowCount).single { context.cell(it, "Kind") == "PDB symbols" }
        assertEquals("does not match", context.cell(row, "Status"))
        assertTrue(context.cell(row, "Notes").startsWith("Not loaded: the PDB age differs"), context.cell(row, "Notes"))
        assertNull(exe.tab("Debug Info", fs))
    }

    @Test
    fun `portable PDB adds source lines to methods and IL`() {
        val exe = pe("sample-clr-portable.exe")
        assertEquals(PdbMatch.Matches, PdbCompanionLoader.load(exe, FixtureFileSystem()))

        val methods = assertNotNull(tabs.singleTab("Methods", exe, TableTabsContext()))
        val greeterCtor = (0 until methods.rowCount).single { methods.cell(it, "Token") == "0x06000002" }
        assertEquals("Sample.cs:19", methods.cell(greeterCtor, "Source"))

        val metadata = assertNotNull(exe.clrMetadata)
        val types = clrIlTypes(metadata)
        val listing = types.flatMap { clrIlTypeRows(metadata, exe.bytes, it, exe.portablePdb) }.map { it.text.trim() }
        assertTrue(listing.any { it.endsWith(".line 19,19 : 9,38 '/src/clr/Sample.cs'") }, listing.filter { ".line" in it }.toString())
        assertTrue(listing.any { it.endsWith(".line 19,19 : 39,40") }, "the document is named only where it changes")

        val info = assertNotNull(exe.tab("Debug Info", FixtureFileSystem()))
        assertEquals("/src/clr/Sample.cs", info.value("File 1"))
    }

    @Test
    fun `embedded portable PDB needs no file`() {
        val exe = pe("sample-clr-embedded.exe")
        val info = assertNotNull(exe.tab("Debug Info", FixtureFileSystem()))
        assertEquals("Portable PDB embedded in the image", info.value("Symbols"))
        val context = assertNotNull(exe.tab("Debug Context", FixtureFileSystem()))
        assertEquals("Embedded Portable PDB", context.cell(0, "Name"))
        assertTrue((0 until context.rowCount).none { context.cell(it, "Kind") == "PDB symbols" })
    }

    @Test
    fun `files that cannot be a PDB are reported and never read`() {
        val pdbPath = dir.resolve("sample.pdb").path
        // A device or directory under the PDB's name, and an oversized file.
        for ((size, reason) in listOf(null to PdbMatch.Unreadable, PdbCompanionLoader.MAX_AUTOMATIC_PDB_SIZE + 1 to PdbMatch.TooLarge)) {
            val exe = pe("sample.exe")
            val fs = FixtureFileSystem(sizes = mapOf(pdbPath to size))
            assertEquals(reason, PdbCompanionLoader.load(exe, fs))
            assertTrue(pdbPath !in fs.reads)
            val context = assertNotNull(exe.tab("Debug Context", fs))
            assertEquals("does not match", context.cell(context.pdbRow(), "Status"))
            assertTrue(context.cell(context.pdbRow(), "Notes").startsWith("Not loaded: ${reason.description}"))
        }
        // Present but not a PDB.
        val exe = pe("sample.exe")
        val fs = FixtureFileSystem(overrides = mapOf(pdbPath to ByteArray(64) { 0x41 }))
        assertEquals(PdbMatch.Unreadable, PdbCompanionLoader.load(exe, fs))
        assertEquals(PdbMatch.Unreadable, exe.debugSymbolsRejection?.reason)
        assertEquals(pdbPath, exe.debugSymbolsRejection?.path)
    }

    @Test
    fun `a corrupt embedded PDB still lets a companion file be looked for`() {
        val bytes = dir.resolve("sample-clr-embedded.exe").readBytes()
        val probe = pe("sample-clr-embedded.exe", bytes)
        val entry = assertNotNull(probe.debugEntries?.firstOrNull { it.type == 17u }?.dataRange)
        bytes[entry.offset] = 0 // "MPDB" signature
        val exe = pe("sample-clr-embedded.exe", bytes)
        assertTrue(exe.hasDebugSymbols, "discovery stays optimistic")
        assertNull(exe.debugSymbols)
        assertTrue(PdbCompanionLoader.candidatePaths(exe).isNotEmpty())
    }
}

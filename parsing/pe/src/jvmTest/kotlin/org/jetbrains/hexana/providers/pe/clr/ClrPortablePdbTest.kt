package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.pe.PeImageFile
import org.jetbrains.hexana.providers.pe.PeParser
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `sample-clr-portable.pdb` (Roslyn `-debug:portable`) against what `System.Reflection.Metadata`
 * reports for it (`src/clr/pdb/DumpPortablePdb.cs` → `sample-clr-portable.pdb.dump.txt`).
 */
class ClrPortablePdbTest {
    private val dir = Path.of(System.getProperty("user.dir")).resolve("../native-fixtures/test-data/pe").normalize()
    private val bytes = dir.resolve("sample-clr-portable.pdb").readBytes()
    private val dump = dir.resolve("sample-clr-portable.pdb.dump.txt").readLines()
    private val metadata = ClrMetadataParser.parsePortablePdb(bytes.toCommonByteBuffer())
    private val pdb = assertNotNull(metadata.portablePdb)

    private fun dumpValue(prefix: String): String = dump.single { it.startsWith(prefix) }.removePrefix(prefix)

    @Test
    fun `detects portable PDBs only`() {
        assertTrue(ClrMetadataParser.isPortablePdb(bytes.toCommonByteBuffer()))
        assertFalse(ClrMetadataParser.isPortablePdb(dir.resolve("sample.pdb").readBytes().toCommonByteBuffer()))
        assertFalse(ClrMetadataParser.isPortablePdb(dir.resolve("sample-clr-portable.exe").readBytes().toCommonByteBuffer()))
    }

    @Test
    fun `pdb stream`() {
        assertEquals(dumpValue("metadata version: "), metadata.root?.version)
        val (guid, stamp) = Regex("guid=(\\S+) stamp=0x(\\S+)").find(dumpValue("pdb id: "))!!.destructured
        assertEquals(guid.uppercase(), pdb.header.guid)
        assertEquals(stamp.toUInt(16), pdb.header.stamp)
        assertEquals(dumpValue("entry point: 0x").toUInt(16), pdb.header.entryPointToken)
        assertEquals(14, pdb.header.referencedTableRows[ClrTableId.METHOD_DEF])
        assertEquals(emptyList(), metadata.diagnostics)
    }

    @Test
    fun `debug table row counts`() {
        val expected = dump.filter { it.startsWith("table ") }.associate { line ->
            val (name, rows) = line.removePrefix("table ").split(": ")
            name to rows.toInt()
        }
        val actual = metadata.tables.filter { it.rowCount > 0 }.associate { it.name to it.rowCount }
        assertEquals(expected, actual)
        assertTrue(metadata.tables.all { it.range != null }, "every debug table must be laid out")
        assertEquals(expected["MethodDebugInformation"], pdb.methodDebugInformationCount)
    }

    @Test
    fun `documents`() {
        val expected = dump.filter { it.startsWith("document ") }.map { line ->
            Regex("document (\\d+): (\\S+) hash=(\\S+):(\\S+) language=(\\S+)").find(line)!!.destructured
        }
        assertEquals(expected.size, pdb.documents.size)
        for ((document, values) in pdb.documents.zip(expected)) {
            val (row, name, hashAlgorithm, hash, language) = values
            assertEquals(row.toInt(), document.row)
            assertEquals(name, document.name)
            assertEquals(ClrPdbGuids.hashAlgorithmName(hashAlgorithm.uppercase()), document.hashAlgorithm)
            assertEquals("SHA-256", document.hashAlgorithm)
            assertEquals(hash, document.hash)
            assertEquals("C#", document.language)
        }
    }

    @Test
    fun `assembly CodeView record names this PDB`() {
        val image = assertNotNull(PeParser.parse(dir.resolve("sample-clr-portable.exe").readBytes().toCommonByteBuffer()).valueOrNull as? PeImageFile)
        val codeView = image.debugEntries.firstNotNullOf { it.codeView }
        assertEquals(pdb.header.guid, codeView.guid)
        assertEquals("/out/pe/sample-clr-portable.pdb", codeView.pdbPath)
        val entry = image.debugEntries.first { it.codeView != null }
        assertEquals(pdb.header.stamp, entry.timeDateStamp)
        assertTrue(entry.isPortablePdbCodeView)
    }

    @Test
    fun `corrupted copies never throw`() {
        val random = Random(4321)
        repeat(3000) {
            val copy = bytes.copyOf()
            repeat(1 + random.nextInt(6)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
            val buffer = copy.toCommonByteBuffer()
            if (ClrMetadataParser.isPortablePdb(buffer)) ClrMetadataParser.parsePortablePdb(buffer).portablePdb?.documents
        }
    }
}

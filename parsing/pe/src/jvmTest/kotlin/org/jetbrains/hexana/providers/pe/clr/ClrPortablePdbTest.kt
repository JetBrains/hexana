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
import kotlin.test.assertNull
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

    /** `method 0x06000002: …` followed by `  IL_0007 document=1 (19,39)-(19,40)` or `  IL_0010 document=1 hidden`. */
    private fun expectedSequencePoints(lines: List<String>): Map<Int, List<String>> {
        val result = LinkedHashMap<Int, MutableList<String>>()
        var method = 0
        for (line in lines) {
            Regex("^method 0x06([0-9A-F]{6}):").find(line)?.let { method = it.groupValues[1].toInt(16); result[method] = mutableListOf() }
            if (line.startsWith("  IL_")) result.getValue(method).add(line.trim())
        }
        return result
    }

    private fun ClrSequencePoint.render(): String {
        val il = "IL_" + ilOffset.toString(16).uppercase().padStart(4, '0')
        return if (isHidden) "$il document=$document hidden" else "$il document=$document ($startLine,$startColumn)-($endLine,$endColumn)"
    }

    @Test
    fun `sequence points match System Reflection Metadata`() {
        val expected = expectedSequencePoints(dump)
        assertEquals(14, expected.size)
        assertTrue(expected.values.sumOf { it.size } > 30)
        for ((method, points) in expected) {
            assertEquals(points, pdb.sequencePoints(method).map { it.render() }, "method $method")
        }
        assertEquals(emptyList(), pdb.sequencePoints(0))
        assertEquals(emptyList(), pdb.sequencePoints(15))
    }

    @Test
    fun `embedded portable PDB decodes to the same debug information`() {
        val exe = dir.resolve("sample-clr-embedded.exe").readBytes().toCommonByteBuffer()
        val image = assertNotNull(PeParser.parse(exe).valueOrNull as? PeImageFile)
        val entry = assertNotNull(ClrEmbeddedPortablePdb.entry(image.debugEntries))
        val embedded = assertNotNull(ClrEmbeddedPortablePdb.decode(exe, entry)?.portablePdb)
        val embeddedDump = dir.resolve("sample-clr-embedded.exe.dump.txt").readLines()
        val (guid, stamp) = Regex("guid=(\\S+) stamp=0x(\\S+)").find(embeddedDump.single { it.startsWith("pdb id: ") })!!.destructured
        assertEquals(guid.uppercase(), embedded.header.guid)
        assertEquals(stamp.toUInt(16), embedded.header.stamp)
        assertEquals(image.debugEntries.firstNotNullOf { it.codeView }.guid, embedded.header.guid)
        assertEquals("/src/clr/Sample.cs", embedded.documents.single().name)
        for ((method, points) in expectedSequencePoints(embeddedDump)) {
            assertEquals(points, embedded.sequencePoints(method).map { it.render() }, "method $method")
        }
        // Corrupt compressed data and an oversized declared size are rejected, not thrown.
        assertNull(ClrEmbeddedPortablePdb.decode(exe, entry, maxSize = 16))
    }

    @Test
    fun `corrupted copies never throw`() {
        val random = Random(4321)
        repeat(3000) {
            val copy = bytes.copyOf()
            repeat(1 + random.nextInt(6)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
            val buffer = copy.toCommonByteBuffer()
            if (ClrMetadataParser.isPortablePdb(buffer)) {
                val corrupted = ClrMetadataParser.parsePortablePdb(buffer).portablePdb
                corrupted?.documents
                for (row in 1..(corrupted?.methodDebugInformationCount ?: 0)) corrupted?.sequencePoints(row)
            }
        }
    }
}

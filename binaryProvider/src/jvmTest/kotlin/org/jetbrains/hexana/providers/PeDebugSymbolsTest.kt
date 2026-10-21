package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Matching PDBs to the PE fixtures in `parsing/native-fixtures/test-data/pe`. */
class PeDebugSymbolsTest {
    private fun bytes(name: String) = File("../parsing/native-fixtures/test-data/pe/$name").readBytes()
    private fun provider(name: String, data: ByteArray = bytes(name)) = createBinaryProvider(data.toCommonByteBuffer(), name, name)
    private fun pe(name: String) = assertIs<PeBinaryProvider>(provider(name))

    @Test
    fun `windows PDB gives the image native line info`() {
        val exe = pe("sample.exe")
        assertEquals(PePdbReference.Windows("AEBD2BE3-8B00-6D1E-4C4C-44205044422E", 1u, "/out/pe/sample.pdb"), exe.pdbReference)
        assertNull(exe.nativeSourceLocationProvider)

        val pdb = assertIs<PdbBinaryProvider>(provider("sample.pdb"))
        assertEquals(PdbMatch.Matches, exe.attachDebugSymbols(pdb, "sample.pdb"))
        assertSame(pdb, assertIs<PeDebugSymbols.File>(exe.debugSymbols).symbols)

        val lines = assertNotNull(exe.nativeSourceLocationProvider)
        // `0001:00000020` is line 20 and `0001:00000028` line 21 (llvm-pdbutil dump -l); .text is at RVA 0x1000.
        assertEquals(HexanaSourceLocation("/src/src/sample.c", 20, 0), lines.getSourceLocationForVirtualAddress(0x140001020uL))
        assertEquals(20, lines.getSourceLocationForVirtualAddress(0x140001027uL)?.line)
        assertEquals(21, lines.getSourceLocationForVirtualAddress(0x140001028uL)?.line)
        assertNull(lines.getSourceLocationForVirtualAddress(0x140000FFFuL))
        assertEquals(listOf(0x140001089uL), lines.getVirtualAddressesForSourceLocation("sample.c", 29))
        assertEquals(12, lines.summary.lineRowCount)
        assertEquals(1, lines.summary.fileCount)
    }

    @Test
    fun `mismatched PDBs are rejected with the reason`() {
        val exe = pe("sample.exe")
        assertEquals(PdbMatch.WrongKind, exe.attachDebugSymbols(assertIs<PdbSymbolsBinaryProvider>(provider("sample-clr-portable.pdb")), null))

        // A rebuilt PDB keeps the GUID of an incremental link but bumps the DBI age (stream 3, offset 8).
        val pdb = assertIs<PdbBinaryProvider>(provider("sample.pdb"))
        val dbiAgeOffset = assertNotNull(pdb.parsedPdb?.stream(3)?.fileOffset(8))
        val rebuilt = bytes("sample.pdb").also { it[dbiAgeOffset] = 2 }
        assertEquals(PdbMatch.AgeMismatch, exe.attachDebugSymbols(assertIs<PdbBinaryProvider>(provider("sample.pdb", rebuilt)), null))
        assertNull(exe.debugSymbols)

        assertEquals(PdbMatch.WrongKind, pe("sample-clr-portable.exe").attachDebugSymbols(pdb, null))
        // sample-clr.exe is built with -debug-: no CodeView record at all.
        assertEquals(PdbMatch.NoReference, pe("sample-clr.exe").attachDebugSymbols(pdb, null))
        // A Portable PDB of another build keeps no GUID or stamp in common; change each half of the id.
        val portable = assertIs<PortablePdbBinaryProvider>(provider("sample-clr-portable.pdb"))
        val idOffset = assertNotNull(portable.metadata.portablePdb).header.range.offset
        val otherStamp = bytes("sample-clr-portable.pdb").also { it[idOffset + 16] = (it[idOffset + 16] + 1).toByte() }
        val otherGuid = bytes("sample-clr-portable.pdb").also { it[idOffset] = (it[idOffset] + 1).toByte() }
        val clr = pe("sample-clr-portable.exe")
        assertEquals(PdbMatch.StampMismatch, clr.attachDebugSymbols(assertIs<PortablePdbBinaryProvider>(provider("x.pdb", otherStamp)), null))
        assertEquals(PdbMatch.GuidMismatch, clr.attachDebugSymbols(assertIs<PortablePdbBinaryProvider>(provider("x.pdb", otherGuid)), null))
    }

    @Test
    fun `portable PDB gives managed methods sequence points`() {
        val exe = pe("sample-clr-portable.exe")
        assertIs<PePdbReference.Portable>(exe.pdbReference)
        assertNull(exe.portablePdb)
        val pdb = assertIs<PortablePdbBinaryProvider>(provider("sample-clr-portable.pdb"))
        assertEquals(PdbMatch.Matches, exe.attachDebugSymbols(pdb, "sample-clr-portable.pdb"))
        val points = assertNotNull(exe.portablePdb).sequencePoints(2)
        assertEquals(listOf(19, 19, 19, 19), points.map { it.startLine })
        // Managed code has no native line table.
        assertNull(exe.nativeSourceLocationProvider)
    }

    @Test
    fun `embedded portable PDB needs no companion`() {
        val exe = pe("sample-clr-embedded.exe")
        val embedded = assertIs<PeDebugSymbols.Embedded>(exe.debugSymbols)
        assertEquals("/src/clr/Sample.cs", embedded.pdb.documents.single().name)
        assertSame(embedded.pdb, exe.portablePdb)
    }
}

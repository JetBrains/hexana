package org.jetbrains.hexana.pdb

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the lld-link fixture against `sample.pdb.llvm-pdbutil.txt`
 * (`llvm-pdbutil dump -summary -streams -modules -files -section-contribs -types -ids`).
 */
class PdbFixtureTest {
    private val bytes = Files.readAllBytes(fixture("sample.pdb"))

    @Test
    fun `superblock and stream directory`() {
        val pdb = assertNotNull(PdbParser.parse(bytes.toCommonByteBuffer()))
        assertEquals(4096, pdb.superBlock.blockSize)
        assertEquals(18u, pdb.superBlock.blockCount)
        assertEquals(3u, pdb.superBlock.blockMapAddress)
        assertEquals(
            listOf(0, 93, 192, 930, 1204, 0, 616, 656, 404, 48, 200, 836, 576, 56, 48),
            pdb.streams.map { it.size },
        )
        assertEquals(emptyList(), pdb.diagnostics)
    }

    @Test
    fun `info stream identity matches the executable's CodeView record`() {
        val pdb = assertNotNull(PdbParser.parse(bytes.toCommonByteBuffer()))
        val info = assertNotNull(pdb.info)
        assertEquals(20000404u, info.version)
        assertEquals(2931633123u, info.signature)
        assertEquals(1u, info.age)
        assertEquals("AEBD2BE3-8B00-6D1E-4C4C-44205044422E", info.guid)
        assertEquals(mapOf("/LinkInfo" to 5, "/names" to 13), info.namedStreams)
        assertEquals(listOf(PdbFeature.Vc140), info.features)
        assertEquals(PdbIdentity("AEBD2BE3-8B00-6D1E-4C4C-44205044422E", 1u), pdb.identity)
    }

    @Test
    fun `dbi stream modules files and contributions`() {
        val pdb = assertNotNull(PdbParser.parse(bytes.toCommonByteBuffer()))
        val dbi = assertNotNull(pdb.dbi)
        assertEquals(0x8664, dbi.machine)
        assertEquals(14, dbi.buildMajor)
        assertEquals(11, dbi.buildMinor)
        assertEquals(6, dbi.globalSymbolStream)
        assertEquals(7, dbi.publicSymbolStream)
        assertEquals(8, dbi.symbolRecordStream)
        assertEquals(10, dbi.optionalDebugStreams[PdbOptionalDebugStream.SectionHeaders])

        assertEquals(listOf("/tmp/sample-5bae4d.o", "* Linker *"), dbi.modules.map { it.moduleName })
        assertEquals(listOf("/tmp/sample-5bae4d.o", ""), dbi.modules.map { it.objectFileName })
        assertEquals(listOf(11, 12), dbi.modules.map { it.symbolStream })
        assertEquals(listOf(listOf("/src/src/sample.c"), emptyList()), dbi.modules.map { it.sourceFiles })

        assertEquals(16, dbi.sectionContributions.size)
        val last = dbi.sectionContributions.last()
        assertEquals(5, last.section)
        assertEquals(0, last.offset)
        assertEquals(12, last.size)
        assertEquals(1, last.moduleIndex)

        assertEquals("Module \"/tmp/sample-5bae4d.o\"", pdb.streamRoles[11])
        assertEquals("Named stream \"/names\"", pdb.streamRoles[13])
        assertEquals("TPI hash", pdb.streamRoles[9])
        assertEquals("IPI hash", pdb.streamRoles[14])
        assertEquals("Section headers", pdb.streamRoles[10])
    }

    @Test
    fun `type stream headers`() {
        val pdb = assertNotNull(PdbParser.parse(bytes.toCommonByteBuffer()))
        assertEquals(10L, pdb.tpi?.recordCount)
        assertEquals(10L, pdb.ipi?.recordCount)
        assertEquals(0x1000u, pdb.tpi?.typeIndexBegin)
    }

    @Test
    fun `stream reads cross block boundaries through the block list`() {
        val pdb = assertNotNull(PdbParser.parse(bytes.toCommonByteBuffer()))
        val dbi = assertNotNull(pdb.stream(3))
        val raw = assertNotNull(dbi.bytes(0, dbi.size))
        assertEquals(raw.size, dbi.size)
        assertEquals(-1, dbi.i32(0))
        assertEquals(listOf(org.jetbrains.hexana.parsing.common.NativeByteRange(12 * 4096, 930)), dbi.blockRanges())
    }

    @Test
    fun `non-PDB input is rejected`() {
        assertNull(PdbParser.parse(Files.readAllBytes(fixture("sample.exe")).toCommonByteBuffer()))
        assertNull(PdbParser.parse(ByteArray(10).toCommonByteBuffer()))
    }

    @Test
    fun `corrupted copies never throw`() {
        val random = Random(1234)
        repeat(2000) {
            val copy = bytes.copyOf()
            repeat(1 + random.nextInt(8)) {
                // Concentrate mutations on the superblock, the directory and the small streams.
                val at = if (random.nextBoolean()) random.nextInt(copy.size) else random.nextInt(minOf(copy.size, 4096 * 18))
                copy[at] = random.nextInt(256).toByte()
            }
            val truncated = if (random.nextInt(4) == 0) copy.copyOf(random.nextInt(copy.size)) else copy
            val pdb = PdbParser.parse(truncated.toCommonByteBuffer()) ?: return@repeat
            pdb.streamRoles
            pdb.identity
            pdb.streams.forEach { it.blockRanges() }
        }
    }

    private fun fixture(name: String): Path = repoRoot().resolve("parsing/native-fixtures/test-data/pe/$name")

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("settings.gradle.kts"))) dir = dir.parent
        return dir
    }
}

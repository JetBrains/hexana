package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParseSuccess
import org.jetbrains.hexana.providers.pe.clr.ClrEmbeddedPortablePdb
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrMetadataParser
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `*.webcil*` fixtures are the `sample-clr*.exe` assemblies run through the .NET SDK's own
 * `WebcilConverter` (`src/clr/webcil`), so everything decoded from them must equal the PE decode:
 * same metadata, and byte-identical IL bodies and resources at their new file offsets.
 */
class WebcilParserTest {
    private fun fixture(name: String): ByteArray = Path.of(System.getProperty("user.dir"))
        .resolve("../native-fixtures/test-data/pe/$name")
        .normalize()
        .readBytes()

    private fun peMetadata(name: String): Pair<CommonByteBuffer, ClrMetadata> {
        val bytes = fixture(name).toCommonByteBuffer()
        val file = assertIs<PeImageFile>(assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(bytes)).value)
        return bytes to assertNotNull(file.clr)
    }

    /** The wrapper's payload is the only `WbIL` in the file; the real lookup goes through the Wasm data section. */
    private fun wrappedPayload(bytes: ByteArray): Pair<Int, Int> {
        val offset = (0..bytes.size - 4).first { WebcilParser.hasMagic(bytes.toCommonByteBuffer(), it) }
        return offset to bytes.size - offset
    }

    private fun CommonByteBuffer.slice(range: NativeByteRange): ByteArray =
        ByteArray(range.length.toInt()) { this[range.start.toInt() + it] }

    @Test
    fun `decodes the bare payload header and synthesizes PE sections`() {
        val bytes = fixture("sample-clr.webcil").toCommonByteBuffer()
        val image = assertNotNull(WebcilParser.parse(bytes, 0, bytes.size))

        assertEquals(0, image.header.versionMajor)
        assertEquals(0, image.header.versionMinor)
        assertEquals(image.header.sectionCount, image.sections.size)
        assertEquals(emptyList(), image.diagnostics)
        assertEquals(".text", image.sections.first().name)
        assertEquals(image.header.cliHeaderRva, image.optionalHeader.dataDirectories[PeDataDirectoryIndex.ClrRuntimeHeader].rva)
        assertEquals(image.header.debugDirectoryRva, image.optionalHeader.dataDirectories[PeDataDirectoryIndex.Debug].rva)
        // The PE image's sections, minus their names, at the converter's new file positions.
        val (_, pe) = peMetadata("sample-clr.exe")
        val peSections = assertIs<PeImageFile>(
            assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(fixture("sample-clr.exe").toCommonByteBuffer())).value,
        ).sections
        assertEquals(peSections.map { it.virtualAddress to it.virtualSize }, image.sections.map { it.virtualAddress to it.virtualSize })
        assertTrue(pe.typeDefs.isNotEmpty())
    }

    @Test
    fun `metadata of the wrapped payload equals the PE assembly's`() {
        val raw = fixture("sample-clr.webcil.wasm")
        val bytes = raw.toCommonByteBuffer()
        val (offset, size) = wrappedPayload(raw)
        val image = assertNotNull(WebcilParser.parse(bytes, offset, size))
        val webcil = assertNotNull(ClrMetadataParser.parse(bytes, image.optionalHeader, image.sections))
        val (peBytes, pe) = peMetadata("sample-clr.exe")

        assertEquals(emptyList(), webcil.diagnostics)
        assertEquals(pe.assembly?.name, webcil.assembly?.name)
        assertEquals(pe.assembly?.version, webcil.assembly?.version)
        assertEquals(pe.module?.mvid, webcil.module?.mvid)
        assertEquals(pe.entryPoint, webcil.entryPoint)
        assertEquals(pe.targetFramework, webcil.targetFramework)
        assertEquals(pe.typeDefs.map { it.fullName }, webcil.typeDefs.map { it.fullName })
        assertEquals(pe.methods.map { it.name to it.signature }, webcil.methods.map { it.name to it.signature })
        assertEquals(pe.userStrings.map { it.value }, webcil.userStrings.map { it.value })

        // IL and resources moved, but must be the same bytes, and inside the payload.
        val payload = offset until offset + size
        for ((peMethod, webcilMethod) in pe.methods.zip(webcil.methods)) {
            val peBody = peMethod.body ?: continue
            val body = assertNotNull(webcilMethod.body, webcilMethod.name)
            assertTrue(body.range.start.toInt() in payload)
            assertContentEquals(peBytes.slice(peBody.range), bytes.slice(body.range), webcilMethod.name)
        }
        assertTrue(webcil.methods.count { it.body != null } > 10)
        for ((peResource, resource) in pe.manifestResources.zip(webcil.manifestResources)) {
            assertContentEquals(peBytes.slice(assertNotNull(peResource.dataRange)), bytes.slice(assertNotNull(resource.dataRange)))
        }
        assertTrue(webcil.manifestResources.isNotEmpty())
    }

    @Test
    fun `debug directory pointers are rebased from the payload to the file`() {
        val raw = fixture("sample-clr-portable.webcil.wasm")
        val bytes = raw.toCommonByteBuffer()
        val (offset, size) = wrappedPayload(raw)
        val image = assertNotNull(WebcilParser.parse(bytes, offset, size))
        val entries = assertNotNull(PeParser.parseDebugEntries(bytes, image.optionalHeader, image.sections, offset.toUInt()))
        val codeView = assertNotNull(entries.firstNotNullOfOrNull { it.codeView })

        val peBytes = fixture("sample-clr-portable.exe").toCommonByteBuffer()
        val pe = assertIs<PeImageFile>(assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(peBytes)).value)
        val peEntries = assertNotNull(PeParser.parseDebugEntries(peBytes, assertNotNull(pe.optionalHeader), pe.sections))
        assertEquals("RSDS", codeView.signature)
        assertEquals(peEntries.firstNotNullOf { it.codeView }.guid, codeView.guid)
        assertEquals(peEntries.firstNotNullOf { it.codeView }.pdbPath, codeView.pdbPath)
        // Without the base, the pointer would land in the Wasm wrapper's header.
        val unrebased = assertNotNull(PeParser.parseDebugEntries(bytes, image.optionalHeader, image.sections))
        assertNull(unrebased.firstNotNullOfOrNull { it.codeView?.guid })
    }

    @Test
    fun `embedded portable PDB decodes through the rebased entry`() {
        val raw = fixture("sample-clr-embedded.webcil.wasm")
        val bytes = raw.toCommonByteBuffer()
        val (offset, size) = wrappedPayload(raw)
        val image = assertNotNull(WebcilParser.parse(bytes, offset, size))
        val entries = assertNotNull(PeParser.parseDebugEntries(bytes, image.optionalHeader, image.sections, offset.toUInt()))
        val pdb = assertNotNull(ClrEmbeddedPortablePdb.decode(bytes, assertNotNull(ClrEmbeddedPortablePdb.entry(entries)))?.portablePdb)
        assertTrue(pdb.documents.isNotEmpty())
    }

    @Test
    fun `rejects other data and keeps sections that leave the payload out of the mapping`() {
        val raw = fixture("sample-clr.webcil")
        assertNull(WebcilParser.parse(fixture("sample-clr.exe").toCommonByteBuffer(), 0, 4096))
        // Unknown major version.
        assertNull(WebcilParser.parse(raw.copyOf().also { it[4] = 1 }.toCommonByteBuffer(), 0, raw.size))
        // Section table longer than the payload.
        assertNull(WebcilParser.parse(raw.copyOf().also { it[8] = 0xFF.toByte(); it[9] = 0 }.toCommonByteBuffer(), 0, 64))

        val truncated = raw.copyOf(raw.size - 1024).toCommonByteBuffer()
        val image = assertNotNull(WebcilParser.parse(truncated, 0, truncated.size))
        assertTrue(image.diagnostics.any { it.code == "webcil.section_out_of_bounds" })
        val dropped = image.sections.filter { it.contentRange == null }
        assertTrue(dropped.isNotEmpty() && dropped.all { it.rawDataSize == 0u })
    }
}

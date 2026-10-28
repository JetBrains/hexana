package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for the parser a user picks by hand ([createBinaryProvider] with a [BinaryParser])
 * and for [binaryParserOf], which a picker uses to mark the current choice.
 */
class BinaryParserTest {

    @Test
    fun idsAreUniqueAndRoundTrip() {
        // The ids are what `.hexana/file_associations.json` stores; two parsers sharing one
        // would make the file ambiguous.
        assertEquals(BinaryParser.entries.size, BinaryParser.entries.map { it.id }.toSet().size)
        for (parser in BinaryParser.entries) {
            assertEquals(parser, BinaryParser.fromId(parser.id))
        }
        assertNull(BinaryParser.fromId("no-such-parser"))
    }

    @Test
    fun genericOverridesWhatTheContentProves() {
        val module = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val provider = createBinaryProvider(BinaryParser.GENERIC, module.buffer())
        assertEquals(GenericBinaryProvider::class, provider!!::class)
        assertEquals(BinaryParser.GENERIC, binaryParserOf(provider))
    }

    @Test
    fun magicLessFormatsOpenWithoutTheirExtension() {
        // The case the override exists for: a wire message saved as `.bin` has nothing
        // the automatic path could recognize it by.
        val message = byteArrayOf(0x08, 0x01)
        assertIs<GenericBinaryProvider>(createBinaryProvider(message.buffer(), "payload.bin"))
        val forced = createBinaryProvider(BinaryParser.PROTOBUF_MESSAGE, message.buffer(), "payload.bin")
        assertIs<ProtobufMessageBinaryProvider>(forced)
        assertEquals(BinaryParser.PROTOBUF_MESSAGE, binaryParserOf(forced))
    }

    @Test
    fun nativeParsersRefuseBytesWithoutTheirHeader() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(26)
        assertNull(createBinaryProvider(BinaryParser.ELF, zip.buffer()))
        assertNull(createBinaryProvider(BinaryParser.PE, zip.buffer()))
        assertNull(createBinaryProvider(BinaryParser.MACH_O, zip.buffer()))
    }

    @Test
    fun nativeParserAcceptsItsOwnHeader() {
        val provider = createBinaryProvider(BinaryParser.ELF, elfHeader().buffer(), "/tmp/a.out")
        assertIs<ElfBinaryProvider>(provider)
        assertEquals(BinaryParser.ELF, binaryParserOf(provider))
    }

    @Test
    fun detectedProvidersMapBackToTheirParser() {
        val module = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        assertEquals(BinaryParser.WASM, binaryParserOf(createBinaryProvider(module.buffer())))
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(26)
        assertEquals(BinaryParser.ZIP, binaryParserOf(createBinaryProvider(zip.buffer())))
    }

    @Test
    fun forcedProviderStartsAtTheFirstByte() {
        val bytes = byteArrayOf(1, 2, 3, 4).buffer()
        bytes.position(3)
        val provider = createBinaryProvider(BinaryParser.GENERIC, bytes)!!
        assertEquals(0, provider.bytes.position())
        assertEquals(1, provider.bytes[0])
    }
}

private fun ByteArray.buffer() = toCommonByteBuffer().also { it.position(0) }

private fun elfHeader(): ByteArray {
    val bytes = ByteArray(64)
    bytes[0] = 0x7F
    bytes[1] = 'E'.code.toByte()
    bytes[2] = 'L'.code.toByte()
    bytes[3] = 'F'.code.toByte()
    bytes[4] = 2 // ELFCLASS64
    bytes[5] = 1 // ELFDATA2LSB
    bytes[6] = 1 // EV_CURRENT
    bytes[16] = 2 // ET_EXEC
    bytes[18] = 0x3E // x86-64
    return bytes
}

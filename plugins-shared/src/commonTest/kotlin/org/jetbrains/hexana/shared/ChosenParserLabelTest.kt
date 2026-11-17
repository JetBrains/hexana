package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/** The format badge's label once the user has chosen a parser for the file. */
class ChosenParserLabelTest {

    @Test
    fun `forced generic on a native binary is labelled generic, not by the sniffed header`() {
        val elf = elfHeader().toCommonByteBuffer()
        val provider = createBinaryProvider(BinaryParser.GENERIC, elf)!!
        // What a plain generic provider calls itself — the header it sniffs.
        assertEquals("ELF64", fileTypeDescription(provider))
        assertEquals("Generic binary", fileTypeDescription(provider, "a.out", BinaryParser.GENERIC))
    }

    @Test
    fun `a host subclass of the generic provider counts as generic`() {
        // IntelliJ swaps in GenericBinaryVirtualFileProvider so the editor keeps its file.
        val provider = object : GenericBinaryProvider(elfHeader().toCommonByteBuffer()) {}
        assertEquals("Generic binary", fileTypeDescription(provider, "a.out", BinaryParser.GENERIC))
    }

    @Test
    fun `detected files keep their usual label`() {
        val provider = GenericBinaryProvider(elfHeader().toCommonByteBuffer())
        assertEquals("ELF64", fileTypeDescription(provider, "a.out", null))
        val elf = createBinaryProvider(elfHeader().toCommonByteBuffer(), "a.out")
        assertEquals("ELF64", fileTypeDescription(elf, "a.out", BinaryParser.ELF))
    }
}

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

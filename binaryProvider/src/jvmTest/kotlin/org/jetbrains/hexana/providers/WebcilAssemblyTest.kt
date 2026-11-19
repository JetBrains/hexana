package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Blazor WebAssembly assemblies (Webcil in a Wasm wrapper) opened as ordinary Wasm files. */
class WebcilAssemblyTest {
    private fun file(name: String): File =
        File("../parsing/native-fixtures/test-data/pe/$name").also { assertTrue(it.exists(), "missing fixture ${it.absolutePath}") }

    private fun wasm(name: String): WasmBinaryProvider =
        assertIs<WasmBinaryProvider>(createBinaryProvider(file(name).readBytes().toCommonByteBuffer(), name))

    @Test
    fun `a Webcil wrapper module exposes its assembly`() {
        val provider = wasm("sample-clr.webcil.wasm")
        val assembly = assertNotNull(provider.webcil)
        assertSame(assembly, provider.webcil)
        val metadata = assertNotNull(assembly.clrMetadata)
        assertEquals("sample-clr", metadata.assembly?.name)
        assertEquals("Hexana.Sample.Program::Main", metadata.entryPoint)
        assertNull(assembly.clrMachine)
        // Payload offsets are file offsets: the payload sits inside the wrapper's data section.
        val dataSection = assertNotNull(provider.wasmModule?.dataSection)
        assertTrue(assembly.image.payloadRange.start >= dataSection.contentOffset)
    }

    @Test
    fun `other Wasm modules are not Webcil`() {
        val bytes = File("../wasmParser/src/commonTest/resources/c/utf8-c.wasm").readBytes()
        assertNull(assertIs<WasmBinaryProvider>(createBinaryProvider(bytes.toCommonByteBuffer(), "utf8-c.wasm")).webcil)
    }

    @Test
    fun `companion Portable PDB attaches by CodeView id, an embedded one is found on its own`() {
        val assembly = assertNotNull(wasm("sample-clr-portable.webcil.wasm").webcil)
        assertNull(assembly.portablePdb)
        val pdbBytes = file("sample-clr-portable.pdb").readBytes().toCommonByteBuffer()
        val pdb = assertIs<PortablePdbBinaryProvider>(createBinaryProvider(pdbBytes, "sample-clr-portable.pdb"))
        assertEquals(PdbMatch.Matches, assembly.attachDebugSymbols(pdb))
        assertNotNull(assembly.portablePdb)

        val other = assertNotNull(wasm("sample-clr.webcil.wasm").webcil)
        assertEquals(PdbMatch.NoReference, other.attachDebugSymbols(pdb))

        assertNotNull(assertNotNull(wasm("sample-clr-embedded.webcil.wasm").webcil).portablePdb)
    }

    @Test
    fun `size breakdown covers the payload sections and adds up`() {
        val assembly = assertNotNull(wasm("sample-clr.webcil.wasm").webcil)
        val root = assertNotNull(assembly.sizeAnalysis)
        val text = assertNotNull(root.children.firstOrNull { it.name == ".text" })
        assertTrue(text.children.any { it.name == ClrSizeGroups.IL_BODIES })
        fun BinarySizeNode.check() {
            if (children.isNotEmpty()) assertEquals(size, children.sumOf { it.size }, "children of '$name'")
            children.forEach { it.check() }
        }
        text.check()
    }

    @Test
    fun `the Webcil sniff rejects malformed section sizes instead of throwing`() {
        val header = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        // A custom section declaring Int.MAX_VALUE bytes: the next section offset would wrap negative.
        val huge = header + byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07, 0x00)
        assertFalse(WebcilAssembly.isWebcilModule(huge.toCommonByteBuffer()))
        // Past the end by one byte.
        assertFalse(WebcilAssembly.isWebcilModule((header + byteArrayOf(0x01, 0x05, 0x00)).toCommonByteBuffer()))
        // Truncated LEB.
        assertFalse(WebcilAssembly.isWebcilModule((header + byteArrayOf(0x01, 0x80.toByte())).toCommonByteBuffer()))
        assertTrue(WebcilAssembly.isWebcilModule(file("sample-clr.webcil.wasm").readBytes().toCommonByteBuffer()))
    }
}

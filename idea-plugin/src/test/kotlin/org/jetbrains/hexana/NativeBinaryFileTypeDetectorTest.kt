package org.jetbrains.hexana

import com.intellij.openapi.util.io.ByteArraySequence
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The magic sniff that gives an extensionless binary a Hexana file type, which is what
 * makes `HexanaFileEditorProvider.accept` take it: that provider has no magic of its
 * own, so a file with neither a known name nor a file type from here never reaches the
 * editor at all.
 */
class NativeBinaryFileTypeDetectorTest {

    private val detector = NativeBinaryFileTypeDetector()

    // detect() reads the byte prefix; the file argument only matters for the .class
    // guard on the fat Mach-O branch.
    private fun detect(bytes: ByteArray, name: String = "payload") = detector.detect(
        LightVirtualFile(name),
        ByteArraySequence(bytes),
        /* firstCharsIfText = */ null,
    )

    @Test
    fun `core wasm module is detected without an extension`() {
        val module = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        assertSame(WasmFileType.INSTANCE, detect(module))
    }

    @Test
    fun `wasm component is detected without an extension`() {
        val component = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)
        assertSame(WasmFileType.INSTANCE, detect(component))
    }

    @Test
    fun `an unknown wasm version is left alone`() {
        // The file type and the provider must agree on what counts as Wasm: typing this
        // as Wasm when WasmBinaryProviderSelector would call it Generic would label a
        // tab Wasm and then show a plain hex view inside it.
        val future = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x7F, 0x00, 0x00, 0x00)
        assertNull(detect(future))
    }

    @Test
    fun `the magic alone is not enough`() {
        assertNull(detect(byteArrayOf(0x00, 0x61, 0x73, 0x6D)))
    }

    @Test
    fun `formats detected before wasm still win`() {
        assertSame(ElfFileType.INSTANCE, detect(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0)))
        assertSame(
            PcapFileType.INSTANCE,
            detect(byteArrayOf(0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(), 2, 0, 4, 0)),
        )
    }

    @Test
    fun `text and short input are left to the platform`() {
        assertNull(detect("hello world, not a binary".encodeToByteArray()))
        assertNull(detect(byteArrayOf(0x00, 0x61)))
        assertNull(detect(byteArrayOf()))
    }

    @Test
    fun `windows and portable PDBs are detected by content, text PDB files are not`() {
        val fixtures = java.io.File("../parsing/native-fixtures/test-data/pe")
        assertSame(PdbFileType.INSTANCE, detect(fixtures.resolve("sample.pdb").readBytes().copyOf(0x100), "sample.pdb"))
        assertSame(PdbFileType.INSTANCE, detect(fixtures.resolve("sample-clr-portable.pdb").readBytes().copyOf(0x100), "sample.pdb"))
        // A Protein Data Bank entry shares the extension.
        assertNull(detect("HEADER    PLANT PROTEIN                           30-APR-81   1CRN\n".encodeToByteArray(), "1crn.pdb"))
    }
}

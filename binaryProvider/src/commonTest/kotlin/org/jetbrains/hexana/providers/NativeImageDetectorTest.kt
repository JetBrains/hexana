package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.elf.ElfProgramHeader
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.elf.ElfSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeImageDetectorTest {

    @Test
    fun `elf with full fingerprint resolves identity and sbom locator`() {
        val strings = listOf(
            str("com.oracle.svm.core.VM=GraalVM 23+37.1 Java 23"),
            str("com.oracle.svm.core.VM.Target.Platform=org.graalvm.nativeimage.Platform\$LINUX_AMD64"),
        )
        val sections = listOf(section(".svm_heap"), section(".data"))
        val symbols = listOf(
            sym("sbom", 0x1100uL),
            sym("sbom_length", 0x1200uL),
            sym("__svm_version_info", 0x1000uL),
            // graal_create_isolate is present in every native image, so it must NOT
            // promote this to a "shared library" classification.
            sym("graal_create_isolate", 0x1300uL),
        )
        val programHeaders = listOf(ptLoad(virtualAddress = 0x1000uL, fileOffset = 0uL, fileSize = 0x400uL))

        // file layout: gzip magic at the sbom offset, an 8-byte LE length at the length-field offset.
        val bytes = ByteArray(0x400)
        bytes[0x100] = 0x1F           // sbom VA 0x1100 -> file 0x100
        bytes[0x101] = 0x8B.toByte()
        bytes[0x200] = 0x10           // sbom_length VA 0x1200 -> file 0x200; LE uint64 = 16

        val info = NativeImageDetector.fromElf(strings, sections, symbols, programHeaders, bytes.toCommonByteBuffer())

        assertTrue(info.isNativeImage)
        assertEquals(NativeImageInfo.Confidence.HIGH, info.confidence)
        assertEquals("GraalVM 23+37.1 Java 23", info.version)
        assertEquals(NativeImageInfo.Edition.UNKNOWN, info.edition)
        assertEquals("LINUX_AMD64", info.platform)
        assertEquals(listOf(".svm_heap"), info.svmSections)

        val sbom = assertNotNull(info.sbom)
        assertEquals(0x100L, sbom.fileOffset)
        assertEquals(16L, sbom.length)
        assertTrue(sbom.isGzip)
    }

    @Test
    fun `enterprise edition detected from EE suffix`() {
        val info = NativeImageDetector.fromElf(
            strings = listOf(str("com.oracle.svm.core.VM=GraalVM 21.0.1 Java 21 EE")),
            sections = emptyList(),
            symbols = emptyList(),
            programHeaders = emptyList(),
            bytes = ByteArray(0).toCommonByteBuffer(),
        )
        assertTrue(info.isNativeImage)
        assertEquals(NativeImageInfo.Edition.ENTERPRISE, info.edition)
    }

    @Test
    fun `community edition detected from CE marker`() {
        val info = NativeImageDetector.fromElf(
            strings = listOf(str("com.oracle.svm.core.VM=GraalVM CE 21.0.1 Java 21")),
            sections = emptyList(),
            symbols = emptyList(),
            programHeaders = emptyList(),
            bytes = ByteArray(0).toCommonByteBuffer(),
        )
        assertEquals(NativeImageInfo.Edition.COMMUNITY, info.edition)
    }

    @Test
    fun `oracle graalvm identity string is recognized as enterprise`() {
        // Real form emitted by Oracle GraalVM 24: "Oracle GraalVM 24.0.2+11.1".
        val info = NativeImageDetector.fromElf(
            strings = listOf(str("com.oracle.svm.core.VM=Oracle GraalVM 24.0.2+11.1")),
            sections = emptyList(),
            symbols = emptyList(),
            programHeaders = emptyList(),
            bytes = ByteArray(0).toCommonByteBuffer(),
        )
        assertTrue(info.isNativeImage)
        assertEquals("Oracle GraalVM 24.0.2+11.1", info.version)
        assertEquals(NativeImageInfo.Edition.ENTERPRISE, info.edition)
    }

    @Test
    fun `svm sentinel symbol alone confirms a stripped native image`() {
        // No identity string, no svm sections — only the marker symbol survived.
        val info = NativeImageDetector.fromElf(
            strings = emptyList(),
            sections = emptyList(),
            symbols = listOf(sym("__svm_version_info", 0x1000uL)),
            programHeaders = emptyList(),
            bytes = ByteArray(0).toCommonByteBuffer(),
        )
        assertTrue(info.isNativeImage)
        assertNull(info.sbom)
    }

    @Test
    fun `non-native binary yields the NONE singleton`() {
        val info = NativeImageDetector.fromElf(
            strings = listOf(str("just some random string"), str("GCC: (GNU) 13.2.0")),
            sections = listOf(section(".text"), section(".data")),
            symbols = listOf(sym("main", 0x1000uL), sym("printf", 0uL)),
            programHeaders = emptyList(),
            bytes = ByteArray(0).toCommonByteBuffer(),
        )
        assertSame(NativeImageInfo.NONE, info)
    }

    @Test
    fun `sbom symbols without resolvable length produce no locator`() {
        // sbom + sbom_length present, but the length field points past EOF.
        val symbols = listOf(sym("sbom", 0x1100uL), sym("sbom_length", 0x9999uL))
        val programHeaders = listOf(ptLoad(virtualAddress = 0x1000uL, fileOffset = 0uL, fileSize = 0x400uL))
        val info = NativeImageDetector.fromElf(
            strings = listOf(str("com.oracle.svm.core.VM=GraalVM 23 Java 23")),
            sections = emptyList(),
            symbols = symbols,
            programHeaders = programHeaders,
            bytes = ByteArray(0x400).toCommonByteBuffer(),
        )
        assertTrue(info.isNativeImage)
        assertNull(info.sbom)
    }

    // --- fixture helpers -----------------------------------------------------

    private fun str(value: String) = BinaryStringRecord(value = value, offset = 0, size = value.length)

    private fun sym(name: String, value: ULong) = ElfSymbol(
        index = 0,
        tableSectionIndex = 0,
        nameOffset = 0u,
        name = name,
        info = 0,
        binding = 0,
        type = 0,
        other = 0,
        visibility = 0,
        sectionIndex = 0,
        value = value,
        size = 0uL,
        recordRange = NativeByteRange(0, 0),
    )

    private fun section(name: String) = ElfSectionHeader(
        index = 0,
        nameOffset = 0u,
        name = name,
        type = 0u,
        flags = 0uL,
        address = 0uL,
        fileOffset = 0uL,
        size = 0uL,
        link = 0u,
        info = 0u,
        addressAlignment = 0uL,
        entrySize = 0uL,
        headerRange = NativeByteRange(0, 0),
        contentRange = null,
    )

    private fun ptLoad(virtualAddress: ULong, fileOffset: ULong, fileSize: ULong) = ElfProgramHeader(
        index = 0,
        type = 1u,
        flags = 0u,
        fileOffset = fileOffset,
        virtualAddress = virtualAddress,
        physicalAddress = 0uL,
        fileSize = fileSize,
        memorySize = fileSize,
        alignment = 0uL,
        headerRange = NativeByteRange(0, 0),
        fileRange = null,
    )
}
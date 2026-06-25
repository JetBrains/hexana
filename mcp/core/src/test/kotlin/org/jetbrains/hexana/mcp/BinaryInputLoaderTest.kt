package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryOmittedResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryRecordResult
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryWarningResult
import org.jetbrains.hexana.mcp.model.binaries.QueryBinaryResult
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BinaryInputLoaderTest {
    @Test
    fun rawBytesUsesContentBasedEqualityAndHashCode() {
        val left = BinaryInput.RawBytes(byteArrayOf(1, 2, 3))
        val right = BinaryInput.RawBytes(byteArrayOf(1, 2, 3))
        val different = BinaryInput.RawBytes(byteArrayOf(1, 2, 4))

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
        assertNotEquals(left, different)
    }

    @Test
    fun rawBytesDefensivelyCopiesInputAndOutputArrays() {
        val source = byteArrayOf(1, 2, 3)
        val rawBytes = BinaryInput.RawBytes(source)

        source[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), rawBytes.bytes)

        val exposedBytes = rawBytes.bytes
        exposedBytes[1] = 8
        assertContentEquals(byteArrayOf(1, 2, 3), rawBytes.bytes)
    }

    @Test
    fun loadsWasmFromRawBytes() {
        val loaded = BinaryInput.RawBytes(minimalWasm).loadBinary()

        assertIs<WasmBinaryProvider>(loaded.provider)
        assertEquals("wasm", loaded.identity.format)
        assertEquals("WebAssembly", loaded.identity.displayFormat)
        assertEquals(minimalWasm.size, loaded.identity.byteLength)
    }

    @Test
    fun loadsWasmComponentFromRawBytes() {
        val loaded = BinaryInput.RawBytes(minimalComponent).loadBinary()

        assertIs<WasmComponentBinaryProvider>(loaded.provider)
        assertEquals("wasm_component", loaded.identity.format)
    }

    @Test
    fun loadsNativeProviderAfterWasmSelectorFallsBackToGeneric() {
        val loaded = BinaryInput.RawBytes(minimalElf).loadBinary()

        assertIs<ElfBinaryProvider>(loaded.provider)
        assertEquals("elf", loaded.identity.format)
        assertEquals("ELF64", loaded.identity.displayFormat)
        assertEquals("little", loaded.identity.endian)
        assertEquals("64", loaded.identity.wordSize)
    }

    @Test
    fun loadsArchiveProviderAfterWasmSelectorFallsBackToGeneric() {
        val loaded = BinaryInput.RawBytes(archiveMagic).loadBinary()

        assertIs<ArchiveBinaryProvider>(loaded.provider)
        assertEquals("archive", loaded.identity.format)
    }

    @Test
    fun unknownBytesLoadAsGenericBinary() {
        val loaded = BinaryInput.RawBytes(byteArrayOf(1, 2, 3, 4, 5)).loadBinary()

        assertIs<GenericBinaryProvider>(loaded.provider)
        assertEquals("generic", loaded.identity.format)
        assertEquals("Generic binary", loaded.identity.displayFormat)
    }

    @Test
    fun loadsBase64Input() {
        val base64 = Base64.getEncoder().encodeToString(minimalWasm)

        val loaded = BinaryInput.InlineBytes(base64).loadBinary()

        assertIs<WasmBinaryProvider>(loaded.provider)
    }

    @Test
    fun loadsFilePathInputWithoutWasmExtensionRestriction() {
        val file = Files.createTempFile("hexana-binary-input", ".bin")
        try {
            Files.write(file, minimalElf)

            val loaded = BinaryInput.FilePath(file.toString()).loadBinary()

            assertIs<ElfBinaryProvider>(loaded.provider)
            assertEquals(file.toString(), loaded.identity.path)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun rejectsInvalidBase64WithHexanaAnalysisException() {
        val error = assertFailsWith<HexanaAnalysisException> {
            BinaryInput.InlineBytes("not valid base64").loadBinary()
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertEquals("Inline bytes must be valid base64", error.message)
    }

    @Test
    fun rejectsInlineInputThatExceedsLimit() {
        val error = assertFailsWith<HexanaAnalysisException> {
            BinaryInput.RawBytes(byteArrayOf(1, 2, 3)).loadBinary(maxInlineInputBytes = 2)
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertEquals("Inline bytes exceed maximum size of 2 bytes", error.message)
    }

    @Test
    fun rejectsFileInputThatExceedsLimit() {
        val file = Files.createTempFile("hexana-binary-input", ".bin")
        try {
            Files.write(file, byteArrayOf(1, 2, 3))

            val error = assertFailsWith<HexanaAnalysisException> {
                BinaryInput.FilePath(file.toString()).loadBinary(maxFileInputBytes = 2)
            }

            assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
            assertEquals("File exceeds maximum size of 2 bytes", error.message)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun defaultServiceCanLoadBinaryIdentity() {
        val service = DefaultHexanaAnalysisService()

        val identity = service.loadBinary(BinaryInput.RawBytes(byteArrayOf(1, 2, 3)))

        assertEquals("generic", identity.format)
    }

    @Test
    fun defaultServiceSummarizesWasmBinary() {
        val service = DefaultHexanaAnalysisService()

        val summary = service.summarizeBinary(BinaryInput.RawBytes(minimalWasm))

        assertEquals("wasm", summary.module.format)
        assertEquals(minimalWasm.size, summary.module.byteLength)
        assertEquals(0, summary.counts.functions)
        assertEquals(0, summary.counts.imports)
        assertEquals(0, summary.counts.exports)
        assertEquals(0, summary.warnings.size)
    }

    @Test
    fun defaultServiceSummarizesNativeIdentityWithPartialWarnings() {
        val service = DefaultHexanaAnalysisService()

        val summary = service.summarizeBinary(BinaryInput.RawBytes(minimalElf))

        assertEquals("elf", summary.module.format)
        assertEquals("64", summary.module.wordSize)
        assertTrue(summary.warnings.any { it.code == "partial_parse" })
    }

    @Test
    fun defaultServiceSummarizesUnknownBytesAsGeneric() {
        val service = DefaultHexanaAnalysisService()

        val summary = service.summarizeBinary(BinaryInput.RawBytes(byteArrayOf(1, 2, 3)))

        assertEquals("generic", summary.module.format)
        assertEquals(3, summary.module.byteLength)
        assertEquals(0, summary.warnings.size)
    }

    @Test
    fun binaryDtosAreSerializable() {
        val identity = BinaryModuleIdentityResult(
            path = "dist/app.bin",
            format = "generic",
            displayFormat = "Generic binary",
            byteLength = 3,
        )
        val summaryJson = encodeHexanaJson(
            BinarySummaryResult(
                module = identity,
                warnings = listOf(BinaryWarningResult("partial_parse", "Some records could not be parsed")),
            )
        )
        val queryJson = encodeHexanaJson(
            QueryBinaryResult(
                module = identity,
                operation = "overview",
                total = 1,
                offset = 0,
                returned = 1,
                nextOffset = null,
                records = listOf(BinaryRecordResult(kind = "bytes", summary = "3 bytes")),
                omitted = listOf(BinaryOmittedResult(kind = "strings", count = 2, reason = "limit")),
            )
        )

        assertTrue(summaryJson.contains("\"format\":\"generic\""))
        assertTrue(queryJson.contains("\"operation\":\"overview\""))
    }

    private companion object {
        val minimalWasm = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
        )
        val minimalComponent = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x0d, 0x00, 0x00, 0x00,
        )
        val minimalElf = byteArrayOf(
            0x7f, 0x45, 0x4c, 0x46,
            0x02, 0x01, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x3e, 0x00,
        )
        val archiveMagic = byteArrayOf(0x21, 0x3c, 0x61, 0x72, 0x63, 0x68, 0x3e, 0x0a)
    }
}

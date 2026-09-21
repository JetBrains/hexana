package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [resolveBinaryProviderKind] and [createBinaryProvider] — the dispatch every
 * Hexana host shares.
 *
 * The bytes here are synthetic headers, deliberately not parseable files: the factory's
 * job is to pick a provider from magic bytes and a name, and each provider's own parsing
 * is covered against real fixtures elsewhere (`plugins-shared`'s `ParquetArrowTabsTest`,
 * the per-format tests under `parsing/`).
 *
 * What these tests guard is the property that made them necessary: the IntelliJ editor
 * and the VS Code webview used to decide the format independently, and the copies
 * drifted — Parquet, Arrow and Protobuf-message files resolved in one host and fell
 * through to a raw hex view in the other.
 */
class BinaryProviderFactoryTest {

    // ------------------------------------------------------------------
    // Formats no magic can identify: resolved by extension
    // ------------------------------------------------------------------

    @Test
    fun pbExtensionResolvesToProtobufMessage() {
        // A wire message has no magic whatsoever, so the name is the only signal there
        // is — these bytes are a single `field 1: varint 1`.
        val bytes = byteArrayOf(0x08, 0x01)
        assertEquals(BinaryProviderKind.ProtobufMessage, resolveBinaryProviderKind(bytes.buffer(), "payload.pb"))
        assertIs<ProtobufMessageBinaryProvider>(createBinaryProvider(bytes.buffer(), "payload.pb"))

        // The same bytes without the name are just bytes.
        assertIs<GenericBinaryProvider>(createBinaryProvider(bytes.buffer(), null))
    }

    @Test
    fun parquetExtensionResolvesEvenWithoutMagic() {
        val truncated = ByteArray(64)
        assertEquals(BinaryProviderKind.Parquet, resolveBinaryProviderKind(truncated.buffer(), "part-0.parquet"))
        assertIs<ParquetBinaryProvider>(createBinaryProvider(truncated.buffer(), "part-0.parquet"))
    }

    @Test
    fun arrowExtensionResolvesEvenWithoutMagic() {
        val truncated = ByteArray(64)
        assertEquals(
            BinaryProviderKind.Arrow(ArrowFormat.FILE),
            resolveBinaryProviderKind(truncated.buffer(), "table.arrow"),
        )
    }

    // ------------------------------------------------------------------
    // Formats resolved from content, whatever they are called
    // ------------------------------------------------------------------

    @Test
    fun parquetMagicResolvesWithoutAName() {
        val bytes = emptyParquetBytes()
        assertEquals(BinaryProviderKind.Parquet, resolveBinaryProviderKind(bytes.buffer()))
        assertIs<ParquetBinaryProvider>(createBinaryProvider(bytes.buffer()))
        // An extensionless dump, or one a tool named something else, still resolves.
        assertIs<ParquetBinaryProvider>(createBinaryProvider(bytes.buffer(), "/tmp/export"))
        assertIs<ParquetBinaryProvider>(createBinaryProvider(bytes.buffer(), "export.bin"))
    }

    @Test
    fun arrowFileMagicResolvesToTheFileFormat() {
        val bytes = arrowFileBytes()
        assertEquals(BinaryProviderKind.Arrow(ArrowFormat.FILE), resolveBinaryProviderKind(bytes.buffer()))
        val provider = assertIs<ArrowBinaryProvider>(createBinaryProvider(bytes.buffer()))
        assertEquals(ArrowFormat.FILE, provider.format)
    }

    @Test
    fun wasmAndComponentMagicResolveFromContentAlone() {
        val module = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val component = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)

        assertEquals(BinaryProviderKind.Wasm, resolveBinaryProviderKind(module.buffer()))
        assertEquals(BinaryProviderKind.WasmComponent, resolveBinaryProviderKind(component.buffer()))

        // Extensionless is the case the IntelliJ editor used to miss: it keyed the whole
        // Wasm branch off an `ext == "wasm"` test, so a module written without a name
        // fell through to a plain hex view.
        assertIs<WasmBinaryProvider>(createBinaryProvider(module.buffer(), "/tmp/build/module"))
        assertIs<WasmComponentBinaryProvider>(createBinaryProvider(component.buffer(), "/tmp/build/comp"))
    }

    @Test
    fun contentWinsOverAMisleadingExtension() {
        // Parquet, Arrow-file and Wasm magic are all long and anchored, so a file
        // carrying one is that format whatever a build step named it — including named
        // as one of the other two, and including the `.pb` name that is otherwise the
        // only signal a Protobuf message has.
        val module = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val component = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)

        for (name in listOf("x.parquet", "x.arrow", "x.arrows", "x.pb", "x.wasm")) {
            assertEquals(BinaryProviderKind.Wasm, resolveBinaryProviderKind(module.buffer(), name), name)
            assertEquals(BinaryProviderKind.WasmComponent, resolveBinaryProviderKind(component.buffer(), name), name)
        }
        for (name in listOf("x.arrow", "x.arrows", "x.pb")) {
            assertEquals(BinaryProviderKind.Parquet, resolveBinaryProviderKind(emptyParquetBytes().buffer(), name), name)
        }
        for (name in listOf("x.parquet", "x.pb")) {
            assertEquals(
                BinaryProviderKind.Arrow(ArrowFormat.FILE),
                resolveBinaryProviderKind(arrowFileBytes().buffer(), name),
                name,
            )
        }
        assertIs<ParquetBinaryProvider>(createBinaryProvider(emptyParquetBytes().buffer(), "x.arrow"))
    }

    // ------------------------------------------------------------------
    // Arrow streams
    // ------------------------------------------------------------------

    @Test
    fun arrowStreamNeedsBothTheExtensionAndTheContinuationMarker() {
        // The stream format's only marker is a 0xFFFFFFFF continuation word, which any
        // all-0xFF prefix matches — a padded ROM image, a corrupted binary. So it counts
        // only once the name has already said Arrow.
        val stream = arrowStreamBytes()
        assertEquals(
            BinaryProviderKind.Arrow(ArrowFormat.STREAM),
            resolveBinaryProviderKind(stream.buffer(), "batches.arrows"),
        )
        assertEquals(
            BinaryProviderKind.Arrow(ArrowFormat.STREAM),
            resolveBinaryProviderKind(stream.buffer(), "batches.arrow"),
        )
        assertEquals(BinaryProviderKind.NativeOrGeneric, resolveBinaryProviderKind(stream.buffer(), "rom.bin"))
        assertEquals(BinaryProviderKind.NativeOrGeneric, resolveBinaryProviderKind(stream.buffer()))
    }

    @Test
    fun arrowFileNamedLikeAStreamStaysAFile() {
        // `ARROW1` does not begin with the continuation word, so the strong magic decides.
        assertEquals(
            BinaryProviderKind.Arrow(ArrowFormat.FILE),
            resolveBinaryProviderKind(arrowFileBytes().buffer(), "table.arrows"),
        )
    }

    // ------------------------------------------------------------------
    // Delegation to nativeOrGenericBinaryProvider
    // ------------------------------------------------------------------

    @Test
    fun otherFormatsFallThroughToTheMagicOnlyFactory() {
        assertIs<ElfBinaryProvider>(createBinaryProvider(elfHeaderBytes().buffer(), "libfoo.so"))
        assertIs<ZipBinaryProvider>(createBinaryProvider(emptyZipBytes().buffer(), "app.jar"))
        assertIs<GenericBinaryProvider>(createBinaryProvider(ByteArray(64).buffer(), "mystery.bin"))
    }

    // ------------------------------------------------------------------
    // Name handling
    // ------------------------------------------------------------------

    @Test
    fun theExtensionComesFromTheLastPathSegment() {
        val truncated = ByteArray(64)
        // A dot in a *directory* name must not lend its suffix to an extensionless file,
        // and both separators appear because callers pass native paths from every host.
        assertEquals(
            BinaryProviderKind.NativeOrGeneric,
            resolveBinaryProviderKind(truncated.buffer(), "/data/v1.parquet/_SUCCESS"),
        )
        assertEquals(
            BinaryProviderKind.Parquet,
            resolveBinaryProviderKind(truncated.buffer(), "/data/warehouse/part-0.PARQUET"),
        )
        assertEquals(
            BinaryProviderKind.Parquet,
            resolveBinaryProviderKind(truncated.buffer(), """C:\data\part-0.parquet"""),
        )
        // A dotfile has a name, not an extension.
        assertEquals(BinaryProviderKind.NativeOrGeneric, resolveBinaryProviderKind(truncated.buffer(), ".pb"))
        assertEquals(BinaryProviderKind.NativeOrGeneric, resolveBinaryProviderKind(truncated.buffer(), "trailing."))
    }

    @Test
    fun theSourcePathIsRecordedSeparatelyFromTheNameTheExtensionComesFrom() {
        // A host that inflated `part-0.parquet.gz` holds bytes named `part-0.parquet`
        // while the path on disk still ends `.gz`; the provider must resolve by the
        // former and report the latter, which is what scripts read as `binary.path`.
        val provider = createBinaryProvider(
            ByteArray(64).buffer(),
            sourcePath = "/data/part-0.parquet.gz",
            fileName = "part-0.parquet",
        )
        assertEquals("/data/part-0.parquet.gz", assertIs<ParquetBinaryProvider>(provider).sourcePath)
    }

    // ------------------------------------------------------------------
    // Buffer handling
    // ------------------------------------------------------------------

    @Test
    fun aBufferHandedOverMidReadIsStillResolvedFromItsStart() {
        // Providers read through `sharedBytes.duplicate()`, and a duplicate inherits the
        // source cursor — so a buffer left mid-read would have them parsing from the
        // middle of the file.
        val bytes = emptyParquetBytes().buffer()
        bytes.position(4)
        assertEquals(BinaryProviderKind.Parquet, resolveBinaryProviderKind(bytes))
        assertIs<ParquetBinaryProvider>(createBinaryProvider(bytes))
        // And resolution leaves the caller's own cursor where it found it.
        assertEquals(4, bytes.position())
    }

    @Test
    fun everyAccessToProviderBytesGetsItsOwnCursor() {
        // The contract in `BinaryProvider.bytes`: concurrent readers must not clobber
        // each other, so the factory must never hand a provider a buffer it aliases.
        val provider = createBinaryProvider(emptyParquetBytes().buffer(), "part-0.parquet")
        val first = provider.bytes
        first.position(4)
        // A second reader starts at 0 regardless of where the first one got to, and both
        // still see the same bytes — separate cursors over shared content.
        assertEquals(0, provider.bytes.position())
        assertEquals(first.size, provider.bytes.size)
        assertEquals(first[0], provider.bytes[0])
    }
}

private fun ByteArray.buffer() = toCommonByteBuffer().also { it.position(0) }

/**
 * The smallest valid Parquet file: `PAR1`, a Thrift-compact `FileMetaData` that is a
 * lone STOP byte, the footer length, and `PAR1` again. `isParquetMagic` checks both ends.
 */
private fun emptyParquetBytes(): ByteArray =
    "PAR1".encodeToByteArray() + byteArrayOf(0, 1, 0, 0, 0) + "PAR1".encodeToByteArray()

/** `ARROW1` plus padding plus `ARROW1`: `isArrowFileMagic` checks both ends. */
private fun arrowFileBytes(): ByteArray =
    "ARROW1".encodeToByteArray() + ByteArray(16) + "ARROW1".encodeToByteArray()

/** An Arrow IPC stream opens with the 0xFFFFFFFF continuation word. */
private fun arrowStreamBytes(): ByteArray =
    ByteArray(4) { 0xFF.toByte() } + ByteArray(28)

/** A 64-bit little-endian ELF header, enough for `NativeBinaryFormatDetector`. */
private fun elfHeaderBytes(): ByteArray {
    val bytes = ByteArray(64)
    bytes[0] = 0x7F
    bytes[1] = 'E'.code.toByte()
    bytes[2] = 'L'.code.toByte()
    bytes[3] = 'F'.code.toByte()
    bytes[4] = 2 // ELFCLASS64
    bytes[5] = 1 // ELFDATA2LSB
    bytes[6] = 1 // EV_CURRENT
    bytes[16] = 2 // e_type = ET_EXEC
    bytes[18] = 0x3E // e_machine = x86-64
    return bytes
}

/** `PK\x03\x04`, the local-file-header signature `ZipBinaryProvider.isZip` keys off. */
private fun emptyZipBytes(): ByteArray =
    byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(26)

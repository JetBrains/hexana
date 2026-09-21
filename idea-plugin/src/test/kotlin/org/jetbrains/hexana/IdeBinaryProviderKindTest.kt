package org.jetbrains.hexana

import com.intellij.openapi.fileTypes.PlainTextFileType
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ArrowFormat
import org.jetbrains.hexana.providers.BinaryProviderKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The IDE's refinement of the shared provider decision.
 *
 * The file type registry is a signal `resolveBinaryProviderKind` structurally cannot
 * see: it is where a user binds their own patterns under Settings | Editor | File
 * Types, and where `ColumnarBinaryFileTypeDetector`'s verdict is recorded. An earlier
 * cut of the shared factory dropped these checks on the grounds that the detector is
 * magic-based and the factory now does magic itself — which silently threw away every
 * association the user had configured by hand.
 */
class IdeBinaryProviderKindTest {

    private fun kindOf(bytes: ByteArray, fileName: String, fileType: com.intellij.openapi.fileTypes.FileType) =
        resolveIdeBinaryProviderKind(bytes.toCommonByteBuffer().also { it.position(0) }, fileName, fileType)

    @Test
    fun `a user-associated pattern resolves through its file type`() {
        // `*.pq` bound to Parquet: an extension the shared factory has never heard of,
        // on a file whose trailing magic is gone because it was truncated mid-write.
        val truncated = "PAR1".encodeToByteArray() + ByteArray(32)
        assertEquals(
            BinaryProviderKind.Parquet,
            kindOf(truncated, "part-0.pq", ParquetFileType.INSTANCE),
        )
        assertEquals(
            BinaryProviderKind.Arrow(ArrowFormat.FILE),
            kindOf(ByteArray(32), "table.ipc", ArrowFileType.INSTANCE),
        )
        assertEquals(
            BinaryProviderKind.ProtobufMessage,
            kindOf(byteArrayOf(0x08, 0x01), "payload.wire", ProtobufMessageFileType.INSTANCE),
        )
    }

    @Test
    fun `an arrow association still tells a stream from a file`() {
        val stream = ByteArray(32).also { it.fill(0xFF.toByte(), 0, 4) }
        assertEquals(
            BinaryProviderKind.Arrow(ArrowFormat.STREAM),
            kindOf(stream, "batches.ipc", ArrowFileType.INSTANCE),
        )
    }

    @Test
    fun `content still wins over the declared type`() {
        // A declared association says what a file is *called*, never what it holds. A
        // real wasm module a user mapped to Parquet is still a wasm module.
        val module = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        assertEquals(BinaryProviderKind.Wasm, kindOf(module, "mod.pq", ParquetFileType.INSTANCE))

        val parquet = "PAR1".encodeToByteArray() + byteArrayOf(0, 1, 0, 0, 0) + "PAR1".encodeToByteArray()
        assertEquals(BinaryProviderKind.Parquet, kindOf(parquet, "x.ipc", ArrowFileType.INSTANCE))
    }

    @Test
    fun `the name still wins over the declared type`() {
        assertEquals(
            BinaryProviderKind.Parquet,
            kindOf(ByteArray(32), "part-0.parquet", ProtobufMessageFileType.INSTANCE),
        )
    }

    @Test
    fun `a file type Hexana has no provider for changes nothing`() {
        assertEquals(
            BinaryProviderKind.NativeOrGeneric,
            kindOf(ByteArray(32), "mystery.bin", PlainTextFileType.INSTANCE),
        )
        assertEquals(
            BinaryProviderKind.NativeOrGeneric,
            kindOf(ByteArray(32), "mystery.bin", BinaryFileType.INSTANCE),
        )
    }
}

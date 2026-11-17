package org.jetbrains.hexana

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.io.ByteSequence

/**
 * Magic-byte detector for columnar binary formats (Parquet and Arrow IPC file).
 *
 * Deliberately separate from [NativeBinaryFileTypeDetector] so the two detectors are
 * independently registered and do not interfere with each other's logic.
 *
 * Arrow STREAM format is intentionally NOT detected here. Its only marker is a
 * 0xFFFFFFFF continuation word at offset 0, which is far too weak — any file whose
 * first four bytes happen to be all-0xFF (e.g. a raw ARM Thumb-2 instruction stream,
 * a corrupted binary) would be mis-identified. Arrow stream files are still supported
 * via the .arrow extension in [HexanaFileEditorProvider.accept] and the buffer-level
 * check in [HexanaFileEditor.resolveProvider].
 */
class ColumnarBinaryFileTypeDetector : FileTypeRegistry.FileTypeDetector {

    // 6 bytes is sufficient for ARROW1 (the longer of the two signatures).
    override fun getDesiredContentPrefixLength(): Int = 0x10

    override fun detect(file: VirtualFile, firstBytes: ByteSequence, firstCharsIfText: CharSequence?): FileType? {
        val len = firstBytes.length()

        // Parquet: magic "PAR1" (50 41 52 31) at offset 0.
        // Parquet files also carry PAR1 at the tail, but the detector only receives a
        // content prefix — head detection is correct and sufficient here.
        if (len >= 4 &&
            firstBytes.byteAt(0) == 0x50.toByte() &&
            firstBytes.byteAt(1) == 0x41.toByte() &&
            firstBytes.byteAt(2) == 0x52.toByte() &&
            firstBytes.byteAt(3) == 0x31.toByte()
        ) {
            return ParquetFileType.INSTANCE
        }

        // Arrow IPC file: magic "ARROW1" (41 52 52 4F 57 31) at offset 0.
        if (len >= 6 &&
            firstBytes.byteAt(0) == 0x41.toByte() &&
            firstBytes.byteAt(1) == 0x52.toByte() &&
            firstBytes.byteAt(2) == 0x52.toByte() &&
            firstBytes.byteAt(3) == 0x4F.toByte() &&
            firstBytes.byteAt(4) == 0x57.toByte() &&
            firstBytes.byteAt(5) == 0x31.toByte()
        ) {
            return ArrowFileType.INSTANCE
        }

        return null
    }
}
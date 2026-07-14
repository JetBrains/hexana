package org.jetbrains.hexana.analytics

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.CoffBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.FatMachOBinaryProvider
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.ArrowBinaryProvider
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.ParquetBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider

/**
 * Maps a `(BinaryProvider, VirtualFile)` pair to the analytics-side
 * [HexanaEvent.BinaryFormat] enum. Single source of truth so the
 * `binary_opened` event and the `hex_tab_selected` enrichment agree on
 * which format string each editor is.
 *
 * The archive / `.class` editors don't set [BinaryProvider]; their format
 * is recovered from the file extension. Order matters: provider-typed
 * cases are checked first so a `.bin`-named ELF doesn't get tagged
 * GENERIC.
 */
internal fun binaryFormatOf(
    file: VirtualFile?,
    provider: BinaryProvider?,
): HexanaEvent.BinaryFormat {
    when (provider) {
        is WasmComponentBinaryProvider -> return HexanaEvent.BinaryFormat.WASM_COMPONENT
        is WasmBinaryProvider -> return HexanaEvent.BinaryFormat.WASM
        is ElfBinaryProvider -> return HexanaEvent.BinaryFormat.ELF
        is MachOBinaryProvider -> return HexanaEvent.BinaryFormat.MACHO
        is FatMachOBinaryProvider -> return HexanaEvent.BinaryFormat.FAT_MACHO
        is PeBinaryProvider -> return HexanaEvent.BinaryFormat.PE
        is CoffBinaryProvider -> return HexanaEvent.BinaryFormat.COFF
        is ParquetBinaryProvider -> return HexanaEvent.BinaryFormat.PARQUET
        is ArrowBinaryProvider -> return HexanaEvent.BinaryFormat.ARROW_IPC
        is ArchiveBinaryProvider -> return HexanaEvent.BinaryFormat.GENERIC
        // ProtobufMessageBinaryProvider (explicit Open-As) intentionally reports as GENERIC — not an auto-detected format.
        is GenericBinaryProvider -> return HexanaEvent.BinaryFormat.GENERIC
        // Zip / class / dex providers are classified by extension below (jar/war/apk/zip/class).
        is ZipBinaryProvider, is ClassFileBinaryProvider, is DexBinaryProvider, null -> Unit
    }
    return when (file?.extension?.lowercase()) {
        "class" -> HexanaEvent.BinaryFormat.CLASS
        "jar" -> HexanaEvent.BinaryFormat.JAR
        "war" -> HexanaEvent.BinaryFormat.WAR
        "apk" -> HexanaEvent.BinaryFormat.APK
        "zip" -> HexanaEvent.BinaryFormat.ZIP
        else -> HexanaEvent.BinaryFormat.GENERIC
    }
}

/**
 * Sub-classification of a [HexanaEvent.BinaryFormat.GENERIC] open: the file
 * extension (bounded to [HexanaEvent.GenericExt], never the raw string) plus
 * a content magic-byte sniff ([HexanaEvent.DetectedFormat], null when no
 * magic matched). Call only when [binaryFormatOf] returned GENERIC.
 *
 * The magic sniff reads at most the first [HEADER_SNIFF_BYTES] bytes from the
 * provider's already-loaded buffer — no fresh disk I/O — and never retains
 * them. The window is wide enough to reach tar's `ustar` magic at offset 257.
 */
internal data class GenericDetail(
    val ext: HexanaEvent.GenericExt,
    val detected: HexanaEvent.DetectedFormat?,
    val header: String?,
)

internal fun genericDetailOf(
    file: VirtualFile?,
    provider: BinaryProvider?,
): GenericDetail = GenericDetail(
    ext = HexanaEvent.GenericExt.fromExtension(file?.extension),
    detected = provider?.let { detectMagic(it.bytes) },
    header = provider?.let { readGenericHeader(it.bytes) },
)

/**
 * Header window read for the magic-byte sniff. 512 bytes (one tar record)
 * covers every offset-0 magic and reaches tar's `ustar` marker at offset 257.
 */
private const val HEADER_SNIFF_BYTES = 512

/**
 * Reads up to [HEADER_SNIFF_BYTES] header bytes from [buffer] (an
 * independent-cursor duplicate per [BinaryProvider.bytes]) and classifies
 * them. Defensive: any failure yields null — analytics must never break the
 * editor open path.
 */
private fun detectMagic(buffer: CommonByteBuffer): HexanaEvent.DetectedFormat? = try {
    val n = minOf(HEADER_SNIFF_BYTES, buffer.size)
    if (n < 4) {
        null
    } else {
        val head = ByteArray(n)
        buffer.position(0)
        buffer.get(head, 0, n)
        // Classification table is shared with the VS Code surface; see
        // HexanaEvent.DetectedFormat.fromMagic.
        HexanaEvent.DetectedFormat.fromMagic(head)
    }
} catch (_: Throwable) {
    null
}

/**
 * Reads the first 8 bytes from [buffer] and classifies them via
 * [HexanaEvent.GenericHeader.of]. Returns null on any failure — analytics
 * must never break the editor open path.
 */
private fun readGenericHeader(buffer: CommonByteBuffer): String? = try {
    if (buffer.size < 8) {
        null
    } else {
        val head = ByteArray(8)
        buffer.position(0)
        buffer.get(head, 0, 8)
        HexanaEvent.GenericHeader.of(head)
    }
} catch (_: Throwable) {
    null
}
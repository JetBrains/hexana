package org.jetbrains.hexana.analytics

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.CoffBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.FatMachOBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

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
        is GenericBinaryProvider -> return HexanaEvent.BinaryFormat.GENERIC
        null -> Unit
        else -> return HexanaEvent.BinaryFormat.GENERIC
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
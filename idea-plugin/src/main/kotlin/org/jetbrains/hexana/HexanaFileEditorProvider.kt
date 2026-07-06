package org.jetbrains.hexana

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.analytics.binaryFormatOf
import org.jetbrains.hexana.analytics.genericDetailOf
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.providers.CoffBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.FatMachOBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.statistics.HexanaCounterUsagesCollector
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode

class HexanaFileEditorProvider : FileEditorProvider, DumbAware {
    companion object {
        const val EDITOR_TYPE_ID: String = "hexana-editor"
    }

    override fun accept(
        project: Project,
        file: VirtualFile
    ): Boolean {
        return file.fileType is WasmFileType ||
            file.fileType is BinaryFileType ||
            file.fileType is NativeBinaryFileType ||
            file.fileType is ParquetFileType ||
            file.fileType is ArrowFileType ||
            file.fileType is ProtobufFileType ||
            file.fileType is ProtobufMessageFileType ||
            file.extension.equals("class", ignoreCase = true) ||
            file.extension.equals("jar", ignoreCase = true) ||
            file.extension.equals("zip", ignoreCase = true) ||
            file.extension.equals("war", ignoreCase = true) ||
            file.extension.equals("apk", ignoreCase = true) ||
            file.extension.equals("jit", ignoreCase = true) ||
            file.extension.equals("dex", ignoreCase = true) ||
            file.extension.equals("parquet", ignoreCase = true) ||
            file.extension.equals("arrow", ignoreCase = true)
    }

    override fun createEditor(
        project: Project,
        file: VirtualFile
    ): FileEditor {
        val service = HexanaService.getInstance()
        if (file.fileType is WasmFileType) {
            // Legacy JetBrains FUS counter — kept until the IDE-side FUS
            // schema is broadened to cover non-WASM formats. PostHog
            // analytics are emitted below via `buildBinaryOpenedEvent` and
            // cover every binary format.
            HexanaCounterUsagesCollector.logWasmFileOpened(project)
        }
        val editor = HexanaFileEditor(project, file)
        if (!isJitFile(file)) {
            // JIT dumps get their own `jit_dump_opened` event (separate
            // taxonomy entry — see HexanaEvent.kt). Skip the binary_opened
            // fire for them so we don't double-count.
            service.captureAnalytics(buildBinaryOpenedEvent(file, editor))
        }
        if (file.fileType is WasmFileType) {
            reportUnparsedOpcodes(editor)
        }
        return editor
    }

    override fun getEditorTypeId(): @NonNls String {
        return EDITOR_TYPE_ID
    }

    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
    }

    private fun reportUnparsedOpcodes(editor: HexanaFileEditor) {
        val provider = editor.binaryProvider as? WasmBinaryProvider ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val codeRecords = provider.codeRecords
            val totalFunctions = codeRecords.size
            val unparsedCount = codeRecords.count { record ->
                record.opCodes.any { it is UnparsedOpCode }
            }
            if (unparsedCount > 0) {
                HexanaService.getInstance().captureAnalytics(
                    HexanaEvent.WasmUnparsedOpcodes(
                        unparsedCount = unparsedCount,
                        totalFunctions = totalFunctions
                    )
                )
            }
        }
    }
}

private fun isJitFile(file: VirtualFile): Boolean =
    file.extension.equals("jit", ignoreCase = true)

/**
 * Builds the `binary_opened` analytics event for the editor's binary. For
 * archive / class / JIT-less paths, [HexanaFileEditor.binaryProvider] is
 * not populated (those panels take a different rendering path); the format
 * falls back to the file extension. For native binaries the helper also
 * fills `arch`, `is_stripped`, `has_debug_info`, and `symbol_count_bucket`.
 * For WASM and archive formats those native-only fields stay null.
 */
private fun buildBinaryOpenedEvent(
    file: VirtualFile,
    editor: HexanaFileEditor,
): HexanaEvent.BinaryOpened {
    val format = binaryFormatOf(file, editor.binaryProvider)
    val native = editor.binaryProvider as? NativeBinaryProvider
    // Only the GENERIC catch-all carries the extension + magic-sniff
    // breakdown; structured formats already identify themselves via `format`.
    val genericDetail = if (format == HexanaEvent.BinaryFormat.GENERIC) {
        genericDetailOf(file, editor.binaryProvider)
    } else {
        null
    }
    return HexanaEvent.BinaryOpened(
        format = format,
        fileSizeBytes = file.length,
        surface = HexanaEvent.Surface.IDE_PLUGIN,
        arch = native?.let { archFor(it) },
        isStripped = native?.let { isStripped(it) },
        hasDebugInfo = native?.let { hasDebugInfo(it) },
        symbolCountBucket = native?.let {
            HexanaEvent.SymbolCountBucket.of(symbolCount(it))
        },
        genericExt = genericDetail?.ext,
        detectedFormat = genericDetail?.detected,
    )
}

private fun archFor(provider: NativeBinaryProvider): HexanaEvent.Arch? =
    when (val format = provider.nativeFormat) {
        is NativeBinaryFormat.Elf -> elfArch(format.machine, format.wordSize)
        is NativeBinaryFormat.Pe -> peArch(format.machine)
        is NativeBinaryFormat.Coff -> peArch(format.machine)
        is NativeBinaryFormat.MachO -> machOArch(format.cpuType)
        // FatMachO multiplexes slices of different architectures; Archive
        // is a container. Neither has a single representative arch.
        is NativeBinaryFormat.Archive,
        is NativeBinaryFormat.FatMachO,
        -> null
    }

private fun elfArch(machine: Int?, wordSize: NativeBinaryWordSize): HexanaEvent.Arch? = when (machine) {
    0x03 -> HexanaEvent.Arch.X86
    0x28 -> HexanaEvent.Arch.ARM
    0x3E -> HexanaEvent.Arch.X86_64
    0xB7 -> HexanaEvent.Arch.ARM64
    0xF3 -> if (wordSize == NativeBinaryWordSize.Bits32) HexanaEvent.Arch.RISCV32 else HexanaEvent.Arch.RISCV64
    else -> null
}

private fun peArch(machine: Int?): HexanaEvent.Arch? = when (machine) {
    0x014C -> HexanaEvent.Arch.X86
    0x01C0, 0x01C4 -> HexanaEvent.Arch.ARM
    0x8664 -> HexanaEvent.Arch.X86_64
    0xAA64 -> HexanaEvent.Arch.ARM64
    0x5032 -> HexanaEvent.Arch.RISCV32
    0x5064 -> HexanaEvent.Arch.RISCV64
    else -> null
}

private fun machOArch(cpuType: Int?): HexanaEvent.Arch? = when (cpuType) {
    0x00000007 -> HexanaEvent.Arch.X86
    0x01000007 -> HexanaEvent.Arch.X86_64
    0x0000000C -> HexanaEvent.Arch.ARM
    0x0100000C -> HexanaEvent.Arch.ARM64
    0x01000018 -> HexanaEvent.Arch.RISCV64
    else -> null
}

/**
 * "Stripped" here means Hexana's symbol-discovery phase yielded no
 * non-imported function records — equivalent to "no usable static symbols"
 * from the analyst's perspective. Dynamic-only binaries (PIE executables
 * with `.dynsym` only) still surface their imports, but we treat the lack
 * of *defining* symbols as the stripped signal because that's what drives
 * the disassembly view's fallback-to-section UX.
 */
private fun isStripped(provider: NativeBinaryProvider): Boolean? {
    val functions = provider.nativeFunctions ?: return null
    return functions.none { !it.imported }
}

private fun symbolCount(provider: NativeBinaryProvider): Int =
    provider.nativeFunctions?.count { !it.imported } ?: 0

/**
 * Returns true if any section / segment name suggests debug info is
 * present. Conservative: matches ELF `.debug_*`, Mach-O `__DWARF` segment
 * and `__debug_*` sections, PE `.debug`. Doesn't try to validate the
 * sections are well-formed — for funnel analytics, presence is enough.
 */
private fun hasDebugInfo(provider: NativeBinaryProvider): Boolean? = when (provider) {
    is ElfBinaryProvider -> provider.sections?.any { section ->
        section.name.startsWith(".debug_") || section.name == ".gdb_index"
    }
    is MachOBinaryProvider -> provider.sections?.any { section ->
        section.segmentName == "__DWARF" || section.name.startsWith("__debug_")
    }
    is PeBinaryProvider -> provider.sections?.any { it.name.startsWith(".debug") }
    // CoffBinaryProvider's debug detection is less standardised across
    // toolchains (Microsoft vs GCC); skip for now to avoid a misleading
    // false-negative in PostHog. ArchiveBinaryProvider wraps a `.a` static
    // archive whose member objects each have their own debug sections —
    // not worth flattening for one boolean.
    else -> null
}
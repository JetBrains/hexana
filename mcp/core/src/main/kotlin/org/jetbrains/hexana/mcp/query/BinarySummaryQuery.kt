package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryCountsResult
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryWarningResult
import org.jetbrains.hexana.mcp.toModuleIdentity
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.CoffBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.FatMachOBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

fun org.jetbrains.hexana.providers.BinaryProvider.readBinarySummary(pathLabel: String? = null): BinarySummaryResult {
    val warnings = mutableListOf<BinaryWarningResult>()
    val counts = when (this) {
        is WasmBinaryProvider -> wasmCounts(warnings)
        is WasmComponentBinaryProvider -> componentCounts(warnings)
        is ElfBinaryProvider -> elfCounts(warnings)
        is MachOBinaryProvider -> machOCounts(warnings)
        is FatMachOBinaryProvider -> fatMachOCounts(warnings)
        is PeBinaryProvider -> peCounts(warnings)
        is CoffBinaryProvider -> coffCounts(warnings)
        is ArchiveBinaryProvider -> archiveCounts(warnings)
        is NativeBinaryProvider -> nativeCounts()
        is ZipBinaryProvider -> BinarySummaryCountsResult()
        is GenericBinaryProvider -> BinarySummaryCountsResult()
        else -> BinarySummaryCountsResult()
    }
    return BinarySummaryResult(
        module = toModuleIdentity(pathLabel),
        counts = counts,
        warnings = warnings,
    )
}

private fun WasmBinaryProvider.wasmCounts(warnings: MutableList<BinaryWarningResult>): BinarySummaryCountsResult {
    val module = wasmModule ?: run {
        warnings += BinaryWarningResult("partial_parse", "Detected WebAssembly core module, but parsing did not produce a module.")
        return BinarySummaryCountsResult()
    }
    val summary = readModuleSummary()
    return BinarySummaryCountsResult(
        functions = summary.functions,
        types = summary.types,
        memories = summary.memories,
        globals = summary.globals,
        tables = summary.tables,
        imports = imports.size,
        exports = summary.exports,
        sections = module.sections.size,
        segments = data.size + readElementSegmentCount(warnings),
    )
}

private fun WasmBinaryProvider.readElementSegmentCount(warnings: MutableList<BinaryWarningResult>): Int {
    val elementSection = wasmModule?.elementSection ?: return 0
    return countOrWarn(warnings, "Could not count WebAssembly element segments.") {
        with(elementSection) { bytes.scanElements().size }
    } ?: 0
}

private fun WasmComponentBinaryProvider.componentCounts(warnings: MutableList<BinaryWarningResult>): BinarySummaryCountsResult {
    val component = wasmModule ?: run {
        warnings += BinaryWarningResult("partial_parse", "Detected WebAssembly component, but parsing did not produce a component.")
        return BinarySummaryCountsResult()
    }
    return BinarySummaryCountsResult(
        types = types.size,
        imports = imports.size,
        exports = exports.size,
        sections = component.sections.size,
        coreModules = modules.size,
        nestedComponents = nestedComponents.size,
    )
}

private fun ElfBinaryProvider.elfCounts(warnings: MutableList<BinaryWarningResult>): BinarySummaryCountsResult =
    BinarySummaryCountsResult(
        functions = warnIfMissing(warnings, "Could not discover ELF functions.", nativeFunctions),
        symbols = warnIfMissing(warnings, "Could not parse ELF symbols.", symbols),
        imports = dynamicEntries?.count { it.neededLibrary != null },
        sections = warnIfMissing(warnings, "Could not parse ELF sections.", sections),
        segments = warnIfMissing(warnings, "Could not parse ELF program headers.", programHeaders),
        relocations = warnIfMissing(warnings, "Could not parse ELF relocations.", relocations),
        strings = strings.size,
    )

private fun MachOBinaryProvider.machOCounts(warnings: MutableList<BinaryWarningResult>): BinarySummaryCountsResult =
    BinarySummaryCountsResult(
        functions = warnIfMissing(warnings, "Could not discover Mach-O functions.", nativeFunctions),
        symbols = warnIfMissing(warnings, "Could not parse Mach-O symbols.", symbols),
        imports = warnIfMissing(warnings, "Could not parse Mach-O dylib load commands.", dylibs),
        sections = warnIfMissing(warnings, "Could not parse Mach-O sections.", sections),
        segments = warnIfMissing(warnings, "Could not parse Mach-O segments.", segments),
        loadCommands = warnIfMissing(warnings, "Could not parse Mach-O load commands.", loadCommands),
        relocations = warnIfMissing(warnings, "Could not parse Mach-O relocations.", relocations),
        strings = strings.size,
    )

private fun FatMachOBinaryProvider.fatMachOCounts(warnings: MutableList<BinaryWarningResult>): BinarySummaryCountsResult =
    BinarySummaryCountsResult(
        architectures = warnIfMissing(warnings, "Could not parse Mach-O universal slices.", architectures),
        strings = strings.size,
    )

private fun PeBinaryProvider.peCounts(warnings: MutableList<BinaryWarningResult>): BinarySummaryCountsResult =
    BinarySummaryCountsResult(
        symbols = warnIfMissing(warnings, "Could not parse PE/COFF symbols.", coffSymbols),
        imports = peImportCount(warnings),
        exports = exports?.entries?.size,
        sections = warnIfMissing(warnings, "Could not parse PE sections.", sections),
        relocations = warnIfMissing(warnings, "Could not parse PE base relocations.", baseRelocations),
        strings = strings.size,
    )

private fun PeBinaryProvider.peImportCount(warnings: MutableList<BinaryWarningResult>): Int? {
    val libraries = imports ?: run {
        warnings += BinaryWarningResult("partial_parse", "Could not parse PE imports.")
        return null
    }
    var total = 0
    for (library in libraries) {
        total += library.entries.size
    }
    return total
}

private fun CoffBinaryProvider.coffCounts(warnings: MutableList<BinaryWarningResult>): BinarySummaryCountsResult =
    BinarySummaryCountsResult(
        functions = warnIfMissing(warnings, "Could not discover COFF functions.", nativeFunctions),
        symbols = warnIfMissing(warnings, "Could not parse COFF symbols.", coffSymbols),
        sections = warnIfMissing(warnings, "Could not parse COFF sections.", sections),
        strings = strings.size,
    )

private fun ArchiveBinaryProvider.archiveCounts(warnings: MutableList<BinaryWarningResult>): BinarySummaryCountsResult =
    BinarySummaryCountsResult(
        archiveMembers = warnIfMissing(warnings, "Could not parse archive members.", archiveFile?.members),
        strings = strings.size,
    )

private fun NativeBinaryProvider.nativeCounts(): BinarySummaryCountsResult =
    BinarySummaryCountsResult(
        functions = nativeFunctions?.size,
        strings = strings.size,
    )

private fun <T> warnIfMissing(
    warnings: MutableList<BinaryWarningResult>,
    message: String,
    value: List<T>?,
): Int? {
    if (value == null) {
        warnings += BinaryWarningResult("partial_parse", message)
    }
    return value?.size
}

private inline fun <T> countOrWarn(
    warnings: MutableList<BinaryWarningResult>,
    message: String,
    block: () -> T,
): T? {
    return try {
        block()
    } catch (_: Throwable) {
        warnings += BinaryWarningResult("partial_parse", message)
        null
    }
}

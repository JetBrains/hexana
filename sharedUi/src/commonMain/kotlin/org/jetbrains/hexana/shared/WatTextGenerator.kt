package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.sections.*

fun generateCoreWat(provider: WasmBinaryProvider): String = buildString {
    appendLine("(module")

    // Types
    val functions = provider.functions
    if (functions.isNotEmpty()) {
        for ((i, func) in functions.withIndex()) {
            if (func is WasmSectionFunctionTypeRecord) {
                val params = func.params.joinToString(" ") { "(param ${wasmValueTypeName(it)})" }
                val results = func.retTypes.joinToString(" ") { "(result ${wasmValueTypeName(it)})" }
                appendLine("  (type (;$i;) (func $params $results))")
            }
        }
    }

    // Imports
    val imports = provider.imports
    for (imp in imports) {
        val kindStr = when (imp.type) {
            is WasmTypeIndexExternalType -> "(func (type ${(imp.type as WasmTypeIndexExternalType).index}))"
            is WasmTableExternalType -> "(table ${limitsStr((imp.type as WasmTableExternalType).limits)} funcref)"
            is WasmMemType -> "(memory ${limitsStr((imp.type as WasmMemType).limits)})"
            is WasmGlobalType -> {
                val gt = imp.type as WasmGlobalType
                val mutStr = if (gt.mutable) "(mut ${wasmValueTypeName(gt.valueType)})" else wasmValueTypeName(gt.valueType)
                "(global $mutStr)"
            }
            is WasmTagType -> "(tag (type ${(imp.type as WasmTagType).index.index}))"
        }
        appendLine("  (import \"${imp.prefix}\" \"${imp.name}\" $kindStr)")
    }

    // Functions (declarations only — code bodies need instruction decoding)
    val codeRecords = provider.codeOffsets
    val importFuncCount = provider.importFuncCount
    for ((i, func) in functions.withIndex()) {
        if (func is WasmSectionFunctionTypeRecord) {
            val funcIndex = importFuncCount + i
            val params = func.params.joinToString(" ") { "(param ${wasmValueTypeName(it)})" }
            val results = func.retTypes.joinToString(" ") { "(result ${wasmValueTypeName(it)})" }
            appendLine("  (func (;$funcIndex;) $params $results")
            appendLine("    ;; body omitted (${codeRecords.getOrNull(i)?.let { "offset 0x${it.offset.toString(16)}" } ?: "unknown offset"})")
            appendLine("  )")
        }
    }

    // Exports
    val exports = provider.exports
    for (export in exports) {
        val kindStr = when (export) {
            is WasmFunctionExportRecordEntry -> "(func ${export.index})"
            is WasmTableExportRecordEntry -> "(table ${export.index})"
            is WasmMemoryExportRecordEntry -> "(memory ${export.index})"
            is WasmGlobalExportRecordEntry -> "(global ${export.index})"
            is WasmTagExportRecordEntry -> "(tag ${export.index})"
            is WasmUnsupportedExportRecordEntry -> "(unknown ${export.index})"
        }
        appendLine("  (export \"${export.name}\" $kindStr)")
    }

    // Data segments count
    val data = provider.data
    if (data.isNotEmpty()) {
        appendLine("  ;; ${data.size} data segment(s)")
    }

    appendLine(")")
}

fun generateComponentWat(provider: WasmComponentBinaryProvider): String = buildString {
    appendLine("(component")

    val imports = provider.imports
    for (imp in imports) {
        appendLine("  (import \"${imp.importName}\" ${componentExternDescriptionLabel(imp.externDescription)})")
    }

    val exports = provider.exports
    for (exp in exports) {
        val descStr = exp.externDescription?.let { " ${componentExternDescriptionLabel(it)}" } ?: ""
        appendLine("  (export \"${exp.exportName}\"$descStr)")
    }

    val modules = provider.modules
    if (modules.isNotEmpty()) {
        appendLine("  ;; ${modules.size} nested core module(s)")
        for ((i, mod) in modules.withIndex()) {
            appendLine("  (core module (;$i;) ;; size: ${mod.contentSize} bytes, offset: 0x${mod.contentOffset.toString(16)})")
        }
    }

    val sections = provider.wasmModule?.sections ?: emptyList()
    appendLine("  ;; ${sections.size} section(s) total")

    appendLine(")")
}

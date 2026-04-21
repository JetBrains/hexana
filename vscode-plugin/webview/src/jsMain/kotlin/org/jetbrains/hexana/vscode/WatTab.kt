package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.sections.*

/**
 * WAT tab content — generates a WAT-like text representation from the parsed module.
 *
 * Since the full `wasmprinter` binary converter is JVM-only (GraalWasm), this tab
 * produces a structural overview from the parser data: types, imports, functions,
 * exports, memory, tables, globals, and data segments.
 */
@Composable
fun WatTabContent(
    bytes: ByteArray,
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmWat(buffer)
        is BinaryProviderType.WasmComponent -> ComponentWat(buffer)
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmWat(buffer: CommonByteBuffer) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val watText = remember(provider) { generateCoreWat(provider) }

    WatTextView(watText)
}

@Composable
private fun ComponentWat(buffer: CommonByteBuffer) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmComponentBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse component-model binary.") }
        return
    }

    val watText = remember(provider) { generateComponentWat(provider) }

    WatTextView(watText)
}

@Composable
private fun WatTextView(watText: String) {
    Div({
        classes("hexana-wat-view")
        style {
            property("font-family", "var(--hexana-font, monospace)")
            property("font-size", "12px")
            property("line-height", "1.5")
            property("white-space", "pre")
            property("overflow-x", "auto")
            property("tab-size", "2")
        }
    }) {
        Pre({
            style {
                property("margin", "0")
                property("font-family", "inherit")
            }
        }) {
            Text(watText)
        }
    }
}

private fun generateCoreWat(provider: WasmBinaryProvider): String = buildString {
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

private fun generateComponentWat(provider: WasmComponentBinaryProvider): String = buildString {
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

private fun limitsStr(limits: WasmLimits<*>): String {
    val min = limits.min
    val max = limits.max
    return if (max != null) "$min $max" else "$min"
}

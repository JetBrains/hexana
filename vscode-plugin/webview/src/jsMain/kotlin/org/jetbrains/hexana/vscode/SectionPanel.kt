package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.wasm.sections.WasmSection

/**
 * Bottom panel that shows parsed Wasm sections (or component-model info).
 *
 * This is a draft scaffold — each section row currently shows type, offset,
 * and size.  Detailed per-section views (imports, exports, functions, etc.)
 * will be implemented incrementally.
 */
@Composable
fun SectionPanel(
    bytes: ByteArray,
    providerType: BinaryProviderType,
) {
    val buffer = remember(bytes) { bytes.toCommonByteBuffer() }

    Div({
        classes("hexana-sections")
        style {
            property("overflow-y", "auto")
            property("border-top", "1px solid var(--hexana-border, #333)")
            property("padding", "8px 12px")
            property("flex-shrink", "0")
            property("max-height", "40%")
        }
    }) {
        when (providerType) {
            is BinaryProviderType.Wasm -> CoreWasmSections(buffer)
            is BinaryProviderType.WasmComponent -> ComponentSections(buffer)
            is BinaryProviderType.Generic -> {
                Div { Text("Not a recognized WebAssembly binary.") }
            }
        }
    }
}

@Composable
private fun CoreWasmSections(buffer: org.jetbrains.hexana.io.CommonByteBuffer) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    H4 { Text("Sections") }

    val sections = provider.wasmModule?.sections ?: emptyList()
    SectionTable(sections)

    val importCount = provider.imports.size
    val exportCount = provider.exports.size
    val functionCount = provider.functions.size

    H4 { Text("Summary") }
    Ul {
        Li { Text("Imports: $importCount") }
        Li { Text("Exports: $exportCount") }
        Li { Text("Functions: $functionCount") }
    }
}

@Composable
private fun ComponentSections(buffer: org.jetbrains.hexana.io.CommonByteBuffer) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmComponentBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse component-model binary.") }
        return
    }

    H4 { Text("Component Sections") }

    val sections = provider.wasmModule?.sections ?: emptyList()
    SectionTable(sections)

    val importCount = provider.imports.size
    val exportCount = provider.exports.size

    H4 { Text("Summary") }
    Ul {
        Li { Text("Imports: $importCount") }
        Li { Text("Exports: $exportCount") }
        Li { Text("Nested modules: ${provider.modules.size}") }
    }
}

@Composable
private fun SectionTable(sections: List<WasmSection>) {
    Table({
        style {
            width(100.percent)
            property("border-collapse", "collapse")
            property("font-size", "12px")
        }
    }) {
        Thead {
            Tr {
                Th({ style { property("text-align", "left"); property("padding", "4px 8px") } }) { Text("Section") }
                Th({ style { property("text-align", "right"); property("padding", "4px 8px") } }) { Text("Offset") }
                Th({ style { property("text-align", "right"); property("padding", "4px 8px") } }) { Text("Size") }
            }
        }
        Tbody {
            for (section in sections) {
                Tr {
                    Td({ style { property("padding", "2px 8px") } }) {
                        Text(section.name)
                    }
                    Td({ style { property("text-align", "right"); property("padding", "2px 8px") } }) {
                        Text("0x${section.sectionOffset.toString(16)}")
                    }
                    Td({ style { property("text-align", "right"); property("padding", "2px 8px") } }) {
                        Text("${section.sectionSize} bytes")
                    }
                }
            }
        }
    }
}

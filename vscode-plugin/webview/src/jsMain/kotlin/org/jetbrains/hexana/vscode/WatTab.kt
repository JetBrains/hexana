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
import org.jetbrains.hexana.shared.generateCoreWat
import org.jetbrains.hexana.shared.generateComponentWat
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


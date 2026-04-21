package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

@Composable
fun ModulesTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
    parentFileName: String,
) {
    when (providerType) {
        is BinaryProviderType.WasmComponent -> ComponentModuleList(buffer, selection, parentFileName)
        else -> Div { Text("Modules tab is only available for component-model binaries.") }
    }
}

@Composable
private fun ComponentModuleList(
    buffer: CommonByteBuffer,
    selection: HexSelectionState,
    parentFileName: String,
) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmComponentBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse component-model binary.") }
        return
    }

    val modules = remember(provider) { provider.modules }

    if (modules.isEmpty()) {
        Div({ style { property("opacity", "0.6"); property("padding", "8px 0") } }) {
            Text("No nested modules found.")
        }
        return
    }

    PanelTable(
        headers = listOf("Module"),
        columnAligns = listOf("left"),
    ) {
        for ((index, module) in modules.withIndex()) {
            val hexOffset = module.contentOffset.toString(16)
            val label = "module:@$hexOffset"
            val baseName = parentFileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.')
            val extension = parentFileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .let { name ->
                    val dot = name.lastIndexOf('.')
                    if (dot >= 0) name.substring(dot + 1) else "wasm"
                }
            val moduleFileName = "${baseName}_${index}@${hexOffset}.$extension"

            PanelTableRow(
                isHighlighted = false,
                onClick = {
                    val moduleBytes = ByteArray(module.contentSize)
                    buffer.position(module.contentOffset)
                    buffer.get(moduleBytes, 0, module.contentSize)
                    postOpenModuleToHost(index, moduleFileName, moduleBytes)
                },
            ) {
                Td({
                    style {
                        property("padding", "2px 8px")
                        property("cursor", "pointer")
                        color(Color("var(--vscode-textLink-foreground, #3794FF)"))
                    }
                }) {
                    Text(label)
                }
            }
        }
    }
}

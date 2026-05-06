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

/**
 * Returns `true` when [fileName] looks like a nested submodule opened in a
 * separate tab.  The naming convention `baseName_index@hexOffset.ext` is
 * produced by [ComponentModuleList] — the `@` before the hex offset is the
 * distinguishing marker.
 */
internal fun isSubmoduleFileName(fileName: String): Boolean {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\')
    return '_' in name && '@' in name
}

/**
 * Extracts the parent component file name from a submodule file name.
 *
 * Given the naming convention `baseName_index@hexOffset.ext`, this returns
 * `baseName.ext`.  Returns `null` when the file name does not look like a
 * submodule.
 */
internal fun parentFileNameOf(fileName: String): String? {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\')
    if ('_' !in name || '@' !in name) return null
    val atIndex = name.indexOf('@')
    val underscoreIndex = name.lastIndexOf('_', atIndex)
    if (underscoreIndex < 0) return null
    val baseName = name.substring(0, underscoreIndex)
    val dotIndex = name.lastIndexOf('.')
    val extension = if (dotIndex >= 0) name.substring(dotIndex) else ".wasm"
    return "$baseName$extension"
}

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
        Div({ classes("empty-hint") }) {
            Text("No nested modules found.")
        }
        return
    }

    SearchableTable(
        headers = listOf("ID", "Module"),
        columnAligns = listOf("right", "left"),
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
                Td({ classes("cell-id") }) {
                    Text(index.toString())
                }
                Td({
                    classes("cell")
                    style {
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

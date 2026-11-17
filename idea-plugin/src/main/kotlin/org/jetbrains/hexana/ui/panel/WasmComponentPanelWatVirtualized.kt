package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.format.ComponentCoreModuleCollector
import org.jetbrains.hexana.providers.format.EmbeddedCoreModule
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Structured, virtualized WAT for a Component Model binary — the same panel core modules get,
 * applied to the component's **embedded core modules**, found recursively (a composed component
 * keeps its core modules inside nested sub-components, so they aren't direct children).
 *
 * The core [WasmPanelWatVirtualized] is hard-typed to [WasmBinaryProvider] and built on
 * core-module structures (chunk tree, `parseCodeRecord`, …) that components don't have. But each
 * embedded core module IS a self-contained core module (begins with the `\0asm` preamble), so we
 * slice it into a [WasmBinaryProvider] — the same zero-copy trick the component diff uses — and
 * render it with the existing panel unchanged. That carries over the full experience (byte
 * offsets, per-function nav, fold, lazy per-function parse, source badges).
 *
 * When more than one core module is embedded, a tab strip selects which to view. Enumeration runs
 * off the EDT ([ComponentCoreModuleCollector] descends nested components, parsing only section
 * tables); only the selected module's bodies are parsed (lazily, inside the reused panel). The
 * component-level glue (imports/exports/types/instances/aliases/canon) has no per-instruction byte
 * model and is surfaced elsewhere (the table tabs and the plain "Wat" tab). The exception is a
 * component that embeds no core modules at all: this panel then renders the component-level
 * constructs themselves via [WasmComponentPanelWatVirtualizedGlue].
 */
@Composable
internal fun WasmComponentPanelWatVirtualized(
    provider: WasmComponentBinaryProvider,
    state: HexViewStateHolder,
) {
    val modulesState = produceState<List<EmbeddedCoreModule>?>(initialValue = null, provider) {
        value = withContext(Dispatchers.IO) { ComponentCoreModuleCollector.collect(provider) }
    }

    when (val modules = modulesState.value) {
        null -> Centered("Scanning embedded core modules…")
        else -> if (modules.isEmpty()) {
            // No embedded core modules anywhere, i.e. a glue-only component
            // (types, imports, exports, instances). Render the component-level
            // constructs in the virtualized panel instead of going empty.
            WasmComponentPanelWatVirtualizedGlue(provider, state)
        } else {
            ModuleSelector(modules, state)
        }
    }
}

@Composable
private fun ModuleSelector(modules: List<EmbeddedCoreModule>, state: HexViewStateHolder) {
    var selected by remember(modules) { mutableStateOf(0) }
    val index = selected.coerceIn(0, modules.lastIndex)

    Column(modifier = Modifier.fillMaxSize()) {
        if (modules.size > 1) {
            val tabs = modules.mapIndexed { i, m ->
                TabData.Default(
                    selected = i == index,
                    content = { Text(m.path) },
                    closable = false,
                    onClick = { selected = i },
                )
            }
            TabStrip(tabs = tabs, style = JewelTheme.defaultTabStyle)
            Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        }

        // The slice is a zero-copy view over the component buffer (which outlives this panel);
        // building the provider parses sections only, function bodies stay lazy in the panel.
        val coreProvider = remember(modules, index) { WasmBinaryProvider(modules[index].bytes) }

        Box(modifier = Modifier.weight(1f)) {
            // `key` gives each module its own panel subtree so switching resets the panel's
            // internal scroll/fold/parse state instead of bleeding across modules.
            key(index) {
                WasmPanelWatVirtualized(provider = coreProvider, state = state)
            }
        }
    }
}

@Composable
private fun Centered(message: String) {
    Box(modifier = Modifier.padding(8.dp)) {
        Text(message, style = TextStyle(fontFamily = FontFamily.Monospace))
    }
}

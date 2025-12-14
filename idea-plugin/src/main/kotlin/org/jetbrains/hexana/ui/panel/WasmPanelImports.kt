package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun WasmPanelImports(wasmBinaryProvider: WasmBinaryProvider) {
    val imports = remember(wasmBinaryProvider) { wasmBinaryProvider.imports }

    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
        items(imports.size) { idx ->
            val importRecord = imports[idx]
            Text(
                importRecord.name,
                style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace)
            )
        }
    }
}
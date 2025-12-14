package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmUnsupportedExportRecordEntry
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun WasmPanelExports(wasmBinaryProvider: WasmBinaryProvider) {
    val exports = remember(wasmBinaryProvider) {
        wasmBinaryProvider.exports
    }

    LazyColumn {
        items(exports.size) { exportIndex ->
            val export = exports[exportIndex]
            val exportKind = when (export) {
                is WasmFunctionExportRecordEntry -> "function"
                is WasmGlobalExportRecordEntry -> "global"
                is WasmMemoryExportRecordEntry -> "memory"
                is WasmTableExportRecordEntry -> "table"
                is WasmTagExportRecordEntry -> "tag"
                is WasmUnsupportedExportRecordEntry -> "unknown"
            }

            Text(
                text = buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            color = HexanaTheme.textColor,
                            fontFamily = FontFamily.Monospace
                        )
                    ) {
                        append(export.name)
                    }

                    append(" ")

                    withStyle(
                        style = SpanStyle(
                            color = Color.LightGray,
                            fontFamily = FontFamily.Monospace
                        )
                    ) {
                        append("(; ${exportKind} ;)")
                    }
                }
            )
        }
    }
}
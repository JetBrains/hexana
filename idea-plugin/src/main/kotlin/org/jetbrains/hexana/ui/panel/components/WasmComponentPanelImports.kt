package org.jetbrains.hexana.ui.panel.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.wasm.components.ComponentExternComponent
import org.jetbrains.hexana.wasm.components.ComponentExternCoreType
import org.jetbrains.hexana.wasm.components.ComponentExternDescription
import org.jetbrains.hexana.wasm.components.ComponentExternInstance
import org.jetbrains.hexana.wasm.components.ComponentExternType
import org.jetbrains.hexana.wasm.components.ComponentExternTypeBound
import org.jetbrains.hexana.wasm.components.ComponentExternValueBound
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveBoolType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveCharType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveErrorContext
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveF32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveF64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS16Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS8Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveString
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU16Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU8Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveValueType
import org.jetbrains.hexana.wasm.components.ComponentValueIdxType
import org.jetbrains.hexana.wasm.components.ComponentValueType
import org.jetbrains.hexana.wasm.components.TypeBound
import org.jetbrains.hexana.wasm.components.TypeBoundSubResource
import org.jetbrains.hexana.wasm.components.TypeBoundType
import org.jetbrains.hexana.wasm.components.ValueBound
import org.jetbrains.hexana.wasm.components.ValueBoundType
import org.jetbrains.hexana.wasm.components.ValueBoundValue

@Composable
internal fun WasmComponentPanelImports(
    wasmBinaryProvider: WasmComponentBinaryProvider,
    state: HexViewStateHolder) {
    val textMeasurer = rememberTextMeasurer()

    val minNameColumnModifier = textMeasurer.measureWidthMonospace("wasi:xxxxxxxx/environent:2.9.0")

    val imports = remember (wasmBinaryProvider) { wasmBinaryProvider.imports }
    Table(listOf(
        TableColumn("Name", modifier = Modifier.widthIn(min = minNameColumnModifier)) { index ->
            val importRecord = imports[index].importName + (imports[index].version ?: "")
            TableCellValue(importRecord)
        },

        TableColumn("Extern Description", modifier = Modifier.fillMaxWidth()) { index ->
            val importRecord = imports[index]
            TableCellValue(importRecord.externDescription.toStringRepresentation())
        },

        ), imports.size) { rowIndex ->
        val importRecord = imports[rowIndex] ?: return@Table
        state.scrollHexViewTo(importRecord.offset, importRecord.size)
    }
}

package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmStructTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmValueType

@Composable
fun WasmPanelFunctions(wasmBinaryProvider: WasmBinaryProvider) {
    val functions = remember(wasmBinaryProvider) { wasmBinaryProvider.functions }
    val nameSection = remember(wasmBinaryProvider) { wasmBinaryProvider.nameSection }

    val functionNamesMap = nameSection?.functionSubsection?.namemap
    val typeNames = nameSection?.typesSection?.namemap

    if (functions.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()

    val columns: List<TableColumn> = buildList {
        if (functionNamesMap != null) {
            val nameColumnWidth = textMeasurer.measureWidthMonospace(universalNameMeasure)
            add(TableColumn("Name", modifier = Modifier.width(nameColumnWidth)) { index ->
                val nameResolved = functionNamesMap[index] ?: ""
                TableCellValue(nameResolved, tooltip = nameResolved.takeIf { it.length > universalNameMeasure.length })
            })
        }
    }

    Table(columns + listOf(
        TableColumn("Signature", modifier = Modifier.fillMaxWidth()) { index ->
            val signatureDescription = functions[index].getDescription(index, typeNames)
            TableCellValue(signatureDescription, tooltip = signatureDescription)
        },
    ), functions.size)
}


private fun WasmTypeRecord.getDescription(index: Int, typeNames: Map<Int, String>?): String {
    return when (this) {
        is WasmSectionFunctionTypeRecord -> "(${(this.params.map { it.getDescription(typeNames) }).joinToString(", ")}) -> " + (this.retTypes.firstOrNull()?.getDescription(typeNames) ?: "void")
        is WasmStructTypeRecord -> {
                val name = typeNames?.get(index)?.let { "struct $it" }
                // TODO: check what is typed in WAT
                name ?: "struct#{index}"
        }
        is WasmRecursiveTypeRecord -> {
            val name = typeNames?.get(index)?.let { "recursive $it" }
            // TODO: check what is typed in WAT
            name ?: "recursive#{index}"
        }
        else -> toString()
    }
}


private fun WasmValueType<*>.getDescription(typeNames: Map<Int, String>?): String {
    return when (this) {
        WasmI32Type -> "i32"
        WasmF32Type -> "f32"
        WasmI64Type -> "i64"
        WasmF64Type -> "f64"
        is WasmRefType -> {
            val type = this.code.heapType.type.toInt()
            val name = typeNames?.get(type)
            val nullabilityIndicator = when (code.heapType) {
                is WasmNullableReferenceType -> "?"
                else -> ""
            }

            (name ?: "ref(${code.heapType.type})") + nullabilityIndicator
        }
        else -> toString()
    }
}

//WasmRefType, WasmReferenceType
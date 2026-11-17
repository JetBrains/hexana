package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import org.jetbrains.hexana.providers.format.fieldDisplayName
import org.jetbrains.hexana.providers.format.storageTypeRefIdx
import org.jetbrains.hexana.providers.format.typeDisplayName
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.ui.component.Text

/** A single type or function entry in the popup's referencer/subtype list. */
internal data class TypeEntry(val typeIdx: Int, val label: String)

internal data class FuncEntry(val funcIdx: Int, val label: String, val isImport: Boolean)

/**
 * A field entry in the type structure preview section of the popup.
 * [label] is the display text (name + storage type + mutability). [typeIdx]
 * is non-null for concrete ref-typed fields and enables click-navigation.
 */
internal data class FieldEntry(val label: String, val typeIdx: Int?)

/**
 * Resolved type-info payload: the structure summary for [typeIdx] and its
 * inbound edges from the TypeGraphIndex.
 */
internal data class TypeInfoData(
    val typeIdx: Int,
    val kindLabel: String,
    /** Rec-group index, or -1 when the type is not in a rec group. */
    val recGroup: Int,
    /** Direct supertypes (usually 0 or 1 entries). */
    val superTypes: List<TypeEntry>,
    /** Direct subtypes. */
    val directSubtypes: List<TypeEntry>,
    /** Types that reference this type via fields/params/results. */
    val referencingTypes: List<TypeEntry>,
    /** Functions whose bodies reference this type via GC opcodes. */
    val referencingFuncs: List<FuncEntry>,
    /**
     * Field / param / result lines for the type's structural preview.
     * Empty for func types with no params/results, for types without
     * structure, and when the TypeGraphIndex is absent.
     */
    val fields: List<FieldEntry> = emptyList(),
)

/** Triggers an off-EDT TypeInfoData build. */
internal data class TypeInfoRequest(val typeIdx: Int)

/**
 * Assembles a [TypeInfoData] for [typeIdx] from the provider's index and leaf records.
 * Runs off the EDT. [funcDisplayNames] maps funcIdx -> display name for referenced funcs.
 * Returns null if the index is absent.
 */
internal fun buildTypeInfoData(
    typeIdx: Int,
    provider: org.jetbrains.hexana.providers.WasmBinaryProvider,
    funcDisplayNames: Map<Int, String> = emptyMap(),
): TypeInfoData? {
    val index = provider.typeGraphIndex ?: return null
    val kind = index.kind(typeIdx)
    val kindLabel = kind.name.lowercase()
    val recGroup = index.recGroup(typeIdx)
    fun typeEntry(t: Int) = TypeEntry(t, provider.typeDisplayName(t))
    fun funcEntry(f: Int): FuncEntry {
        val isImport = f < provider.importFuncCount
        val label = funcDisplayNames[f] ?: if (isImport) "(imported)" else "\$func$f"
        return FuncEntry(f, label, isImport)
    }
    val fields: List<FieldEntry> = when (val rec = provider.leafTypeRecords.getOrNull(typeIdx)) {
        is org.jetbrains.hexana.wasm.sections.WasmStructTypeRecord -> rec.fields.mapIndexed { fi, field ->
            val name = provider.fieldDisplayName(typeIdx, fi) ?: "field $fi"
            val (typeText, refTypeIdx) = storageTypeLabelPair(field.storageType, provider)
            val mut = if (field.isMutable) " mut" else ""
            FieldEntry("$name: $typeText$mut", refTypeIdx)
        }
        is org.jetbrains.hexana.wasm.sections.WasmArrayTypeRecord -> {
            val (typeText, refTypeIdx) = storageTypeLabelPair(rec.fieldType.storageType, provider)
            val mut = if (rec.fieldType.isMutable) " mut" else ""
            listOf(FieldEntry("elem: $typeText$mut", refTypeIdx))
        }
        is org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord -> {
            val params = rec.params.mapIndexed { pi, vt ->
                val (typeText, refTypeIdx) = storageTypeLabelPair(vt, provider)
                FieldEntry("param $pi: $typeText", refTypeIdx)
            }
            val results = rec.retTypes.mapIndexed { ri, vt ->
                val (typeText, refTypeIdx) = storageTypeLabelPair(vt, provider)
                FieldEntry("result $ri: $typeText", refTypeIdx)
            }
            params + results
        }
        else -> emptyList()
    }
    return TypeInfoData(
        typeIdx = typeIdx,
        kindLabel = kindLabel,
        recGroup = recGroup,
        superTypes = index.superTypes(typeIdx).map(::typeEntry),
        directSubtypes = index.directSubtypes(typeIdx).map(::typeEntry),
        referencingTypes = index.referencingTypes(typeIdx).map(::typeEntry),
        referencingFuncs = index.referencingFuncs(typeIdx).map(::funcEntry),
        fields = fields,
    )
}

/** Returns (displayText, refTypeIdx?) for a storage/value type. */
private fun storageTypeLabelPair(
    vt: org.jetbrains.hexana.wasm.sections.WasmValueType<*>,
    provider: org.jetbrains.hexana.providers.WasmBinaryProvider,
): Pair<String, Int?> {
    val refTypeIdx = org.jetbrains.hexana.providers.format.storageTypeRefIdx(vt)
    val text = when (vt) {
        is org.jetbrains.hexana.wasm.sections.WasmPactTypeI8 -> "i8"
        is org.jetbrains.hexana.wasm.sections.WasmPactTypeI16 -> "i16"
        is org.jetbrains.hexana.wasm.sections.WasmI32Type -> "i32"
        is org.jetbrains.hexana.wasm.sections.WasmI64Type -> "i64"
        is org.jetbrains.hexana.wasm.sections.WasmF32Type -> "f32"
        is org.jetbrains.hexana.wasm.sections.WasmF64Type -> "f64"
        is org.jetbrains.hexana.wasm.sections.WasmVecType -> "v128"
        is org.jetbrains.hexana.wasm.sections.WasmRefType -> {
            if (refTypeIdx != null) {
                val name = provider.typeDisplayName(refTypeIdx)
                "(ref $name)"
            } else {
                vt.toString()
            }
        }
        else -> vt.toString()
    }
    return text to refTypeIdx
}

private const val MAX_LIST_ITEMS = 100

/**
 * Info popup for a WasmGC type: shows kind, rec-group membership, supertype,
 * direct subtypes, types referencing it, and functions referencing it.
 * Clicking a type entry navigates to that type; clicking a func entry navigates
 * to the function's declaration.
 *
 * [onShowTypeGraph] is called when the user clicks "Show Type Graph" — null
 * hides that button. Passed from the WAT panel when the Type Graph tab is
 * wired up in BinaryEditorView.
 *
 * No Tooltip wrapper — the WAT panel wraps its content in a SelectionContainer
 * and Tooltip + Popup crosses the selection hierarchy, triggering a
 * "not part of the same hierarchy" crash. Use DisableSelection + Popup directly.
 */
@Composable
internal fun WasmTypeInfoPopup(
    data: TypeInfoData,
    onGotoType: (Int) -> Unit,
    onGotoFunc: (Int) -> Unit,
    onDismiss: () -> Unit,
    onShowTypeGraph: ((Int) -> Unit)? = null,
) {
    val textStyle = CHUNK_PANEL_ROW_TEXT_STYLE.copy(color = HexanaTheme.textColor)

    Popup(
        alignment = Alignment.TopCenter,
        focusable = true,
        onDismissRequest = onDismiss,
    ) {
        DisableSelection {
            Column(
                modifier = Modifier
                    .width(460.dp)
                    .background(HexanaTheme.backgroundColor)
                    .border(width = 1.dp, color = HexanaTheme.textColor, shape = RoundedCornerShape(4.dp))
                    .padding(12.dp)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                val recGroupSuffix = if (data.recGroup >= 0) "  rec #${data.recGroup}" else ""
                Text(
                    text = "\$type${data.typeIdx}  ${data.kindLabel}$recGroupSuffix",
                    style = textStyle,
                )
                if (data.fields.isNotEmpty()) {
                    Text(
                        text = "fields:",
                        style = textStyle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    val shownFields = data.fields.take(MAX_LIST_ITEMS)
                    val overflowFields = data.fields.size - shownFields.size
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(shownFields) { field ->
                            if (field.typeIdx != null) {
                                Text(
                                    text = "  ${field.label}",
                                    style = textStyle.copy(color = HexanaTheme.textColor.copy(alpha = 0.8f)),
                                    modifier = Modifier.clickable { onGotoType(field.typeIdx) }.padding(vertical = 1.dp),
                                )
                            } else {
                                Text(
                                    text = "  ${field.label}",
                                    style = textStyle.copy(color = HexanaTheme.textColor.copy(alpha = 0.8f)),
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                        if (overflowFields > 0) {
                            item {
                                Text(
                                    text = "  ... and $overflowFields more",
                                    style = textStyle.copy(color = HexanaTheme.textColor.copy(alpha = 0.5f)),
                                )
                            }
                        }
                    }
                }
                if (data.superTypes.isNotEmpty()) {
                    Text(
                        text = "sub:",
                        style = textStyle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TypeEntryList(entries = data.superTypes, textStyle = textStyle, onSelect = onGotoType)
                }
                if (data.directSubtypes.isNotEmpty()) {
                    Text(
                        text = "subtypes (${data.directSubtypes.size}):",
                        style = textStyle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TypeEntryList(entries = data.directSubtypes, textStyle = textStyle, onSelect = onGotoType)
                }
                if (data.referencingTypes.isNotEmpty()) {
                    Text(
                        text = "referenced by ${data.referencingTypes.size} type${if (data.referencingTypes.size == 1) "" else "s"}:",
                        style = textStyle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TypeEntryList(entries = data.referencingTypes, textStyle = textStyle, onSelect = onGotoType)
                }
                if (data.referencingFuncs.isNotEmpty()) {
                    Text(
                        text = "referenced by ${data.referencingFuncs.size} func${if (data.referencingFuncs.size == 1) "" else "s"}:",
                        style = textStyle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    FuncEntryList(entries = data.referencingFuncs, textStyle = textStyle, onSelect = onGotoFunc)
                }
                if (data.superTypes.isEmpty() && data.directSubtypes.isEmpty() &&
                    data.referencingTypes.isEmpty() && data.referencingFuncs.isEmpty() &&
                    data.fields.isEmpty()
                ) {
                    Text(
                        text = "(no supertypes, subtypes, or references found)",
                        style = textStyle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (onShowTypeGraph != null) {
                    Text(
                        text = "Show Type Graph",
                        style = textStyle,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clickable {
                                onDismiss()
                                onShowTypeGraph(data.typeIdx)
                            },
                    )
                }
                Text(
                    text = "click: jump to type/func  ·  Esc: close",
                    style = textStyle,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TypeEntryList(
    entries: List<TypeEntry>,
    textStyle: androidx.compose.ui.text.TextStyle,
    onSelect: (Int) -> Unit,
) {
    val visible = remember(entries) { entries.take(MAX_LIST_ITEMS) }
    val overflow = entries.size - visible.size
    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
        items(count = visible.size, key = { visible[it].typeIdx }) { i ->
            val e = visible[i]
            Text(
                text = "  ${e.label}",
                style = textStyle,
                modifier = Modifier.clickable { onSelect(e.typeIdx) },
            )
        }
        if (overflow > 0) {
            item(key = "overflow") {
                Text(text = "  (+$overflow more)", style = textStyle)
            }
        }
    }
}

@Composable
private fun FuncEntryList(
    entries: List<FuncEntry>,
    textStyle: androidx.compose.ui.text.TextStyle,
    onSelect: (Int) -> Unit,
) {
    val visible = remember(entries) { entries.take(MAX_LIST_ITEMS) }
    val overflow = entries.size - visible.size
    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
        items(count = visible.size, key = { visible[it].funcIdx }) { i ->
            val e = visible[i]
            val suffix = if (e.isImport) "  (import)" else ""
            Text(
                text = "  ${e.label}$suffix",
                style = textStyle,
                modifier = if (!e.isImport) Modifier.clickable { onSelect(e.funcIdx) } else Modifier,
            )
        }
        if (overflow > 0) {
            item(key = "overflow") {
                Text(text = "  (+$overflow more)", style = textStyle)
            }
        }
    }
}

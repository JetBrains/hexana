package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.ui.component.Text

/** One dispatch candidate row: display label + whether it can be jumped to. */
internal data class IndirectCandidateEntry(
    val funcIdx: Int,
    val label: String,
    /** Imports have no code-section body chunk to navigate to. */
    val navigable: Boolean,
    /** Body size in bytes; null for imports. */
    val size: Int? = null,
)

/**
 * A `call_indirect` site whose candidates were requested: the type immediate
 * plus, when the row could supply them, the site's defined-function index and
 * byte offset — the inputs of exact-target narrowing (constant-index
 * analysis). Without them the popup falls back to the signature set.
 */
internal data class IndirectSiteRequest(
    val typeIdx: Int,
    val codeIdx: Int? = null,
    val siteOffset: Long? = null,
)

/** Resolved popup payload for one `call_indirect` site's type immediate. */
internal data class IndirectCandidatesData(
    val signatureText: String,
    val entries: List<IndirectCandidateEntry>,
    /** Narrowing outcome ("exact target: constant table index 3"), or null. */
    val note: String? = null,
    /** Index-operand provenance ("index: i32.load(+12) of local 1"), or null. */
    val provenance: String? = null,
)

/**
 * Display group key for a candidate label. Rust trait impls
 * (`<T as core::fmt::Display>::fmt::h…`) group by `Trait::method`; other
 * names group by their last two path segments. Hash suffixes and `[N]`
 * disambiguators are stripped from the KEY only — rows stay keyed by funcidx
 * (a name is not a unique key). `$funcN` placeholders group together.
 */
internal fun candidateGroupKey(label: String): String {
    if (label.startsWith("\$func") || label.startsWith("(import")) return "(unnamed)"
    val name = label
        .replace(Regex("""\[\d+]$"""), "")
        .replace(Regex("""::h[0-9a-f]{8,}$"""), "")
    if (name.startsWith("<")) {
        // Find the top-level closing '>' of `<Type as Trait>::method…`.
        var depth = 0
        var close = -1
        for (i in name.indices) {
            when (name[i]) {
                '<' -> depth++
                '>' -> if (--depth == 0) {
                    close = i
                    break
                }
            }
        }
        if (close > 0) {
            val inside = name.substring(1, close)
            val traitPath = inside.substringAfterLast(" as ", "")
            val method = name.substring(close + 1).removePrefix("::").substringBefore("::")
            if (traitPath.isNotEmpty() && method.isNotEmpty()) {
                return "${traitPath.substringAfterLast("::")}::$method"
            }
        }
    }
    // Split at top-level `::` only (generic arguments contain `::`), and
    // strip generic arguments so every drop_in_place<T> / Foo<T> variant
    // groups together.
    val segments = topLevelSegments(name)
        .map { it.substringBefore('<') }
        .filter { it.isNotEmpty() }
    return segments.takeLast(2).joinToString("::").ifEmpty { "(unnamed)" }
}

private fun topLevelSegments(name: String): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    val current = StringBuilder()
    var i = 0
    while (i < name.length) {
        val c = name[i]
        when {
            c == '<' -> { depth++; current.append(c) }
            c == '>' -> { depth--; current.append(c) }
            c == ':' && depth == 0 && i + 1 < name.length && name[i + 1] == ':' -> {
                out.add(current.toString())
                current.clear()
                i++
            }
            else -> current.append(c)
        }
        i++
    }
    if (current.isNotEmpty()) out.add(current.toString())
    return out
}

/**
 * Candidates picker for a `call_indirect` / `return_call_indirect` row.
 * Candidates render in collapsible groups keyed by demangled trait method
 * (flat when there is a single group or an active filter), largest group
 * first and largest body first within a group; a type-to-filter field
 * appears for long lists. Clicking a defined function navigates to its
 * declaration. Static under-approximation by design: runtime `table.set`
 * writes are invisible, hence "statically known" in the header. When
 * exact-target narrowing applies, [IndirectCandidatesData.note] says so and
 * the list is the narrowed set.
 */
@Composable
internal fun WasmIndirectCandidatesPopup(
    data: IndirectCandidatesData,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val textStyle = CHUNK_PANEL_ROW_TEXT_STYLE.copy(color = HexanaTheme.textColor)
    var filter by remember { mutableStateOf("") }
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    val showFilter = data.entries.size > 8

    val groups = remember(data) {
        data.entries
            .groupBy { candidateGroupKey(it.label) }
            .mapValues { (_, entries) -> entries.sortedByDescending { it.size ?: 0 } }
            .entries
            .sortedByDescending { it.value.size }
    }

    Popup(
        alignment = Alignment.TopCenter,
        focusable = true,
        onDismissRequest = onDismiss,
    ) {
        // The WAT panel wraps its content in a SelectionContainer. A Popup
        // inherits that container's LocalSelectionRegistrar, so the popup's
        // Text nodes register as selectables in a DIFFERENT layout hierarchy
        // than the panel's. A mouse selection gesture over the popup then
        // crosses hierarchies in SelectionManager.getSelectionLayout and throws
        // "layouts are not part of the same hierarchy", which kills the tab's
        // composition until it is reopened. DisableSelection detaches the
        // popup's text from that registrar.
        DisableSelection {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .background(HexanaTheme.backgroundColor)
                    .border(width = 1.dp, color = HexanaTheme.textColor, shape = RoundedCornerShape(4.dp))
                    .padding(12.dp)
                    .then(if (showFilter) Modifier else Modifier.focusRequester(requester).focusable())
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
            ) {
            Text(
                text = "call_indirect ${data.signatureText}: ${data.entries.size} statically known candidates",
                style = textStyle,
            )
            data.provenance?.let {
                Text(text = "(; $it ;)", style = textStyle, modifier = Modifier.padding(top = 2.dp))
            }
            data.note?.let {
                Text(text = "(; $it ;)", style = textStyle, modifier = Modifier.padding(top = 2.dp))
            }
            if (data.entries.isEmpty()) {
                Text(
                    text = "(; no table entries match — this call site can only trap ;)",
                    style = textStyle,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                if (showFilter) {
                    BasicTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        cursorBrush = SolidColor(HexanaTheme.textColor),
                        textStyle = textStyle,
                        decorationBox = { inner ->
                            if (filter.isEmpty()) {
                                Text("filter…", style = textStyle.copy(color = HexanaTheme.textColor.copy(alpha = 0.5f)))
                            }
                            inner()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(requester)
                            .padding(top = 6.dp),
                    )
                }
                CandidateList(
                    groups = groups,
                    filter = filter.trim(),
                    collapsedGroups = collapsedGroups,
                    onToggleGroup = { key ->
                        collapsedGroups = if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key
                    },
                    textStyle = textStyle,
                    onSelect = onSelect,
                )
            }
            Text(
                text = "click: jump  ·  Esc: close",
                style = textStyle,
                modifier = Modifier.padding(top = 6.dp),
            )
            }
        }
    }
}

@Composable
private fun CandidateList(
    groups: List<Map.Entry<String, List<IndirectCandidateEntry>>>,
    filter: String,
    collapsedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    textStyle: TextStyle,
    onSelect: (Int) -> Unit,
) {
    val filtering = filter.isNotEmpty()
    val flat = filtering || groups.size == 1
    // A LazyColumn inside a Popup must get a DEFINITE height, not just a max:
    // a Popup sizes to its content while the LazyColumn wants a bounded height
    // from its parent, and with only `heightIn(max=...)` that mutual dependency
    // becomes a measure/beyond-bounds busy-loop that wedges the tab (observed on
    // the 68-candidate core::fmt::write site). Short lists that comfortably fit
    // keep wrap-content sizing; anything that could scroll gets a fixed height.
    val total = groups.sumOf { it.value.size }
    val listModifier = if (total > 12) Modifier.height(340.dp) else Modifier.heightIn(max = 340.dp)
    LazyColumn(
        modifier = listModifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        if (flat) {
            val entries = groups
                .flatMap { it.value }
                .filter { !filtering || it.label.contains(filter, ignoreCase = true) }
                .sortedByDescending { it.size ?: 0 }
            items(count = entries.size, key = { entries[it].funcIdx }) { i ->
                CandidateRow(entries[i], textStyle, indent = false, onSelect)
            }
        } else {
            for ((key, entries) in groups) {
                val collapsed = key in collapsedGroups
                item(key = "group:$key") {
                    Text(
                        text = "${if (collapsed) "▶" else "▼"} $key (${entries.size})",
                        style = textStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleGroup(key) },
                    )
                }
                if (!collapsed) {
                    items(count = entries.size, key = { entries[it].funcIdx }) { i ->
                        CandidateRow(entries[i], textStyle, indent = true, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    entry: IndirectCandidateEntry,
    textStyle: TextStyle,
    indent: Boolean,
    onSelect: (Int) -> Unit,
) {
    val suffix = buildString {
        entry.size?.let { append("  ${it}B") }
        if (!entry.navigable) append("  (import)")
    }
    val base = Modifier
        .fillMaxWidth()
        .padding(start = if (indent) 12.dp else 0.dp)
    Text(
        text = "func[${entry.funcIdx}]  ${entry.label}$suffix",
        style = textStyle,
        modifier = if (entry.navigable) base.clickable { onSelect(entry.funcIdx) } else base,
    )
}

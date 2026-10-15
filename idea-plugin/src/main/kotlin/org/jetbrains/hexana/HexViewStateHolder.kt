package org.jetbrains.hexana

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.hexana.shared.DEX_SMALI_TAB_NAME
import org.jetbrains.hexana.shared.RowDetailData
import org.jetbrains.hexana.ui.panel.WatConversionState


class HexPosition(val line: Int, val lineOffset: Int) {
    fun getByteOffset(bytesPerRow: Int): Int {
        return line * bytesPerRow + lineOffset
    }
}
class HexSelection(val start: HexPosition, val end: HexPosition) {
    fun contains(line: Int, lineOffset: Int): Boolean {
        return when {
            line < start.line -> false
            start.line == end.line -> line == start.line && lineOffset >= start.lineOffset && lineOffset <= end.lineOffset
            line == start.line -> lineOffset >= start.lineOffset
            line == end.line -> lineOffset <= end.lineOffset
            line > end.line -> false
            else -> true
        }
    }
}

sealed class HexViewMode {
    object Hex : HexViewMode()
    object Wat : HexViewMode()
}

data class HexHighlightRange(
    val offset: Long,
    val size: Int,
    val color: Color,
)

data class HexScrollRequest(
    val offset: Long,
    val setCursor: Boolean = false,
    val highlightLine: Boolean = false,
)

class HexViewStateHolder(
    val project: Project,
) {
    val currentState: MutableState<HexViewState> = mutableStateOf(HexViewState())

    private var _viewMode = mutableStateOf<HexViewMode>(HexViewMode.Hex)
    val viewMode: HexViewMode get() = _viewMode.value
    fun setViewMode(mode: HexViewMode) {
        _viewMode.value = mode
    }

    /** True when a table tab has Compose focus — used to suppress the hex search shortcut. */
    @Volatile
    var isTableFocused: Boolean = false

    var showSearchDialog = mutableStateOf(false)
    val searchText = mutableStateOf("")
    val searchResults = mutableStateOf<List<LongRange>>(emptyList())
    val currentSearchIndex = mutableStateOf(-1)
    private val _searchReverse: MutableState<Boolean> = mutableStateOf(false)
    internal val searchReverse: Boolean get() = _searchReverse.value

    private val _searchBinaryMode: MutableState<Boolean> = mutableStateOf(false)
    internal val searchBinaryMode: Boolean get() = _searchBinaryMode.value

    // Keep the latest requested scroll offset so late collectors (e.g., HexViewer composed after
    // the emission from a LaunchedEffect) still receive it.
    private val _hexScrollFlow = MutableSharedFlow<HexScrollRequest>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val hexScrollFlow: SharedFlow<HexScrollRequest> = _hexScrollFlow

    // Replay-1 so requests issued before BinaryEditorView is composed (e.g., from a Goto Symbol
    // navigate() that opens the editor) still reach the late collector that resolves panel index.
    private val _pendingTabSelection = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pendingTabSelection: SharedFlow<String> = _pendingTabSelection

    fun requestSelectTabByDescription(description: String) {
        _pendingTabSelection.tryEmit(description)
    }

    // Pending "reveal class N in the Smali tab" request (index into
    // DexFile.classDefs). A state flow rather than a shared one: the Smali
    // panel only composes once its tab is selected, so the request has to
    // wait for it. The panel resets it to null after consuming, which lets
    // an identical re-request fire again.
    val dexSmaliClassNav: MutableStateFlow<Int?> = MutableStateFlow(null)

    fun requestRevealDexClassInSmali(classDefIndex: Int) {
        dexSmaliClassNav.value = classDefIndex
        requestSelectTabByDescription(DEX_SMALI_TAB_NAME)
    }

    // Live tab names as rendered by BinaryEditorView, in display order. Empty
    // until the editor composes its panel list. Read by the UI-test probe so
    // it reports the tabs a user actually sees (placeholders included).
    private val _panelNames = mutableStateOf<List<String>>(emptyList())
    val panelNames: List<String> get() = _panelNames.value

    fun setPanelNames(names: List<String>) {
        _panelNames.value = names
    }

    // True once BinaryEditorView has finished resolving every advertised
    // placeholder tab for the current provider (background tab resolution
    // complete). Reset when a new resolution pass starts. Read by the UI-test
    // probe so tests can wait on product state instead of inferring
    // completion from tab-name changes.
    private val _panelsResolved = mutableStateOf(false)
    val panelsResolved: Boolean get() = _panelsResolved.value

    fun setPanelsResolved(resolved: Boolean) {
        _panelsResolved.value = resolved
    }

    // Column headers of the table tabs that actually resolved, keyed by tab
    // name and published as each tab replaces its placeholder. Grouped
    // (multi-subtab) tabs are not included — they have no single column list.
    // Read by the UI-test probe: rebuilding a descriptor from the provider
    // instead would not notice a tab that resolved *before* a late-arriving
    // input was applied (e.g. a pcap SSL key log), which is exactly the
    // ordering these tests exist to pin.
    private val _tableTabColumns = mutableStateOf<Map<String, List<String>>>(emptyMap())
    val tableTabColumns: Map<String, List<String>> get() = _tableTabColumns.value

    // Prologue text of the resolved table tabs, keyed by tab name. Same
    // rationale as [tableTabColumns].
    private val _tableTabPrologues = mutableStateOf<Map<String, String>>(emptyMap())
    val tableTabPrologues: Map<String, String> get() = _tableTabPrologues.value

    fun publishResolvedTableTab(tabName: String, columns: List<String>, prologueText: String?) {
        _tableTabColumns.value = _tableTabColumns.value + (tabName to columns)
        _tableTabPrologues.value = if (prologueText == null) {
            _tableTabPrologues.value - tabName
        } else {
            _tableTabPrologues.value + (tabName to prologueText)
        }
    }

    fun clearResolvedTableTabs() {
        _tableTabColumns.value = emptyMap()
        _tableTabPrologues.value = emptyMap()
    }

    private val _cursorPosition = mutableStateOf(HexPosition(-1, -1))
    val cursorPosition: HexPosition get() = _cursorPosition.value

    private val _hoverPosition = mutableStateOf(HexPosition(-1, -1))
    val hoverPosition: HexPosition get() = _hoverPosition.value

    private val _selection = mutableStateOf(HexSelection(HexPosition(-1, -1), HexPosition(-1, -1)))
    val selection: HexSelection get() = _selection.value

    private val _bytesPerRow: MutableState<Int> = mutableStateOf(16)
    val bytesPerRow: State<Int> get() = _bytesPerRow

    var displayBaseOffset: Int = 0

    private val _conversionState = MutableStateFlow<WatConversionState?>(null)
    internal val conversionStateFlow = _conversionState.asStateFlow()

    private val _highlightRanges = mutableStateOf<List<HexHighlightRange>>(emptyList())
    val highlightRanges: List<HexHighlightRange> get() = _highlightRanges.value

    fun setHighlightRanges(ranges: List<HexHighlightRange>) {
        _highlightRanges.value = ranges
    }

    fun clearHighlightRanges() {
        _highlightRanges.value = emptyList()
    }

    private val _showAscii = mutableStateOf(true)
    val showAscii: Boolean get() = _showAscii.value

    private val _showDataInspector = mutableStateOf(false)
    val showDataInspector: Boolean get() = _showDataInspector.value

    /**
     * Bytes of the last clicked table row that exist nowhere in the file - the
     * decrypted plaintext of a TLS record, for instance. `null` means the row
     * had none.
     */
    private val _rowDetail: MutableState<RowDetailData?> = mutableStateOf(null)
    val rowDetail: RowDetailData? get() = _rowDetail.value

    private val _showRowDetail = mutableStateOf(false)
    val showRowDetail: Boolean get() = _showRowDetail.value

    val topFraction: Float
        get() = currentState.value.topFraction

    private val _editor: MutableState<Editor?> = mutableStateOf(null)
    internal val editor: Editor? get() = _editor.value

    fun setTopFraction(topFraction: Float) {
        currentState.value = currentState.value.copy(topFraction = topFraction.coerceIn(0f, 1f))
    }

    fun setSelectedTab(tabIndex: Int) {
        currentState.value = currentState.value.copy(tabIndex = tabIndex)
    }

    fun scrollHexViewTo(offset: Long, setCursor: Boolean = false, highlightLine: Boolean = false) {
        _hexScrollFlow.tryEmit(HexScrollRequest(offset, setCursor, highlightLine))
    }

    fun setCursorPosition(line: Int, lineOffset: Int) {
        _cursorPosition.value = HexPosition(line, lineOffset)
    }

    fun setCursorPosition(offset: Long, bytesPerRow: Int) {
        val line = (offset / bytesPerRow).toInt()
        val lineOffset  = offset.rem(bytesPerRow).toInt()
        _cursorPosition.value = HexPosition(line, lineOffset)
    }

    fun setHoverPosition(line: Int, lineOffset: Int) {
        _hoverPosition.value = HexPosition(line, lineOffset)
    }

    fun resetSelection() {
        _selection.value = HexSelection(HexPosition(-1, -1), HexPosition(-1, -1))
    }

    fun setSelection(startLine: Int, startLineOffset: Int, endLine: Int, endLineOffset: Int) {
        var startLineResolved = startLine
        var startOffsetResolved = startLineOffset
        var endLineResolved = endLine
        var endOffsetResolved = endLineOffset

        if (startLineResolved > endLineResolved) {
            startLineResolved = endLine
            endLineResolved = startLine

            startOffsetResolved = endLineOffset
            endOffsetResolved = startLineOffset
        } else if (startLineResolved == endLineResolved) {
            if (endOffsetResolved < startOffsetResolved) {
                startOffsetResolved = endLineOffset
                endOffsetResolved = startLineOffset
            }
        }

        _selection.value = HexSelection(HexPosition(startLineResolved, startOffsetResolved), HexPosition(endLineResolved, endOffsetResolved))
    }

    fun setSelection(offsetRange: LongRange) {
        val bytesPerRow = bytesPerRow.value

        val startLine = (offsetRange.first / bytesPerRow).toInt()
        val startLineOffset = (offsetRange.first % bytesPerRow).toInt()

        val endLine = (offsetRange.last / bytesPerRow).toInt()
        val endLineOffset = (offsetRange.last % bytesPerRow).toInt()

        setSelection(startLine, startLineOffset, endLine, endLineOffset)
    }

    fun startConversion(): Boolean {
        return _conversionState.compareAndSet(null, WatConversionState.Loading)
    }

    fun setConversionState(state: WatConversionState) {
        _conversionState.value = state
    }

    fun setShowAscii(showAscii: Boolean) {
        _showAscii.value = showAscii
    }

    fun setShowDataInspector(show: Boolean) {
        _showDataInspector.value = show
    }

    fun setRowDetail(detail: RowDetailData?) {
        _rowDetail.value = detail
    }

    fun setShowRowDetail(show: Boolean) {
        _showRowDetail.value = show
    }

    fun setEditor(editor: Editor?) {
        _editor.value = editor
    }

    fun setSearchReverse(doSearchReverse: Boolean) {
        _searchReverse.value = doSearchReverse
    }

    fun setSearchBinaryMode(doSearchBinaryMode: Boolean) {
        _searchBinaryMode.value = doSearchBinaryMode
    }
}
package org.jetbrains.hexana

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.intellij.openapi.editor.Editor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.hexana.ui.WatConversionState


class HexPosition(val line: Int, val lineOffset: Int)
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

class HexViewStateHolder {
    val currentState: MutableState<HexViewState> = mutableStateOf(HexViewState())

    private var _viewMode = mutableStateOf<HexViewMode>(HexViewMode.Hex)
    val viewMode: HexViewMode get() = _viewMode.value
    fun setViewMode(mode: HexViewMode) {
        _viewMode.value = mode
    }

    var showSearchDialog = mutableStateOf(false)

    // Keep the latest requested scroll offset so late collectors (e.g., HexViewer composed after
    // the emission from a LaunchedEffect) still receive it.
    private val _hexScrollFlow = MutableSharedFlow<Long>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val hexScrollFlow: SharedFlow<Long> = _hexScrollFlow

    private val _cursorPosition = mutableStateOf(HexPosition(-1, -1))
    val cursorPosition: HexPosition get() = _cursorPosition.value

    private val _hoverPosition = mutableStateOf(HexPosition(-1, -1))
    val hoverPosition: HexPosition get() = _hoverPosition.value

    private val _selection = mutableStateOf(HexSelection(HexPosition(-1, -1), HexPosition(-1, -1)))
    val selection: HexSelection get() = _selection.value

    private val _bytesPerRow: MutableState<Int> = mutableStateOf(16)
    val bytesPerRow: State<Int> get() = _bytesPerRow

    private val _conversionState = MutableStateFlow<WatConversionState?>(null)
    internal val conversionStateFlow = _conversionState.asStateFlow()

    private val _showAscii = mutableStateOf(true)
    val showAscii: Boolean get() = _showAscii.value

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

    fun scrollHexViewTo(offset: Long) {
        _hexScrollFlow.tryEmit(offset)
    }

    fun setCursorPosition(line: Int, lineOffset: Int) {
        _cursorPosition.value = HexPosition(line, lineOffset)
    }

    fun setHoverPosition(line: Int, lineOffset: Int) {
        _hoverPosition.value = HexPosition(line, lineOffset)
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

    fun setEditor(editor: Editor?) {
        _editor.value = editor
    }
}
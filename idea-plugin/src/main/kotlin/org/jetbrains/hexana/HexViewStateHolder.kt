package org.jetbrains.hexana

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.hexana.ui.WatConversionState

class HexViewStateHolder {
    val currentState: MutableState<HexViewState> = mutableStateOf(HexViewState())

    private val _hexScrollFlow = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val hexScrollFlow: SharedFlow<Long> = _hexScrollFlow

    private val _cursorLineOffset: MutableState<Int> = mutableStateOf(-1)
    val cursorLineOffset: State<Int> get() = _cursorLineOffset

    private val _cursorPositionLine: MutableState<Int> = mutableStateOf(-1)
    val cursorPositionLine: State<Int> get() = _cursorPositionLine

    private val _hoverLineOffset: MutableState<Int> = mutableStateOf(-1)
    val hoverLineOffset: State<Int> get() = _hoverLineOffset

    private val _hoverLine: MutableState<Int> = mutableStateOf(-1)
    val hoverLine: State<Int> get() = _hoverLine

    private val _bytesPerRow: MutableState<Int> = mutableStateOf(16)
    val bytesPerRow: State<Int> get() = _bytesPerRow

    private val _conversionState = MutableStateFlow<WatConversionState?>(null)
    internal val conversionStateFlow = _conversionState.asStateFlow()

    val topFraction: Float
        get() = currentState.value.topFraction

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
        _cursorLineOffset.value = lineOffset
        _cursorPositionLine.value = line
    }

    fun startConversion(): Boolean {
        return _conversionState.compareAndSet(null, WatConversionState.Loading)
    }

    fun setConversionState(state: WatConversionState) {
        _conversionState.value = state
    }

    fun setHoverPosition(line: Int, lineOffset: Int) {
        _hoverLineOffset.value = lineOffset
        _hoverLine.value = line
    }
}
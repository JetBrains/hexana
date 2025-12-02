package org.jetbrains.hexana

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.hexana.ui.WatConversionState


class HexPosition(val line: Int, val lineOffset: Int)

class HexViewStateHolder {
    val currentState: MutableState<HexViewState> = mutableStateOf(HexViewState())

    private val _hexScrollFlow = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val hexScrollFlow: SharedFlow<Long> = _hexScrollFlow

    private val _cursorPosition = mutableStateOf(HexPosition(-1, -1))
    val cursorPosition: HexPosition get() = _cursorPosition.value

    private val _hoverPosition = mutableStateOf(HexPosition(-1, -1))
    val hoverPosition: HexPosition get() = _hoverPosition.value

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
        _cursorPosition.value = HexPosition(line, lineOffset)
    }

    fun setHoverPosition(line: Int, lineOffset: Int) {
        _hoverPosition.value = HexPosition(line, lineOffset)
    }

    fun startConversion(): Boolean {
        return _conversionState.compareAndSet(null, WatConversionState.Loading)
    }

    fun setConversionState(state: WatConversionState) {
        _conversionState.value = state
    }
}
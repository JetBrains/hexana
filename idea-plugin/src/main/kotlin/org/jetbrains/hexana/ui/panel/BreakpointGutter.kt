package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.hexana.breakpoints.SessionBreakpointState
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.shared.MaterializationState

/** Width reserved for the breakpoint gutter column. */
val GUTTER_WIDTH = 16.dp

/**
 * Per-row gutter mark derived from [BinaryBreakpoint] + [SessionBreakpointState].
 *
 * [state] is null when no debug session is active (breakpoint is not yet materialized).
 */
data class GutterMark(
    val breakpointId: String,
    val enabled: Boolean,
    val rehomed: Boolean,
    val stale: Boolean,
    val state: MaterializationState?,
    val detail: String?,
)

/**
 * Combines the persistent breakpoint registry and the per-session materialization states
 * into a [marks] flow keyed by byte offset (WASM) or virtual address as Long (native).
 *
 * The combine runs on the [scope]'s dispatcher; callers collect [marks] in a Composable
 * via [kotlinx.coroutines.flow.StateFlow.collectAsState].
 *
 * Only breakpoints whose [BinaryBreakpoint.identity] matches [identity] appear in the map.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GutterMarksModel(
    breakpointsFlow: StateFlow<List<BinaryBreakpoint>>,
    sessionStatesFlow: StateFlow<Map<String, SessionBreakpointState>>,
    private val identity: BinaryIdentity,
    scope: CoroutineScope,
) {
    val marks: StateFlow<Map<Long, GutterMark>> = combine(
        breakpointsFlow,
        sessionStatesFlow,
    ) { breakpoints, sessionStates ->
        val result = mutableMapOf<Long, GutterMark>()
        for (bp in breakpoints) {
            if (!identity.matches(bp.identity)) continue
            val sessionState = sessionStates[bp.id]
            val mark = GutterMark(
                breakpointId = bp.id,
                enabled = bp.enabled,
                rehomed = bp.rehomed,
                stale = bp.stale,
                state = sessionState?.state,
                detail = sessionState?.detail,
            )
            val key: Long = when (val anchor = bp.anchor) {
                is BinaryAnchor.Wasm -> anchor.byteOffset
                is BinaryAnchor.Native -> anchor.virtualAddress.toLong()
            }
            // First breakpoint at each offset wins (multiple breakpoints at the same
            // position are not expected but are not forbidden by the registry).
            result.putIfAbsent(key, mark)
        }
        result
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyMap(),
    )
}

private val GUTTER_DOT_SIZE = 10.dp
private val GUTTER_STROKE = 1.5.dp

private object GutterColors {
    val enabled = Color(0xFFCC0000)
    val disabled = Color(0xFF888888)
    val stale = Color(0xFFAA6600)
    val verified = Color(0xFF22AA22)
    val failed = Color(0xFF880000)
    val hover = Color(0x33CC0000)
}

/**
 * Single gutter cell composable for one instruction row.
 *
 * - [mark] null: no breakpoint at this row. A hollow ghost dot appears on hover (not
 *   implemented here; a simple transparent box is rendered as a click target).
 * - [mark] non-null: a filled or hollow circle indicating breakpoint state.
 * - [onToggle] called on left-click; adds or removes a breakpoint at this row.
 * - [onDelete] called with the breakpoint ID on right-click; removes the breakpoint.
 *   Null when no breakpoint is present or when deletion is not supported.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BreakpointGutterCell(
    mark: GutterMark?,
    onToggle: () -> Unit,
    onDelete: ((String) -> Unit)? = null,
) {
    val dotColor = remember(mark) {
        when {
            mark == null -> Color.Transparent
            mark.stale -> GutterColors.stale
            !mark.enabled -> GutterColors.disabled
            mark.state == MaterializationState.VERIFIED -> GutterColors.verified
            mark.state == MaterializationState.FAILED -> GutterColors.failed
            else -> GutterColors.enabled
        }
    }
    val filled = mark != null && mark.enabled && !mark.stale

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(GUTTER_WIDTH, GUTTER_WIDTH)
            .combinedClickable(
                onClick = onToggle,
                onLongClick = if (mark != null && onDelete != null) {
                    { onDelete(mark.breakpointId) }
                } else null,
            ),
    ) {
        if (mark != null) {
            Canvas(modifier = Modifier.size(GUTTER_DOT_SIZE)) {
                if (filled) {
                    drawCircle(color = dotColor)
                } else {
                    drawCircle(
                        color = dotColor,
                        style = Stroke(width = GUTTER_STROKE.toPx()),
                    )
                }
            }
        }
    }
}

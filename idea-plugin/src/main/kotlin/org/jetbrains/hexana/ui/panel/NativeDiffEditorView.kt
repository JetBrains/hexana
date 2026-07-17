package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level view for a native binary diff: a single size-impact panel showing
 * the per-section size differences between two native binaries.
 *
 * Unlike the WASM diff, there is no WAT panel or entity tab — native formats
 * only support section-level size comparison.
 *
 * The expensive size diff is NOT computed here. It is computed once, off the EDT,
 * by [org.jetbrains.hexana.HexanaFileEditor] and supplied as [sizeModelFlow] —
 * editor-scoped state that lives for the lifetime of the editor TAB.
 *
 * Hosted for a [org.jetbrains.hexana.HexanaNativeDiffVirtualFile].
 */
@Composable
internal fun NativeDiffEditorView(
    leftName: String,
    rightName: String,
    sizeModelFlow: StateFlow<DiffLoadable<NativeSizeReady>>,
) {
    val sizeModel by sizeModelFlow.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        NativeSizeDiffPanel(sizeModel, leftName, rightName)
    }
}

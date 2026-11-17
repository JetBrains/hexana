package org.jetbrains.hexana.vscode

import androidx.compose.runtime.compositionLocalOf

/**
 * Where the analysis tabs sit relative to the hex view, and how to move them.
 *
 * Provided by hosts that persist the choice (VS Code: the `hexana.tabsPanelPosition` setting, shared
 * by every open editor). Absent — the browser webapp — the tabs stay below the hex view and no
 * dock button is shown.
 */
class TabsPanelDock(
    /** `true` docks the tabs to the right of the hex view, listed vertically. */
    val onRight: Boolean,
    /** Moves the tabs; the host persists the choice. */
    val setOnRight: (Boolean) -> Unit,
)

val LocalTabsPanelDock = compositionLocalOf<TabsPanelDock?> { null }

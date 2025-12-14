package org.jetbrains.hexana.ui.panel

import androidx.compose.runtime.Composable

class Panel(val description: String, val render: @Composable () -> Unit)
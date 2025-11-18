package org.jetbrains.hexana.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposePanel
import java.awt.Dimension

fun createComposePanel(content: @Composable () -> Unit): ComposePanel {
    val composePanel = ComposePanel()
    composePanel.preferredSize = Dimension(200, 120)
    composePanel.setContent(content)

    return composePanel
}

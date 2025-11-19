package org.jetbrains.hexana.ui

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.bridge.JewelComposePanel
import javax.swing.JComponent

fun createComposePanel(content: @Composable () -> Unit): JComponent {
    val composePanel = JewelComposePanel { content() }
    return composePanel
}
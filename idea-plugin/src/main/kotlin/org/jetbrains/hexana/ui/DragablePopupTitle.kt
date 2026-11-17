package org.jetbrains.hexana.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intellij.util.ui.UIUtil
import org.jetbrains.jewel.ui.component.Text
import java.awt.Component
import java.awt.MouseInfo
import java.awt.Point

/**
 * [DraggablePopupTitle] is used instead of the swing popup title so that speed
 * search area is drawn on top of it.
 * With the standard swing TitlePanel the search area is shown on top of the
 * structure tree and hides the top item.
 */
@Composable
fun DraggablePopupTitle(
    parentComponent: Component,
    text: String,
) {
    val window = remember(parentComponent) {
        UIUtil.getWindow(parentComponent)!!
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                var dragStartMousePos: Point? = null
                var dragStartWindowPos: Point? = null

                detectDragGestures(
                    onDragStart = {
                        val mousePos = MouseInfo.getPointerInfo().location
                        dragStartMousePos = mousePos
                        dragStartWindowPos = window.location
                    },
                    onDrag = { _, _ ->
                        val currentMousePos = MouseInfo.getPointerInfo().location

                        val startMousePos = dragStartMousePos
                        val startWindowPos = dragStartWindowPos
                        if (startMousePos != null && startWindowPos != null) {
                            val dx = currentMousePos.x - startMousePos.x
                            val dy = currentMousePos.y - startMousePos.y
                            window.setLocation(startWindowPos.x + dx, startWindowPos.y + dy)
                        }
                    }
                )
            }
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.jewel.ui.component.Text
import java.awt.Cursor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A side panel that displays the bytes at the current cursor position
 * interpreted as various numeric types in both little-endian and big-endian
 * byte orders — the standard "Data Inspector" found in most hex editors.
 */
@Composable
internal fun DataInspectorPanel(
    state: HexViewStateHolder,
    bytes: CommonByteBuffer,
) {
    val bytesPerRow by state.bytesPerRow
    val cursor = state.cursorPosition
    val hasCursor = cursor.line >= 0 && cursor.lineOffset >= 0
    val offset = if (hasCursor) cursor.getByteOffset(bytesPerRow) else -1
    val totalBytes = bytes.limit()

    var littleEndian by remember { mutableStateOf(true) }

    SelectionContainer {
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(HexanaTheme.backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        val headerStyle = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor,
        )
        val labelStyle = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor.copy(alpha = 0.6f),
        )
        val valueStyle = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor,
        )

        Text("Data Inspector", style = headerStyle.copy(fontSize = 12.sp))
        Spacer(Modifier.height(8.dp))

        EndianSwitch(littleEndian, headerStyle) { littleEndian = it }
        Spacer(Modifier.height(8.dp))

        if (!hasCursor || offset < 0 || offset >= totalBytes) {
            Text("No byte selected", style = labelStyle)
            return@Column
        }

        // Read up to 8 bytes starting at cursor for the widest type (Double/Long).
        val available = (totalBytes - offset).coerceAtMost(8)
        val raw = ByteArray(available)
        for (i in 0 until available) {
            raw[i] = bytes[offset + i]
        }

        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        inspectorRows(raw, order, labelStyle, valueStyle)

        Spacer(Modifier.height(8.dp))

        // Binary / Octal representation of the current byte
        val b = raw[0].toInt() and 0xFF
        InspectorRow("Binary", b.toString(2).padStart(8, '0'), labelStyle, valueStyle)
        InspectorRow("Octal", b.toString(8), labelStyle, valueStyle)

        // ASCII
        val asciiChar = if (b in 0x20..0x7E) b.toChar().toString() else "·"
        InspectorRow("ASCII", asciiChar, labelStyle, valueStyle)

        // UTF-8 string starting at cursor until a non-UTF-8 sequence
        val maxUtf8 = (totalBytes - offset).coerceAtMost(64)
        val utf8Raw = ByteArray(maxUtf8)
        for (i in 0 until maxUtf8) {
            utf8Raw[i] = bytes[offset + i]
        }
        val utf8Str = decodeUtf8Prefix(utf8Raw)
        if (utf8Str.isNotEmpty()) {
            InspectorRow("UTF-8", utf8Str, labelStyle, valueStyle)
        }
    }
    }
}

@Composable
private fun EndianSwitch(littleEndian: Boolean, style: TextStyle, onToggle: (Boolean) -> Unit) {
    val activeStyle = style.copy(fontWeight = FontWeight.Bold)
    val inactiveStyle = style.copy(color = style.color.copy(alpha = 0.5f))
    val handCursor = Modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Little Endian",
            style = if (littleEndian) activeStyle else inactiveStyle,
            modifier = handCursor.then(Modifier.clickable { onToggle(true) }),
        )
        Text("/", style = style)
        Text(
            "Big Endian",
            style = if (!littleEndian) activeStyle else inactiveStyle,
            modifier = handCursor.then(Modifier.clickable { onToggle(false) }),
        )
    }
}

@Composable
private fun inspectorRows(raw: ByteArray, order: ByteOrder, labelStyle: TextStyle, valueStyle: TextStyle) {
    val buf = ByteBuffer.wrap(raw.copyOf(8)).order(order)

    // Int8 / UInt8 — always available (at least 1 byte)
    InspectorRow("Int8", raw[0].toString(), labelStyle, valueStyle)
    InspectorRow("UInt8", (raw[0].toInt() and 0xFF).toString(), labelStyle, valueStyle)

    if (raw.size >= 2) {
        buf.position(0)
        val s = buf.short
        InspectorRow("Int16", s.toString(), labelStyle, valueStyle)
        InspectorRow("UInt16", (s.toInt() and 0xFFFF).toString(), labelStyle, valueStyle)
    }

    if (raw.size >= 4) {
        buf.position(0)
        val i = buf.int
        InspectorRow("Int32", i.toString(), labelStyle, valueStyle)
        InspectorRow("UInt32", (i.toLong() and 0xFFFFFFFFL).toString(), labelStyle, valueStyle)

        buf.position(0)
        InspectorRow("Float32", buf.float.toString(), labelStyle, valueStyle)
    }

    if (raw.size >= 8) {
        buf.position(0)
        val l = buf.long
        InspectorRow("Int64", l.toString(), labelStyle, valueStyle)
        InspectorRow("UInt64", java.lang.Long.toUnsignedString(l), labelStyle, valueStyle)

        buf.position(0)
        InspectorRow("Float64", buf.double.toString(), labelStyle, valueStyle)
    }
}

@Composable
private fun InspectorRow(label: String, value: String, labelStyle: TextStyle, valueStyle: TextStyle) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = labelStyle, modifier = Modifier.width(60.dp))
        Text(value, style = valueStyle)
    }
}

/**
 * Decodes a valid UTF-8 prefix from [data], stopping at the first byte that
 * cannot start or continue a valid UTF-8 character sequence.
 */
private fun decodeUtf8Prefix(data: ByteArray): String {
    val sb = StringBuilder()
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val seqLen: Int
        val codePoint: Int
        when {
            b0 <= 0x7F -> { seqLen = 1; codePoint = b0 }
            b0 in 0xC2..0xDF -> { seqLen = 2; codePoint = b0 and 0x1F }
            b0 in 0xE0..0xEF -> { seqLen = 3; codePoint = b0 and 0x0F }
            b0 in 0xF0..0xF4 -> { seqLen = 4; codePoint = b0 and 0x07 }
            else -> break
        }
        if (i + seqLen > data.size) break
        var cp = codePoint
        var valid = true
        for (j in 1 until seqLen) {
            val cont = data[i + j].toInt() and 0xFF
            if (cont and 0xC0 != 0x80) { valid = false; break }
            cp = (cp shl 6) or (cont and 0x3F)
        }
        if (!valid) break
        // Reject overlong encodings and surrogates
        if (seqLen == 2 && cp < 0x80) break
        if (seqLen == 3 && cp < 0x800) break
        if (seqLen == 4 && cp < 0x10000) break
        if (cp in 0xD800..0xDFFF) break
        if (cp > 0x10FFFF) break
        // Skip control characters (except common whitespace)
        if (cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D) break
        sb.appendCodePoint(cp)
        i += seqLen
        if (sb.length >= 32) break
    }
    return sb.toString()
}

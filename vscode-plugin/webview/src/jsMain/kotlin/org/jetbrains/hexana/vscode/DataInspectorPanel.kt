package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Data Inspector side panel for the VS Code hex viewer.
 * Displays the bytes at the current cursor position interpreted as various
 * numeric types, binary, octal, ASCII, and UTF-8 string.
 */
@Composable
fun DataInspectorPanel(
    bytes: CommonByteBuffer,
    selection: HexSelectionState,
) {
    val offset = selection.cursorIndex
    val totalBytes = bytes.size

    var littleEndian by remember { mutableStateOf(true) }

    Div({
        classes("hexana-data-inspector")
        style {
            width(280.px)
            property("min-width", "280px")
            property("flex-shrink", "0")
            property("overflow-y", "auto")
            property("overflow-x", "hidden")
            property("border-left", "1px solid var(--hexana-border, #333)")
            property("padding", "8px")
            property("font-family", "monospace")
            property("font-size", "11px")
            property("user-select", "text")
        }
    }) {
        // Header
        Div({
            style {
                property("font-size", "12px")
                property("margin-bottom", "8px")
            }
        }) { Text("Data Inspector") }

        // Endian switch
        EndianSwitch(littleEndian) { littleEndian = it }

        Div({ style { property("margin-bottom", "8px") } }) {}

        if (offset < 0 || offset >= totalBytes) {
            Div({ style { property("opacity", "0.6") } }) {
                Text("No byte selected")
            }
            return@Div
        }

        // Read up to 8 bytes starting at cursor
        val available = (totalBytes - offset).coerceAtMost(8)
        val raw = ByteArray(available)
        for (i in 0 until available) {
            raw[i] = bytes[offset + i]
        }

        // Numeric types
        inspectorRows(raw, littleEndian)

        Div({ style { property("margin-top", "8px") } }) {}

        // Binary / Octal
        val b = raw[0].toInt() and 0xFF
        InspectorRow("Binary", b.toString(2).padStart(8, '0'))
        InspectorRow("Octal", b.toString(8))

        // ASCII
        val asciiChar = if (b in 0x20..0x7E) b.toChar().toString() else "·"
        InspectorRow("ASCII", asciiChar)

        // UTF-8 string
        val maxUtf8 = (totalBytes - offset).coerceAtMost(64)
        val utf8Raw = ByteArray(maxUtf8)
        for (i in 0 until maxUtf8) {
            utf8Raw[i] = bytes[offset + i]
        }
        val utf8Str = decodeUtf8Prefix(utf8Raw)
        if (utf8Str.isNotEmpty()) {
            InspectorRow("UTF-8", utf8Str)
        }
    }
}

@Composable
private fun EndianSwitch(littleEndian: Boolean, onToggle: (Boolean) -> Unit) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("gap", "4px")
        }
    }) {
        Span({
            style {
                property("font-weight", if (littleEndian) "bold" else "normal")
                property("opacity", if (littleEndian) "1" else "0.5")
                property("cursor", "pointer")
            }
            onClick { onToggle(true) }
        }) { Text("Little Endian") }

        Span { Text("/") }

        Span({
            style {
                property("font-weight", if (!littleEndian) "bold" else "normal")
                property("opacity", if (!littleEndian) "1" else "0.5")
                property("cursor", "pointer")
            }
            onClick { onToggle(false) }
        }) { Text("Big Endian") }
    }
}

@Composable
private fun inspectorRows(raw: ByteArray, littleEndian: Boolean) {
    // Int8 / UInt8
    InspectorRow("Int8", raw[0].toString())
    InspectorRow("UInt8", (raw[0].toInt() and 0xFF).toString())

    if (raw.size >= 2) {
        val s = readInt16(raw, littleEndian)
        InspectorRow("Int16", s.toString())
        InspectorRow("UInt16", (s.toInt() and 0xFFFF).toString())
    }

    if (raw.size >= 4) {
        val i = readInt32(raw, littleEndian)
        InspectorRow("Int32", i.toString())
        InspectorRow("UInt32", (i.toLong() and 0xFFFFFFFFL).toString())
        InspectorRow("Float32", Float.fromBits(i).toString())
    }

    if (raw.size >= 8) {
        val l = readInt64(raw, littleEndian)
        InspectorRow("Int64", l.toString())
        // Kotlin/JS Long.toULong() works for unsigned display
        @Suppress("ConvertToStringTemplate")
        InspectorRow("UInt64", l.toULong().toString())
        InspectorRow("Float64", Double.fromBits(l).toString())
    }
}

private fun readInt16(raw: ByteArray, littleEndian: Boolean): Short {
    val b0 = raw[0].toInt() and 0xFF
    val b1 = raw[1].toInt() and 0xFF
    return if (littleEndian) {
        (b1 shl 8 or b0).toShort()
    } else {
        (b0 shl 8 or b1).toShort()
    }
}

private fun readInt32(raw: ByteArray, littleEndian: Boolean): Int {
    val b0 = raw[0].toInt() and 0xFF
    val b1 = raw[1].toInt() and 0xFF
    val b2 = raw[2].toInt() and 0xFF
    val b3 = raw[3].toInt() and 0xFF
    return if (littleEndian) {
        b3 shl 24 or (b2 shl 16) or (b1 shl 8) or b0
    } else {
        b0 shl 24 or (b1 shl 16) or (b2 shl 8) or b3
    }
}

private fun readInt64(raw: ByteArray, littleEndian: Boolean): Long {
    val bytes = if (littleEndian) {
        longArrayOf(
            raw[7].toLong() and 0xFF, raw[6].toLong() and 0xFF,
            raw[5].toLong() and 0xFF, raw[4].toLong() and 0xFF,
            raw[3].toLong() and 0xFF, raw[2].toLong() and 0xFF,
            raw[1].toLong() and 0xFF, raw[0].toLong() and 0xFF,
        )
    } else {
        longArrayOf(
            raw[0].toLong() and 0xFF, raw[1].toLong() and 0xFF,
            raw[2].toLong() and 0xFF, raw[3].toLong() and 0xFF,
            raw[4].toLong() and 0xFF, raw[5].toLong() and 0xFF,
            raw[6].toLong() and 0xFF, raw[7].toLong() and 0xFF,
        )
    }
    var result = 0L
    for (b in bytes) {
        result = (result shl 8) or b
    }
    return result
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("justify-content", "space-between")
            property("padding", "1px 0")
        }
    }) {
        Span({
            style { property("opacity", "0.6") }
        }) { Text(label) }
        Span { Text(value) }
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
        if (seqLen == 2 && cp < 0x80) break
        if (seqLen == 3 && cp < 0x800) break
        if (seqLen == 4 && cp < 0x10000) break
        if (cp in 0xD800..0xDFFF) break
        if (cp > 0x10FFFF) break
        if (cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D) break
        // Kotlin/JS: use Char pairs for supplementary code points
        if (cp <= 0xFFFF) {
            sb.append(cp.toChar())
        } else {
            val adjusted = cp - 0x10000
            sb.append((0xD800 + (adjusted shr 10)).toChar())
            sb.append((0xDC00 + (adjusted and 0x3FF)).toChar())
        }
        i += seqLen
        if (sb.length >= 32) break
    }
    return sb.toString()
}

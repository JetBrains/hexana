package org.jetbrains.hexana.shared

/**
 * Rendering of [RowDetailData.bytes] for the hex view's detail side panel.
 *
 * Both hosts (IntelliJ Compose and the VS Code webview) format detail bytes
 * through these functions so the panel reads identically in either one.
 */

/**
 * How many bytes the panel renders. A TLS record cannot exceed 16 KiB, so this
 * only ever trims something unusual - and when it does, the panel says so
 * rather than quietly showing a prefix.
 */
const val ROW_DETAIL_BYTE_LIMIT: Int = 16 * 1024

/** Bytes per line of a hex dump, the usual width for an 80-column pane. */
private const val HEX_DUMP_LINE_WIDTH = 16

/** How much of a payload decides whether it reads as text. */
private const val TEXT_SNIFF_LIMIT = 512

/** Fraction of sniffed bytes that must be printable for text mode to win. */
private const val TEXT_PRINTABLE_RATIO = 0.95

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * The payload as text: printable ASCII and the whitespace that structures
 * protocol heads survive, everything else becomes a dot. The same mapping the
 * pcap dissector uses to read HTTP heads, so a request looks in the panel the
 * way its Info column already reads.
 */
fun rowDetailText(bytes: ByteArray, limit: Int = ROW_DETAIL_BYTE_LIMIT): String {
    val shown = minOf(bytes.size, limit)
    val text = buildString(shown) {
        for (i in 0 until shown) append(detailChar(bytes[i].toInt() and 0xFF))
    }
    return text + truncationNote(bytes.size, shown)
}

/**
 * The payload as a hex dump - offset, 16 hex bytes, then those bytes as ASCII:
 *
 * ```
 * 00000000  47 45 54 20 2f 20 48 54  54 50 2f 31 2e 30 0d 0a  |GET / HTTP/1.0..|
 * ```
 */
fun rowDetailHexDump(bytes: ByteArray, limit: Int = ROW_DETAIL_BYTE_LIMIT): String {
    val shown = minOf(bytes.size, limit)
    val dump = buildString {
        var lineStart = 0
        while (lineStart < shown) {
            val lineEnd = minOf(lineStart + HEX_DUMP_LINE_WIDTH, shown)
            if (lineStart > 0) append('\n')
            appendHexDumpLine(bytes, lineStart, lineEnd)
            lineStart = lineEnd
        }
    }
    return dump + truncationNote(bytes.size, shown)
}

/**
 * What the panel's Copy control puts on the clipboard: the rendering the panel
 * is showing, but whole. [ROW_DETAIL_BYTE_LIMIT] exists to keep the renderer
 * from laying out a wall of text, and the clipboard has no such problem - a
 * copy that stopped where the view stops would be a trap, since a record
 * inflated out of a compressed one can run well past the limit.
 */
fun rowDetailClipboardContent(bytes: ByteArray, asText: Boolean): String =
    if (asText) rowDetailText(bytes, limit = bytes.size) else rowDetailHexDump(bytes, limit = bytes.size)

/**
 * Whether [bytes] reads as text, deciding which mode the panel opens in. Only
 * the start is sniffed, so a payload that is mostly a binary body - an image
 * behind a short HTTP head, say - opens in hex, where its head is still legible
 * in the ascii gutter.
 */
fun looksLikeText(bytes: ByteArray): Boolean {
    val sniffed = minOf(bytes.size, TEXT_SNIFF_LIMIT)
    if (sniffed == 0) return false
    var printable = 0
    for (i in 0 until sniffed) {
        if (isPrintable(bytes[i].toInt() and 0xFF)) printable++
    }
    return printable >= sniffed * TEXT_PRINTABLE_RATIO
}

private fun StringBuilder.appendHexDumpLine(bytes: ByteArray, from: Int, until: Int) {
    appendOffset(from)
    append("  ")
    for (i in from until from + HEX_DUMP_LINE_WIDTH) {
        // A gap after the eighth byte, and spaces where a short last line stops,
        // so the ASCII gutter stays in the same column on every line.
        if (i == from + HEX_DUMP_LINE_WIDTH / 2) append(' ')
        if (i < until) appendHexByte(bytes[i].toInt() and 0xFF) else append("  ")
        append(' ')
    }
    append(" |")
    for (i in from until until) append(detailChar(bytes[i].toInt() and 0xFF, whitespaceAsDot = true))
    append('|')
}

private fun StringBuilder.appendOffset(offset: Int) {
    for (shift in 28 downTo 0 step 4) append(HEX_DIGITS[(offset shr shift) and 0xF])
}

private fun StringBuilder.appendHexByte(b: Int) {
    append(HEX_DIGITS[(b shr 4) and 0xF])
    append(HEX_DIGITS[b and 0xF])
}

/**
 * A note naming the bytes the panel left out, appended so a trimmed payload can
 * never be mistaken for the whole thing.
 */
private fun truncationNote(total: Int, shown: Int): String =
    if (total > shown) "\n\n... ${total - shown} more bytes not shown" else ""

private fun detailChar(b: Int, whitespaceAsDot: Boolean = false): Char = when {
    b in 0x20..0x7E -> b.toChar()
    whitespaceAsDot -> '.'
    b == 0x0D || b == 0x0A || b == 0x09 -> b.toChar()
    else -> '.'
}

private fun isPrintable(b: Int): Boolean = b in 0x20..0x7E || b == 0x0D || b == 0x0A || b == 0x09

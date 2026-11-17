package org.jetbrains.hexana.shared

import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import kotlin.math.roundToInt

internal fun formatOneDecimal(value: Double): String {
    val rounded = (value * 10).roundToInt()
    return "${rounded / 10}.${rounded % 10}"
}

/**
 * Formats a byte count into a human-readable size string (B / KB / MB).
 */
fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${formatOneDecimal(bytes / 1024.0)} KB"
    else -> "${formatOneDecimal(bytes / (1024.0 * 1024.0))} MB"
}

/**
 * Formats a byte count (Long) into a human-readable size string.
 */
fun formatSizeLong(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "${formatOneDecimal(bytes / 1024.0)} KB"
    bytes < 1024L * 1024 * 1024 -> "${formatOneDecimal(bytes / (1024.0 * 1024.0))} MB"
    else -> "${formatOneDecimal(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
}

/**
 * Builds a descriptive label for a [BinarySizeNode] within a module of the given total [moduleSize].
 */
fun sizeNodeLabel(node: BinarySizeNode, moduleSize: Int): String {
    val pct = node.percentage(moduleSize)
    val pctStr = "${formatOneDecimal(pct)}%"
    val hex = "0x${node.offset.toString(16).uppercase()}"
    return "${node.name}  ${formatSize(node.size)}  $pctStr  @$hex"
}

/**
 * Converts a byte to its printable ASCII character, or null if non-printable.
 */
fun byteToAscii(b: Byte): String? {
    val v = b.toInt() and 0xFF
    return if (v in 32..126) v.toChar().toString() else null
}

/**
 * Renders a byte array as a printable ASCII preview, replacing non-printable bytes with ".".
 */
fun bytesToAsciiPreview(bytes: ByteArray): String =
    bytes.joinToString(separator = "") { byteToAscii(it) ?: "." }

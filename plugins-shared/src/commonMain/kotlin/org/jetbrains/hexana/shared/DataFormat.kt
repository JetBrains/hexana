package org.jetbrains.hexana.shared

/**
 * Returns a human-readable label for a Wasm data segment type code.
 */
fun dataSegmentTypeLabel(type: UInt): String = when (type) {
    0u -> "0 (active)"
    1u -> "1 (passive)"
    2u -> "2 (active)"
    else -> "$type (unknown)"
}

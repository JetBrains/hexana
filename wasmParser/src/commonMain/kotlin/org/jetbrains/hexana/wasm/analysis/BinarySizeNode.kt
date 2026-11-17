package org.jetbrains.hexana.wasm.analysis

/**
 * Format-agnostic tree node for binary size profiling.
 *
 * Each node represents a named region of the binary with a byte [size] at a
 * given [offset], optionally containing [children] that break the region down
 * further.  The tree is rooted at a single node that covers the whole file.
 *
 * Wasm-specific subclasses live in [SizeNode]; native-format providers will
 * supply their own concrete subtypes in the future.
 */
open class BinarySizeNode(
    val name: String,
    val size: Int,
    val offset: Int,
    val children: List<BinarySizeNode> = emptyList(),
) {
    fun percentage(parentSize: Int): Double =
        if (parentSize == 0) 0.0 else (size.toDouble() / parentSize) * 100.0
}

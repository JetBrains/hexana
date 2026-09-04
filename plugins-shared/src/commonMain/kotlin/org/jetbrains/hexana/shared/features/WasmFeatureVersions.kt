package org.jetbrains.hexana.shared.features

/**
 * Compares two runtime version strings as they appear in `features.json`.
 *
 * The file mixes single-segment browser versions (`"85"`, `"137"`), two-segment ones
 * (`"0.4"`, `"12.5"`, `"16.4"`) and three-segment ones (`"1.0.24"`, `"0.10.1"`), so the
 * comparison is segment-by-segment and numeric, with missing trailing segments read as 0 —
 * `"1.0"` and `"1.0.0"` compare equal, and `"26.0"` is above `"9.1"`.
 *
 * A segment that is not a plain number (a hypothetical `"1.0-rc1"`) falls back to string
 * comparison for that segment only. Blank input sorts below everything.
 *
 * @return negative when [a] precedes [b], zero when equal, positive when [a] follows [b]
 */
fun compareRuntimeVersions(a: String, b: String): Int {
    val left = a.trim()
    val right = b.trim()
    if (left == right) return 0
    if (left.isEmpty()) return -1
    if (right.isEmpty()) return 1

    val leftSegments = left.split('.')
    val rightSegments = right.split('.')
    for (i in 0 until maxOf(leftSegments.size, rightSegments.size)) {
        val leftSegment = leftSegments.getOrElse(i) { "0" }
        val rightSegment = rightSegments.getOrElse(i) { "0" }
        val leftNumber = leftSegment.toLongOrNull()
        val rightNumber = rightSegment.toLongOrNull()
        val comparison = if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            leftSegment.compareTo(rightSegment)
        }
        if (comparison != 0) return comparison
    }
    return 0
}

/**
 * Whether [version] is at or past [minimum] — i.e. whether a runtime pinned at [version]
 * has a feature that landed in [minimum].
 */
fun runtimeVersionAtLeast(version: String, minimum: String): Boolean =
    compareRuntimeVersions(version, minimum) >= 0

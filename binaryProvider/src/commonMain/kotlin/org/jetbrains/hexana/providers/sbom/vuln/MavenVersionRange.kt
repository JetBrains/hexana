package org.jetbrains.hexana.providers.sbom.vuln

/**
 * Best-effort Maven version comparison + OSV range containment. This is a pragmatic
 * subset of Maven's `ComparableVersion` semantics — enough for vulnerability range
 * checks on real-world release versions, not a full reimplementation:
 *  - dot/dash/underscore separated, with digit/letter boundaries also splitting;
 *  - numeric segments compared numerically; a numeric segment outranks a qualifier;
 *  - release synonyms (`final`/`ga`/`release`/empty) are dropped so `1.0.Final` == `1.0`;
 *  - known pre-release qualifiers order alpha < beta < milestone < rc < snapshot < release < sp.
 * Edge cases (build metadata, exotic qualifiers) fall back to lexical order.
 */
object MavenVersionRange {

    /** True if [version] falls in any range of [affected], or is listed explicitly. */
    fun isAffected(version: String, affected: OsvAffected): Boolean {
        if (affected.versions.any { it.equals(version, ignoreCase = true) }) return true
        return affected.ranges.any { contains(it, version) }
    }

    private data class Interval(val introduced: String, val end: String?, val endInclusive: Boolean)

    private fun contains(range: OsvRange, version: String): Boolean {
        // OSV events are linear along the version timeline; fold them into
        // [introduced, fixed) / [introduced, last_affected] intervals.
        val intervals = mutableListOf<Interval>()
        var intro: String? = null
        for (e in range.events) {
            when (e.kind) {
                OsvEvent.Kind.Introduced -> {
                    if (intro != null) intervals.add(Interval(intro!!, null, false))
                    intro = e.version
                }
                OsvEvent.Kind.Fixed -> {
                    intervals.add(Interval(intro ?: "0", e.version, endInclusive = false)); intro = null
                }
                OsvEvent.Kind.LastAffected -> {
                    intervals.add(Interval(intro ?: "0", e.version, endInclusive = true)); intro = null
                }
            }
        }
        if (intro != null) intervals.add(Interval(intro!!, null, false))

        return intervals.any { iv ->
            val geIntro = iv.introduced == "0" || compare(version, iv.introduced) >= 0
            val leEnd = when {
                iv.end == null -> true
                iv.endInclusive -> compare(version, iv.end) <= 0
                else -> compare(version, iv.end) < 0
            }
            geIntro && leEnd
        }
    }

    fun compare(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val n = maxOf(ta.size, tb.size)
        for (i in 0 until n) {
            val x = ta.getOrNull(i) ?: Token.Num(0)
            val y = tb.getOrNull(i) ?: Token.Num(0)
            val c = x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    private sealed class Token : Comparable<Token> {
        data class Num(val v: Long) : Token()
        data class Str(val s: String) : Token()

        override fun compareTo(other: Token): Int = when {
            this is Num && other is Num -> v.compareTo(other.v)
            this is Num -> 1 // a release number outranks a qualifier at the same position
            other is Num -> -1
            this is Str && other is Str -> {
                val r = rank(s).compareTo(rank(other.s))
                if (r != 0) r else s.compareTo(other.s)
            }
            else -> 0
        }
    }

    private fun rank(q: String): Int = when (q) {
        "alpha", "a" -> -5
        "beta", "b" -> -4
        "milestone", "m" -> -3
        "rc", "cr" -> -2
        "snapshot" -> -1
        "sp" -> 1
        else -> 0
    }

    private val RELEASE_SYNONYMS = setOf("", "final", "ga", "release")

    private fun tokenize(version: String): List<Token> {
        val v = version.lowercase()
        val tokens = mutableListOf<Token>()
        val sb = StringBuilder()
        var digit = false
        fun flush() {
            if (sb.isEmpty()) return
            val s = sb.toString()
            sb.clear()
            if (digit) {
                tokens.add(Token.Num(s.toLongOrNull() ?: 0))
            } else if (s !in RELEASE_SYNONYMS) {
                tokens.add(Token.Str(s))
            }
        }
        for (ch in v) {
            when {
                ch == '.' || ch == '-' || ch == '_' || ch == '+' -> flush()
                ch.isDigit() -> {
                    if (!digit && sb.isNotEmpty()) flush()
                    digit = true
                    sb.append(ch)
                }
                else -> {
                    if (digit && sb.isNotEmpty()) flush()
                    digit = false
                    sb.append(ch)
                }
            }
        }
        flush()
        return tokens
    }
}
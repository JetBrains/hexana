package org.jetbrains.hexana.nativebin.vuln

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** Qualitative CVSS severity bucket, ordered by [rank] for sorting and badge colour. */
enum class CvssSeverity(val label: String, val rank: Int) {
    UNKNOWN("Unknown", -1),
    NONE("None", 0),
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3),
    CRITICAL("Critical", 4),
}

data class CvssScore(val base: Double?, val severity: CvssSeverity, val vector: String)

/**
 * Computes a CVSS v3.0/3.1 base score from a vector string. OSV records carry the
 * vector (e.g. `CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H`) but not the numeric
 * score, so we apply the published v3.1 formula. v2/v4 vectors are not computed —
 * they yield [CvssSeverity.UNKNOWN] but keep the raw vector for display.
 */
object Cvss {

    fun parse(vector: String): CvssScore {
        val parts = vector.split('/')
        val version = parts.firstOrNull()?.substringAfter(':', "")?.trim().orEmpty()
        if (!version.startsWith("3.")) return CvssScore(null, CvssSeverity.UNKNOWN, vector)

        val m = parts.drop(1).mapNotNull { token ->
            val kv = token.split(':')
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()

        val av = AV[m["AV"]] ?: return unknown(vector)
        val ac = AC[m["AC"]] ?: return unknown(vector)
        val ui = UI[m["UI"]] ?: return unknown(vector)
        val scopeChanged = when (m["S"]) {
            "C" -> true
            "U" -> false
            else -> return unknown(vector)
        }
        val pr = (if (scopeChanged) PR_CHANGED else PR_UNCHANGED)[m["PR"]] ?: return unknown(vector)
        val c = CIA[m["C"]] ?: return unknown(vector)
        val i = CIA[m["I"]] ?: return unknown(vector)
        val a = CIA[m["A"]] ?: return unknown(vector)

        val iss = 1.0 - (1.0 - c) * (1.0 - i) * (1.0 - a)
        val impact = if (scopeChanged) {
            7.52 * (iss - 0.029) - 3.25 * (iss - 0.02).pow(15.0)
        } else {
            6.42 * iss
        }
        val exploitability = 8.22 * av * ac * pr * ui
        val base = when {
            impact <= 0.0 -> 0.0
            scopeChanged -> roundUp(min(1.08 * (impact + exploitability), 10.0))
            else -> roundUp(min(impact + exploitability, 10.0))
        }
        return CvssScore(base, severityForScore(base), vector)
    }

    fun severityForScore(score: Double): CvssSeverity = when {
        score <= 0.0 -> CvssSeverity.NONE
        score < 4.0 -> CvssSeverity.LOW
        score < 7.0 -> CvssSeverity.MEDIUM
        score < 9.0 -> CvssSeverity.HIGH
        else -> CvssSeverity.CRITICAL
    }

    private fun unknown(vector: String) = CvssScore(null, CvssSeverity.UNKNOWN, vector)

    /** CVSS 3.1 Roundup: ceil to one decimal, with integer arithmetic to avoid fp drift. */
    private fun roundUp(input: Double): Double {
        val intInput = (input * 100000).roundToLong()
        return if (intInput % 10000L == 0L) {
            intInput / 100000.0
        } else {
            (floor(intInput / 10000.0) + 1.0) / 10.0
        }
    }

    private val AV = mapOf("N" to 0.85, "A" to 0.62, "L" to 0.55, "P" to 0.20)
    private val AC = mapOf("L" to 0.77, "H" to 0.44)
    private val UI = mapOf("N" to 0.85, "R" to 0.62)
    private val PR_UNCHANGED = mapOf("N" to 0.85, "L" to 0.62, "H" to 0.27)
    private val PR_CHANGED = mapOf("N" to 0.85, "L" to 0.68, "H" to 0.50)
    private val CIA = mapOf("H" to 0.56, "L" to 0.22, "N" to 0.0)
}

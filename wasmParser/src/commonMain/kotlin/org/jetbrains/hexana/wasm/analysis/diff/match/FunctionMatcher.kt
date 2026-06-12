package org.jetbrains.hexana.wasm.analysis.diff.match

import org.jetbrains.hexana.wasm.analysis.MonosAnalyzer
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import kotlin.math.abs
import kotlin.math.max

/** How a function pair was matched (decreasing confidence). */
public enum class MatchEvidence { HASH_EXACT, NAME_ANCHOR, PROPAGATED, FUZZY }

/**
 * Per-side input to [FunctionMatcher]. [codeIdx] identifies the function within
 * its own module (index into the code section, == position in this list);
 * [globalIdx] is the WASM function index (imports first). [callees]/[callers]
 * are global function indices, for call-graph propagation.
 */
public data class FunctionDescriptor(
    val codeIdx: Int,
    val globalIdx: Int,
    val resolvedName: String?,
    val round1Hash: Long,
    val fingerprint: FunctionFingerprint,
    val callees: List<Int> = emptyList(),
    val callers: List<Int> = emptyList(),
)

/** A confirmed cross-module function pairing. [indexDelta] = right.codeIdx − left.codeIdx. */
public data class FunctionMatch(
    val leftIdx: Int,
    val rightIdx: Int,
    val classification: DiffClass,
    val confidence: Float,
    val evidence: MatchEvidence,
    val indexDelta: Int,
)

/** [matches] plus the functions that paired with nothing (REMOVED on the left, ADDED on the right). */
public data class FunctionMatchResult(
    val matches: List<FunctionMatch>,
    val unmatchedLeft: List<Int>,
    val unmatchedRight: List<Int>,
)

/**
 * Pairs functions across two modules, BinDiff-style, via ordered monotone passes
 * (each consumes the prior pass's residual):
 *
 * 1. **Exact content hash** unique on both sides → IDENTICAL / MOVED.
 * 2. **Name anchor** → MODIFIED (hash differs) or IDENTICAL/MOVED.
 * 1b. **Positional** pairing of content-identical functions left in ambiguous hash buckets.
 * 3. **Call-graph propagation** — confirmed pairs vote for their unmatched neighbours
 *    (mutual-best with a margin; anti-cascade).
 * 4. **Fuzzy fingerprint** — arity-gated structural similarity for the remainder.
 *
 * Residual functions are REMOVED/ADDED. Indices in [FunctionDescriptor] lists are
 * treated as list positions; [FunctionDescriptor.codeIdx] is reported in results.
 */
public object FunctionMatcher {

    private const val MAX_SWEEPS = 4
    private const val MAX_DEGREE = 64
    private const val VOTE_MARGIN = 1.5
    private const val FUZZY_FLOOR = 0.6
    private const val MAX_FUZZY_CELLS = 200_000L

    private data class RawPair(val leftPos: Int, val rightPos: Int, val evidence: MatchEvidence)

    public fun match(left: List<FunctionDescriptor>, right: List<FunctionDescriptor>): FunctionMatchResult {
        val matchedLeft = BooleanArray(left.size)
        val matchedRight = BooleanArray(right.size)
        val pairs = ArrayList<RawPair>()
        val confirm: (Int, Int, MatchEvidence) -> Unit = { l, r, e ->
            matchedLeft[l] = true
            matchedRight[r] = true
            pairs.add(RawPair(l, r, e))
        }

        pairUniqueByKey(left, right, matchedLeft, matchedRight, confirm, MatchEvidence.HASH_EXACT) { it.round1Hash }
        pairUniqueByKey(left, right, matchedLeft, matchedRight, confirm, MatchEvidence.NAME_ANCHOR) { it.resolvedName }
        pairResidualHashBucketsPositionally(left, right, matchedLeft, matchedRight, confirm)
        propagate(left, right, matchedLeft, matchedRight, pairs, confirm)
        fuzzy(left, right, matchedLeft, matchedRight, confirm)

        val matches = pairs.map { classify(left[it.leftPos], right[it.rightPos], it.evidence) }
        val unmatchedLeft = left.indices.filterNot { matchedLeft[it] }.map { left[it].codeIdx }
        val unmatchedRight = right.indices.filterNot { matchedRight[it] }.map { right[it].codeIdx }
        return FunctionMatchResult(matches, unmatchedLeft, unmatchedRight)
    }

    // --- Pass 1 / 2: unique key ---

    private inline fun pairUniqueByKey(
        left: List<FunctionDescriptor>,
        right: List<FunctionDescriptor>,
        matchedLeft: BooleanArray,
        matchedRight: BooleanArray,
        confirm: (Int, Int, MatchEvidence) -> Unit,
        evidence: MatchEvidence,
        keyOf: (FunctionDescriptor) -> Any?,
    ) {
        val leftBuckets = bucketUnmatched(left, matchedLeft, keyOf)
        val rightBuckets = bucketUnmatched(right, matchedRight, keyOf)
        for ((key, lis) in leftBuckets) {
            val ris = rightBuckets[key] ?: continue
            if (lis.size == 1 && ris.size == 1) confirm(lis[0], ris[0], evidence)
        }
    }

    // --- Pass 1b: content-identical leftovers ---

    private fun pairResidualHashBucketsPositionally(
        left: List<FunctionDescriptor>,
        right: List<FunctionDescriptor>,
        matchedLeft: BooleanArray,
        matchedRight: BooleanArray,
        confirm: (Int, Int, MatchEvidence) -> Unit,
    ) {
        val leftBuckets = bucketUnmatched(left, matchedLeft) { it.round1Hash }
        val rightBuckets = bucketUnmatched(right, matchedRight) { it.round1Hash }
        for ((hash, lis) in leftBuckets) {
            val ris = rightBuckets[hash] ?: continue
            val ls = lis.sortedBy { left[it].codeIdx }
            val rs = ris.sortedBy { right[it].codeIdx }
            for (i in 0 until minOf(ls.size, rs.size)) confirm(ls[i], rs[i], MatchEvidence.HASH_EXACT)
        }
    }

    // --- Pass 3: call-graph propagation ---

    private fun propagate(
        left: List<FunctionDescriptor>,
        right: List<FunctionDescriptor>,
        matchedLeft: BooleanArray,
        matchedRight: BooleanArray,
        pairs: List<RawPair>,
        confirm: (Int, Int, MatchEvidence) -> Unit,
    ) {
        val leftPosByGlobal = HashMap<Int, Int>().apply { left.forEachIndexed { i, d -> put(d.globalIdx, i) } }
        val rightPosByGlobal = HashMap<Int, Int>().apply { right.forEachIndexed { i, d -> put(d.globalIdx, i) } }

        repeat(MAX_SWEEPS) {
            val votes = HashMap<Long, Double>()
            for (p in pairs.toList()) {
                val l = left[p.leftPos]
                val r = right[p.rightPos]
                vote(l.callees, r.callees, left, right, leftPosByGlobal, rightPosByGlobal, matchedLeft, matchedRight, votes)
                vote(l.callers, r.callers, left, right, leftPosByGlobal, rightPosByGlobal, matchedLeft, matchedRight, votes)
            }
            val confirmedThisSweep = resolveMutualBest(votes)
            if (confirmedThisSweep.isEmpty()) return
            for ((l, r) in confirmedThisSweep) {
                if (!matchedLeft[l] && !matchedRight[r]) confirm(l, r, MatchEvidence.PROPAGATED)
            }
        }
    }

    private fun vote(
        leftNeighbors: List<Int>,
        rightNeighbors: List<Int>,
        left: List<FunctionDescriptor>,
        right: List<FunctionDescriptor>,
        leftPosByGlobal: Map<Int, Int>,
        rightPosByGlobal: Map<Int, Int>,
        matchedLeft: BooleanArray,
        matchedRight: BooleanArray,
        votes: HashMap<Long, Double>,
    ) {
        if (leftNeighbors.size > MAX_DEGREE || rightNeighbors.size > MAX_DEGREE) return
        val lp = leftNeighbors.mapNotNull { leftPosByGlobal[it] }.filterNot { matchedLeft[it] }
        val rp = rightNeighbors.mapNotNull { rightPosByGlobal[it] }.filterNot { matchedRight[it] }
        for (l in lp) for (r in rp) {
            if (left[l].fingerprint.typeArity != right[r].fingerprint.typeArity) continue
            val key = (l.toLong() shl 32) or (r.toLong() and 0xFFFFFFFFL)
            votes[key] = (votes[key] ?: 0.0) + 1.0
        }
    }

    /** Confirms (l,r) when it is the mutual best AND beats each side's runner-up by [VOTE_MARGIN]. */
    private fun resolveMutualBest(votes: Map<Long, Double>): List<Pair<Int, Int>> {
        val bestForLeft = HashMap<Int, DoubleArray>()   // l -> [bestR, bestScore, secondScore]
        val bestForRight = HashMap<Int, DoubleArray>()   // r -> [bestL, bestScore, secondScore]
        for ((key, score) in votes) {
            val l = (key ushr 32).toInt()
            val r = (key and 0xFFFFFFFFL).toInt()
            updateBest(bestForLeft, l, r, score)
            updateBest(bestForRight, r, l, score)
        }
        val out = ArrayList<Pair<Int, Int>>()
        for ((l, lb) in bestForLeft) {
            val r = lb[0].toInt()
            val rb = bestForRight[r] ?: continue
            if (rb[0].toInt() != l) continue                       // mutual best
            if (lb[1] < VOTE_MARGIN * lb[2] || rb[1] < VOTE_MARGIN * rb[2]) continue   // margin
            out.add(l to r)
        }
        return out
    }

    private fun updateBest(map: HashMap<Int, DoubleArray>, key: Int, other: Int, score: Double) {
        val cur = map[key]
        if (cur == null) {
            map[key] = doubleArrayOf(other.toDouble(), score, 0.0)
        } else if (score > cur[1]) {
            map[key] = doubleArrayOf(other.toDouble(), score, cur[1])
        } else if (score > cur[2]) {
            cur[2] = score
        }
    }

    // --- Pass 4: fuzzy fingerprint ---

    private fun fuzzy(
        left: List<FunctionDescriptor>,
        right: List<FunctionDescriptor>,
        matchedLeft: BooleanArray,
        matchedRight: BooleanArray,
        confirm: (Int, Int, MatchEvidence) -> Unit,
    ) {
        val leftByArity = left.indices.filterNot { matchedLeft[it] }.groupBy { left[it].fingerprint.typeArity }
        val rightByArity = right.indices.filterNot { matchedRight[it] }.groupBy { right[it].fingerprint.typeArity }
        for ((arity, ls) in leftByArity) {
            val rs = rightByArity[arity] ?: continue
            if (ls.size.toLong() * rs.size > MAX_FUZZY_CELLS) continue
            val scored = ArrayList<Triple<Int, Int, Double>>()
            for (l in ls) for (r in rs) {
                val s = similarity(left[l], right[r])
                if (s >= FUZZY_FLOOR) scored.add(Triple(l, r, s))
            }
            scored.sortByDescending { it.third }
            for ((l, r, _) in scored) {
                if (!matchedLeft[l] && !matchedRight[r]) confirm(l, r, MatchEvidence.FUZZY)
            }
        }
    }

    private fun similarity(l: FunctionDescriptor, r: FunctionDescriptor): Double {
        val a = l.fingerprint
        val b = r.fingerprint
        if (a.typeArity != b.typeArity) return 0.0
        // No instructions ⇒ no structural evidence to fuzzy-match on.
        if (a.instrCount == 0 || b.instrCount == 0) return 0.0
        var score = 0.6 * ratio(a.instrCount, b.instrCount) +
            0.2 * ratio(a.callCount, b.callCount) +
            0.2 * ratio(a.maxNestDepth, b.maxNestDepth)
        val ln = l.resolvedName
        val rn = r.resolvedName
        if (ln != null && rn != null && MonosAnalyzer.extractBaseName(ln) == MonosAnalyzer.extractBaseName(rn)) {
            score = minOf(1.0, score + 0.3)
        }
        return score
    }

    private fun ratio(x: Int, y: Int): Double {
        val m = max(x, y)
        return if (m == 0) 1.0 else 1.0 - abs(x - y).toDouble() / m
    }

    // --- shared ---

    private inline fun bucketUnmatched(
        descriptors: List<FunctionDescriptor>,
        matched: BooleanArray,
        keyOf: (FunctionDescriptor) -> Any?,
    ): Map<Any, MutableList<Int>> {
        val buckets = HashMap<Any, MutableList<Int>>()
        descriptors.forEachIndexed { i, d ->
            if (matched[i]) return@forEachIndexed
            val key = keyOf(d) ?: return@forEachIndexed
            buckets.getOrPut(key) { ArrayList() }.add(i)
        }
        return buckets
    }

    private fun classify(l: FunctionDescriptor, r: FunctionDescriptor, evidence: MatchEvidence): FunctionMatch {
        // MOVED is keyed on the code-section index, not the global index: a global-index
        // shift from adding/removing imports is not a "move" and must not flip every
        // defined function to MOVED. A genuine reorder within the code section is.
        val classification = when {
            l.round1Hash != r.round1Hash -> DiffClass.MODIFIED
            l.codeIdx == r.codeIdx -> DiffClass.IDENTICAL
            else -> DiffClass.MOVED
        }
        val confidence = when (evidence) {
            MatchEvidence.HASH_EXACT -> 1.0f
            MatchEvidence.NAME_ANCHOR -> 0.9f
            MatchEvidence.PROPAGATED -> 0.75f
            MatchEvidence.FUZZY -> 0.6f
        }
        return FunctionMatch(l.codeIdx, r.codeIdx, classification, confidence, evidence, r.codeIdx - l.codeIdx)
    }
}

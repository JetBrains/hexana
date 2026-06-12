package org.jetbrains.hexana.wasm.analysis.diff.match

import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionMatcherTest {

    private val fp = FunctionFingerprint(0, 0, 0, 0)

    private fun desc(
        codeIdx: Int,
        globalIdx: Int = codeIdx,
        name: String? = null,
        hash: Long,
        fp: FunctionFingerprint = this.fp,
        callees: List<Int> = emptyList(),
    ) = FunctionDescriptor(codeIdx, globalIdx, name, hash, fp, callees)

    @Test
    fun `identical content at same index is IDENTICAL`() {
        val left = listOf(desc(0, hash = 100), desc(1, hash = 200))
        val right = listOf(desc(0, hash = 100), desc(1, hash = 200))
        val r = FunctionMatcher.match(left, right)
        assertEquals(2, r.matches.size)
        assertEquals(2, r.matches.count { it.classification == DiffClass.IDENTICAL })
        assertEquals(0, r.unmatchedLeft.size + r.unmatchedRight.size)
    }

    @Test
    fun `identical content at a different code index is MOVED`() {
        // Same body hash 100, but at code index 0 (left) vs 3 (right) -> MOVED.
        val left = listOf(desc(0, hash = 100))
        val right = listOf(desc(3, hash = 100))
        val r = FunctionMatcher.match(left, right)
        val m = r.matches.single()
        assertEquals(DiffClass.MOVED, m.classification)
        assertEquals(3, m.indexDelta)
        assertEquals(MatchEvidence.HASH_EXACT, m.evidence)
    }

    @Test
    fun `adding an import does not flip a defined function to MOVED`() {
        // codeIdx unchanged (0), only globalIdx shifts (0 -> 1) due to a new import.
        val left = listOf(desc(0, globalIdx = 0, hash = 100))
        val right = listOf(desc(0, globalIdx = 1, hash = 100))
        val m = FunctionMatcher.match(left, right).matches.single()
        assertEquals(DiffClass.IDENTICAL, m.classification)
        assertEquals(0, m.indexDelta)
    }

    @Test
    fun `name anchor pairs a changed body as MODIFIED`() {
        val left = listOf(desc(0, name = "foo", hash = 100))
        val right = listOf(desc(0, name = "foo", hash = 999)) // body changed
        val r = FunctionMatcher.match(left, right)
        val m = r.matches.single()
        assertEquals(DiffClass.MODIFIED, m.classification)
        assertEquals(MatchEvidence.NAME_ANCHOR, m.evidence)
    }

    @Test
    fun `unmatched functions are added and removed`() {
        val left = listOf(desc(0, name = "gone", hash = 1))
        val right = listOf(desc(0, name = "fresh", hash = 2))
        val r = FunctionMatcher.match(left, right)
        assertEquals(0, r.matches.size)
        assertEquals(listOf(0), r.unmatchedLeft)
        assertEquals(listOf(0), r.unmatchedRight)
    }

    @Test
    fun `propagation pairs an unnamed neighbour of an anchored pair`() {
        // A/A' anchored by name; their unmatched callee B/B' (unnamed, changed body) pairs by propagation.
        val left = listOf(
            desc(0, globalIdx = 0, name = "anchor", hash = 1, callees = listOf(1)),
            desc(1, globalIdx = 1, name = null, hash = 5),
        )
        val right = listOf(
            desc(0, globalIdx = 0, name = "anchor", hash = 1, callees = listOf(1)),
            desc(1, globalIdx = 1, name = null, hash = 9),
        )
        val r = FunctionMatcher.match(left, right)
        val propagated = r.matches.single { it.evidence == MatchEvidence.PROPAGATED }
        assertEquals(1, propagated.leftIdx)
        assertEquals(1, propagated.rightIdx)
        assertEquals(DiffClass.MODIFIED, propagated.classification)
        assertEquals(0, r.unmatchedLeft.size + r.unmatchedRight.size)
    }

    @Test
    fun `fuzzy pairs a renamed near-duplicate and respects the arity gate`() {
        val similar = FunctionFingerprint(typeArity = FunctionFingerprint.arity(1, 1), instrCount = 100, callCount = 5, maxNestDepth = 3)
        val similar2 = FunctionFingerprint(typeArity = FunctionFingerprint.arity(1, 1), instrCount = 102, callCount = 5, maxNestDepth = 3)
        val left = listOf(desc(0, name = "old_name", hash = 1, fp = similar))
        val right = listOf(desc(0, name = "new_name", hash = 2, fp = similar2))
        val m = FunctionMatcher.match(left, right).matches.single()
        assertEquals(MatchEvidence.FUZZY, m.evidence)
        assertEquals(DiffClass.MODIFIED, m.classification)

        // Different arity -> not paired.
        val diffArity = FunctionFingerprint(typeArity = FunctionFingerprint.arity(2, 0), instrCount = 102, callCount = 5, maxNestDepth = 3)
        val r2 = FunctionMatcher.match(left, listOf(desc(0, name = "new_name", hash = 2, fp = diffArity)))
        assertEquals(0, r2.matches.size)
        assertEquals(1, r2.unmatchedLeft.size)
    }

    @Test
    fun `exact hash beats name and ambiguous hash buckets defer to name`() {
        // Two left + two right share hash 100 (ambiguous) but have distinct names.
        // Pass 1 cannot pair (ambiguous); pass 2 pairs by name.
        val left = listOf(desc(0, name = "a", hash = 100), desc(1, name = "b", hash = 100))
        val right = listOf(desc(0, name = "b", hash = 100), desc(1, name = "a", hash = 100))
        val r = FunctionMatcher.match(left, right)
        assertEquals(2, r.matches.size)
        // "a": left idx0 <-> right idx1; "b": left idx1 <-> right idx0. Both content-equal.
        assertEquals(2, r.matches.count { it.classification == DiffClass.MOVED || it.classification == DiffClass.IDENTICAL })
    }
}

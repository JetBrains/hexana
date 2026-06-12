package org.jetbrains.hexana.wasm.analysis.diff.line

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineDiffTest {

    @Test
    fun `common prefix and suffix are matched, indices stay absolute`() {
        val left = listOf("a", "b", "c", "d", "e")
        val right = listOf("a", "X", "c", "d", "e")
        val ops = LineDiff.diff(left, right)
        // a SAME; b REMOVED; X ADDED; c,d,e SAME.
        assertEquals(LineOpKind.SAME, ops[0].kind)
        assertEquals(0, ops[0].leftIdx)
        val removed = ops.single { it.kind == LineOpKind.REMOVED }
        assertEquals("b", removed.text); assertEquals(1, removed.leftIdx)
        val added = ops.single { it.kind == LineOpKind.ADDED }
        assertEquals("X", added.text); assertEquals(1, added.rightIdx)
        // Trailing c,d,e keep absolute indices.
        val cdE = ops.filter { it.kind == LineOpKind.SAME && it.text in setOf("c", "d", "e") }
        assertEquals(listOf(2, 3, 4), cdE.map { it.leftIdx })
        assertEquals(listOf(2, 3, 4), cdE.map { it.rightIdx })
    }

    @Test
    fun `huge differing inputs fall back without an O(n*m) matrix`() {
        // 2000 x 2000 distinct lines = 4M cells > cap -> fallback (all removed then all added).
        val left = (0 until 2000).map { "L$it" }
        val right = (0 until 2000).map { "R$it" }
        val ops = LineDiff.diff(left, right)
        assertEquals(4000, ops.size)
        assertEquals(2000, ops.count { it.kind == LineOpKind.REMOVED })
        assertEquals(2000, ops.count { it.kind == LineOpKind.ADDED })
        assertEquals(0, ops.count { it.kind == LineOpKind.SAME })
        // REMOVED precede ADDED.
        val firstAdded = ops.indexOfFirst { it.kind == LineOpKind.ADDED }
        val lastRemoved = ops.indexOfLast { it.kind == LineOpKind.REMOVED }
        assertTrue(lastRemoved < firstAdded)
    }

    @Test
    fun `identical lists are all SAME`() {
        val lines = listOf("a", "b", "c")
        val ops = LineDiff.diff(lines, lines)
        assertEquals(3, ops.size)
        assertTrue(ops.all { it.kind == LineOpKind.SAME })
        assertEquals(listOf(0, 1, 2), ops.map { it.leftIdx })
        assertEquals(listOf(0, 1, 2), ops.map { it.rightIdx })
    }

    @Test
    fun `empty inputs produce no ops`() {
        assertEquals(emptyList(), LineDiff.diff(emptyList(), emptyList()))
    }

    @Test
    fun `inserted line is ADDED, rest SAME`() {
        val ops = LineDiff.diff(listOf("a", "c"), listOf("a", "b", "c"))
        assertEquals(LineOpKind.SAME, ops[0].kind)
        assertEquals(LineOpKind.ADDED, ops[1].kind)
        assertEquals("b", ops[1].text)
        assertEquals(null, ops[1].leftIdx)
        assertEquals(1, ops[1].rightIdx)
        assertEquals(LineOpKind.SAME, ops[2].kind)
        assertEquals(1, ops.count { it.kind == LineOpKind.ADDED })
        assertEquals(0, ops.count { it.kind == LineOpKind.REMOVED })
    }

    @Test
    fun `deleted line is REMOVED`() {
        val ops = LineDiff.diff(listOf("a", "b", "c"), listOf("a", "c"))
        assertEquals(1, ops.count { it.kind == LineOpKind.REMOVED })
        val removed = ops.single { it.kind == LineOpKind.REMOVED }
        assertEquals("b", removed.text)
        assertEquals(1, removed.leftIdx)
        assertEquals(null, removed.rightIdx)
    }

    @Test
    fun `replaced line is REMOVED then ADDED`() {
        val ops = LineDiff.diff(listOf("a", "x", "c"), listOf("a", "y", "c"))
        val mid = ops.filter { it.kind != LineOpKind.SAME }
        assertEquals(2, mid.size)
        assertEquals(LineOpKind.REMOVED, mid[0].kind)
        assertEquals("x", mid[0].text)
        assertEquals(LineOpKind.ADDED, mid[1].kind)
        assertEquals("y", mid[1].text)
        // Reading order: REMOVED before ADDED in the hunk.
        assertTrue(ops.indexOf(mid[0]) < ops.indexOf(mid[1]))
    }
}

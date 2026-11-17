package org.jetbrains.hexana.ui

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.ui.panel.TgNodeRect
import org.jetbrains.hexana.ui.panel.TypeGraphFilter
import org.jetbrains.hexana.ui.panel.assignTypeLayers
import org.jetbrains.hexana.ui.panel.placeNewNodesIncrementally
import org.jetbrains.hexana.ui.panel.recFrameIsHonest
import org.jetbrains.hexana.ui.panel.recGroupClusters
import org.jetbrains.hexana.ui.panel.unionWithExpansions
import org.jetbrains.hexana.wasm.analysis.typegraph.TypeGraphIndex
import org.jetbrains.hexana.wasm.analysis.typegraph.TypeGraphNodeKind

/**
 * Pure-logic tests for WasmTypeGraphPanel helpers.
 * No Compose runtime or IntelliJ Platform is required: all helpers are pure functions.
 */
class WasmTypeGraphPanelTest : UsefulTestCase() {

    // -------------------------------------------------------------------
    // Minimal TypeGraphIndex stub
    // -------------------------------------------------------------------

    /**
     * Builds a minimal TypeGraphIndex from raw arrays for testing.
     * kinds: 0=STRUCT, 1=ARRAY, 2=FUNC (matches TypeGraphNodeKind ordinals).
     * superTypesOf: parallel array of supertype lists for each type index.
     * subtypesOf:   parallel array of directSubtype lists.
     * recGroupOf:   rec-group index per type (-1 = none).
     */
    private fun buildIndex(
        count: Int,
        kinds: IntArray = IntArray(count),           // default: all STRUCT
        superTypesOf: Array<IntArray> = Array(count) { intArrayOf() },
        subtypesOf: Array<IntArray> = Array(count) { intArrayOf() },
        recGroupOf: IntArray = IntArray(count) { -1 },
    ): TypeGraphIndex {
        val kindBytes = ByteArray(count) { kinds[it].toByte() }
        val recGroups = recGroupOf.toList()
            .mapIndexedNotNull { i, g -> if (g >= 0) g to i else null }
            .groupBy({ it.first }, { it.second })
            .map { (_, members) -> members.min()..members.max() }
        return TypeGraphIndex(
            typeCount = count,
            kinds = kindBytes,
            recGroupOf = recGroupOf,
            recGroups = recGroups,
            superTypesOf = superTypesOf,
            subtypesOf = subtypesOf,
            fieldRefsOf = Array(count) { intArrayOf() },
            referencedByTypes = Array(count) { intArrayOf() },
            referencedByFuncs = Array(count) { intArrayOf() },
        )
    }

    // -------------------------------------------------------------------
    // assignTypeLayers
    // -------------------------------------------------------------------

    fun testLayerAssignmentFlat() {
        // Four types, no hierarchy — all must be depth 0.
        val index = buildIndex(4)
        val layers = assignTypeLayers(intArrayOf(0, 1, 2, 3), index)
        assertEquals("type count", 4, layers.size)
        assertEquals(0, layers[0])
        assertEquals(0, layers[1])
        assertEquals(0, layers[2])
        assertEquals(0, layers[3])
    }

    fun testLayerAssignmentLinearChain() {
        // 0 -> 1 -> 2 -> 3 (subtype chain, depth grows by 1 each step).
        val superTypesOf = Array(4) { i -> if (i > 0) intArrayOf(i - 1) else intArrayOf() }
        val subtypesOf = Array(4) { i -> if (i < 3) intArrayOf(i + 1) else intArrayOf() }
        val index = buildIndex(4, superTypesOf = superTypesOf, subtypesOf = subtypesOf)
        val layers = assignTypeLayers(intArrayOf(0, 1, 2, 3), index)
        assertEquals(0, layers[0])
        assertEquals(1, layers[1])
        assertEquals(2, layers[2])
        assertEquals(3, layers[3])
    }

    fun testLayerAssignmentDiamond() {
        // Diamond: 0 is root; 1 and 2 are both subtypes of 0; 3 is subtype of both 1 and 2.
        // Expected: 0=0, 1=1, 2=1, 3=2.
        val superTypesOf = arrayOf(
            intArrayOf(),          // 0: root
            intArrayOf(0),         // 1: subtype of 0
            intArrayOf(0),         // 2: subtype of 0
            intArrayOf(1, 2),      // 3: subtype of both (diamond)
        )
        val subtypesOf = arrayOf(
            intArrayOf(1, 2),      // 0 -> {1, 2}
            intArrayOf(3),         // 1 -> 3
            intArrayOf(3),         // 2 -> 3
            intArrayOf(),          // 3: leaf
        )
        val index = buildIndex(4, superTypesOf = superTypesOf, subtypesOf = subtypesOf)
        val layers = assignTypeLayers(intArrayOf(0, 1, 2, 3), index)
        assertEquals(0, layers[0])
        assertEquals(1, layers[1])
        assertEquals(1, layers[2])
        assertEquals(2, layers[3])
    }

    fun testLayerAssignmentSubsetOnly() {
        // Chain 0->1->2->3, but only 1 and 2 are included.
        // 1's supertype (0) is not in the set, so 1 gets depth 0; 2 gets depth 1.
        val superTypesOf = Array(4) { i -> if (i > 0) intArrayOf(i - 1) else intArrayOf() }
        val subtypesOf = Array(4) { i -> if (i < 3) intArrayOf(i + 1) else intArrayOf() }
        val index = buildIndex(4, superTypesOf = superTypesOf, subtypesOf = subtypesOf)
        val layers = assignTypeLayers(intArrayOf(1, 2), index)
        assertEquals(2, layers.size)
        assertEquals(0, layers[1])
        assertEquals(1, layers[2])
    }

    // -------------------------------------------------------------------
    // unionWithExpansions
    // -------------------------------------------------------------------

    fun testUnionWithExpansionsNoExpansions() {
        val index = buildIndex(4)
        val base = intArrayOf(0, 1)
        val result = unionWithExpansions(base, emptySet(), index, TypeGraphFilter.BOTH)
        assertEquals(2, result.size)
        assertTrue(0 in result)
        assertTrue(1 in result)
    }

    fun testUnionWithExpansionsAddsSubtypes() {
        // Type 0 has subtypes 1 and 2; base includes only 0; expanding 0 should add 1 and 2.
        val subtypesOf = arrayOf(intArrayOf(1, 2), intArrayOf(), intArrayOf(), intArrayOf())
        val index = buildIndex(4, subtypesOf = subtypesOf)
        val result = unionWithExpansions(intArrayOf(0), setOf(0), index, TypeGraphFilter.HIERARCHY)
        assertEquals(3, result.size)
        assertTrue(0 in result)
        assertTrue(1 in result)
        assertTrue(2 in result)
    }

    fun testUnionWithExpansionsDoesNotAddNotInBase() {
        // Type 5 is in expandedTypes but not in base; it must not add 5's neighbors.
        val subtypesOf = arrayOf(intArrayOf(), intArrayOf(), intArrayOf(), intArrayOf(),
            intArrayOf(), intArrayOf(6))
        val index = buildIndex(7, subtypesOf = subtypesOf)
        val result = unionWithExpansions(intArrayOf(0, 1, 2), setOf(5), index, TypeGraphFilter.HIERARCHY)
        // 5 is not in base, so its subtype 6 must not appear.
        assertEquals(3, result.size)
        assertFalse(5 in result)
        assertFalse(6 in result)
    }

    // -------------------------------------------------------------------
    // recGroupClusters
    // -------------------------------------------------------------------

    fun testRecGroupClustersEmptyWhenNoGroups() {
        val index = buildIndex(4) // all recGroupOf = -1
        val clusters = recGroupClusters(listOf(0, 1, 2, 3), index)
        assertTrue(clusters.isEmpty())
    }

    fun testRecGroupClustersOmitsSingleMember() {
        // Rec group 0 has only type 0 included; rec group 1 has types 1 and 2.
        val recGroupOf = intArrayOf(0, 1, 1, -1)
        val index = buildIndex(4, recGroupOf = recGroupOf)
        val clusters = recGroupClusters(listOf(0, 1, 2, 3), index)
        assertFalse(0 in clusters)       // group 0 has only 1 member
        assertTrue(1 in clusters)
        assertEquals(listOf(1, 2), clusters[1]?.sorted())
    }

    fun testRecGroupClustersThreeMembers() {
        val recGroupOf = intArrayOf(0, 0, 0, -1)
        val index = buildIndex(4, recGroupOf = recGroupOf)
        val clusters = recGroupClusters(listOf(0, 1, 2, 3), index)
        assertEquals(1, clusters.size)
        assertEquals(listOf(0, 1, 2), clusters[0]?.sorted())
    }

    // -------------------------------------------------------------------
    // placeNewNodesIncrementally
    // -------------------------------------------------------------------

    /** Helper: asserts two TgNodeRects do not overlap (strict separation on at least one axis). */
    private fun assertNoOverlap(a: TgNodeRect, b: TgNodeRect, label: String) {
        val hSep = a.x + a.width <= b.x || b.x + b.width <= a.x
        val vSep = a.y + a.height <= b.y || b.y + b.height <= a.y
        assertTrue("$label: rects overlap. a=$a  b=$b", hSep || vSep)
    }

    /** Test 1: survivors keep IDENTICAL rects after expansion. */
    fun testIncrementalSurvivorRectsUnchanged() {
        val anchor = TgNodeRect(0f, 0f, 100f, 40f)
        val survivor1 = TgNodeRect(200f, 0f, 80f, 40f)
        val survivor2 = TgNodeRect(-150f, 0f, 90f, 40f)
        val previousPositions = mapOf(
            0 to anchor,
            1 to survivor1,
            2 to survivor2,
        )
        val survivorIds = setOf(0, 1, 2) // all three survive; no new nodes
        val result = placeNewNodesIncrementally(
            previousPositions = previousPositions,
            survivorIds = survivorIds,
            newNodeSizes = emptyMap(),
            anchorRect = anchor,
            maxRowWidth = 1800f,
            hGap = 40f,
            vGap = 140f,
        )
        // No new nodes: result must be empty (function returns only new node rects).
        assertTrue("result must be empty when no new nodes", result.isEmpty())
    }

    /** Test 2: new nodes land below the anchor's bottom edge and overlap no existing rect. */
    fun testIncrementalNewNodesBelowAnchorNoOverlap() {
        val anchor = TgNodeRect(0f, 100f, 100f, 40f)
        val previousPositions = mapOf(0 to anchor)
        val survivorIds = setOf(0)
        val newSizes = mapOf(10 to (80f to 36f), 11 to (90f to 36f))

        val result = placeNewNodesIncrementally(
            previousPositions = previousPositions,
            survivorIds = survivorIds,
            newNodeSizes = newSizes,
            anchorRect = anchor,
            maxRowWidth = 1800f,
            hGap = 40f,
            vGap = 140f,
        )

        assertEquals("should place both new nodes", 2, result.size)
        val anchorBottom = anchor.y + anchor.height // 140f
        for ((id, rect) in result) {
            assertTrue("new node $id must start below anchor bottom ($anchorBottom), got y=${rect.y}",
                rect.y >= anchorBottom)
        }
        // Pairwise non-overlap: new nodes vs anchor, and new nodes vs each other.
        val allRects = previousPositions + result
        val ids = allRects.keys.toList()
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                assertNoOverlap(allRects[ids[i]]!!, allRects[ids[j]]!!, "ids ${ids[i]} and ${ids[j]}")
            }
        }
    }

    /** Test 3: row wrapping when new nodes exceed maxRowWidth. */
    fun testIncrementalRowWrappingProducesMultipleRows() {
        val anchor = TgNodeRect(0f, 0f, 100f, 40f)
        val previousPositions = mapOf(0 to anchor)
        val survivorIds = setOf(0)
        // Three wide nodes that cannot all fit in 300px with 40px gaps:
        // 120+40+120+40+120 = 440 > 300; so node 3 must wrap to a new row.
        val newSizes = mapOf(
            1 to (120f to 36f),
            2 to (120f to 36f),
            3 to (120f to 36f),
        )

        val result = placeNewNodesIncrementally(
            previousPositions = previousPositions,
            survivorIds = survivorIds,
            newNodeSizes = newSizes,
            anchorRect = anchor,
            maxRowWidth = 300f,
            hGap = 40f,
            vGap = 60f,
        )

        assertEquals("should place all 3 new nodes", 3, result.size)
        val distinctY = result.values.map { it.y }.toSet()
        assertTrue("should have more than one distinct y (wrapping), got $distinctY", distinctY.size > 1)
    }

    /**
     * Test 4: collapse case. placeNewNodesIncrementally with no new nodes returns empty map;
     * the caller's merge (survivors only) contains no removed ids.
     * Exercises the pure function's contract for the collapse direction.
     */
    fun testIncrementalCollapseResultContainsNoRemovedIds() {
        val anchor = TgNodeRect(0f, 0f, 100f, 40f)
        val previousPositions = mapOf(
            0 to anchor,
            5 to TgNodeRect(200f, 200f, 80f, 36f), // this node will be "removed"
        )
        val survivorIds = setOf(0) // node 5 collapsed away
        val result = placeNewNodesIncrementally(
            previousPositions = previousPositions,
            survivorIds = survivorIds,
            newNodeSizes = emptyMap(), // no new nodes on collapse
            anchorRect = anchor,
            maxRowWidth = 1800f,
            hGap = 40f,
            vGap = 140f,
        )
        // Function returns only new node rects; empty here.
        assertTrue("no new nodes on collapse: result must be empty", result.isEmpty())
        // Simulate the caller's merge: survivorIds filtered from previousPositions.
        val merged = previousPositions.filterKeys { it in survivorIds } + result
        assertFalse("removed id 5 must not appear in merged result", 5 in merged)
        assertTrue("survivor 0 must be present", 0 in merged)
        assertEquals("merged result has exactly the survivor", 1, merged.size)
    }

    /** Test 5: collision push-down. Pre-occupied space below anchor forces new nodes lower. */
    fun testIncrementalCollisionPushDown() {
        val anchor = TgNodeRect(0f, 0f, 100f, 40f)
        // A blocker rect sits exactly where the first row would land (y=40..140).
        val blocker = TgNodeRect(-200f, 40f, 600f, 100f)
        val previousPositions = mapOf(0 to anchor, 99 to blocker)
        val survivorIds = setOf(0, 99)
        val newSizes = mapOf(10 to (80f to 36f))

        val result = placeNewNodesIncrementally(
            previousPositions = previousPositions,
            survivorIds = survivorIds,
            newNodeSizes = newSizes,
            anchorRect = anchor,
            maxRowWidth = 1800f,
            hGap = 40f,
            vGap = 40f,
        )

        assertEquals(1, result.size)
        val newRect = result[10]!!
        // The blocker occupies y=40..140; new node must be placed below blocker bottom (140f).
        assertTrue("new node must be placed below blocker (y >= 140), got y=${newRect.y}", newRect.y >= 140f)
        // Must not overlap the blocker.
        assertNoOverlap(blocker, newRect, "blocker vs new node 10")
    }

    /**
     * Test 6: chained expansions.
     * First call: place nodes 10, 11 below anchor 0.
     * Merge into positions.
     * Second call: place nodes 20, 21 below anchor 1 (a survivor from the first merge).
     * Assert all rects from the first merge are unchanged, and full pairwise non-overlap holds.
     */
    fun testIncrementalChainedExpansionsPreservePriorRects() {
        val anchor0 = TgNodeRect(0f, 0f, 100f, 40f)
        val anchor1 = TgNodeRect(200f, 0f, 80f, 40f)
        val positions0 = mapOf(0 to anchor0, 1 to anchor1)
        val survivors0 = setOf(0, 1)
        val newSizes1 = mapOf(10 to (80f to 36f), 11 to (80f to 36f))

        // First expansion: expand anchor 0.
        val placed1 = placeNewNodesIncrementally(
            previousPositions = positions0,
            survivorIds = survivors0,
            newNodeSizes = newSizes1,
            anchorRect = anchor0,
            maxRowWidth = 1800f,
            hGap = 40f,
            vGap = 140f,
            rowGap = 60f,
        )
        assertEquals("first expansion: 2 new nodes", 2, placed1.size)

        // Merge: survivors + placed1.
        val positions1 = positions0 + placed1

        // Second expansion: expand anchor 1, adding nodes 20, 21.
        val newSizes2 = mapOf(20 to (80f to 36f), 21 to (80f to 36f))
        val survivors1 = positions1.keys.toSet()
        val placed2 = placeNewNodesIncrementally(
            previousPositions = positions1,
            survivorIds = survivors1,
            newNodeSizes = newSizes2,
            anchorRect = anchor1,
            maxRowWidth = 1800f,
            hGap = 40f,
            vGap = 140f,
            rowGap = 60f,
        )
        assertEquals("second expansion: 2 new nodes", 2, placed2.size)

        // Merge again.
        val positions2 = positions1 + placed2

        // All rects from positions1 must be identical in positions2 (chained expansion never moves survivors).
        for ((id, rect) in positions1) {
            val inFinal = positions2[id]
            assertNotNull("id $id must survive into final merged positions", inFinal)
            assertEquals("id $id x unchanged", rect.x, inFinal!!.x, 0.01f)
            assertEquals("id $id y unchanged", rect.y, inFinal.y, 0.01f)
            assertEquals("id $id w unchanged", rect.width, inFinal.width, 0.01f)
            assertEquals("id $id h unchanged", rect.height, inFinal.height, 0.01f)
        }

        // Full pairwise non-overlap across all rects in the final merged map.
        val allIds = positions2.keys.toList()
        for (i in allIds.indices) {
            for (j in i + 1 until allIds.size) {
                assertNoOverlap(positions2[allIds[i]]!!, positions2[allIds[j]]!!, "ids ${allIds[i]} and ${allIds[j]}")
            }
        }
    }

    /**
     * Test 7: double-place guard (FIX 3 contract).
     * An id present in previousPositions passed inside newNodeSizes must NOT be re-placed
     * (it is absent from the returned map).
     */
    fun testIncrementalDoublePlace_guardFiltersAlreadyPlacedIds() {
        val anchor = TgNodeRect(0f, 0f, 100f, 40f)
        val alreadyPlaced = TgNodeRect(300f, 100f, 80f, 36f)
        val previousPositions = mapOf(0 to anchor, 5 to alreadyPlaced)
        val survivorIds = setOf(0, 5)

        // Pass id 5 (already in previousPositions) alongside id 10 (genuinely new).
        val newSizes = mapOf(
            5 to (80f to 36f),   // already placed — must be filtered out
            10 to (80f to 36f),  // genuinely new — must appear in result
        )

        val result = placeNewNodesIncrementally(
            previousPositions = previousPositions,
            survivorIds = survivorIds,
            newNodeSizes = newSizes,
            anchorRect = anchor,
            maxRowWidth = 1800f,
            hGap = 40f,
            vGap = 140f,
        )

        // id 5 must NOT appear in result (it was already in previousPositions).
        assertFalse("already-placed id 5 must not be re-placed", 5 in result)
        // id 10 must appear (genuinely new).
        assertTrue("genuinely new id 10 must be placed", 10 in result)
    }

    // -------------------------------------------------------------------
    // recFrameIsHonest tests
    // -------------------------------------------------------------------

    fun testRecFrameIsHonest_allMembersInsideIsHonest() {
        // Frame covers exactly its two members; no non-member nodes present.
        val positions = mapOf(
            0 to TgNodeRect(10f, 10f, 80f, 36f),
            1 to TgNodeRect(110f, 10f, 80f, 36f),
        )
        val memberIds = setOf(0, 1)
        // Frame bbox that tightly wraps the members.
        val honest = recFrameIsHonest(0f, 0f, 200f, 56f, memberIds, positions)
        assertTrue("frame containing only members must be honest", honest)
    }

    fun testRecFrameIsHonest_nonMemberInsideIsDishonest() {
        // Non-member node 2 sits inside the frame bbox.
        val positions = mapOf(
            0 to TgNodeRect(10f, 10f, 80f, 36f),
            1 to TgNodeRect(110f, 10f, 80f, 36f),
            2 to TgNodeRect(50f, 15f, 60f, 30f), // non-member, inside frame
        )
        val memberIds = setOf(0, 1)
        val honest = recFrameIsHonest(0f, 0f, 200f, 56f, memberIds, positions)
        assertFalse("frame that engulfs a non-member node must be dishonest", honest)
    }
}

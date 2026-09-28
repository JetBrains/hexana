package org.jetbrains.hexana.ui

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.ui.panel.TypeGraphFilter
import org.jetbrains.hexana.ui.panel.assignTypeLayers
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
}

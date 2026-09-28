package org.jetbrains.hexana.wasm.analysis.typegraph

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [computeTypeNeighborhood] using the typegraph.wasm fixture.
 *
 * Type index layout (see TypeGraphIndexTest for full table):
 *   0=$Base  1=$Mid  2=$BaseArr  3=$Leaf  4=$Other  5=$Sig  6=$SubArr
 *   7=implicit func type for $fn_gc body  8=implicit func type for $fn_other body
 *   totalTypes=9
 *
 * Focusing on $Leaf (idx 3):
 *   supertype chain: 3->1->0
 *   directSubtypes[3]=[]  directSubtypes[1]=[]  directSubtypes[0]=[1]
 *   fieldRefs[3]=[2]  referencingTypes[3]=[]
 *
 *   Hierarchy-only (subtypeDepth=2):
 *     Full super chain: {0,1,3}. BFS subtypes from focus 3 (depth 2): none. Result: {0,1,3}.
 *
 *   FieldRefs-only (fieldHops=1):
 *     BFS from 3 (STRUCT): outgoing fieldRefs[3]=[2] -> add 2 at hop 1.
 *     incoming referencingTypes[3]=[] -> nothing.
 *     At hop=1, node 2 (ARRAY): fieldRefs[2]=[] (no ref elem), referencingTypes[2]=[1,3,4] -> would be hop=2.
 *     Result (hops=1): {2,3}.
 *
 *   FieldRefs-only (fieldHops=2):
 *     hop=1: add 2. hop=2 from node 2: referencingTypes[2]=[1,3,4] -> add 1,4 (3 already in visited).
 *     From node 1 (STRUCT) at hop=2: fieldRefs[1]=[2](already), referencingTypes[1]=[].
 *     From node 4 (STRUCT) at hop=2: fieldRefs[4]=[0,2](already), referencingTypes[4]=[].
 *     Result (hops=2): {1,2,3,4}.
 *
 *   Both filters (subtypeDepth=2, fieldHops=2):
 *     hierarchy: {0,1,3}. fieldRefs: {1,2,3,4}. Union: {0,1,2,3,4}.
 *
 *   $Sig (idx 5) is FUNC: outgoing edges (fieldRefs) must never be traversed.
 *     When $Sig is a neighbor, its field ref to $Base (idx 0) must NOT pull in more nodes via outgoing.
 */
class TypeGraphNeighborhoodTest {

    private suspend fun buildIndex(): TypeGraphIndex {
        val buffer = resourceAsByteBuffer("snippets/typegraph.wasm")
        val result = WasmParser().parse(buffer)
        val module = (result as WasmParseSuccess).wasmModule as WasmModule
        return TypeGraphIndexBuilder(buffer, module).build()
    }

    @Test
    fun `hierarchy-only subtypeDepth=2 from $Leaf`() = runTest {
        val index = buildIndex()
        val n = computeTypeNeighborhood(index, focus = 3, includeHierarchy = true, includeFieldRefs = false, subtypeDepth = 2)
        // super chain: {0,1,3}. No subtypes of 3. directSubtypes[1]=[], directSubtypes[0]=[1] already in.
        assertContentEquals(intArrayOf(0, 1, 3), n.included)
    }

    @Test
    fun `hierarchy-only subtypeDepth=1 from $Leaf`() = runTest {
        val index = buildIndex()
        val n = computeTypeNeighborhood(index, focus = 3, includeHierarchy = true, includeFieldRefs = false, subtypeDepth = 1)
        // super chain: {0,1,3}. BFS depth=1: directSubtypes[3]=[], directSubtypes[1]=[], directSubtypes[0]=[1] already in.
        assertContentEquals(intArrayOf(0, 1, 3), n.included)
    }

    @Test
    fun `fieldRefs-only fieldHops=1 from $Leaf`() = runTest {
        val index = buildIndex()
        val n = computeTypeNeighborhood(index, focus = 3, includeHierarchy = false, includeFieldRefs = true, fieldHops = 1)
        // hop=1 outgoing: fieldRefs[3]=[2]. incoming: referencingTypes[3]=[]. Result: {2,3}.
        assertContentEquals(intArrayOf(2, 3), n.included)
    }

    @Test
    fun `fieldRefs-only fieldHops=2 from $Leaf`() = runTest {
        val index = buildIndex()
        val n = computeTypeNeighborhood(index, focus = 3, includeHierarchy = false, includeFieldRefs = true, fieldHops = 2)
        // hop=1: add 2. hop=2 from 2: referencingTypes[2]=[1,3,4] -> add 1,4. Result: {1,2,3,4}.
        assertContentEquals(intArrayOf(1, 2, 3, 4), n.included)
    }

    @Test
    fun `both filters subtypeDepth=2 fieldHops=2 from $Leaf`() = runTest {
        val index = buildIndex()
        val n = computeTypeNeighborhood(index, focus = 3, includeHierarchy = true, includeFieldRefs = true, subtypeDepth = 2, fieldHops = 2)
        // hierarchy: {0,1,3}. fieldRefs: {1,2,3,4}. Union: {0,1,2,3,4}.
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4), n.included)
    }

    @Test
    fun `FUNC node edges are never traversed as outgoing`() = runTest {
        val index = buildIndex()
        // Focus on $Other (idx 4) which has fieldRefs=[0,2].
        // When we reach $Sig (idx 5, FUNC) via referencingTypes[0]=[4,5], its
        // outgoing fieldRefs[5]=[0] must NOT pull in new nodes via outgoing edges.
        // With fieldHops=2 from focus 4:
        //   hop=0: {4}
        //   hop=1 from 4 (STRUCT): outgoing fieldRefs[4]=[0,2] -> add 0,2; incoming referencingTypes[4]=[] -> nothing.
        //   hop=2 from 0 (STRUCT): outgoing fieldRefs[0]=[] -> nothing; incoming referencingTypes[0]=[4,5] -> add 5.
        //   hop=2 from 2 (ARRAY): outgoing fieldRefs[2]=[] -> nothing; incoming referencingTypes[2]=[1,3,4] -> add 1,3.
        //   $Sig (idx 5) is reached at hop=2, but since it's FUNC and at maxHops, its edges aren't traversed anyway.
        //   Result: {0,1,2,3,4,5}
        val n = computeTypeNeighborhood(index, focus = 4, includeHierarchy = false, includeFieldRefs = true, fieldHops = 2)
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5), n.included)
        // Verify $Sig is in the result but its fieldRefs target ($Base=0) was not traversed as outgoing from $Sig
        assertTrue(5 in n.included.toSet(), "\$Sig must be included when reachable via referencingTypes")
    }

    @Test
    fun `focus is always included regardless of filters`() = runTest {
        val index = buildIndex()
        val n1 = computeTypeNeighborhood(index, focus = 6, includeHierarchy = false, includeFieldRefs = false)
        assertContentEquals(intArrayOf(6), n1.included)
        val n2 = computeTypeNeighborhood(index, focus = 5, includeHierarchy = false, includeFieldRefs = false)
        assertContentEquals(intArrayOf(5), n2.included)
    }

    @Test
    fun `totalTypes is propagated correctly`() = runTest {
        val index = buildIndex()
        val n = computeTypeNeighborhood(index, focus = 3, includeHierarchy = true, includeFieldRefs = false)
        // 7 declared + 2 implicit func types injected by wasm-tools
        assertEquals(9, n.totalTypes)
    }
}

package org.jetbrains.hexana.wasm.analysis.typegraph

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [TypeGraphIndex] built from the typegraph.wasm fixture.
 *
 * Type index layout (see snippets/typegraph.wat):
 *   rec group #0 (0..2):
 *     0 = $Base    STRUCT  sub-open  { i32 }
 *     1 = $Mid     STRUCT  sub $Base { i32, (ref null $BaseArr) }  -> fieldRefs=[2]
 *     2 = $BaseArr ARRAY   sub-open  (mut i32)
 *   rec group #1 (3..4):
 *     3 = $Leaf    STRUCT  sub final $Mid  { i32, (ref null $BaseArr), i32 }
 *     4 = $Other   STRUCT  { (ref null $BaseArr), (ref null $Base) }  -> fieldRefs=[0,2]
 *   non-rec (implicit func types injected by wasm-tools for function bodies):
 *     5 = $Sig     FUNC    (param (ref null $Base)) (result i32)  -> fieldRefs=[0]
 *     6 = $SubArr  ARRAY   sub $BaseArr (mut i32)
 *     7 = implicit FUNC    (param (ref null $Sig)) (result i32)   -> fieldRefs=[5]
 *     8 = implicit FUNC    () (result i32)
 *
 *   Total: 9 types, 2 rec groups.
 *
 *   directSubtypes:
 *     0 -> [1]    1 -> [3]    2 -> [6]    others: []
 *   $Leaf has field (ref null $BaseArr) at position 1, so fieldRefs[3]=[2]
 *   referencingTypes:
 *     0 -> [4,5]  2 -> [1,3,4]  5 -> [7]  others: []
 *   referencedByFuncs (funcIdx 0 = $fn_gc, 1 = $fn_other):
 *     1 -> [0]    2 -> [0]    5 -> [0]    others: []
 */
class TypeGraphIndexTest {

    private suspend fun buildIndex(): Pair<TypeGraphIndex, org.jetbrains.hexana.io.CommonByteBuffer> {
        val buffer = resourceAsByteBuffer("snippets/typegraph.wasm")
        val result = WasmParser().parse(buffer)
        val module = (result as WasmParseSuccess).wasmModule as WasmModule
        val index = TypeGraphIndexBuilder(buffer, module).build()
        return index to buffer
    }

    @Test
    fun `type count and hasGcTypes`() = runTest {
        val (index, _) = buildIndex()
        // 7 explicitly declared + 2 implicit func types injected by wasm-tools for $fn_gc and $fn_other
        assertEquals(9, index.typeCount)
        assertTrue(index.hasGcTypes)
    }

    @Test
    fun `node kinds are correct`() = runTest {
        val (index, _) = buildIndex()
        assertEquals(TypeGraphNodeKind.STRUCT, index.kind(0)) // $Base
        assertEquals(TypeGraphNodeKind.STRUCT, index.kind(1)) // $Mid
        assertEquals(TypeGraphNodeKind.ARRAY, index.kind(2))  // $BaseArr
        assertEquals(TypeGraphNodeKind.STRUCT, index.kind(3)) // $Leaf
        assertEquals(TypeGraphNodeKind.STRUCT, index.kind(4)) // $Other
        assertEquals(TypeGraphNodeKind.FUNC, index.kind(5))   // $Sig
        assertEquals(TypeGraphNodeKind.ARRAY, index.kind(6))  // $SubArr
        assertEquals(TypeGraphNodeKind.FUNC, index.kind(7))   // implicit func type for $fn_gc body
        assertEquals(TypeGraphNodeKind.FUNC, index.kind(8))   // implicit func type for $fn_other body
    }

    @Test
    fun `rec groups have correct ranges`() = runTest {
        val (index, _) = buildIndex()
        assertEquals(2, index.recGroups.size)
        assertEquals(0..2, index.recGroups[0])
        assertEquals(3..4, index.recGroups[1])
    }

    @Test
    fun `recGroup per type index`() = runTest {
        val (index, _) = buildIndex()
        assertEquals(0, index.recGroup(0))  // $Base in group 0
        assertEquals(0, index.recGroup(1))  // $Mid in group 0
        assertEquals(0, index.recGroup(2))  // $BaseArr in group 0
        assertEquals(1, index.recGroup(3))  // $Leaf in group 1
        assertEquals(1, index.recGroup(4))  // $Other in group 1
        assertEquals(-1, index.recGroup(5)) // $Sig non-rec
        assertEquals(-1, index.recGroup(6)) // $SubArr non-rec
        assertEquals(-1, index.recGroup(7)) // implicit func type non-rec
        assertEquals(-1, index.recGroup(8)) // implicit func type non-rec
    }

    @Test
    fun `superTypes for 3-level chain`() = runTest {
        val (index, _) = buildIndex()
        assertContentEquals(intArrayOf(), index.superTypes(0))  // $Base has no super
        assertContentEquals(intArrayOf(0), index.superTypes(1)) // $Mid <: $Base
        assertContentEquals(intArrayOf(), index.superTypes(2))  // $BaseArr no super
        assertContentEquals(intArrayOf(1), index.superTypes(3)) // $Leaf <: $Mid (cross-group)
        assertContentEquals(intArrayOf(), index.superTypes(4))  // $Other no super
        assertContentEquals(intArrayOf(), index.superTypes(5))  // $Sig no super
        assertContentEquals(intArrayOf(2), index.superTypes(6)) // $SubArr <: $BaseArr (array subtype, Task-1)
        assertContentEquals(intArrayOf(), index.superTypes(7))  // implicit func types have no super
        assertContentEquals(intArrayOf(), index.superTypes(8))
    }

    @Test
    fun `directSubtypes inverts superTypes`() = runTest {
        val (index, _) = buildIndex()
        assertContentEquals(intArrayOf(1), index.directSubtypes(0))  // $Base -> [$Mid]
        assertContentEquals(intArrayOf(3), index.directSubtypes(1))  // $Mid -> [$Leaf]
        assertContentEquals(intArrayOf(6), index.directSubtypes(2))  // $BaseArr -> [$SubArr]
        assertContentEquals(intArrayOf(), index.directSubtypes(3))
        assertContentEquals(intArrayOf(), index.directSubtypes(4))
        assertContentEquals(intArrayOf(), index.directSubtypes(5))
        assertContentEquals(intArrayOf(), index.directSubtypes(6))
        assertContentEquals(intArrayOf(), index.directSubtypes(7))
        assertContentEquals(intArrayOf(), index.directSubtypes(8))
    }

    @Test
    fun `fieldRefs for struct and func types`() = runTest {
        val (index, _) = buildIndex()
        assertContentEquals(intArrayOf(), index.fieldRefs(0))   // $Base: no ref fields
        assertContentEquals(intArrayOf(2), index.fieldRefs(1))  // $Mid: (ref null $BaseArr)
        assertContentEquals(intArrayOf(), index.fieldRefs(2))   // $BaseArr: element type is i32
        assertContentEquals(intArrayOf(2), index.fieldRefs(3))   // $Leaf: has (ref null $BaseArr) at field 1
        assertContentEquals(intArrayOf(0, 2), index.fieldRefs(4)) // $Other: (ref null $BaseArr),(ref null $Base)
        assertContentEquals(intArrayOf(0), index.fieldRefs(5))  // $Sig: param (ref null $Base)
        assertContentEquals(intArrayOf(), index.fieldRefs(6))   // $SubArr: element type is i32
        assertContentEquals(intArrayOf(5), index.fieldRefs(7))  // implicit func: param (ref null $Sig)
        assertContentEquals(intArrayOf(), index.fieldRefs(8))   // implicit func: no ref params
    }

    @Test
    fun `referencingTypes is inverse of fieldRefs`() = runTest {
        val (index, _) = buildIndex()
        assertContentEquals(intArrayOf(4, 5), index.referencingTypes(0)) // $Base referenced by $Other(field1) and $Sig(param)
        assertContentEquals(intArrayOf(), index.referencingTypes(1))
        // $BaseArr referenced by $Mid(field1), $Leaf(field1), and $Other(field0)
        assertContentEquals(intArrayOf(1, 3, 4), index.referencingTypes(2))
        assertContentEquals(intArrayOf(), index.referencingTypes(3))
        assertContentEquals(intArrayOf(), index.referencingTypes(4))
        // $Sig referenced by implicit type 7 (the function-body type for $fn_gc which takes (ref null $Sig))
        assertContentEquals(intArrayOf(7), index.referencingTypes(5))
        assertContentEquals(intArrayOf(), index.referencingTypes(6))
        assertContentEquals(intArrayOf(), index.referencingTypes(7))
        assertContentEquals(intArrayOf(), index.referencingTypes(8))
    }

    @Test
    fun `referencingFuncs from GC opcode code pass`() = runTest {
        val (index, _) = buildIndex()
        // funcIdx 0 ($fn_gc): ref.test (ref $Mid)=1, ref.cast (ref null $Mid)=1,
        //   struct.get $Mid 0=1, array.new_default $BaseArr=2, call_ref $Sig=5
        assertContentEquals(intArrayOf(), index.referencingFuncs(0))
        assertContentEquals(intArrayOf(0), index.referencingFuncs(1))  // $Mid from $fn_gc
        assertContentEquals(intArrayOf(0), index.referencingFuncs(2))  // $BaseArr from $fn_gc
        assertContentEquals(intArrayOf(), index.referencingFuncs(3))
        assertContentEquals(intArrayOf(), index.referencingFuncs(4))
        assertContentEquals(intArrayOf(0), index.referencingFuncs(5))  // $Sig from call_ref
        assertContentEquals(intArrayOf(), index.referencingFuncs(6))
        assertContentEquals(intArrayOf(), index.referencingFuncs(7))   // implicit func types not ref'd via GC opcodes
        assertContentEquals(intArrayOf(), index.referencingFuncs(8))
    }

    @Test
    fun `array supertype is non-empty (Task-1 coverage)`() = runTest {
        val (index, _) = buildIndex()
        // $SubArr (idx 6) is an array subtype of $BaseArr (idx 2) — requires Task-1 fix
        // to thread superTypes into WasmArrayTypeRecord
        assertTrue(index.superTypes(6).isNotEmpty(), "array supertype must be non-empty")
        assertContentEquals(intArrayOf(2), index.superTypes(6))
    }

    @Test
    fun `GC-less module has hasGcTypes == false and empty arrays`() = runTest {
        // dispatch-candidates.wasm is an MVP module with no GC types
        val buffer = resourceAsByteBuffer("snippets/dispatch-candidates.wasm")
        val result = WasmParser().parse(buffer)
        val module = (result as WasmParseSuccess).wasmModule as WasmModule
        val index = TypeGraphIndexBuilder(buffer, module).build()

        assertFalse(index.hasGcTypes, "dispatch-candidates.wasm must have hasGcTypes=false")
        // All per-type arrays must be empty (no struct/array records, no supertype edges)
        for (i in 0 until index.typeCount) {
            assertContentEquals(intArrayOf(), index.superTypes(i), "superTypes[$i] must be empty")
            assertContentEquals(intArrayOf(), index.directSubtypes(i), "directSubtypes[$i] must be empty")
            assertContentEquals(intArrayOf(), index.fieldRefs(i), "fieldRefs[$i] must be empty")
        }
    }
}

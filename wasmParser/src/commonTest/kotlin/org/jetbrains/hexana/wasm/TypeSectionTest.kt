package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.parseWat
import org.jetbrains.hexana.tests.parseWatResource
import org.jetbrains.hexana.tests.watResourceToBuffer
import org.jetbrains.hexana.wasm.sections.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for WebAssembly 3.0 type section parsing.
 *
 * Covers: numeric types, vector types, reference types (funcref, externref,
 * typed refs, abstract heap types), function types (multi-param, multi-value),
 * struct types (fields, mutability, packed i8/i16, subtyping),
 * array types (value, packed, ref-element), recursive type groups.
 */
class TypeSectionTest {

    private suspend fun parseTypes(resourcePath: String): List<WasmTypeRecord> {
        val buffer = watResourceToBuffer(resourcePath)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val typesSection = module.typesSection!!
        return with(typesSection) { buffer.parseTypes() }
    }

    // --- Function types ---

    @Test
    fun `func type — void to void`() = runTest {
        val types = parseTypes("wat/types/func_types.wat")
        val t = types[0]
        assertIs<WasmSectionFunctionTypeRecord>(t)
        assertEquals(0, t.params.size)
        assertEquals(0, t.retTypes.size)
    }

    @Test
    fun `func type — i32 identity`() = runTest {
        val types = parseTypes("wat/types/func_types.wat")
        val t = types[1]
        assertIs<WasmSectionFunctionTypeRecord>(t)
        assertEquals(1, t.params.size)
        assertIs<WasmI32Type>(t.params[0])
        assertEquals(1, t.retTypes.size)
        assertIs<WasmI32Type>(t.retTypes[0])
    }

    @Test
    fun `func type — multi param`() = runTest {
        val types = parseTypes("wat/types/func_types.wat")
        val t = types[2]
        assertIs<WasmSectionFunctionTypeRecord>(t)
        assertEquals(2, t.params.size)
        assertIs<WasmI32Type>(t.params[0])
        assertIs<WasmI64Type>(t.params[1])
        assertEquals(1, t.retTypes.size)
        assertIs<WasmF32Type>(t.retTypes[0])
    }

    @Test
    fun `func type — multi-value return`() = runTest {
        val types = parseTypes("wat/types/func_types.wat")
        val t = types[3]
        assertIs<WasmSectionFunctionTypeRecord>(t)
        assertEquals(0, t.params.size)
        assertEquals(2, t.retTypes.size)
        assertIs<WasmI32Type>(t.retTypes[0])
        assertIs<WasmI64Type>(t.retTypes[1])
    }

    @Test
    fun `func type — complex params and multi-value return`() = runTest {
        val types = parseTypes("wat/types/func_types.wat")
        val t = types[4]
        assertIs<WasmSectionFunctionTypeRecord>(t)
        assertEquals(4, t.params.size)
        assertIs<WasmF32Type>(t.params[0])
        assertIs<WasmF64Type>(t.params[1])
        assertIs<WasmI32Type>(t.params[2])
        assertIs<WasmI64Type>(t.params[3])
        assertEquals(2, t.retTypes.size)
        assertIs<WasmF32Type>(t.retTypes[0])
        assertIs<WasmF64Type>(t.retTypes[1])
    }

    // --- Numeric types ---

    @Test
    fun `all four numeric types`() = runTest {
        val types = parseTypes("wat/types/num_types.wat")
        // 4 declared types + 1 implicit void->void for (func (export "f"))
        assertTrue(types.size >= 4)

        assertIs<WasmI32Type>((types[0] as WasmSectionFunctionTypeRecord).params[0])
        assertIs<WasmI64Type>((types[1] as WasmSectionFunctionTypeRecord).params[0])
        assertIs<WasmF32Type>((types[2] as WasmSectionFunctionTypeRecord).params[0])
        assertIs<WasmF64Type>((types[3] as WasmSectionFunctionTypeRecord).params[0])
    }

    // --- Vector type ---

    @Test
    fun `v128 vector type`() = runTest {
        val types = parseTypes("wat/types/vec_type.wat")
        val t = types[0]
        assertIs<WasmSectionFunctionTypeRecord>(t)
        assertIs<WasmVecType>(t.params[0])
        assertIs<WasmVecType>(t.retTypes[0])
    }

    // --- Reference types ---

    @Test
    fun `funcref and externref`() = runTest {
        val types = parseTypes("wat/types/ref_types.wat")
        assertTrue(types.size >= 2)
        val funcrefType = (types[0] as WasmSectionFunctionTypeRecord).params[0]
        assertIs<WasmRefType>(funcrefType)
        assertEquals(WasmFuncHeapType, funcrefType.code.heapType)

        val externrefType = (types[1] as WasmSectionFunctionTypeRecord).params[0]
        assertIs<WasmRefType>(externrefType)
        assertEquals(WasmExternRefHeapType, externrefType.code.heapType)
    }

    @Test
    fun `typed refs — nullable and non-nullable`() = runTest {
        val types = parseTypes("wat/types/ref_typed.wat")
        // type 0 = struct $s
        // type 1 = func (param (ref null $s)) (result (ref $s))
        val funcType = types[1]
        assertIs<WasmSectionFunctionTypeRecord>(funcType)

        val paramRef = funcType.params[0]
        assertIs<WasmRefType>(paramRef)
        assertIs<WasmNullableReferenceType>(paramRef.code)
        assertIs<WasmIndexHeapType>(paramRef.code.heapType)

        val retRef = funcType.retTypes[0]
        assertIs<WasmRefType>(retRef)
        assertIs<WasmNonNullableReferenceType>(retRef.code)
        assertIs<WasmIndexHeapType>(retRef.code.heapType)
    }

    @Test
    fun `abstract heap types — anyref eqref i31ref structref arrayref`() = runTest {
        val types = parseTypes("wat/types/ref_typed.wat")
        // types 2..9 are funcs with abstract heap type params
        val heapTypes = (2..9).map { idx ->
            val ft = types[idx] as WasmSectionFunctionTypeRecord
            (ft.params[0] as WasmRefType).code.heapType
        }
        assertIs<WasmAnyRefHeapType>(heapTypes[0])
        assertIs<WasmEqRefHeapType>(heapTypes[1])
        assertIs<WasmI31RefHeapType>(heapTypes[2])
        assertIs<WasmStructRefHeapType>(heapTypes[3])
        assertIs<WasmArrayRefHeapType>(heapTypes[4])
        assertIs<WasmNoneHeapType>(heapTypes[5])
        assertIs<WasmNoExternRefHeapType>(heapTypes[6])
        assertIs<WasmNoFuncHeapType>(heapTypes[7])
    }

    // --- Struct types ---

    @Test
    fun `struct — simple immutable fields`() = runTest {
        val types = parseTypes("wat/types/struct_simple.wat")
        val t = types[0]
        assertIs<WasmStructTypeRecord>(t)
        assertEquals(2, t.fields.size)
        assertIs<WasmI32Type>(t.fields[0].storageType)
        assertIs<WasmI32Type>(t.fields[1].storageType)
        assertTrue(!t.fields[0].isMutable)
        assertTrue(!t.fields[1].isMutable)
    }

    @Test
    fun `struct — mutable fields`() = runTest {
        val types = parseTypes("wat/types/struct_simple.wat")
        val t = types[1]
        assertIs<WasmStructTypeRecord>(t)
        assertEquals(2, t.fields.size)
        assertTrue(t.fields[0].isMutable)
        assertTrue(t.fields[1].isMutable)
    }

    @Test
    fun `struct — mixed mutability`() = runTest {
        val types = parseTypes("wat/types/struct_simple.wat")
        val t = types[2]
        assertIs<WasmStructTypeRecord>(t)
        assertEquals(2, t.fields.size)
        assertIs<WasmI32Type>(t.fields[0].storageType)
        assertTrue(!t.fields[0].isMutable)
        assertIs<WasmF64Type>(t.fields[1].storageType)
        assertTrue(t.fields[1].isMutable)
    }

    @Test
    fun `struct — packed i8 and i16 fields`() = runTest {
        val types = parseTypes("wat/types/struct_packed.wat")
        val t = types[0]
        assertIs<WasmStructTypeRecord>(t)
        assertEquals(3, t.fields.size)
        assertIs<WasmPactTypeI8>(t.fields[0].storageType)
        assertIs<WasmPactTypeI16>(t.fields[1].storageType)
        assertIs<WasmPactTypeI8>(t.fields[2].storageType)
        assertTrue(!t.fields[0].isMutable)
        assertTrue(!t.fields[1].isMutable)
        assertTrue(t.fields[2].isMutable)
    }

    @Test
    fun `struct — subtyping chain`() = runTest {
        val types = parseTypes("wat/types/struct_subtyping.wat")
        // $parent: sub (struct (field i32)) — open for subtyping
        val parent = types[0]
        assertIs<WasmStructTypeRecord>(parent)
        assertEquals(1, parent.fields.size)
        assertTrue(parent.superTypes.isEmpty())

        // $child: sub $parent (struct (field i32) (field f64))
        val child = types[1]
        assertIs<WasmStructTypeRecord>(child)
        assertEquals(2, child.fields.size)
        assertEquals(listOf(0u), child.superTypes)

        // $final_child: sub final $child (...)
        val finalChild = types[2]
        assertIs<WasmStructTypeRecord>(finalChild)
        assertEquals(3, finalChild.fields.size)
        assertEquals(listOf(1u), finalChild.superTypes)
        assertTrue(finalChild.isFinal)
    }

    @Test
    fun `struct — reference-typed fields`() = runTest {
        val types = parseTypes("wat/types/struct_ref_fields.wat")
        // $inner = struct (field i32)
        // $outer = struct with ref fields
        val outer = types[1]
        assertIs<WasmStructTypeRecord>(outer)
        assertEquals(4, outer.fields.size)

        // field 0: (ref null $inner) — nullable typed ref
        assertIs<WasmRefType>(outer.fields[0].storageType)
        assertTrue(!outer.fields[0].isMutable)

        // field 1: (mut (ref null $inner)) — mutable nullable typed ref
        assertIs<WasmRefType>(outer.fields[1].storageType)
        assertTrue(outer.fields[1].isMutable)

        // field 2: funcref
        val funcrefField = outer.fields[2].storageType
        assertIs<WasmRefType>(funcrefField)
        assertEquals(WasmFuncHeapType, funcrefField.code.heapType)

        // field 3: externref
        val externrefField = outer.fields[3].storageType
        assertIs<WasmRefType>(externrefField)
        assertEquals(WasmExternRefHeapType, externrefField.code.heapType)
    }

    // --- Array types ---

    @Test
    fun `array — immutable i32`() = runTest {
        val types = parseTypes("wat/types/array_types.wat")
        val t = types[0]
        assertIs<WasmArrayTypeRecord>(t)
        assertIs<WasmI32Type>(t.fieldType.storageType)
        assertTrue(!t.fieldType.isMutable)
    }

    @Test
    fun `array — mutable f64`() = runTest {
        val types = parseTypes("wat/types/array_types.wat")
        val t = types[1]
        assertIs<WasmArrayTypeRecord>(t)
        assertIs<WasmF64Type>(t.fieldType.storageType)
        assertTrue(t.fieldType.isMutable)
    }

    @Test
    fun `array — packed i8 (byte array)`() = runTest {
        val types = parseTypes("wat/types/array_types.wat")
        val t = types[2]
        assertIs<WasmArrayTypeRecord>(t)
        assertIs<WasmPactTypeI8>(t.fieldType.storageType)
        assertTrue(t.fieldType.isMutable)
    }

    @Test
    fun `array — packed i16`() = runTest {
        val types = parseTypes("wat/types/array_types.wat")
        val t = types[3]
        assertIs<WasmArrayTypeRecord>(t)
        assertIs<WasmPactTypeI16>(t.fieldType.storageType)
        assertTrue(!t.fieldType.isMutable)
    }

    @Test
    fun `array — element type is struct ref`() = runTest {
        val types = parseTypes("wat/types/array_of_structs.wat")
        // type 0 = $point struct
        // type 1 = $points array of (mut (ref null $point))
        val arr = types[1]
        assertIs<WasmArrayTypeRecord>(arr)
        assertTrue(arr.fieldType.isMutable)
        val elemType = arr.fieldType.storageType
        assertIs<WasmRefType>(elemType)
        assertIs<WasmIndexHeapType>(elemType.code.heapType)
        assertEquals(0L, elemType.code.heapType.type) // references type 0 ($point)
    }

    // --- Recursive type groups ---

    @Test
    fun `rec group — mutually referential types`() = runTest {
        val types = parseTypes("wat/types/rec_group.wat")
        // rec group + implicit void->void type for the dummy export func
        assertTrue(types.size >= 1)
        val rec = types[0]
        assertIs<WasmRecursiveTypeRecord>(rec)
        assertEquals(2, rec.entries.size)

        // $node = struct (field i32) (field (ref null $list))
        val node = rec.entries[0]
        assertIs<WasmStructTypeRecord>(node)
        assertEquals(2, node.fields.size)
        assertIs<WasmI32Type>(node.fields[0].storageType)
        val nodeRefField = node.fields[1].storageType
        assertIs<WasmRefType>(nodeRefField)

        // $list = struct (field (ref null $node)) (field (ref null $list))
        val list = rec.entries[1]
        assertIs<WasmStructTypeRecord>(list)
        assertEquals(2, list.fields.size)
    }

    // --- Inline WAT tests ---

    @Test
    fun `inline — empty module has no types`() = runTest {
        val module = parseWat("(module)")
        assertTrue(module.typesSection == null || run {
            val buffer = org.jetbrains.hexana.tests.watStringToBuffer("(module)")
            with(module.typesSection!!) { buffer.parseTypes() }.isEmpty()
        })
    }

    @Test
    fun `inline — single func type`() = runTest {
        val module = parseWat("""
            (module
              (type (func (param i32 i32) (result i32)))
              (func (export "f") (nop))
            )
        """.trimIndent())
        val buffer = org.jetbrains.hexana.tests.watStringToBuffer("""
            (module
              (type (func (param i32 i32) (result i32)))
              (func (export "f") (nop))
            )
        """.trimIndent())
        val types = with(module.typesSection!!) { buffer.parseTypes() }
        assertEquals(2, types.size) // declared type + implicit func type for (func (export "f"))
        val t = types[0]
        assertIs<WasmSectionFunctionTypeRecord>(t)
        assertEquals(2, t.params.size)
        assertEquals(1, t.retTypes.size)
    }
}
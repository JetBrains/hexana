package org.jetbrains.hexana.ui.components

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.*
import org.jetbrains.hexana.shared.componentTypeName
import org.jetbrains.hexana.shared.extractNameFromWitExport
import org.jetbrains.hexana.shared.formatComponentSignature
import org.jetbrains.hexana.shared.isTrivialComponentType
import org.jetbrains.hexana.shared.looksLikeWitInterfaceExport
import org.jetbrains.hexana.wasm.components.*

class WitExportNameTest : UsefulTestCase() {

    fun testExtractNameFromWitExport() {
        assertEquals("subtract", extractNameFromWitExport("component:subtractor/subtract@0.1.0"))
        assertEquals("calculate", extractNameFromWitExport("component:calculator/calculate@0.1.0"))
        assertEquals("greet", extractNameFromWitExport("wasi:http/greet@1.0.0"))
        assertEquals("my-func", extractNameFromWitExport("ns:pkg/my-func@2.0.0"))
    }

    fun testExtractNameWithoutVersion() {
        assertEquals("subtract", extractNameFromWitExport("component:subtractor/subtract"))
    }

    fun testExtractNameReturnsNullForPlainName() {
        assertNull(extractNameFromWitExport("subtract"))
        assertNull(extractNameFromWitExport("my-function"))
        assertNull(extractNameFromWitExport(""))
    }

    fun testLooksLikeWitInterfaceExport() {
        assertTrue(looksLikeWitInterfaceExport("component:subtractor/subtract@0.1.0"))
        assertTrue(looksLikeWitInterfaceExport("component:calculator/calculate@0.1.0"))
        assertTrue(looksLikeWitInterfaceExport("wasi:http/greet@1.0.0"))
        assertTrue(looksLikeWitInterfaceExport("ns:pkg/my-func"))
    }

    fun testLooksLikeWitInterfaceExportReturnsFalseForPlainName() {
        assertFalse(looksLikeWitInterfaceExport("subtract"))
        assertFalse(looksLikeWitInterfaceExport("my-function"))
        assertFalse(looksLikeWitInterfaceExport(""))
    }

    fun testComponentTypeName() {
        assertEquals("u32", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveU32Type)))
        assertEquals("s32", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveS32Type)))
        assertEquals("u64", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveU64Type)))
        assertEquals("s64", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveS64Type)))
        assertEquals("f32", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveF32Type)))
        assertEquals("f64", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveF64Type)))
        assertEquals("bool", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveBoolType)))
        assertEquals("char", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveCharType)))
        assertEquals("string", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveString)))
        assertEquals("u8", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveU8Type)))
        assertEquals("s8", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveS8Type)))
        assertEquals("u16", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveU16Type)))
        assertEquals("s16", componentTypeName(ComponentPrimitiveValueType(ComponentPrimitiveS16Type)))
        assertEquals("type(5)", componentTypeName(ComponentValueIdxType(5u)))
    }

    fun testComponentTypeNameWithEnumIdx() {
        val types = listOf<ComponentDefType>(
            ComponentEnumType(listOf("red", "green", "blue")),
        )
        assertEquals("enum{red, green, blue}", componentTypeName(ComponentValueIdxType(0u), types))
        assertEquals("type(1)", componentTypeName(ComponentValueIdxType(1u), types))
    }

    fun testIsTrivialComponentType() {
        assertTrue(isTrivialComponentType(ComponentPrimitiveValueType(ComponentPrimitiveU32Type)))
        assertTrue(isTrivialComponentType(ComponentPrimitiveValueType(ComponentPrimitiveS32Type)))
        assertTrue(isTrivialComponentType(ComponentPrimitiveValueType(ComponentPrimitiveF64Type)))
        assertTrue(isTrivialComponentType(ComponentPrimitiveValueType(ComponentPrimitiveBoolType)))
        assertTrue(isTrivialComponentType(ComponentPrimitiveValueType(ComponentPrimitiveString)))
        assertFalse(isTrivialComponentType(ComponentValueIdxType(0u)))
        assertFalse(isTrivialComponentType(ComponentPrimitiveValueType(ComponentPrimitiveErrorContext)))
    }

    fun testIsTrivialComponentTypeWithEnumIdx() {
        val types = listOf<ComponentDefType>(
            ComponentEnumType(listOf("add", "subtract")),
        )
        assertTrue(isTrivialComponentType(ComponentValueIdxType(0u), types))
        assertFalse(isTrivialComponentType(ComponentValueIdxType(1u), types))
        // Non-enum type idx is not trivial
        val typesWithRecord = listOf<ComponentDefType>(
            ComponentRecordType(listOf(LabelValueType("x", ComponentPrimitiveValueType(ComponentPrimitiveU32Type)))),
        )
        assertFalse(isTrivialComponentType(ComponentValueIdxType(0u), typesWithRecord))
    }

    fun testFormatComponentSignature() {
        val u32 = ComponentPrimitiveValueType(ComponentPrimitiveU32Type)
        val funcType = ComponentFunctionType(
            listOf(LabelValueType("x", u32), LabelValueType("y", u32)),
            u32,
        )
        assertEquals("(x: u32, y: u32) -> u32", formatComponentSignature(funcType))
    }

    fun testFormatComponentSignatureVoidReturn() {
        val s64 = ComponentPrimitiveValueType(ComponentPrimitiveS64Type)
        val funcType = ComponentFunctionType(
            listOf(LabelValueType("val", s64)),
            ComponentVoidType,
        )
        assertEquals("(val: s64) -> void", formatComponentSignature(funcType))
    }

    fun testFormatComponentSignatureNoParams() {
        val funcType = ComponentFunctionType(
            emptyList(),
            ComponentPrimitiveValueType(ComponentPrimitiveBoolType),
        )
        assertEquals("() -> bool", formatComponentSignature(funcType))
    }
}

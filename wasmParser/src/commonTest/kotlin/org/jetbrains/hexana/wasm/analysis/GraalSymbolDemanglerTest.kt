package org.jetbrains.hexana.wasm.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cases are taken verbatim from a real GraalVM Web Image build
 * (graalvm-demos `web-image/javac/web/javac.js.wasm`, REDUCED naming mode).
 */
class GraalSymbolDemanglerTest {

    @Test
    fun `no-arg method returning a reference type`() {
        val sym = GraalSymbolDemangler.demangle("_Highlighter_finish___String")!!
        assertEquals("Highlighter", sym.className)
        assertEquals("Highlighter", sym.simpleClassName)
        assertEquals("finish", sym.methodName)
        assertEquals(emptyList(), sym.parameterTypes)
        assertEquals("String", sym.returnType)
    }

    @Test
    fun `primitive param and void return`() {
        val sym = GraalSymbolDemangler.demangle("_Highlighter_append___C_V")!!
        assertEquals("Highlighter", sym.className)
        assertEquals("append", sym.methodName)
        assertEquals(listOf("char"), sym.parameterTypes)
        assertEquals("void", sym.returnType)
    }

    @Test
    fun `boxed reference param and primitive return`() {
        val sym = GraalSymbolDemangler.demangle("_WasmGCJSConversion_unboxLong___Long_J")!!
        assertEquals("WasmGCJSConversion", sym.className)
        assertEquals("unboxLong", sym.methodName)
        assertEquals(listOf("Long"), sym.parameterTypes)
        assertEquals("long", sym.returnType)
    }

    @Test
    fun `primitive array return`() {
        val sym = GraalSymbolDemangler.demangle("_WasmGCJSConversion_stringFromCharArray___String_\$C")!!
        assertEquals("stringFromCharArray", sym.methodName)
        assertEquals(listOf("String"), sym.parameterTypes)
        assertEquals("char[]", sym.returnType)
    }

    @Test
    fun `primitive array param`() {
        val sym = GraalSymbolDemangler.demangle("_WasmGCConversion_stringFromCharArray___\$C_String")!!
        assertEquals("stringFromCharArray", sym.methodName)
        assertEquals(listOf("char[]"), sym.parameterTypes)
        assertEquals("String", sym.returnType)
    }

    @Test
    fun `nested class via dollar`() {
        val sym = GraalSymbolDemangler.demangle("_JavacCompilerWrapper\$Result_hashCode___I")!!
        assertEquals("JavacCompilerWrapper\$Result", sym.className)
        assertEquals("Result", sym.simpleClassName)
        assertEquals("hashCode", sym.methodName)
        assertEquals(emptyList(), sym.parameterTypes)
        assertEquals("int", sym.returnType)
    }

    @Test
    fun `qualified array param decodes package`() {
        val sym = GraalSymbolDemangler.demangle("_WebImageWasmGCJavaMainSupport_run__\$Ljava_lang_String__I")!!
        assertEquals("WebImageWasmGCJavaMainSupport", sym.className)
        assertEquals("run", sym.methodName)
        assertEquals(listOf("java.lang.String[]"), sym.parameterTypes)
        assertEquals("int", sym.returnType)
    }

    @Test
    fun `lambda is flagged synthetic`() {
        val sym = GraalSymbolDemangler.demangle(
            "_WebMain\$\$Lambda_0x90771d46050d7840aff2e3ffeabde2f00_run___V",
        )!!
        assertEquals("run", sym.methodName)
        assertTrue(sym.isSynthetic)
        assertEquals("void", sym.returnType)
    }

    @Test
    fun `display renders readable signature`() {
        val sym = GraalSymbolDemangler.demangle("_Highlighter_append___C_V")!!
        assertEquals("Highlighter.append(char) : void", sym.display())
    }

    @Test
    fun `internal func prefix is not a java method`() {
        assertNull(GraalSymbolDemangler.demangle("func.0"))
        assertNull(GraalSymbolDemangler.demangle("func.gc.something"))
    }

    @Test
    fun `non java shapes return null`() {
        assertNull(GraalSymbolDemangler.demangle("interop.genBacktrace"))
        assertNull(GraalSymbolDemangler.demangle("memory"))
        assertNull(GraalSymbolDemangler.demangle(""))
    }
}
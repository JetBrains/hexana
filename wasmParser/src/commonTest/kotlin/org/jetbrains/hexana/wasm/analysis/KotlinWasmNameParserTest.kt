package org.jetbrains.hexana.wasm.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Cases are drawn from real Kotlin/Wasm `name`-section function names (Kotlin 2.x `wasmJs` output and
 * the `androidx.compose.*` names in `23735.mpp-demo.wasm`). Kotlin/Wasm writes plain FQNs, so the
 * parser only *structures* them — it does not decode a type signature.
 */
class KotlinWasmNameParserTest {

    @Test
    fun `instance method on a class`() {
        val s = KotlinWasmNameParser.parse("com.example.Greeting.greet")!!
        assertEquals(KotlinWasmSymbolKind.METHOD, s.kind)
        assertEquals("com.example.Greeting", s.classFqn)
        assertEquals("Greeting", s.simpleClassName)
        assertEquals("greet", s.member)
        assertNull(s.packageFqn)
        assertEquals("com.example.Greeting.greet()", s.display())
    }

    @Test
    fun `top-level function on a file facade resolves as a class method`() {
        // Kotlin lowers top-level `main` to MainKt; the facade IS uppercase, so it parses as a class.
        val s = KotlinWasmNameParser.parse("com.example.MainKt.main")!!
        assertEquals(KotlinWasmSymbolKind.METHOD, s.kind)
        assertEquals("com.example.MainKt", s.classFqn)
        assertEquals("main", s.member)
    }

    @Test
    fun `constructor name`() {
        val s = KotlinWasmNameParser.parse("kotlin.Number.<init>")!!
        assertEquals(KotlinWasmSymbolKind.CONSTRUCTOR, s.kind)
        assertEquals("kotlin.Number", s.classFqn)
        assertEquals("<init>", s.member)
    }

    @Test
    fun `property getter`() {
        val s = KotlinWasmNameParser.parse("kotlin.JsFun.<get-code>")!!
        assertEquals(KotlinWasmSymbolKind.PROPERTY_GETTER, s.kind)
        assertEquals("kotlin.JsFun", s.classFqn)
        assertEquals("code", s.member)
        assertEquals("val kotlin.JsFun.code", s.display())
    }

    @Test
    fun `property setter with multi-segment package`() {
        val s = KotlinWasmNameParser.parse("kotlin.internal.SharedVariableBox.<set-element>")!!
        assertEquals(KotlinWasmSymbolKind.PROPERTY_SETTER, s.kind)
        assertEquals("kotlin.internal.SharedVariableBox", s.classFqn)
        assertEquals("element", s.member)
    }

    @Test
    fun `compose accessor name (env-module fixture shape)`() {
        val s = KotlinWasmNameParser.parse("androidx.compose.ui.node.ObserverNodeOwnerScope.<get-observerNode>")!!
        assertEquals(KotlinWasmSymbolKind.PROPERTY_GETTER, s.kind)
        assertEquals("androidx.compose.ui.node.ObserverNodeOwnerScope", s.classFqn)
        assertEquals("observerNode", s.member)
    }

    @Test
    fun `top-level function in a package (no facade segment)`() {
        val s = KotlinWasmNameParser.parse("kotlin.collections.joinToString\$default")!!
        assertEquals(KotlinWasmSymbolKind.TOP_LEVEL, s.kind)
        assertNull(s.classFqn)
        assertEquals("kotlin.collections", s.packageFqn)
        assertEquals("joinToString", s.member) // $default stub stripped
        assertEquals("kotlin.collections.joinToString()", s.display())
    }

    @Test
    fun `lambda constructor navigates to the enclosing top-level declaration`() {
        val s = KotlinWasmNameParser.parse("kotlin.collections.sortedByDescending\$lambda.<init>")!!
        assertEquals(KotlinWasmSymbolKind.LAMBDA, s.kind)
        assertEquals("kotlin.collections.sortedByDescending", s.classFqn)
        assertEquals("<init>", s.member)
    }

    @Test
    fun `lambda nested in a class strips synthetic suffixes to the outer class`() {
        val s = KotlinWasmNameParser.parse("kotlin.collections.AbstractMutableCollection\$removeAll\$lambda.<init>")!!
        assertEquals(KotlinWasmSymbolKind.LAMBDA, s.kind)
        assertEquals("kotlin.collections.AbstractMutableCollection", s.classFqn)
    }

    @Test
    fun `anonymous synthetic class strips to the outer class as a method`() {
        val s = KotlinWasmNameParser.parse("com.example.App\$Content\$1.invoke")!!
        assertEquals("com.example.App", s.classFqn)
        assertEquals("invoke", s.member)
    }

    @Test
    fun `non-declaration names return null`() {
        assertNull(KotlinWasmNameParser.parse(""))
        assertNull(KotlinWasmNameParser.parse("memory")) // no dot
        assertNull(KotlinWasmNameParser.parse("func.0")) // numeric internal name
        assertNull(KotlinWasmNameParser.parse(".leadingDot"))
        assertNull(KotlinWasmNameParser.parse("trailingDot."))
    }
}

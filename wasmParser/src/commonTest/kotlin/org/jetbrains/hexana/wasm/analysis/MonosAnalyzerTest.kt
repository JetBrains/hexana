package org.jetbrains.hexana.wasm.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonosAnalyzerTest {

    @Test
    fun `groups functions with identical base names`() {
        val names = mapOf(
            10 to "std::vec::Vec<i32>::push",
            11 to "std::vec::Vec<f64>::push",
            12 to "std::vec::Vec<u8>::push",
            20 to "main",
        )
        val sizes = mapOf(10 to 100, 11 to 120, 12 to 80, 20 to 50)

        val result = MonosAnalyzer.analyze(names, sizes)

        assertEquals(1, result.groups.size)
        val group = result.groups[0]
        assertEquals("std::vec::Vec::push", group.baseName)
        assertEquals(3, group.count)
        assertEquals(300, group.totalSize)
        assertEquals(100, group.avgSize)
    }

    @Test
    fun `strips Rust hash suffixes`() {
        val names = mapOf(
            0 to "core::fmt::write::h1a2b3c4d5e6f7890",
            1 to "core::fmt::write::habcdef0123456789",
        )
        val sizes = mapOf(0 to 200, 1 to 200)

        val result = MonosAnalyzer.analyze(names, sizes)

        assertEquals(1, result.groups.size)
        assertEquals("core::fmt::write", result.groups[0].baseName)
    }

    @Test
    fun `strips Kotlin lambda suffixes`() {
        val names = mapOf(
            0 to "kotlin.collections.ArrayList\$forEach\$lambda\$0",
            1 to "kotlin.collections.ArrayList\$forEach\$lambda\$1",
        )
        val sizes = mapOf(0 to 50, 1 to 60)

        val result = MonosAnalyzer.analyze(names, sizes)

        assertEquals(1, result.groups.size)
        assertEquals("kotlin.collections.ArrayList\$forEach\$lambda", result.groups[0].baseName)
    }

    @Test
    fun `normalizes Emscripten invoke wrappers`() {
        val names = mapOf(
            0 to "invoke_vii",
            1 to "invoke_viii",
            2 to "invoke_viiii",
        )
        val sizes = mapOf(0 to 30, 1 to 35, 2 to 40)

        val result = MonosAnalyzer.analyze(names, sizes)

        assertEquals(1, result.groups.size)
        assertEquals("invoke_*", result.groups[0].baseName)
        assertEquals(3, result.groups[0].count)
    }

    @Test
    fun `ignores functions without size data`() {
        val names = mapOf(0 to "foo", 1 to "foo", 2 to "bar")
        val sizes = mapOf(0 to 10) // only funcIdx 0 has size

        val result = MonosAnalyzer.analyze(names, sizes)

        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `ignores singleton groups`() {
        val names = mapOf(0 to "unique_a", 1 to "unique_b", 2 to "unique_c")
        val sizes = mapOf(0 to 100, 1 to 200, 2 to 300)

        val result = MonosAnalyzer.analyze(names, sizes)

        assertTrue(result.groups.isEmpty())
        assertEquals(0, result.totalBloat)
    }

    @Test
    fun `sorts groups by total size descending`() {
        val names = mapOf(
            0 to "small<A>", 1 to "small<B>",
            2 to "big<X>", 3 to "big<Y>",
        )
        val sizes = mapOf(0 to 10, 1 to 10, 2 to 500, 3 to 500)

        val result = MonosAnalyzer.analyze(names, sizes)

        assertEquals(2, result.groups.size)
        assertEquals("big", result.groups[0].baseName)
        assertEquals("small", result.groups[1].baseName)
    }

    @Test
    fun `computes total bloat`() {
        // 3 instances of 100 bytes each → bloat = 2 * 100 = 200
        val names = mapOf(0 to "f<A>", 1 to "f<B>", 2 to "f<C>")
        val sizes = mapOf(0 to 100, 1 to 100, 2 to 100)

        val result = MonosAnalyzer.analyze(names, sizes)

        assertEquals(200, result.totalBloat)
    }

    // extractBaseName unit tests

    @Test
    fun `extractBaseName strips angle brackets`() {
        assertEquals("std::vec::Vec::push", MonosAnalyzer.extractBaseName("std::vec::Vec<i32>::push"))
    }

    @Test
    fun `extractBaseName strips nested angle brackets`() {
        assertEquals("HashMap::insert", MonosAnalyzer.extractBaseName("HashMap<String, Vec<i32>>::insert"))
    }

    @Test
    fun `extractBaseName strips Rust hash`() {
        assertEquals("alloc::fmt::format", MonosAnalyzer.extractBaseName("alloc::fmt::format::h1234abcd5678ef90"))
    }

    @Test
    fun `extractBaseName preserves plain names`() {
        assertEquals("malloc", MonosAnalyzer.extractBaseName("malloc"))
    }

    @Test
    fun `extractBaseName strips trailing numeric suffix`() {
        assertEquals("foo", MonosAnalyzer.extractBaseName("foo.42"))
        assertEquals("bar", MonosAnalyzer.extractBaseName("bar\$0"))
    }

    @Test
    fun `extractBaseName normalizes invoke wrappers`() {
        assertEquals("invoke_*", MonosAnalyzer.extractBaseName("invoke_viiii"))
    }

    @Test
    fun `extractBaseName handles Kotlin names`() {
        val base = MonosAnalyzer.extractBaseName(
            "kotlinx.serialization.internal.TripleSerializer\$descriptor\$lambda\$0"
        )
        assertEquals("kotlinx.serialization.internal.TripleSerializer\$descriptor\$lambda", base)
    }
}
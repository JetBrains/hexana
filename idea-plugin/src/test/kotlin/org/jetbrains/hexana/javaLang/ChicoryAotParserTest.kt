package org.jetbrains.hexana.javaLang

import junit.framework.TestCase

/**
 * Unit tests for the Chicory AOT matcher predicate.
 *
 * The text-extraction helpers (Maven pom.xml / Gradle DSL) that were previously
 * tested here have been removed along with the deleted [ChicoryAotPluginIndex].
 * Config population is now the responsibility of the Maven and Gradle import
 * extensions ([ChicoryMavenConfigurator], [ChicoryGradleProjectResolver]).
 *
 * These tests exercise only the FQN-matching regex logic — no IDE fixture or
 * FileBasedIndex infrastructure required.  [matcherCheck] replicates the
 * two-step predicate from [ChicoryAotRegistryImpl.findMatch] without needing a
 * live [VirtualFile].
 */
class ChicoryAotParserTest : TestCase() {

    private val funcIdxPattern = Regex("""^func_(\d+)$""")
    private val groupSuffixRegex = Regex("""ModuleMachineFuncGroup_\d+$""")

    /**
     * Mirrors the two-step logic inside [ChicoryAotRegistryImpl.findMatch]:
     * returns `(funcIdx, moduleInterface)` when both patterns hit, or `null`
     * when either check fails.  [expectedInterface] is used to assert that the
     * derived interface matches a given expected value.
     */
    private fun matcherCheck(classFqn: String, methodName: String, expectedInterface: String?): Pair<Int, String>? {
        val funcIdx = funcIdxPattern.find(methodName)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val moduleInterface = groupSuffixRegex.replace(classFqn, "").trimEnd('.')
        if (moduleInterface == classFqn) return null
        if (expectedInterface != null && moduleInterface != expectedInterface) return null
        return Pair(funcIdx, moduleInterface)
    }

    // -----------------------------------------------------------------------
    // Matcher predicate (no VFS / IDE infra needed)
    // -----------------------------------------------------------------------

    fun testMatcherPositiveCase() {
        // io.roastedroot.jq4j.JqModuleMachineFuncGroup_0::func_50 → idx 50
        val result = matcherCheck(
            "io.roastedroot.jq4j.JqModuleMachineFuncGroup_0",
            "func_50",
            "io.roastedroot.jq4j.Jq"
        )
        assertNotNull(result)
        assertEquals(50, result!!.first)
        assertEquals("io.roastedroot.jq4j.Jq", result.second)
    }

    fun testMatcherLargeGroupIndex() {
        // Group suffix _123 should also match.
        val result = matcherCheck(
            "com.example.FooModuleMachineFuncGroup_123",
            "func_0",
            "com.example.Foo"
        )
        assertNotNull(result)
        assertEquals(0, result!!.first)
    }

    fun testMatcherWrongMethodShape() {
        // Method name not matching func_N pattern → null
        val result = matcherCheck(
            "io.roastedroot.jq4j.JqModuleMachineFuncGroup_0",
            "someOtherMethod",
            "io.roastedroot.jq4j.Jq"
        )
        assertNull(result)
    }

    fun testMatcherWrongClassShape() {
        // Class FQN without the ModuleMachineFunGroup suffix → null
        val result = matcherCheck(
            "io.roastedroot.jq4j.Jq",
            "func_5",
            "io.roastedroot.jq4j.Jq"
        )
        assertNull(result)
    }

    fun testMatcherWrongPackage() {
        // Package mismatch → interface derived is different
        val result = matcherCheck(
            "com.other.JqModuleMachineFuncGroup_0",
            "func_10",
            "io.roastedroot.jq4j.Jq"  // expected doesn't match derived
        )
        assertNull(result)
    }

    fun testMatcherFuncIndexZero() {
        val result = matcherCheck(
            "a.b.CModuleMachineFuncGroup_0",
            "func_0",
            "a.b.C"
        )
        assertNotNull(result)
        assertEquals(0, result!!.first)
    }

    fun testMatcherFuncIndexLarge() {
        val result = matcherCheck(
            "a.b.CModuleMachineFuncGroup_7",
            "func_14316",
            "a.b.C"
        )
        assertNotNull(result)
        assertEquals(14316, result!!.first)
    }
}

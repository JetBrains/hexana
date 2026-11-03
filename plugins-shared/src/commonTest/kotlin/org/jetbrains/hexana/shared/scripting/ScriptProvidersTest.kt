package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.providers.StructureRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Validation of what a provider's `parse()` returns, and tab matching. */
class ScriptProvidersTest {

    @Test
    fun `malformed regions are dropped and counted, not fatal`() {
        val builder = ProviderParseResultBuilder()
        builder.region("Header", 0, 16, null)
        builder.region(null, 0, 4, null)
        builder.region("Negative", -1, 4, null)
        builder.region("Fraction", 1.5, 4, null)
        builder.region("NaN", Double.NaN, 4, null)
        builder.region("Body", 16, 32.0, "Fields")
        val result = builder.build()
        assertEquals(listOf("Header", "Body"), result.regions.map { it.name })
        assertEquals(4, result.dropped)
        assertEquals("Fields", result.regions[1].tab)
    }

    @Test
    fun `regions outside the file never reach the hex view`() {
        val result = ProviderParseResult(
            listOf(
                ProviderRegion("Inside", 0, 8),
                ProviderRegion("Empty", 8, 0),
                ProviderRegion("Past end", 60, 8),
                ProviderRegion("Overflow", Long.MAX_VALUE - 1, Long.MAX_VALUE),
            ),
        )
        assertEquals(listOf(StructureRange(0, 8, "Inside")), result.structureRanges(fileSize = 64))
    }

    @Test
    fun `tabs match by built-in format, provider id, or wildcard`() {
        val ids = scriptFormatIds(BinaryParser.ELF, "user:acme-fw")
        assertTrue(appliesToFormats(listOf("elf"), ids))
        assertTrue(appliesToFormats(listOf("user:acme-fw"), ids))
        assertTrue(appliesToFormats(listOf("*"), ids))
        assertTrue(appliesToFormats(emptyList(), ids))
        assertFalse(appliesToFormats(listOf("pe", "user:other"), ids))
    }

    @Test
    fun `slugs fold everything but letters and digits`() {
        assertEquals("acme-firmware-v2", providerScriptSlug("ACME firmware (v2)"))
        assertEquals("provider", providerScriptSlug("  ***  "))
    }

    @Test
    fun `literals escape what would break the script`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", jsStringLiteral("a\"b\\c\nd"))
        assertEquals("\"\\u0001\"", jsStringLiteral("\u0001"))
    }

    @Test
    fun `the template inserts the id everywhere it is used`() {
        val text = providerScript("user:acme", "ACME")
        assertTrue("id: \"user:acme\"," in text)
        assertTrue("name: \"ACME\"," in text)
        assertTrue("appliesTo: [\"user:acme\"]" in text)
        assertFalse(PROVIDER_ID_PLACEHOLDER in text)
        assertTrue(PROVIDER_ID_PLACEHOLDER in providerScriptTemplate(PROVIDER_ID_PLACEHOLDER, PROVIDER_NAME_PLACEHOLDER))
    }
}

package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.shared.scripting.ProviderRegion
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `hexana.defineProvider` / `hexana.defineTab` registration and `parse()` through a session. */
class HexanaScriptProvidersTest {

    private val runner = HexanaScriptRunner()

    private fun provider(size: Int = 64) = GenericBinaryProvider(ByteArray(size) { it.toByte() }.toCommonByteBuffer())

    private fun session(script: String, size: Int = 64) =
        runner.openSession(HexanaScriptSource.Inline(script.trimIndent()), provider(size))

    @Test
    fun providerMetadataIsCollectedWithoutCallingParse() {
        session(
            """
            hexana.defineProvider({
                id: "user:acme-fw",
                name: "ACME firmware",
                base: "elf",
                description: "Boot images",
                parse(binary) { throw new Error("not during registration"); },
            });
            """,
        ).use { s ->
            assertTrue(s.initialResult.success, s.initialResult.diagnostics.toString())
            val info = s.initialResult.providers.single()
            assertEquals("user:acme-fw", info.id)
            assertEquals("ACME firmware", info.name)
            assertEquals(BinaryParser.ELF, info.base)
            assertEquals("Boot images", info.description)
        }
    }

    @Test
    fun parseReportsRegionsReadFromTheBinary() {
        session(
            """
            hexana.defineProvider({
                id: "user:acme-fw",
                name: "ACME firmware",
                parse(binary) {
                    return { regions: [
                        { name: "Header", offset: 0, length: binary.size / 4 },
                        { name: "Body", offset: 16, length: 48, tab: "user:Fields" },
                        { name: "Broken", offset: "x", length: 1 },
                    ] };
                },
            });
            """,
        ).use { s ->
            val outcome = assertIs<ProviderParseOutcome.Ok>(s.runParse("user:acme-fw"))
            assertEquals(
                listOf(ProviderRegion("Header", 0, 16), ProviderRegion("Body", 16, 48, "user:Fields")),
                outcome.result.regions,
            )
            assertEquals(1, outcome.result.dropped)
        }
    }

    @Test
    fun parseMayReturnTheRegionArrayOrNothing() {
        session(
            """
            hexana.defineProvider({ id: "user:a", name: "A", parse() { return [{ name: "R", offset: 0, length: 1 }]; } });
            hexana.defineProvider({ id: "user:b", name: "B", parse() {} });
            hexana.defineProvider({ id: "user:c", name: "C" });
            """,
        ).use { s ->
            assertEquals(1, assertIs<ProviderParseOutcome.Ok>(s.runParse("user:a")).result.regions.size)
            assertEquals(0, assertIs<ProviderParseOutcome.Ok>(s.runParse("user:b")).result.regions.size)
            assertEquals(0, assertIs<ProviderParseOutcome.Ok>(s.runParse("user:c")).result.regions.size)
        }
    }

    @Test
    fun aThrowingParseIsReportedAndKeepsTheSessionAlive() {
        session(
            """
            hexana.defineProvider({ id: "user:bad", name: "Bad", parse() { throw new Error("boom"); } });
            hexana.defineTab({
                name: "user:Bytes", appliesTo: ["user:bad"],
                columns: [{ name: "Offset", type: "offset" }],
                rows() { return { rows: [{ cells: [0] }] }; },
            });
            """,
        ).use { s ->
            val error = assertIs<ProviderParseOutcome.Err>(s.runParse("user:bad"))
            assertTrue("boom" in error.message, error.message)
            assertTrue(s.isAlive)
            assertIs<SlicePageResult.Ok>(s.fetchPage("user:Bytes", 0, 10))
        }
    }

    @Test
    fun defineTabRegistersASliceThatSaysWhereItAttaches() {
        session(
            """
            hexana.defineTab({
                name: "user:Header", appliesTo: ["elf", "user:acme-fw"],
                columns: [{ name: "Field", type: "string" }],
                rows() { return { rows: [] }; },
            });
            """,
        ).use { s ->
            val tab = s.initialResult.slices.single()
            assertEquals(listOf("elf", "user:acme-fw"), tab.formats)
            assertTrue(tab.isTab)
        }
    }

    @Test
    fun invalidSpecsFailTheScriptWithAClearMessage() {
        val cases = mapOf(
            """hexana.defineProvider({ id: "acme", name: "A" });""" to "must start with \"user:\"",
            """hexana.defineProvider({ id: "user:a" });""" to "spec.name is required",
            """hexana.defineProvider({ id: "user:a", name: "A", base: "nope" });""" to "is not a built-in parser",
            """hexana.defineProvider({ id: "user:a", name: "A" }); hexana.defineProvider({ id: "user:a", name: "B" });"""
                to "duplicate provider id",
            """hexana.defineTab({ name: "user:t", columns: [{ name: "c", type: "int" }], rows() {} });"""
                to "spec.appliesTo is required",
            """hexana.defineTab({ name: "user:t", appliesTo: [], columns: [{ name: "c", type: "int" }], rows() {} });"""
                to "must not be empty",
        )
        for ((script, expected) in cases) {
            session(script).use { s ->
                val messages = s.initialResult.diagnostics.joinToString { it.message }
                assertTrue(!s.initialResult.success && expected in messages, "$script -> $messages")
            }
        }
    }

    @Test
    fun hasReportsTheNewFeatures() {
        session(
            """
            if (!hexana.has("providers") || !hexana.has("tabs")) throw new Error("missing feature");
            """,
        ).use { s -> assertTrue(s.initialResult.success, s.initialResult.diagnostics.toString()) }
    }

    @Test
    fun theExampleProviderScriptParsesItsFormat() {
        val example = java.io.File("../../examples/providers/firmware-image.js")
        assertTrue(example.isFile, "missing ${example.absolutePath}")
        // "FWIM", version 2, entry 0x8000, a 64-byte payload, then a 32-byte signature.
        val image = java.nio.ByteBuffer.allocate(16 + 64 + 32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("FWIM".toByteArray()).putInt(2).putInt(0x8000).putInt(64).array()
        val provider = GenericBinaryProvider(image.toCommonByteBuffer())
        runner.openSession(HexanaScriptSource.Inline(example.readText(), example.name), provider).use { s ->
            assertTrue(s.initialResult.success, s.initialResult.diagnostics.toString())
            val regions = assertIs<ProviderParseOutcome.Ok>(s.runParse("user:acme-fw")).result.regions
            assertEquals(listOf("Header" to 16L, "Payload" to 64L, "Signature" to 32L), regions.map { it.name to it.length })
            val page = assertIs<SlicePageResult.Ok>(s.fetchPage("user:Header", 0, 10)).page
            assertEquals("0x8000", page.rows[2].cells[1].text)
        }
    }
}

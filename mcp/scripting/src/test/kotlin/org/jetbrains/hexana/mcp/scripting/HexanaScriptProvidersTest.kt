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
            hexana.defineProvider({ id: "user:d", name: "D", parse() { return {}; } });
            """,
        ).use { s ->
            assertEquals(1, assertIs<ProviderParseOutcome.Ok>(s.runParse("user:a")).result.regions.size)
            assertEquals(0, assertIs<ProviderParseOutcome.Ok>(s.runParse("user:b")).result.regions.size)
            assertEquals(0, assertIs<ProviderParseOutcome.Ok>(s.runParse("user:c")).result.regions.size)
            assertEquals(0, assertIs<ProviderParseOutcome.Ok>(s.runParse("user:d")).result.regions.size)
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
    fun theExampleProviderScriptParsesTheExampleImage() {
        // The demo project in examples/providers/: the script, and the image it is assigned to.
        val demo = java.io.File("../../examples/providers")
        val script = java.io.File(demo, ".hexana/providers/firmware-image.js")
        val image = java.io.File(demo, "firmware-image.bin")
        assertTrue(script.isFile && image.isFile, "missing demo files under ${demo.absolutePath}")
        val provider = GenericBinaryProvider(image.readBytes().toCommonByteBuffer())
        runner.openSession(HexanaScriptSource.Inline(script.readText(), script.name), provider).use { s ->
            assertTrue(s.initialResult.success, s.initialResult.diagnostics.toString())
            val regions = assertIs<ProviderParseOutcome.Ok>(s.runParse("user:acme-fw")).result.regions
            // The regions tile the whole file, so none is dropped for lying outside it.
            assertEquals(listOf("Header" to 16L, "Payload" to 96L, "Signature" to 32L), regions.map { it.name to it.length })
            assertEquals(image.length(), regions.sumOf { it.length })
            val page = assertIs<SlicePageResult.Ok>(s.fetchPage("user:Header", 0, 10)).page
            assertEquals(listOf("FWIM", "2", "0x8000", "96"), page.rows.map { it.cells[1].text })
        }
    }

    @Test
    fun theDemoProjectAssignsTheExampleProvider() {
        val text = java.io.File("../../examples/providers/.hexana/file_associations.json").readText()
        val associations = org.jetbrains.hexana.shared.features.parseFileAssociations(text)
        assertEquals(
            org.jetbrains.hexana.shared.features.ParserRef.Scripted("user:acme-fw"),
            associations.parserFor("firmware-image.bin"),
        )
        // Committed exactly as the editors write it, so opening the demo leaves it unchanged.
        assertEquals(org.jetbrains.hexana.shared.features.serializeFileAssociations(associations), text)
    }

    @Test
    fun theNewProviderStubRunsAsWritten() {
        // A name a user might type: quotes and a newline must not break the generated script.
        val text = org.jetbrains.hexana.shared.scripting.providerScript("user:my-format", "My \"format\"\nv2")
        session(text).use { s ->
            assertTrue(s.initialResult.success, s.initialResult.diagnostics.toString())
            val info = s.initialResult.providers.single()
            assertEquals("My \"format\"\nv2", info.name)
            val regions = assertIs<ProviderParseOutcome.Ok>(s.runParse("user:my-format")).result.regions
            assertEquals(listOf(ProviderRegion("Header", 0, 16)), regions)
        }
    }
}

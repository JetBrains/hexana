package org.jetbrains.hexana.shared.features

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.shared.scripting.ProviderRegistrationInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `.hexana/file_associations.json` is hand-editable and committed, like `runtimes.json`:
 * parsing is lenient, serialization byte-stable, and paths mean the same on every OS.
 */
class FileAssociationsTest {

    @Test
    fun `path constants are the documented project-relative locations`() {
        assertEquals(".hexana/file_associations.json", FILE_ASSOCIATIONS_PATH)
        assertEquals(".hexana/file_associations.schema.json", FILE_ASSOCIATIONS_SCHEMA_PATH)
    }

    @Test
    fun `round trip preserves every association`() {
        val associations = FileAssociations(
            mapOf("fw/boot.img" to BinaryParser.ELF.ref(), "data/blob.dat" to BinaryParser.GENERIC.ref()),
        )
        val parsed = parseFileAssociations(serializeFileAssociations(associations))
        assertEquals(associations, parsed)
        assertEquals(BinaryParser.ELF.ref(), parsed.parserFor("fw/boot.img"))
        assertNull(parsed.parserFor("other.bin"))
    }

    @Test
    fun `serialization is sorted and stable`() {
        val a = FileAssociations(mapOf("z.bin" to BinaryParser.PE.ref(), "a.bin" to BinaryParser.ZIP.ref()))
        val b = FileAssociations(mapOf("a.bin" to BinaryParser.ZIP.ref(), "z.bin" to BinaryParser.PE.ref()))
        assertEquals(serializeFileAssociations(a), serializeFileAssociations(b))
        val text = serializeFileAssociations(a)
        assertTrue(text.indexOf("a.bin") < text.indexOf("z.bin"))
        assertTrue(text.startsWith("{\n    \"\$schema\": \"./file_associations.schema.json\""), text)
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `malformed or empty text means nothing is associated`() {
        assertTrue(parseFileAssociations("").isEmpty)
        assertTrue(parseFileAssociations("{ not json").isEmpty)
        assertTrue(parseFileAssociations("[]").isEmpty)
        assertTrue(parseFileAssociations("{\"version\": 1}").isEmpty)
    }

    @Test
    fun `unknown parsers and unusable paths are dropped, the rest kept`() {
        val parsed = parseFileAssociations(
            """
            {
              "associations": {
                "./a\\b.bin": "elf",
                "c.bin": "future-format",
                "/abs.bin": "pe",
                "../outside.bin": "pe",
                "d.bin": 7,
                "e.bin": "generic"
              }
            }
            """.trimIndent(),
        )
        assertEquals(
            FileAssociations(mapOf("a/b.bin" to BinaryParser.ELF.ref(), "e.bin" to BinaryParser.GENERIC.ref())),
            parsed,
        )
    }

    @Test
    fun `with binds and unbinds`() {
        val one = FileAssociations.EMPTY.with("x.bin", BinaryParser.DEX.ref())
        assertEquals(BinaryParser.DEX.ref(), one.parserFor("x.bin"))
        assertTrue(one.with("x.bin", null).isEmpty)
    }

    @Test
    fun `keys are project relative with forward slashes`() {
        assertEquals("build/out.bin", fileAssociationKey("/home/u/proj", "/home/u/proj/build/out.bin"))
        assertEquals("build/out.bin", fileAssociationKey("/home/u/proj/", "/home/u/proj/build/out.bin"))
        assertEquals("build/out.bin", fileAssociationKey("C:\\w\\proj", "C:\\w\\proj\\build\\out.bin"))
        assertNull(fileAssociationKey("/home/u/proj", "/home/u/project2/out.bin"))
        assertNull(fileAssociationKey("/home/u/proj", "/home/u/proj"))
        assertNull(fileAssociationKey("", "/out.bin"))
    }

    @Test
    fun `schema offers every parser id`() {
        val schema = Json.parseToJsonElement(serializeFileAssociationsSchema()).jsonObject
        val anyOf = schema["definitions"]!!.jsonObject["parser"]!!.jsonObject["anyOf"]!!.jsonArray
        val ids = anyOf[0].jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(BinaryParser.entries.map { it.id }, ids)
        // Without loaded providers: built-in ids, then any user: id.
        assertEquals(2, anyOf.size)
        assertEquals("^user:.+$", anyOf[1].jsonObject["pattern"]!!.jsonPrimitive.content)
        assertEquals(
            "#/definitions/parser",
            schema["properties"]!!.jsonObject["associations"]!!.jsonObject["additionalProperties"]!!
                .jsonObject["\$ref"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `user ids round trip as scripted references, unknown ids are dropped`() {
        val parsed = parseFileAssociations(
            """{ "associations": { "fw.img": "user:acme-fw", "a.bin": "user:", "b.bin": "acme-fw" } }""",
        )
        assertEquals(FileAssociations(mapOf("fw.img" to ParserRef.Scripted("user:acme-fw"))), parsed)
        assertEquals(parsed, parseFileAssociations(serializeFileAssociations(parsed)))
    }

    @Test
    fun `schema completes the providers loaded when it was written`() {
        val schema = Json.parseToJsonElement(
            serializeFileAssociationsSchema(
                listOf(
                    ProviderRegistrationInfo("user:z", "Zed", BinaryParser.GENERIC, null),
                    ProviderRegistrationInfo("user:a", "Ay", BinaryParser.ELF, null),
                ),
            ),
        ).jsonObject
        val anyOf = schema["definitions"]!!.jsonObject["parser"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(3, anyOf.size)
        assertEquals(listOf("user:a", "user:z"), anyOf[1].jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}

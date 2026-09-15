package org.jetbrains.hexana.shared.features

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `.hexana/runtimes.json` is hand-editable and committed to version control, so parsing is
 * lenient and serialization is byte-stable. Both properties are pinned here.
 */
class RuntimeTargetsTest {

    @Test
    fun `path constant is the documented project-relative location`() {
        assertEquals(".hexana/runtimes.json", RUNTIME_TARGETS_PATH)
        assertEquals(".hexana", HEXANA_PROJECT_DIR)
    }

    @Test
    fun `round trip preserves runtimes, versions and order`() {
        val targets = RuntimeTargets(
            listOf(
                RuntimeTarget("Chrome", "120"),
                RuntimeTarget("Wasmtime", "26.0"),
                RuntimeTarget("Node.js"),
            )
        )
        val parsed = parseRuntimeTargets(serializeRuntimeTargets(targets))
        assertEquals(targets.targets, parsed.targets)
        assertEquals(RUNTIME_TARGETS_SCHEMA_VERSION, parsed.schemaVersion)
    }

    @Test
    fun `serialization is stable so an unchanged save produces an identical file`() {
        val targets = RuntimeTargets(listOf(RuntimeTarget("Chrome", "120"), RuntimeTarget("Deno")))
        val first = serializeRuntimeTargets(targets)
        assertEquals(first, serializeRuntimeTargets(parseRuntimeTargets(first)))
        assertTrue(first.endsWith("\n"), "expected a trailing newline for a committed file")
    }

    @Test
    fun `absent version is omitted rather than written as null`() {
        val text = serializeRuntimeTargets(RuntimeTargets(listOf(RuntimeTarget("Node.js"))))
        assertTrue("\"version\": null" !in text, "expected no null version key in:\n$text")
        assertNull(parseRuntimeTargets(text).targets.single().version)
    }

    @Test
    fun `blank version is normalized to any-version`() {
        val parsed = parseRuntimeTargets(
            """{"version": 1, "targets": [{"runtime": "Chrome", "version": "   "}]}"""
        )
        assertNull(parsed.targets.single().version)
    }

    @Test
    fun `runtime names are trimmed and blank entries dropped`() {
        val parsed = parseRuntimeTargets(
            """{"version": 1, "targets": [{"runtime": "  Chrome  "}, {"runtime": ""}, {"version": "1"}]}"""
        )
        assertEquals(listOf(RuntimeTarget("Chrome")), parsed.targets)
    }

    @Test
    fun `a duplicated runtime keeps its first entry`() {
        val parsed = parseRuntimeTargets(
            """{"version": 1, "targets": [{"runtime": "Chrome", "version": "100"}, {"runtime": "Chrome", "version": "137"}]}"""
        )
        assertEquals(listOf(RuntimeTarget("Chrome", "100")), parsed.targets)
    }

    @Test
    fun `malformed input degrades to empty rather than throwing`() {
        assertTrue(parseRuntimeTargets("").isEmpty)
        assertTrue(parseRuntimeTargets("{").isEmpty)
        assertTrue(parseRuntimeTargets("not json").isEmpty)
        assertTrue(parseRuntimeTargets("[]").isEmpty)
        assertTrue(parseRuntimeTargets("""{"targets": "wrong type"}""").isEmpty)
        assertTrue(parseRuntimeTargets("""{"targets": [1, 2, 3]}""").isEmpty)
    }

    @Test
    fun `unknown keys are ignored so a newer file still loads`() {
        val parsed = parseRuntimeTargets(
            """{"version": 1, "somethingNew": {"a": 1}, "targets": [{"runtime": "Chrome", "future": true}]}"""
        )
        assertEquals(listOf(RuntimeTarget("Chrome")), parsed.targets)
    }

    @Test
    fun `a missing version field defaults to the current schema version`() {
        assertEquals(
            RUNTIME_TARGETS_SCHEMA_VERSION,
            parseRuntimeTargets("""{"targets": []}""").schemaVersion,
        )
    }

    @Test
    fun `the written file points at its schema sibling`() {
        val text = serializeRuntimeTargets(RuntimeTargets(listOf(RuntimeTarget("Chrome"))))
        assertTrue("\"${'$'}schema\": \"./runtimes.schema.json\"" in text, "expected a schema reference in:\n$text")
        assertEquals(".hexana/runtimes.schema.json", RUNTIME_TARGETS_SCHEMA_PATH)
        // The reference is a key the parser knows nothing about; it must not disturb reading.
        assertEquals(listOf(RuntimeTarget("Chrome")), parseRuntimeTargets(text).targets)
    }

    @Test
    fun `the generated schema validates the file the serializer writes`() {
        val catalog = WasmFeatureCatalog.bundled
        val schema = parseJsonObject(serializeRuntimeTargetsSchema(catalog))

        assertEquals("http://json-schema.org/draft-07/schema#", schema.string("${'$'}schema"))
        val target = schema.obj("definitions").obj("target")
        assertEquals(listOf("runtime"), target.array("required").map { it.jsonPrimitive.content })

        // Every runtime the settings UI can pick has to pass the schema it writes alongside.
        val offered = catalog.executionRuntimes.keys
        val allowed = target.obj("properties").obj("runtime").array("enum").map { it.jsonPrimitive.content }
        assertEquals(emptySet(), offered - allowed.toSet(), "runtimes the schema would reject")

        // And the keys the serializer emits have to be the keys the schema declares.
        val written = parseJsonObject(serializeRuntimeTargets(RuntimeTargets(listOf(RuntimeTarget("Chrome", "120")))))
        assertEquals(setOf("${'$'}schema", "version", "targets"), written.keys)
        assertTrue(schema.obj("properties").keys.containsAll(written.keys))
        val entry = (written["targets"] as JsonArray).single().jsonObject
        assertTrue(target.obj("properties").keys.containsAll(entry.keys), "unknown key in $entry")
    }

    @Test
    fun `a schema generated without a catalog constrains nothing it cannot know`() {
        val runtime = parseJsonObject(serializeRuntimeTargetsSchema(null))
            .obj("definitions").obj("target").obj("properties").obj("runtime")
        assertEquals("string", runtime.string("type"))
        assertNull(runtime["enum"], "expected no runtime names without a catalog to take them from")
    }

    @Test
    fun `target label reads as runtime plus version`() {
        assertEquals("Chrome 120", RuntimeTarget("Chrome", "120").toString())
        assertEquals("Node.js", RuntimeTarget("Node.js").toString())
    }
}

private fun parseJsonObject(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

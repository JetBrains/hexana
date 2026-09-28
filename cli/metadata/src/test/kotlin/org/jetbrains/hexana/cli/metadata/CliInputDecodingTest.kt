package org.jetbrains.hexana.cli.metadata

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliInputDecodingTest {
    @Test
    fun `integer boolean and list inputs keep JSON types and semantic bounds`() {
        assertEquals(25, CliInputDecoding.int(JsonPrimitive(25), "limit", 1, 25))
        assertEquals(false, CliInputDecoding.boolean(JsonPrimitive(false), "enabled"))
        assertEquals(listOf(0, 2), CliInputDecoding.intList(
            JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(2))), "indices", 2, 0, 10,
        ))
        assertFailsWith<IllegalArgumentException> {
            CliInputDecoding.int(JsonPrimitive("25"), "limit")
        }
        assertFailsWith<IllegalArgumentException> {
            CliInputDecoding.boolean(JsonPrimitive("false"), "enabled")
        }
        assertFailsWith<IllegalArgumentException> {
            CliInputDecoding.intList(JsonArray(listOf(JsonPrimitive(-1))), "indices", itemMinimum = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CliInputDecoding.intList(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), "indices", maxItems = 1)
        }
    }

    @Test
    fun `JSON and string fields reject incompatible shapes`() {
        val json = JsonObject(mapOf("query" to JsonPrimitive("name")))
        assertEquals(json, CliInputDecoding.jsonObject(json, "filters"))
        assertEquals(listOf("a", "b"), CliInputDecoding.stringList(
            JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), "names"))
        assertFailsWith<IllegalArgumentException> { CliInputDecoding.jsonObject(JsonPrimitive("{}"), "filters") }
        assertFailsWith<IllegalArgumentException> { CliInputDecoding.string(JsonPrimitive(3), "query") }
        assertFailsWith<IllegalArgumentException> { CliInputDecoding.string(JsonPrimitive("abc"), "query", maxLength = 2) }
    }
}

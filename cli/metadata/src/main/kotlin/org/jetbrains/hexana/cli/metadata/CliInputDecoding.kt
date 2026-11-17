package org.jetbrains.hexana.cli.metadata

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal object CliInputDecoding {
    fun int(value: JsonElement, name: String, minimum: Int? = null, maximum: Int? = null): Int {
        val parsed = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw IllegalArgumentException("$name must be an integer.")
        require(minimum == null || parsed >= minimum) { "$name must be >= $minimum." }
        require(maximum == null || parsed <= maximum) { "$name must be <= $maximum." }
        return parsed
    }

    fun string(value: JsonElement, name: String, maxLength: Int? = null): String {
        val parsed = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("$name must be a string.")
        require(maxLength == null || parsed.length <= maxLength) { "$name exceeds $maxLength characters." }
        return parsed
    }

    fun boolean(value: JsonElement, name: String): Boolean =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("$name must be a boolean.")

    fun jsonObject(value: JsonElement, name: String): JsonObject =
        value as? JsonObject ?: throw IllegalArgumentException("$name must be a JSON object.")

    fun intList(
        value: JsonElement,
        name: String,
        maxItems: Int? = null,
        itemMinimum: Int? = null,
        itemMaximum: Int? = null,
    ): List<Int> {
        val array = value as? JsonArray ?: throw IllegalArgumentException("$name must be an array.")
        require(maxItems == null || array.size <= maxItems) { "$name exceeds $maxItems items." }
        return array.mapIndexed { index, item ->
            int(item, "$name[$index]", itemMinimum, itemMaximum)
        }
    }

    fun stringList(value: JsonElement, name: String, maxItems: Int? = null): List<String> {
        val array = value as? JsonArray ?: throw IllegalArgumentException("$name must be an array.")
        require(maxItems == null || array.size <= maxItems) { "$name exceeds $maxItems items." }
        return array.mapIndexed { index, item -> string(item, "$name[$index]") }
    }
}

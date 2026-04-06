package org.jetbrains.hexana.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

object HexanaJson {
    val compact: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }
}

inline fun <reified T> encodeHexanaJson(value: T): String {
    return HexanaJson.compact.encodeToString(value)
}

inline fun <reified T> encodeHexanaJsonElement(value: T): JsonElement {
    return HexanaJson.compact.encodeToJsonElement(value)
}

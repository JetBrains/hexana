package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.tools.HexanaCliProductRegistry
import org.junit.Test
import kotlin.test.assertEquals

class CliReleaseArtifactDescriptorTest {
    @Test
    fun `unpackaged CLI product does not advertise native release artifacts`() {
        val descriptor = Json.parseToJsonElement(buildCliReleaseArtifactDescriptor(HexanaCliProductRegistry)).jsonObject
        val products = descriptor.getValue("products").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        val artifacts = descriptor.getValue("artifacts").jsonArray.map { it.jsonObject.getValue("product").jsonPrimitive.content }

        assertEquals(listOf("hexana-cli", "hexana-cli-script"), products)
        assertEquals(2, artifacts.count { it == "hexana-cli" })
        assertEquals(2, artifacts.count { it == "hexana-cli-script" })
        assertEquals(4, artifacts.size)
    }
}

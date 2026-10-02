package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.tools.CliProductRegistry
import org.jetbrains.hexana.mcp.tools.CliProductSpec
import org.jetbrains.hexana.mcp.tools.HexanaCliProductRegistry
import org.jetbrains.hexana.mcp.tools.ProductId
import org.junit.Test
import kotlin.test.assertEquals

class CliReleaseArtifactDescriptorTest {
    @Test
    fun `current release includes exactly six native archives with product launchers`() {
        val descriptor = Json.parseToJsonElement(buildCliReleaseArtifactDescriptor(HexanaCliProductRegistry)).jsonObject
        val products = descriptor.getValue("products").jsonArray.map { it.jsonObject }
        val artifacts = descriptor.getValue("artifacts").jsonArray.map { it.jsonObject }
        val productIds = listOf("hexana-cli", "hexana-cli-script", "hexana-wasm-tools")
        val targets = listOf("macos-arm64", "linux-x64")

        assertEquals(productIds, products.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(productIds, products.map { it.getValue("executable").jsonPrimitive.content })
        assertEquals(productIds.map { "bin/$it" }, products.map { it.getValue("launcher").jsonPrimitive.content })
        assertEquals(listOf("standard-cli-native", "script-cli-native", "hexana-wasm-tools-native"),
            products.map { it.getValue("artifactKind").jsonPrimitive.content })
        assertEquals(targets.flatMap { target -> productIds.map { target to it } }, artifacts.map {
            it.getValue("target").jsonPrimitive.content to it.getValue("product").jsonPrimitive.content
        })
        assertEquals(targets.flatMap { target -> productIds.map { "$it-native-$target-{version}.zip" } },
            artifacts.map { it.getValue("archiveNameTemplate").jsonPrimitive.content })
    }

    @Test
    fun `future unpackaged product does not advertise native release artifacts`() {
        val fixture = CliProductSpec(ProductId("fixture-cli"), "fixture-cli", "Unpackaged fixture.",
            nativeReleaseEnabled = false)
        val registry = CliProductRegistry(HexanaCliProductRegistry.products + fixture)
        assertEquals(buildCliReleaseArtifactDescriptor(HexanaCliProductRegistry), buildCliReleaseArtifactDescriptor(registry))
    }
}

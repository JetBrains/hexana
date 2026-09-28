package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.hexana.mcp.tools.CliProductRegistry

private data class NativeReleaseTarget(val id: String, val platform: String, val architecture: String, val displayName: String)

private val nativeReleaseTargets = listOf(
    NativeReleaseTarget("macos-arm64", "macos", "arm64", "macOS Apple silicon"),
    NativeReleaseTarget("linux-x64", "linux", "x64", "Linux x86-64"),
)

private val releaseJson = Json { prettyPrint = true }

internal fun buildCliReleaseArtifactDescriptor(products: CliProductRegistry): String {
    val document = buildJsonObject {
        put("schemaVersion", 1)
        put("kind", "hexana-cli-native-release-artifacts")
        put("targets", buildJsonArray {
            nativeReleaseTargets.forEach { target ->
                add(buildJsonObject {
                    put("id", target.id)
                    put("platform", target.platform)
                    put("architecture", target.architecture)
                    put("displayName", target.displayName)
                    put("identityFile", "${target.id}-identity.json")
                })
            }
        })
        put("products", buildJsonArray {
            products.products.forEach { product ->
                add(buildJsonObject {
                    put("id", product.id.value)
                    put("executable", product.executableName)
                    put("artifactKind", product.nativeArtifactKind)
                    put("launcher", "bin/${product.executableName}")
                })
            }
        })
        put("artifacts", buildJsonArray {
            nativeReleaseTargets.forEach { target ->
                products.products.forEach { product ->
                    add(buildJsonObject {
                        put("target", target.id)
                        put("product", product.id.value)
                        put("archiveNameTemplate", "${product.executableName}-native-${target.id}-{version}.zip")
                    })
                }
            }
        })
    }
    return releaseJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), document) + "\n"
}

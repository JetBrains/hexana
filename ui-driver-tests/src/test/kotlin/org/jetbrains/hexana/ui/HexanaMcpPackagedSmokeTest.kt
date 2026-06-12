package org.jetbrains.hexana.ui

import kotlinx.serialization.json.*
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.withGlobalMcpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

@Tag("hexana")
class HexanaMcpPackagedSmokeTest {
    @Test
    fun packagedPluginExposesHexanaToolsOverGlobalMcp() {
        hexanaIde(
            testName = "hexana-packaged-mcp-smoke",
            configure = {
                runTimeout(20.minutes)
                enableGlobalMcp()
            },
        ) {
            withGlobalMcpClient { mcp ->
                val toolNames = mcp.toolNames()
                assertTrue(
                    toolNames.containsAll(EXPECTED_TOOL_NAMES),
                    "Missing expected packaged MCP tools ${EXPECTED_TOOL_NAMES - toolNames}; got $toolNames"
                )

                val artifactOverview = mcp.callStructuredTool(
                    name = "query_artifacts",
                    arguments = wasmPath(HexanaUiDriverTestSupport.wasmFileName) {
                        put("operation", JsonPrimitive("overview"))
                    },
                )
                val module = artifactOverview.array("modules").single().jsonObject
                assertTrue(
                    module.fieldAsInt("moduleSize") > 0,
                    "Expected query_artifacts moduleSize > 0, got $artifactOverview"
                )
                assertTrue(
                    module.fieldAsInt("functions") > 0,
                    "Expected query_artifacts functions > 0, got $artifactOverview"
                )

            }
        }
    }

    private fun wasmPath(path: String, configure: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("path", JsonPrimitive(path))
            configure()
        }

    private fun JsonObject.array(name: String): JsonArray {
        return getValue(name).jsonArray
    }

    private fun JsonObject.fieldAsInt(name: String): Int {
        return getValue(name).jsonPrimitive.int
    }

    private companion object {
        val EXPECTED_TOOL_NAMES = setOf(
            "query_artifacts",
        )
    }
}

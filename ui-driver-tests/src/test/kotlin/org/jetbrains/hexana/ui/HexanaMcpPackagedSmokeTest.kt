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

                val moduleSummary = mcp.callStructuredTool(
                    name = "summarize_module",
                    arguments = wasmPath(HexanaUiDriverTestSupport.wasmFileName),
                )
                assertTrue(
                    moduleSummary.fieldAsInt("moduleSize") > 0,
                    "Expected summarize_module moduleSize > 0, got $moduleSummary"
                )
                assertTrue(
                    moduleSummary.fieldAsInt("functions") > 0,
                    "Expected summarize_module functions > 0, got $moduleSummary"
                )

            }
        }
    }

    private fun wasmPath(path: String): JsonObject =
        buildJsonObject {
            put("path", JsonPrimitive(path))
        }

    private fun JsonObject.fieldAsInt(name: String): Int {
        return getValue(name).jsonPrimitive.int
    }

    private companion object {
        val EXPECTED_TOOL_NAMES = setOf(
            "summarize_module",
        )
    }
}

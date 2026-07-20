package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class HexanaMcpDataToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListDataSegmentsReturnsSegmentPreviewAndSummary() {
        val fixture = createDataFixture("fixtures/direct/data/list_data_segments.wasm")

        val result = callToolJsonObject(
            "list_data_segments",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("segmentRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(1))
                })
                put("includeExports", JsonPrimitive(false))
                put("includeGlobals", JsonPrimitive(true))
            }
        )

        assertEquals(1, result.array("segments").size)
        assertEquals("active", result.array("segments").obj(0).text("mode"))
        assertEquals("Hello", result.array("segments").obj(0).text("utf8Preview"))
        assertFalse(result.containsKey("linkingMetadata"))
        assertTrue(result.array("memory").isNotEmpty())
        assertEquals(1, result.obj("summary").int("totalSegments"))
        assertEquals(1, result.obj("summary").int("activeSegments"))
        assertEquals(0, result.obj("summary").int("passiveSegments"))
    }

    fun testListDataSegmentsProvidesStructuredContentForTypedResult() {
        val fixture = createDataFixture("fixtures/direct/data/structured_list_data_segments.wasm")

        val result = callTool(
            "list_data_segments",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("segmentRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(1))
                })
                put("includeExports", JsonPrimitive(false))
                put("includeGlobals", JsonPrimitive(true))
            }
        )

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        assertEquals(1, structuredContent.array("segments").size)
        assertEquals("Hello", structuredContent.array("segments").obj(0).text("utf8Preview"))
        assertFalse(structuredContent.containsKey("linkingMetadata"))
        assertEquals(1, structuredContent.obj("summary").int("totalSegments"))
    }

    fun testAuditDataPayloadPolicyReturnsReportReadyMatches() {
        val fixture = createWasmFixture(
            "fixtures/direct/data/payload_policy.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "safe payload")
              (data (i32.const 2048) "https://api.shadow.internal/v2/keys"))
            """.trimIndent()
        )
        val policy = createTextFixture(
            "fixtures/direct/data/payload_policy.json",
            """
            {
              "policy_id": "payload-policy-test",
              "forbidden_payloads": [
                {
                  "id": "shadow-admin-endpoint",
                  "match": "https://api.shadow.internal/v2/keys",
                  "severity": "critical"
                }
              ]
            }
            """.trimIndent()
        )

        val result = callTool(
            "audit_data_payload_policy",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("policyPath", JsonPrimitive(policy.projectRelativePath))
            }
        )

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        assertEquals("payload-policy-test", structuredContent.text("policyId"))
        assertEquals(2, structuredContent.int("scannedSegments"))
        assertEquals(listOf(0), structuredContent.array("cleanSegments").map { it.jsonPrimitive.int })
        val match = structuredContent.array("matches").obj(0)
        assertEquals("shadow-admin-endpoint", match.text("policyEntryId"))
        assertEquals(1, match.int("segmentIndex"))
        assertTrue(match["rawBytesConfirmed"]!!.jsonPrimitive.boolean)
        val report = structuredContent.obj("recommendedReport")
        assertEquals("payload-policy-test", report.text("policy_id"))
        assertEquals(2, report.int("scanned_segments"))
        assertEquals(1, report.array("matches").obj(0).int("segment_index"))
        assertEquals(listOf(1), report.obj("minimal_fix").array("remove_or_replace_segments").map { it.jsonPrimitive.int })
    }

    private fun createDataFixture(pathInProject: String) = createWasmFixture(
        pathInProject,
        """
        (module
          (memory (export "memory") 1)
          (data (i32.const 0) "Hello"))
        """.trimIndent()
    )
}

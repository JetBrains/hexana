package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HexanaToolsTest {

    private val queryArtifacts = HexanaTools.single { it.name == "query_artifacts" }

    @Test
    fun `registry exposes only the generic artifact query tool`() {
        assertEquals(listOf("query_artifacts"), HexanaTools.map { it.name })
    }

    @Test
    fun `tool names use snake_case`() {
        val snakeCase = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
        HexanaTools.forEach { spec ->
            assertTrue(snakeCase.matches(spec.name), "tool name `${spec.name}` is not snake_case")
        }
    }

    @Test
    fun `tool builder rejects names outside strict snake_case`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("queryArtifacts") {
                    summary = "s"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    output = WasmInputClass
                    handlerFqn = queryArtifacts.handlerFqn
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("query__artifacts") {
                    summary = "s"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    output = WasmInputClass
                    handlerFqn = queryArtifacts.handlerFqn
                }
            }
        }
    }

    @Test
    fun `every tool declares summary, input, output, handler`() {
        for (spec in HexanaTools) {
            assertTrue(spec.summary.isNotBlank(), "summary blank for ${spec.name}")
            assertTrue(spec.handlerFqn.isNotBlank(), "handlerFqn blank for ${spec.name}")
            assertTrue(spec.input.fields.isNotEmpty(), "empty input class for ${spec.name}")
            assertTrue(
                spec.output.kotlinFqn != null,
                "output class ${spec.output.name} missing kotlinPackage for ${spec.name}",
            )
            assertTrue(spec.output.fields.isNotEmpty(), "empty output class for ${spec.name}")
        }
    }

    @Test
    fun `tool output Kotlin FQNs point at matching result classes`() {
        HexanaTools.forEach { spec ->
            val outputFqn = requireNotNull(spec.output.kotlinFqn)
            assertEquals(spec.output.name, outputFqn.substringAfterLast('.'), "bad output FQN for ${spec.name}")
            assertTrue(
                outputFqn.startsWith("org.jetbrains.hexana.mcp.model."),
                "output FQN for ${spec.name} should point at mcp/core model package",
            )
        }
    }

    @Test
    fun `query_artifacts uses the artifact query invocation pattern`() {
        assertEquals(ToolInputPattern.ArtifactQuery, queryArtifacts.inputPattern)
    }

    @Test
    fun `wasmInputClass carries a oneOf constraint`() {
        assertEquals(listOf("path", "bytesBase64", "bytes"), WasmInputClass.oneOf)
    }

    @Test
    fun `standalone schema constraints are declared in the DSL`() {
        val bytesBase64 = WasmInputClass.fields.single { it.name == "bytesBase64" }
        assertEquals(SchemaIntValue.MaxInlineWasmBase64Length, bytesBase64.maxLength)

        val bytes = WasmInputClass.fields.single { it.name == "bytes" }
        assertEquals(SchemaIntValue.MaxInlineWasmBytes, bytes.maxItems)
        assertEquals(0, bytes.itemMinimum)
        assertEquals(255, bytes.itemMaximum)
    }

    @Test
    fun `query_artifacts input carries defaults and bounds`() {
        val fields = queryArtifacts.input.fields.associateBy { it.name }

        assertEquals("overview", fields.getValue("operation").default)
        assertEquals(50, fields.getValue("limit").default)
        assertEquals("summary", fields.getValue("detail").default)
        assertEquals(1, fields.getValue("limit").minimum)
        assertEquals(200, fields.getValue("limit").maximum)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("entityTypes").maxItems)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("query").maxItems)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("relations").maxItems)
    }

    @Test
    fun `artifact query pattern validates required input fields`() {
        val fields = queryArtifacts.input.fields.associateBy { it.name }
        listOf(
            "baselinePath",
            "operation",
            "entityTypes",
            "query",
            "startName",
            "startFuncidx",
            "relations",
            "limit",
            "detail"
        ).forEach { fieldName ->
            assertNotNull(fields[fieldName], "Missing query_artifacts input field `$fieldName`")
        }
    }

    @Test
    fun `schema constraints reject incompatible field types`() {
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                string("name").minimum(0)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                int("count").maxItems(1)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                list("names", of = string()).itemsMinimum(0)
            }
        }
    }

    @Test
    fun `field names must be lower camel case`() {
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                int("bad_name")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                int("1bad")
            }
        }
    }
}

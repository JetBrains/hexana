package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HexanaToolsTest {

    private val sampleHandler = HexanaTools.single { it.name == "summarize_module" }.handlerFqn

    @Test
    fun `registry exposes the expected tool names`() {
        assertEquals(
            listOf(
                "functions_for_indices",
                "get_globals_for_indices",
                "get_instructions_for_functions",
                "get_locals_for_functions",
                "get_memory_for_indices",
                "get_types_for_indices",
                "list_data",
                "list_data_segments",
                "list_element_segments",
                "list_exported_functions",
                "list_exports",
                "list_functions",
                "list_globals",
                "list_imports",
                "list_memory",
                "list_types",
                "summarize_module",
            ),
            HexanaTools.map { it.name }.sorted(),
        )
    }

    @Test
    fun `registry preserves canonical generation order`() {
        assertEquals(
            listOf(
                "summarize_module",
                "list_imports",
                "list_exports",
                "list_globals",
                "list_types",
                "list_memory",
                "list_element_segments",
                "list_functions",
                "functions_for_indices",
                "get_globals_for_indices",
                "get_memory_for_indices",
                "get_types_for_indices",
                "get_locals_for_functions",
                "get_instructions_for_functions",
                "list_exported_functions",
                "list_data",
                "list_data_segments",
            ),
            HexanaTools.map { it.name },
        )
    }

    @Test
    fun `critical Wasm-analysis tools are always present`() {
        // Guard against accidental removal of the headline tools even as the full registry grows.
        val names = HexanaTools.map { it.name }.toSet()
        listOf("summarize_module", "list_imports", "list_functions", "get_instructions_for_functions").forEach {
            assertTrue(it in names, "critical tool `$it` missing from HexanaTools")
        }
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
                tool("listFunctions") {
                    summary = "s"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("list__functions") {
                    summary = "s"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    output = WasmInputClass
                    handlerFqn = sampleHandler
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
            assertTrue(
                spec.output.fields.isNotEmpty() || spec.output.name == "DataSegmentLinkingMetadataResult",
                "empty output class for ${spec.name}",
            )
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
    fun `tools expose input invocation patterns explicitly`() {
        assertEquals(ToolInputPattern.WasmOnly, HexanaTools.single { it.name == "summarize_module" }.inputPattern)
        assertEquals(ToolInputPattern.FunctionPage, HexanaTools.single { it.name == "list_functions" }.inputPattern)
        assertEquals(ToolInputPattern.Indices, HexanaTools.single { it.name == "functions_for_indices" }.inputPattern)
        assertEquals(
            ToolInputPattern.ClosedRange(parameterName = "entriesRange"),
            HexanaTools.single { it.name == "list_exported_functions" }.inputPattern,
        )
        assertEquals(ToolInputPattern.DataSegments, HexanaTools.single { it.name == "list_data_segments" }.inputPattern)
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

        val rangeFrom = ClosedIntRange.fields.single { it.name == "from" }
        val rangeTo = ClosedIntRange.fields.single { it.name == "to" }
        assertEquals(0, rangeFrom.minimum)
        assertEquals(0, rangeTo.minimum)
    }

    @Test
    fun `input defaults are declared in the DSL`() {
        val listFunctions = HexanaTools.single { it.name == "list_functions" }.input
        assertEquals(0, listFunctions.fields.single { it.name == "offset" }.default)
        assertEquals(200, listFunctions.fields.single { it.name == "limit" }.default)

        val listDataSegments = HexanaTools.single { it.name == "list_data_segments" }.input
        assertEquals(false, listDataSegments.fields.single { it.name == "includeExports" }.default)
        assertEquals(true, listDataSegments.fields.single { it.name == "includeGlobals" }.default)
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
    fun `blank field descriptions are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                int("value").describe("   ")
            }
        }
    }

    @Test
    fun `class kotlin package must produce a valid FQN`() {
        assertFailsWith<IllegalArgumentException> {
            class_("Broken", kotlinPackage = "not a package") {
                int("value")
            }
        }
    }

    @Test
    fun `list_functions composes wasm input with offset and limit`() {
        val spec = HexanaTools.single { it.name == "list_functions" }
        val fieldNames = spec.input.fields.map { it.name }
        assertTrue(fieldNames.containsAll(listOf("path", "bytesBase64", "bytes", "offset", "limit")))
    }

    @Test
    fun `functions_for_indices composes wasm input with indices list`() {
        val spec = HexanaTools.single { it.name == "functions_for_indices" }
        val indices = spec.input.fields.single { it.name == "indices" }
        val element = (indices.type as FieldType.ListOf).element
        assertEquals(FieldType.Int32, element)
    }

    @Test
    fun `list_exported_functions embeds a closed range object`() {
        val spec = HexanaTools.single { it.name == "list_exported_functions" }
        val range = spec.input.fields.single { it.name == "entriesRange" }
        val clazz = (range.type as FieldType.ClassRef).clazz
        assertEquals("ClosedIntRange", clazz.name)
        assertEquals(listOf("from", "to"), clazz.fields.map { it.name })
    }

    @Test
    fun `list_imports output references ImportFunctionResult`() {
        val spec = HexanaTools.single { it.name == "list_imports" }
        val imports = spec.output.fields.single { it.name == "imports" }
        val list = imports.type as FieldType.ListOf
        val clazz = (list.element as FieldType.ClassRef).clazz
        assertEquals("ImportFunctionResult", clazz.name)
    }

    @Test
    fun `ImportFunctionSignature applies serialName override on ret`() {
        val ret = ImportFunctionSignatureClass.fields.single { it.name == "ret" }
        assertEquals("return", ret.serialName)
    }

    @Test
    fun `data payload schema references the core base64 serializer`() {
        val data = DataEntryResultClass.fields.single { it.name == "data" }
        assertEquals("org.jetbrains.hexana.mcp.model.data.Base64ByteArraySerializer", data.serializerFqn)
    }

    @Test
    fun `with() rejects redeclaration of a seeded field`() {
        val parent = class_("Parent") { nullable("path", string()) }
        assertFailsWith<IllegalArgumentException> {
            parent.with("Child") { nullable("path", string()) }
        }
    }

    @Test
    fun `with() rejects redeclaration of a grandparent-seeded field`() {
        val grandparent = class_("Grandparent") { nullable("path", string()) }
        val parent = grandparent.with("Parent") { int("offset", default = 0) }
        assertFailsWith<IllegalArgumentException> {
            parent.with("Child") { nullable("path", string()) }
        }
    }

    @Test
    fun `duplicate tool names are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("dup") {
                    summary = "s"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
                tool("dup") {
                    summary = "t"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
    }

    @Test
    fun `tool without summary is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    input = WasmInputClass
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
    }

    @Test
    fun `tool with blank summary is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    summary = "   "
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
    }

    @Test
    fun `tool without input pattern is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    summary = "s"
                    input = WasmInputClass
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
    }

    @Test
    fun `input pattern must match input shape`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    summary = "s"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.Indices
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
    }

    @Test
    fun `input pattern validates field contracts`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    summary = "s"
                    input = WasmInputClass.with("BrokenFunctionPageInput") {
                        string("offset", default = "0").describe("Offset")
                        int("limit", default = 1).describe("Limit")
                    }
                    inputPattern = ToolInputPattern.FunctionPage
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    summary = "s"
                    input = WasmInputClass.with("BrokenFunctionPageInput") {
                        int("offset").describe("Offset")
                        int("limit", default = 1).describe("Limit")
                    }
                    inputPattern = ToolInputPattern.FunctionPage
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    summary = "s"
                    input = WasmInputClass.with("BrokenFunctionPageInput") {
                        int("offset", default = 0)
                        int("limit", default = 1).describe("Limit")
                    }
                    inputPattern = ToolInputPattern.FunctionPage
                    output = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
    }

    @Test
    fun `tool without output is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    summary = "s"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    handlerFqn = sampleHandler
                }
            }
        }
    }
}

package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
    fun `every tool declares summary, input, output, handler`() {
        for (spec in HexanaTools) {
            assertTrue(spec.summary.isNotBlank(), "summary blank for ${spec.name}")
            assertTrue(spec.handlerFqn.isNotBlank(), "handlerFqn blank for ${spec.name}")
            assertTrue(spec.input.fields.isNotEmpty(), "empty input class for ${spec.name}")
            assertTrue(spec.output.fields.isNotEmpty() || spec.output.name == "DataSegmentLinkingMetadataResult",
                "empty output class for ${spec.name}")
        }
    }

    @Test
    fun `wasmInputClass carries a oneOf constraint`() {
        assertEquals(listOf("path", "bytesBase64", "bytes"), WasmInputClass.oneOf)
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
                    summary = "s"; input = WasmInputClass; output = WasmInputClass; handlerFqn = sampleHandler
                }
                tool("dup") {
                    summary = "t"; input = WasmInputClass; output = WasmInputClass; handlerFqn = sampleHandler
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
    fun `tool without output is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    summary = "s"
                    input = WasmInputClass
                    handlerFqn = sampleHandler
                }
            }
        }
    }
}

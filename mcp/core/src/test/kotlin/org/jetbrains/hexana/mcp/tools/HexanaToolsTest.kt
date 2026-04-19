package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HexanaToolsTest {

    private val sampleHandler = HexanaTools.first().handler

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
    fun `every tool declares a summary and a handler`() {
        for (spec in HexanaTools) {
            assertTrue(spec.summary.isNotBlank(), "summary blank for ${spec.name}")
            assertNotNull(spec.handler, "handler null for ${spec.name}")
        }
    }

    @Test
    fun `list_functions declares offset and limit int parameters`() {
        val spec = HexanaTools.single { it.name == "list_functions" }
        val params = spec.input.parameters.filterIsInstance<InputParameter.IntParam>()
        assertEquals(listOf("offset", "limit"), params.map { it.name })
        assertEquals(0, params[0].default)
        assertEquals(200, params[1].default)
    }

    @Test
    fun `functions_for_indices declares an intList named indices`() {
        val spec = HexanaTools.single { it.name == "functions_for_indices" }
        val params = spec.input.parameters.filterIsInstance<InputParameter.IntListParam>()
        assertEquals(listOf("indices"), params.map { it.name })
        assertEquals(1, params.single().minSize)
    }

    @Test
    fun `list_exported_functions declares a closed range on entriesRange`() {
        val spec = HexanaTools.single { it.name == "list_exported_functions" }
        val params = spec.input.parameters.filterIsInstance<InputParameter.ClosedRangeParam>()
        assertEquals(listOf("entriesRange"), params.map { it.name })
    }

    @Test
    fun `duplicate tool names are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("dup") { summary = "s"; input { wasmModule() }; handler = sampleHandler }
                tool("dup") { summary = "t"; input { wasmModule() }; handler = sampleHandler }
            }
        }
    }

    @Test
    fun `input block without wasmModule is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    summary = "bad"
                    input { /* missing wasmModule() */ }
                    handler = sampleHandler
                }
            }
        }
    }

    @Test
    fun `tool without summary is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("broken") {
                    input { wasmModule() }
                    handler = sampleHandler
                }
            }
        }
    }
}

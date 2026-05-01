package org.jetbrains.hexana.mcp.direct

class HexanaMcpRegistrationTest : HexanaMcpToolsetDirectTestBase() {
    fun testRegistersExpectedToolNames() {
        val expectedHexanaTools = setOf(
            "list_imports",
            "list_custom_sections",
            "list_functions",
            "list_code_section_offsets",
            "resolve_function_signature",
            "disassemble_function",
            "search_functions_by_name",
            "list_data",
            "list_exports",
            "search_exports",
            "list_exported_functions",
            "functions_for_indices",
            "list_globals",
            "list_memory",
            "get_globals_for_indices",
            "list_data_segments",
            "get_memory_for_indices",
            "list_types",
            "list_value_types",
            "get_types_for_indices",
            "get_locals_for_functions",
            "get_instructions_for_functions",
            "list_element_segments",
            "summarize_module"
        )

        assertTrue(toolNames().containsAll(expectedHexanaTools))
    }
}

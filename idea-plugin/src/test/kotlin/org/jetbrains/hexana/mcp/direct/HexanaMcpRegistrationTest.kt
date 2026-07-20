package org.jetbrains.hexana.mcp.direct

import org.jetbrains.hexana.mcp.HexanaToolset

class HexanaMcpRegistrationTest : HexanaMcpToolsetDirectTestBase() {
    fun testRegistersExpectedToolNames() {
        val hexanaNames = HexanaToolset().getTools().map { it.descriptor.name }

        assertEquals(expectedHexanaToolNames, hexanaNames)
        assertEquals(29, hexanaNames.size)
        assertFalse("query_artifacts" in hexanaNames)
        assertFalse("query_binary" in hexanaNames)
        assertFalse("list_binary_functions" in hexanaNames)
    }

    private companion object {
        val expectedHexanaToolNames = listOf(
            "summarize_binary", "summarize_module", "list_enabled_features", "get_entrypoint_policy",
            "list_required_proposals", "diff_required_proposals", "check_runtime_compatibility",
            "check_component_compatibility", "plan_abi_binding_regeneration", "check_memory_contract",
            "audit_global_state_policy", "audit_local_stack_budget", "list_imports", "list_exports",
            "search_exports", "list_exported_functions", "list_functions", "resolve_function_signature",
            "disassemble_function", "get_function_call_graph", "search_functions_by_name", "resolve_wasm_frames",
            "resolve_function_symbols", "resolve_source_locations", "list_memory", "list_globals",
            "list_element_segments", "list_data_segments", "audit_data_payload_policy",
        )
    }
}

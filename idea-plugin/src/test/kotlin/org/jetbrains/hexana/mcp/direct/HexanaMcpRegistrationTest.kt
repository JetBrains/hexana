package org.jetbrains.hexana.mcp.direct

class HexanaMcpRegistrationTest : HexanaMcpToolsetDirectTestBase() {
    fun testRegistersExpectedToolNames() {
        val names = toolNames()
        assertTrue("query_artifacts" in names)
        assertTrue("get_entrypoint_policy" in names)
        assertTrue("summarize_module" in names)
        assertTrue("list_required_proposals" in names)
        assertTrue("diff_required_proposals" in names)
        assertTrue("check_runtime_compatibility" in names)
        assertTrue("list_imports" in names)
        assertTrue("list_exports" in names)
        assertTrue("list_functions" in names)
        assertTrue("list_memory" in names)
        assertTrue("check_memory_contract" in names)
        assertTrue("audit_global_state_policy" in names)
        assertTrue("list_globals" in names)
        assertTrue("list_types" in names)
        assertTrue("list_element_segments" in names)
        assertTrue("list_data_segments" in names)
        assertTrue("repair_host_adapter_config" in names)
    }
}

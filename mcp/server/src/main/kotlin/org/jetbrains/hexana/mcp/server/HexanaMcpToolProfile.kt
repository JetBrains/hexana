package org.jetbrains.hexana.mcp.server

enum class HexanaMcpToolProfile {
    PRODUCTION,
    ADVANCED;

    fun cliValue(): String = name.lowercase()

    internal fun includesTool(toolName: String): Boolean {
        return when (this) {
            PRODUCTION -> toolName in PRODUCTION_TOOL_NAMES
            ADVANCED -> true
        }
    }

    companion object {
        private val ALIASES = mapOf(
            "default" to PRODUCTION,
            "production" to PRODUCTION,
            "advanced" to ADVANCED,
            "debug" to ADVANCED,
            "full" to ADVANCED,
        )

        fun parseCliValue(value: String): HexanaMcpToolProfile {
            return ALIASES[value.lowercase()]
                ?: throw IllegalArgumentException("Unsupported MCP tool profile: $value")
        }
    }
}

private val PRODUCTION_TOOL_NAMES = setOf(
    "audit_data_payload_policy",
    "audit_global_state_policy",
    "check_memory_contract",
    "check_component_compatibility",
    "check_runtime_compatibility",
    "diff_required_proposals",
    "disassemble_function",
    "get_entrypoint_policy",
    "get_function_call_graph",
    "list_data_segments",
    "list_element_segments",
    "list_enabled_features",
    "list_exported_functions",
    "list_exports",
    "list_functions",
    "list_globals",
    "list_imports",
    "list_memory",
    "list_required_proposals",
    "plan_abi_binding_regeneration",
    "resolve_function_symbols",
    "resolve_function_signature",
    "resolve_source_locations",
    "resolve_wasm_frames",
    "search_exports",
    "search_functions_by_name",
    "summarize_module",
)

package org.jetbrains.hexana.mcp.direct

class HexanaMcpRegistrationTest : HexanaMcpToolsetDirectTestBase() {
    fun testRegistersExpectedToolNames() {
        val names = toolNames()
        assertTrue("query_artifacts" in names)
        assertFalse("summarize_module" in names)
        assertFalse("list_imports" in names)
    }
}

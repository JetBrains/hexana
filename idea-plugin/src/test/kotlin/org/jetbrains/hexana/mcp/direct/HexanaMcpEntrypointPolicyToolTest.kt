package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.jsonObject

class HexanaMcpEntrypointPolicyToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testGetEntrypointPolicyReturnsVerifierReadyShape() {
        val fixture = createWasmFixture(
            "dist/runtime/entrypoints.wasm",
            """
            (module
              (import "env" "log_i32" (func ${'$'}log_i32 (param i32)))
              (import "env" "seed" (func ${'$'}seed (result i64)))
              (func (export "notify_host") (param i32)
                local.get 0
                call ${'$'}log_i32)
              (func (export "read_seed") (result i64)
                call ${'$'}seed)
              (func ${'$'}host_helper (param i32)
                local.get 0
                call ${'$'}log_i32)
              (func (export "transitive_log") (param i32)
                local.get 0
                call ${'$'}host_helper))
            """.trimIndent()
        )

        val result = callToolJsonObject("get_entrypoint_policy", jsonPath(fixture.projectRelativePath))

        assertEquals("entrypoint-host-call-policy-v2", result.text("policy_id"))
        assertEquals(fixture.projectRelativePath, result.text("module"))
        assertEquals(listOf("notify_host", "read_seed"), result.array("direct_host_entrypoints").map {
            it.jsonObject.text("export")
        })
        assertEquals(listOf("transitive_log"), result.array("excluded_transitive_exports").map { it.textValue() })

        val notify = result.array("direct_host_entrypoints").obj(0)
        assertEquals("notify_host", notify.text("export"))
        assertEquals(2, notify.int("funcidx"))
        assertEquals(listOf("i32"), notify.obj("signature").array("params").map { it.textValue() })
        assertEquals(0, notify.obj("signature").array("results").size)

        val calledImport = notify.array("called_imports").obj(0)
        assertEquals("env", calledImport.text("module"))
        assertEquals("log_i32", calledImport.text("name"))
        assertEquals(listOf("i32"), calledImport.obj("signature").array("params").map { it.textValue() })
        assertEquals(0, calledImport.obj("signature").array("return").size)
    }
}

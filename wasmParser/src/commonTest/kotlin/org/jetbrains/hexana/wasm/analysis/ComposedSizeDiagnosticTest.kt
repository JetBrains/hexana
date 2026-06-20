package org.jetbrains.hexana.wasm.analysis

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.components.WasmComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression test on the real composed component: its code lives entirely in MULTIPLE nested
 * sub-components (no direct core modules). The size tree must surface all of them, each with a byte
 * slice that lazily expands into a non-empty breakdown (the panel builds it on demand).
 */
class ComposedSizeDiagnosticTest {

    @Test
    fun `composed_wasm size tree surfaces all nested components, lazily expandable`() = runTest {
        val buffer = resourceAsByteBuffer("component/composed.wasm")
        val component = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmComponent

        val root = SizeAnalysisBuilder(buffer, component).buildTree()
        val embedded = root.children.filterIsInstance<SizeNode.EmbeddedComponent>()

        // composed.wasm has four top-level nested sub-components and no direct core modules.
        assertEquals(4, embedded.size)
        assertEquals(0, root.children.filterIsInstance<SizeNode.EmbeddedModule>().size)
        assertTrue(embedded.all { it.slice != null }, "each nested component carries its slice")

        // Lazily expanding each one (as PanelSize does) must yield a non-empty breakdown.
        for (node in embedded) {
            val slice = node.slice!!
            val sub = (WasmParser().parse(slice) as? WasmParseSuccess)?.wasmModule
            assertIs<WasmComponent>(sub)
            assertTrue(
                SizeAnalysisBuilder(slice, sub).buildTree().children.isNotEmpty(),
                "nested component @0x${node.offset.toString(16)} expands to children",
            )
        }
    }
}

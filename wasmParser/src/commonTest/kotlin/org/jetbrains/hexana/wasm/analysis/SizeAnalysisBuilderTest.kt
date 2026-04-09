package org.jetbrains.hexana.wasm.analysis

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.components.CoreWasmComponent
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SizeAnalysisBuilderTest {

    @Test
    fun `buildTree returns module with sections for core wasm`() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)

        val builder = SizeAnalysisBuilder(buffer, result.wasmModule)
        val tree = builder.buildTree()

        assertTrue(tree.children.isNotEmpty(), "Module should have section children")
        assertTrue(tree.size > 0, "Module size should be positive")

        // Verify Code section exists
        val codeSection = tree.children.filterIsInstance<SizeNode.Section>()
            .find { it.sectionId == WasmSectionCode.SECTION_ID.toInt() }
        assertTrue(codeSection != null, "Code section should be present")
    }

    @Test
    fun `section sizes sum approximately to module size`() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)

        val builder = SizeAnalysisBuilder(buffer, result.wasmModule)
        val tree = builder.buildTree()

        val childSizeSum = tree.children.sumOf { it.size }
        // Child sizes should not exceed module size (they exclude the 8-byte header)
        assertTrue(
            childSizeSum <= tree.size,
            "Sum of child sizes ($childSizeSum) should not exceed module size (${tree.size})"
        )
        // Child sizes should cover most of the module (at least 80%, allowing for header overhead)
        val ratio = childSizeSum.toDouble() / tree.size
        assertTrue(
            ratio > 0.8,
            "Child sizes should cover most of the module (ratio=$ratio)"
        )
    }

    @Test
    fun `code section contains function children`() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)

        val builder = SizeAnalysisBuilder(buffer, result.wasmModule)
        val tree = builder.buildTree()

        val codeSection = tree.children.filterIsInstance<SizeNode.Section>()
            .find { it.sectionId == WasmSectionCode.SECTION_ID.toInt() }
        assertIs<SizeNode.Section>(codeSection)

        val functions = codeSection.children.filterIsInstance<SizeNode.Function>()
        assertTrue(functions.isNotEmpty(), "Code section should have Function children")

        // Verify function indices are sequential
        functions.forEachIndexed { index, func ->
            assertTrue(
                func.funcIndex == index,
                "Function at position $index should have funcIndex=$index, got ${func.funcIndex}"
            )
        }

        // Verify each function has positive size
        functions.forEach { func ->
            assertTrue(func.size > 0, "Function '${func.name}' should have positive size")
        }
    }
}
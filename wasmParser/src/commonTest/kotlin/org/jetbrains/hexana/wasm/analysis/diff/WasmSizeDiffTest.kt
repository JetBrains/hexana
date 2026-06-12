package org.jetbrains.hexana.wasm.analysis.diff

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasmSizeDiffTest {

    private fun functions(tree: SizeDiffNode) = tree.flatten().filter { it.kind == NodeKind.FUNCTION }

    private fun countFns(tree: SizeDiffNode, cls: DiffClass) = functions(tree).count { it.classification == cls }

    // --- Algorithm tests on hand-built trees (deterministic, no parsing) ---

    @Test
    fun `added function is isolated, others identical`() {
        val base = SizeNode.Module(
            "module", 100, 0,
            listOf(
                SizeNode.Section(
                    "code", 50, 8, WasmSectionCode.SECTION_ID.toInt(),
                    listOf(
                        SizeNode.Function("func 0", 25, 8, 0),
                        SizeNode.Function("func 1", 25, 33, 1),
                    ),
                ),
            ),
        )
        val head = SizeNode.Module(
            "module", 130, 0,
            listOf(
                SizeNode.Section(
                    "code", 80, 8, WasmSectionCode.SECTION_ID.toInt(),
                    listOf(
                        SizeNode.Function("func 0", 25, 8, 0),
                        SizeNode.Function("func 1", 25, 33, 1),
                        SizeNode.Function("func 2", 30, 58, 2),
                    ),
                ),
            ),
        )

        val diff = WasmSizeDiff.diff(base, head)

        assertEquals(DiffClass.MODIFIED, diff.classification, "module grew, must be MODIFIED")
        assertEquals(30, diff.delta)
        assertEquals(1, countFns(diff, DiffClass.ADDED))
        assertEquals(0, countFns(diff, DiffClass.REMOVED))
        assertEquals(2, countFns(diff, DiffClass.IDENTICAL))

        val summary = diff.summarize()
        assertEquals(30, summary.totalDelta)
        val code = summary.sections.single { it.sectionId == WasmSectionCode.SECTION_ID.toInt() }
        assertEquals(1, code.addedFns)
        assertEquals(0, code.removedFns)
    }

    @Test
    fun `removed function is isolated`() {
        val base = SizeNode.Module(
            "module", 130, 0,
            listOf(
                SizeNode.Section(
                    "code", 80, 8, WasmSectionCode.SECTION_ID.toInt(),
                    listOf(
                        SizeNode.Function("func 0", 25, 8, 0),
                        SizeNode.Function("func 1", 25, 33, 1),
                        SizeNode.Function("func 2", 30, 58, 2),
                    ),
                ),
            ),
        )
        val head = SizeNode.Module(
            "module", 100, 0,
            listOf(
                SizeNode.Section(
                    "code", 50, 8, WasmSectionCode.SECTION_ID.toInt(),
                    listOf(
                        SizeNode.Function("func 0", 25, 8, 0),
                        SizeNode.Function("func 1", 25, 33, 1),
                    ),
                ),
            ),
        )

        val diff = WasmSizeDiff.diff(base, head)

        assertEquals(-30, diff.delta)
        assertEquals(1, countFns(diff, DiffClass.REMOVED))
        assertEquals(0, countFns(diff, DiffClass.ADDED))
        assertEquals(2, countFns(diff, DiffClass.IDENTICAL))
    }

    @Test
    fun `size change with same key is MODIFIED, not added-removed`() {
        val base = SizeNode.Module(
            "module", 50, 0,
            listOf(
                SizeNode.Section(
                    "code", 42, 8, WasmSectionCode.SECTION_ID.toInt(),
                    listOf(SizeNode.Function("func 0", 25, 8, 0)),
                ),
            ),
        )
        val head = SizeNode.Module(
            "module", 60, 0,
            listOf(
                SizeNode.Section(
                    "code", 52, 8, WasmSectionCode.SECTION_ID.toInt(),
                    listOf(SizeNode.Function("func 0", 35, 8, 0)),
                ),
            ),
        )

        val diff = WasmSizeDiff.diff(base, head)

        assertEquals(1, countFns(diff, DiffClass.MODIFIED))
        assertEquals(0, countFns(diff, DiffClass.ADDED))
        assertEquals(0, countFns(diff, DiffClass.REMOVED))
        val fn = functions(diff).single()
        assertEquals(25, fn.sizeA)
        assertEquals(35, fn.sizeB)
        assertEquals(10, fn.delta)
    }

    // --- Real-fixture tests ---

    @Test
    fun `self-diff of a real module is all identical`() = runTest {
        suspend fun tree(): SizeNode.Module {
            val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
            val result = WasmParser().parse(buffer)
            assertIs<WasmParseSuccess>(result)
            return SizeAnalysisBuilder(buffer, result.wasmModule).buildTree()
        }

        val diff = WasmSizeDiff.diff(tree(), tree())

        assertEquals(DiffClass.IDENTICAL, diff.classification)
        assertEquals(0, diff.delta)
        val flat = diff.flatten()
        assertTrue(flat.isNotEmpty())
        assertTrue(
            flat.all { it.classification == DiffClass.IDENTICAL },
            "every node of a self-diff must be IDENTICAL",
        )
        assertEquals(0, diff.summarize().totalDelta)
    }

    @Test
    fun `diff of two different skiko builds reports changes`() = runTest {
        suspend fun tree(name: String): SizeNode.Module {
            val buffer = resourceAsByteBuffer(name)
            val result = WasmParser().parse(buffer)
            assertIs<WasmParseSuccess>(result)
            return SizeAnalysisBuilder(buffer, result.wasmModule).buildTree()
        }

        val diff = WasmSizeDiff.diff(tree("11449.skiko.wasm"), tree("1381.skiko.wasm"))

        val summary = diff.summarize()
        assertTrue(summary.totalDelta != 0, "two different builds should differ in size")
        assertTrue(
            summary.sections.any { it.delta != 0 },
            "at least one section should differ",
        )
        val changedFns = countFns(diff, DiffClass.ADDED) +
            countFns(diff, DiffClass.REMOVED) +
            countFns(diff, DiffClass.MODIFIED)
        assertTrue(changedFns > 0, "expected some changed functions, got $changedFns")
    }
}

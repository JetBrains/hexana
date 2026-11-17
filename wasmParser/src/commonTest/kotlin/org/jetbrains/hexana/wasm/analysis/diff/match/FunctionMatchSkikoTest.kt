package org.jetbrains.hexana.wasm.analysis.diff.match

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** End-to-end matcher run on a real (thousands-of-functions) module. */
class FunctionMatchSkikoTest {

    private val fixture = "skiko.wasm"

    private suspend fun descriptors(): List<FunctionDescriptor> {
        // Each section read gets a fresh position-0 duplicate, mirroring WasmBinaryProvider:
        // the section scans seek to absolute offsets, but the parse-consumed buffer must
        // not be shared across reads.
        val original = resourceAsByteBuffer(fixture)
        val result = WasmParser().parse(original.duplicate())
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule as WasmModule

        val importFuncCount = module.importSection?.let { section ->
            with(section) { original.duplicate().getImportRecords() }
        }?.count { it.type is WasmTypeIndexExternalType } ?: 0

        val names: Map<Int, String> = module.getCustomSectionByName("name")?.let { custom ->
            with(custom) { original.duplicate().parseAsNameSection() }.functionSubsection?.namemap
        }.orEmpty()

        val codeRecords = module.codeSection?.let { section ->
            with(section) { original.duplicate().scan() }
        }.orEmpty()

        // Name-aware callee tokens so calls discriminate (mirrors WasmFunctionMatchProvider).
        val ctx = object : NormalizerContext {
            override fun calleeToken(funcIdx: Int): Long = names[funcIdx]?.let { FnvHash().update(it).value() } ?: 0L
            override fun typeArity(typeIdx: Int): Int = 0
            override fun globalToken(globalIdx: Int): Long = 0L
        }

        val normalizer = FunctionNormalizer()
        return codeRecords.mapIndexed { codeIdx, record ->
            val globalIdx = importFuncCount + codeIdx
            val normalized = normalizer.normalize(record.opCodes, 0, 0, 0L, ctx)
            FunctionDescriptor(codeIdx, globalIdx, names[globalIdx], normalized.hash, normalized.fingerprint)
        }
    }

    @Test
    fun `self-diff of a real module pairs nearly everything with no false changes`() = runTest {
        val left = descriptors()
        val right = descriptors()
        assertTrue(left.size > 100, "expected a substantial module, got ${left.size} functions")

        val result = FunctionMatcher.match(left, right)

        // Self-diff: nothing can be genuinely modified or moved.
        assertEquals(0, result.matches.count { it.classification == DiffClass.MODIFIED })
        assertEquals(0, result.matches.count { it.classification == DiffClass.MOVED })

        // The vast majority must pair (exact hash + name anchor); a few anonymous
        // hash-collision stubs may remain for later passes.
        val unmatched = result.unmatchedLeft.size
        val total = left.size
        assertTrue(
            unmatched <= total / 20,
            "self-diff left $unmatched/$total unmatched (> 5%) — matching too weak",
        )
        assertEquals(result.unmatchedLeft.size, result.unmatchedRight.size)
    }
}

package org.jetbrains.hexana.ui

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.typeDisplayName
import org.jetbrains.hexana.ui.panel.TypeGraphFilter
import org.jetbrains.hexana.ui.panel.assignTypeLayers
import org.jetbrains.hexana.ui.panel.unionWithExpansions
import org.jetbrains.hexana.wasm.analysis.typegraph.computeTypeNeighborhood
import java.io.File

/**
 * Off-EDT pipeline performance measurement for the WasmGC Type Graph panel.
 *
 * Loads the Kotlin/Wasm hello.wasm fixture (hundreds of GC types, deep rec groups),
 * finds all typeIdx values whose display name is "$type19", times each pipeline stage
 * with warmup, and prints results. Assertions are lenient (no timing thresholds).
 */
class WasmTypeGraphPerfTest : UsefulTestCase() {

    private fun loadProvider(): WasmBinaryProvider {
        val file = File("../wasmParser/src/commonTest/resources/kotlin/hello.wasm")
        assertTrue("hello.wasm must exist at ${file.absolutePath}", file.exists())
        return WasmBinaryProvider(file.readBytes().toCommonByteBuffer())
    }

    // -------------------------------------------------------------------
    // Timing helpers
    // -------------------------------------------------------------------

    /** Runs [block] [warmup]+[measured] times; returns median of measured runs in nanoseconds. */
    private inline fun timeMedianNanos(warmup: Int, measured: Int, block: () -> Unit): Long {
        repeat(warmup) { block() }
        val samples = LongArray(measured)
        for (i in 0 until measured) {
            val t0 = System.nanoTime()
            block()
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        return samples[measured / 2]
    }

    private fun Long.toMs(): String = "%.2f ms".format(this / 1_000_000.0)

    // -------------------------------------------------------------------
    // Main perf test
    // -------------------------------------------------------------------

    fun testTypeGraphPipelinePerformance() {
        val provider = loadProvider()
        val index = provider.typeGraphIndex
        assertNotNull("hello.wasm must produce a TypeGraphIndex (has GC types)", index)
        index!!
        assertTrue("hello.wasm must have GC types", index.hasGcTypes)

        // Probe: collect all display names to understand what hello.wasm actually has.
        // hello.wasm is a Kotlin/Wasm module. If it has a full name-section for types,
        // there may be no fallback "$typeN" names. We print the first 30 display names
        // so this information is available in the test log, then look for "$type19" by
        // both exact match and also the fallback path.
        val allNames = (0 until index.typeCount).map { it to provider.typeDisplayName(it) }
        println("[WasmTypeGraphPerfTest] First 30 type display names:")
        allNames.take(30).forEach { (idx, name) -> println("  [$idx] $name") }

        // "$type19" is the name displayed in the panel when the user focuses that type.
        // It may be a name-section name (if the module has named types) OR the fallback
        // "$type<idx>" form. We first look for an exact match of the string "$type19",
        // then fall back to typeIdx=19 if no name matches (which also renders as "$type19"
        // in the panel when there is no name-section entry for that index).
        val matchingByName = (0 until index.typeCount).filter { provider.typeDisplayName(it) == "\$type19" }
        val matchingIndices: List<Int>
        if (matchingByName.isNotEmpty()) {
            matchingIndices = matchingByName
            if (matchingIndices.size > 1) {
                println("[WasmTypeGraphPerfTest] NOTE: display name \$type19 matches ${matchingIndices.size} typeIdx values: $matchingIndices")
                println("[WasmTypeGraphPerfTest] Using first match (typeIdx=${matchingIndices[0]}) as focus.")
            } else {
                println("[WasmTypeGraphPerfTest] \$type19 -> typeIdx=${matchingIndices[0]} (unique by name)")
            }
        } else {
            // hello.wasm has a complete name section; "$type19" is the fallback display name
            // for typeIdx=19 only when no name is recorded. Since all types have names here,
            // "$type19" as a fallback does not exist. We use typeIdx=19 directly — it is a
            // well-defined GC type in this module, and its display name is printed above.
            println("[WasmTypeGraphPerfTest] No type with fallback display name \$type19 found.")
            println("[WasmTypeGraphPerfTest] This means hello.wasm has a full name section (expected for Kotlin/Wasm).")
            println("[WasmTypeGraphPerfTest] Using typeIdx=19 directly (display name: ${provider.typeDisplayName(19)}).")
            matchingIndices = listOf(19)
        }
        assertTrue("typeIdx 19 must be in range", 19 < index.typeCount)

        val focus = matchingIndices[0]
        val totalTypes = index.typeCount
        println("[WasmTypeGraphPerfTest] module total types: $totalTypes")

        // --- Stage 1: computeTypeNeighborhood ---
        val neighborhoodResult = computeTypeNeighborhood(index, focus, includeHierarchy = true, includeFieldRefs = true)
        val neighborhoodNanos = timeMedianNanos(warmup = 5, measured = 10) {
            computeTypeNeighborhood(index, focus, includeHierarchy = true, includeFieldRefs = true)
        }
        println("[WasmTypeGraphPerfTest] computeTypeNeighborhood: ${neighborhoodNanos.toMs()} (median, 5w+10m)")
        println("[WasmTypeGraphPerfTest]   neighborhood node count: ${neighborhoodResult.included.size}")

        // --- Stage 2: unionWithExpansions (no expansions = baseline) ---
        val baseIncluded = neighborhoodResult.included
        val unionNanos0 = timeMedianNanos(warmup = 5, measured = 10) {
            unionWithExpansions(baseIncluded, emptySet(), index, TypeGraphFilter.BOTH)
        }
        println("[WasmTypeGraphPerfTest] unionWithExpansions (0 expansions): ${unionNanos0.toMs()}")

        // --- Stage 3: assignTypeLayers ---
        val includedSet0 = unionWithExpansions(baseIncluded, emptySet(), index, TypeGraphFilter.BOTH)
        val includedArr0 = includedSet0.toIntArray().also { it.sort() }
        val layersNanos0 = timeMedianNanos(warmup = 5, measured = 10) {
            assignTypeLayers(includedArr0, index)
        }
        println("[WasmTypeGraphPerfTest] assignTypeLayers (${includedArr0.size} nodes): ${layersNanos0.toMs()}")

        // Summary for focus with 0 expansions
        val edgeCount0 = countEdges(includedSet0, index)
        println("[WasmTypeGraphPerfTest] --- focus + 0 expansions: nodes=${includedArr0.size} edges=$edgeCount0 ---")

        // --- Expansion scaling: grow expandedTypes with 1, 3, 5 real neighbors ---
        val neighborIds = baseIncluded.toList().filter { it != focus }.take(5)
        val expansionSizes = listOf(1, 3, 5)
        for (expSize in expansionSizes) {
            val expanded = neighborIds.take(expSize).toSet()
            if (expanded.size < expSize) {
                println("[WasmTypeGraphPerfTest] skip expansion=$expSize: not enough neighbors (have ${expanded.size})")
                continue
            }

            val uNanos = timeMedianNanos(warmup = 5, measured = 10) {
                unionWithExpansions(baseIncluded, expanded, index, TypeGraphFilter.BOTH)
            }
            val includedSetN = unionWithExpansions(baseIncluded, expanded, index, TypeGraphFilter.BOTH)
            val includedArrN = includedSetN.toIntArray().also { it.sort() }

            val lNanos = timeMedianNanos(warmup = 5, measured = 10) {
                assignTypeLayers(includedArrN, index)
            }
            val edgeCountN = countEdges(includedSetN, index)
            println(
                "[WasmTypeGraphPerfTest] --- expansion=$expSize: " +
                    "nodes=${includedArrN.size} edges=$edgeCountN " +
                    "union=${uNanos.toMs()} layers=${lNanos.toMs()} ---",
            )
        }

        // --- Lenient assertions ---
        assertTrue("neighborhood must include at least the focus node", neighborhoodResult.included.isNotEmpty())
        assertTrue("included set must include the focus", focus in includedSet0)
        assertTrue("layering must assign a layer to every included node", assignTypeLayers(includedArr0, index).size == includedArr0.size)
    }

    /** Counts directed edges in the included set (subtype + field-ref). */
    private fun countEdges(included: Set<Int>, index: org.jetbrains.hexana.wasm.analysis.typegraph.TypeGraphIndex): Int {
        var count = 0
        for (t in included) {
            for (sub in index.directSubtypes(t)) if (sub in included) count++
            for (ref in index.fieldRefs(t)) if (ref in included && ref != t) count++
        }
        return count
    }
}

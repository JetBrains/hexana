package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM-only corpus harness: runs the production parse path over every .wasm
 * binary in the two pre-extracted corpus directories and writes a baseline CSV
 * to /tmp/hexana-corpus-baseline2.csv.
 *
 * valid/   — fully valid binaries (target: zero ParseProblems, zero throws)
 * invalid/ — validation-invalid or binary-malformed binaries
 *
 * Per file, inside a 10-second timeout (single-thread Executor +
 * Future.get(10, SECONDS)): construct WasmBinaryProvider, read parseProblems,
 * then force EVERY public lazy property on the provider — malformed sections
 * in unguarded lazies throw only when accessed, so parseProblems alone
 * undercounts.
 *
 * Verdicts (exactly one per file):
 *   uncaught-exception — ANY property access threw; detail lists
 *                        property=exceptionClass: message for each throw;
 *                        `property` column = first throwing property
 *   problems-reported  — no throws, parseProblems non-empty; detail = kinds
 *   clean              — no throws, parseProblems empty
 *   timeout            — did not complete within 10 s
 *
 * CSV: file, dir, verdict, detail, property
 */
class CorpusHarnessTest {

    @Test
    fun runCorpusHarness() {
        // The corpus is a local checkout of the extracted WASM spec testsuite; it
        // does not exist on CI. Skipping is allowed ONLY at the directory level;
        // per-file results below are real observations, never graceful skips.
        val corpusRoot = System.getenv("HEXANA_CORPUS_DIR").orEmpty()
        val validDir = File(corpusRoot, "valid")
        val invalidDir = File(corpusRoot, "invalid")

        org.junit.Assume.assumeTrue(
            "Corpus directories absent under '$corpusRoot'; set HEXANA_CORPUS_DIR or check out the testsuite",
            corpusRoot.isNotEmpty() && validDir.exists() && invalidDir.exists(),
        )

        val csvOut = File("/tmp/hexana-corpus-baseline2.csv")
        val executor = Executors.newSingleThreadExecutor()

        data class Row(
            val file: String,
            val dir: String,
            val verdict: String,
            val detail: String,
            val property: String = "",
        )

        // Every public lazy property on WasmBinaryProvider, forced in
        // dependency-ish order (cheap sections first, derived analyses last).
        val propertyAccessors: List<Pair<String, (WasmBinaryProvider) -> Any?>> = listOf(
            "parsedModule" to { p -> p.parsedModule },
            "importFuncCount" to { p -> p.importFuncCount },
            "imports" to { p -> p.imports },
            "exports" to { p -> p.exports },
            "data" to { p -> p.data },
            "functions" to { p -> p.functions },
            "codeOffsets" to { p -> p.codeOffsets },
            "codeRecords" to { p -> p.codeRecords },
            "nameSection" to { p -> p.nameSection },
            "sourceMappingUrl" to { p -> p.sourceMappingUrl },
            "sourceLocationProvider" to { p -> p.sourceLocationProvider },
            "structureRanges" to { p -> p.structureRanges },
            "graalWebImageInfo" to { p -> p.graalWebImageInfo },
            "kotlinWasmInfo" to { p -> p.kotlinWasmInfo },
            "sizeAnalysisBuilder" to { p -> p.sizeAnalysisBuilder },
            "sizeAnalysis" to { p -> p.sizeAnalysis },
            "callGraphResult" to { p -> p.callGraphResult },
            "callGraphDominators" to { p -> p.callGraphDominators },
            "dispatchCandidates" to { p -> p.dispatchCandidates },
            "exceptionTags" to { p -> p.exceptionTags },
            "exceptionPropagation" to { p -> p.exceptionPropagation },
            "monosAnalysis" to { p -> p.monosAnalysis },
            "garbageAnalysis" to { p -> p.garbageAnalysis },
            "usedProposals" to { p -> p.usedProposals },
            "minification" to { p -> p.minification },
        )

        fun throwSignature(t: Throwable): String {
            val cls = t.javaClass.name
            val msg = t.message?.take(100)?.replace(",", ";")?.replace("\n", " ") ?: ""
            val frame = t.stackTrace
                .firstOrNull { it.className.startsWith("org.jetbrains.hexana") }
                ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
                ?: t.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
                ?: ""
            return "$cls: $msg @ $frame"
        }

        val rows = mutableListOf<Row>()

        fun processDir(dir: File) {
            val files = dir.listFiles { f -> f.extension == "wasm" }!!
                .sortedBy { it.name }
            for (f in files) {
                val future: Future<Row> = executor.submit<Row> {
                    val throwing = mutableListOf<Pair<String, String>>() // property -> signature
                    var kinds = ""
                    var provider: WasmBinaryProvider? = null
                    try {
                        provider = WasmBinaryProvider(f.readBytes().toCommonByteBuffer())
                    } catch (t: Throwable) {
                        throwing.add("<constructor>" to throwSignature(t))
                    }
                    if (provider != null) {
                        try {
                            val problems = provider.parseProblems
                            kinds = problems.map { it.kind.name }.distinct().sorted().joinToString("|")
                        } catch (t: Throwable) {
                            throwing.add("parseProblems" to throwSignature(t))
                        }
                        for ((name, accessor) in propertyAccessors) {
                            try {
                                accessor(provider)
                            } catch (t: Throwable) {
                                throwing.add(name to throwSignature(t))
                            }
                        }
                    }
                    when {
                        throwing.isNotEmpty() -> Row(
                            f.name, dir.name, "uncaught-exception",
                            throwing.joinToString(" || ") { (p, sig) -> "$p=$sig" },
                            throwing.first().first,
                        )
                        kinds.isNotEmpty() -> Row(f.name, dir.name, "problems-reported", kinds)
                        else -> Row(f.name, dir.name, "clean", "")
                    }
                }
                val row = try {
                    future.get(10, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    Row(f.name, dir.name, "timeout", "")
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    Row(f.name, dir.name, "uncaught-exception", "<submit>=${throwSignature(cause)}", "<submit>")
                }
                rows.add(row)
            }
        }

        processDir(validDir)
        processDir(invalidDir)

        executor.shutdown()

        // Write CSV
        fun csvField(s: String): String =
            if (s.contains(',') || s.contains('"')) "\"${s.replace("\"", "\"\"")}\"" else s
        PrintWriter(csvOut).use { w ->
            w.println("file,dir,verdict,detail,property")
            for (r in rows) {
                w.println("${r.file},${r.dir},${r.verdict},${csvField(r.detail)},${csvField(r.property)}")
            }
        }

        // ---- Summary ----
        val validRows = rows.filter { it.dir == "valid" }
        val invalidRows = rows.filter { it.dir == "invalid" }

        fun printVerdictCounts(label: String, subset: List<Row>) {
            println("=== $label (${subset.size} files) ===")
            subset.groupBy { it.verdict }.entries.sortedByDescending { it.value.size }.forEach { (v, rs) ->
                println("  $v: ${rs.size}")
            }
        }

        println()
        printVerdictCounts("valid/", validRows)
        printVerdictCounts("invalid/", invalidRows)

        // Top 10 (property, exception) signatures across all throws
        data class Sig(val property: String, val exceptionClass: String)
        val allThrows = mutableListOf<Triple<Sig, String, Row>>() // sig, full detail, row
        rows.filter { it.verdict == "uncaught-exception" }.forEach { r ->
            r.detail.split(" || ").forEach { entry ->
                val prop = entry.substringBefore('=')
                val cls = entry.substringAfter('=').substringBefore(':').trim()
                allThrows.add(Triple(Sig(prop, cls), entry, r))
            }
        }
        if (allThrows.isNotEmpty()) {
            println()
            println("=== Top 10 (property, exception) signatures ===")
            allThrows.groupBy { it.first }.entries.sortedByDescending { it.value.size }.take(10)
                .forEach { (sig, entries) ->
                    println("  [${entries.size}] ${sig.property} -> ${sig.exceptionClass}")
                    println("        e.g. ${entries.first().second.take(140)}")
                    entries.map { it.third }.distinctBy { it.file }.take(3).forEach { r ->
                        println("        example: ${r.dir}/${r.file}")
                    }
                }
        }

        // Silent-crash cluster: a lazy property threw while the constructor and
        // parseProblems were both fine — files the first baseline called clean
        // or problems-only but that crash on deeper section access.
        val silentCrash = rows.filter { r ->
            r.verdict == "uncaught-exception" && !r.detail.contains("parseProblems=") &&
                !r.detail.contains("<constructor>=")
        }
        println()
        println("=== Silent-crash cluster (property throws, constructor+parseProblems fine) ===")
        println("  count: ${silentCrash.size}")
        silentCrash.groupBy { it.property }.entries.sortedByDescending { it.value.size }.forEach { (p, rs) ->
            println("  first-throwing property $p: ${rs.size}")
            rs.take(3).forEach { r -> println("    ${r.dir}/${r.file}: ${r.detail.take(140)}") }
        }

        // ParseProblemKind counts on valid/
        val validProblems = validRows.filter { it.verdict == "problems-reported" }
        if (validProblems.isNotEmpty()) {
            val kindCounts = mutableMapOf<String, Int>()
            validProblems.forEach { r -> r.detail.split("|").forEach { k -> kindCounts[k] = (kindCounts[k] ?: 0) + 1 } }
            println()
            println("=== ParseProblemKind false positives on valid/ ===")
            kindCounts.entries.sortedByDescending { it.value }.forEach { (k, c) -> println("  $k: $c files") }
            validProblems.take(3).forEach { r -> println("    example: ${r.file}: ${r.detail}") }
        }

        println()
        println("CSV written to: ${csvOut.absolutePath}  (${rows.size} rows)")

        val validCount = validDir.listFiles { f -> f.extension == "wasm" }!!.size
        val invalidCount = invalidDir.listFiles { f -> f.extension == "wasm" }!!.size
        assertEquals(validCount + invalidCount, rows.size,
            "Row count mismatch: expected ${validCount + invalidCount}, got ${rows.size}")
    }
}

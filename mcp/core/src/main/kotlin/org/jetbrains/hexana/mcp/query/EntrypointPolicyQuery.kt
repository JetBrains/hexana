package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.model.entrypoints.EntrypointCalledImportResult
import org.jetbrains.hexana.mcp.model.entrypoints.EntrypointImportSignatureResult
import org.jetbrains.hexana.mcp.model.entrypoints.EntrypointPolicyEntryResult
import org.jetbrains.hexana.mcp.model.entrypoints.EntrypointPolicyResult
import org.jetbrains.hexana.mcp.model.entrypoints.EntrypointSignatureResult
import org.jetbrains.hexana.mcp.model.functions.FunctionCallGraphResult
import org.jetbrains.hexana.mcp.model.functions.FunctionDirectCallResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry

private const val ENTRYPOINT_POLICY_ID = "entrypoint-host-call-policy-v2"

fun WasmBinaryProvider.readEntrypointPolicy(pathLabel: String? = null): EntrypointPolicyResult {
    val exports = exportedLocalFunctions()
    val localGraphsByIndex = readReachableLocalFunctionGraphs(exports.map { it.function.funcidx })
    val graphsByIndex = exports
        .mapNotNull { export -> localGraphsByIndex[export.function.funcidx]?.let { export.function.funcidx to it } }
        .toMap()

    val directHostEntrypoints = exports.mapNotNull { export ->
        graphsByIndex[export.function.funcidx]?.toEntrypointPolicyEntry(export.name)
    }.sortedBy { it.export }
    val excludedTransitiveExports = exports.mapNotNull { export ->
        val graph = graphsByIndex[export.function.funcidx] ?: return@mapNotNull null
        export.name.takeIf {
            graph.directImportedCalls.isEmpty() && graph.reachesImportedCall(localGraphsByIndex)
        }
    }.sorted()

    return EntrypointPolicyResult(
        policyId = ENTRYPOINT_POLICY_ID,
        module = pathLabel.orEmpty(),
        directHostEntrypoints = directHostEntrypoints,
        excludedTransitiveExports = excludedTransitiveExports
    )
}

private fun WasmBinaryProvider.exportedLocalFunctions(): List<ExportedLocalFunction> {
    val functionsByIndex = listFunctionEntries().associateBy { it.funcidx }
    return exports
        .filterIsInstance<WasmFunctionExportRecordEntry>()
        .mapNotNull { export ->
            val function = functionsByIndex[export.index.toInt()] ?: return@mapNotNull null
            ExportedLocalFunction(export.name, function).takeIf { function.import == null }
        }
        .sortedBy { it.name }
}

private fun WasmBinaryProvider.readReachableLocalFunctionGraphs(rootIndices: List<Int>): Map<Int, FunctionCallGraphResult> {
    val localIndices = listFunctionEntries()
        .asSequence()
        .filter { it.import == null }
        .map { it.funcidx }
        .toSet()
    val graphsByIndex = mutableMapOf<Int, FunctionCallGraphResult>()
    val pending = ArrayDeque(rootIndices.filter { it in localIndices }.distinct())

    while (pending.isNotEmpty()) {
        val batch = buildList {
            while (pending.isNotEmpty()) {
                val funcidx = pending.removeFirst()
                if (funcidx !in graphsByIndex && funcidx in localIndices) add(funcidx)
            }
        }
        if (batch.isEmpty()) continue

        for (graph in readFunctionCallGraph(batch).functions) {
            graphsByIndex[graph.funcidx] = graph
            graph.directCalls
                .map { it.targetFuncidx }
                .filter { it in localIndices && it !in graphsByIndex }
                .forEach { pending.addLast(it) }
        }
    }

    return graphsByIndex
}

private fun FunctionCallGraphResult.toEntrypointPolicyEntry(exportName: String): EntrypointPolicyEntryResult? {
    val calledImports = directImportedCalls.toPolicyCalledImports()
    if (calledImports.isEmpty()) return null
    return EntrypointPolicyEntryResult(
        export = exportName,
        funcidx = funcidx,
        signature = EntrypointSignatureResult(
            params = signature?.params.orEmpty(),
            results = signature?.ret.orEmpty()
        ),
        calledImports = calledImports
    )
}

private fun List<FunctionDirectCallResult>.toPolicyCalledImports(): List<EntrypointCalledImportResult> {
    return mapNotNull { call ->
        val import = call.target?.importInfo ?: return@mapNotNull null
        EntrypointCalledImportResult(
            module = import.module,
            name = import.name,
            signature = EntrypointImportSignatureResult(
                params = call.target.signature?.params.orEmpty(),
                result = call.target.signature?.ret.orEmpty()
            )
        )
    }
        .distinctBy { "${it.module}.${it.name}" }
        .sortedWith(compareBy<EntrypointCalledImportResult> { it.module }.thenBy { it.name })
}

private fun FunctionCallGraphResult.reachesImportedCall(
    localGraphsByIndex: Map<Int, FunctionCallGraphResult>
): Boolean {
    val visited = mutableSetOf(funcidx)
    val queue = ArrayDeque<Int>()
    directCalls
        .map { it.targetFuncidx }
        .filter { localGraphsByIndex.containsKey(it) }
        .forEach { queue.addLast(it) }

    while (queue.isNotEmpty()) {
        val targetFuncidx = queue.removeFirst()
        if (!visited.add(targetFuncidx)) continue
        val targetGraph = localGraphsByIndex[targetFuncidx] ?: continue
        if (targetGraph.directImportedCalls.isNotEmpty()) return true
        targetGraph.directCalls
            .map { it.targetFuncidx }
            .filter { localGraphsByIndex.containsKey(it) }
            .forEach { queue.addLast(it) }
    }
    return false
}

private data class ExportedLocalFunction(
    val name: String,
    val function: GeneralFunctionEntry
)

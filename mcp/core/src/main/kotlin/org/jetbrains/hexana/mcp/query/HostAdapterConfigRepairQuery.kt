package org.jetbrains.hexana.mcp.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.encodeHexanaJson
import org.jetbrains.hexana.mcp.model.artifacts.HostAdapterConfigMismatchResult
import org.jetbrains.hexana.mcp.model.artifacts.HostAdapterConfigModuleIdentity
import org.jetbrains.hexana.mcp.model.artifacts.HostAdapterEntrypoint
import org.jetbrains.hexana.mcp.model.artifacts.HostAdapterRepairConfig
import org.jetbrains.hexana.mcp.model.artifacts.HostAdapterSignature
import org.jetbrains.hexana.mcp.model.artifacts.HostAdapterSignatureRepair
import org.jetbrains.hexana.mcp.model.artifacts.RepairHostAdapterConfigResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

private val hostAdapterConfigJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun WasmBinaryProvider.repairHostAdapterConfig(
    configText: String,
    pathLabel: String? = null,
    configPathLabel: String? = null
): RepairHostAdapterConfigResult {
    val warnings = mutableListOf<String>()
    return repairHostAdapterConfigOrCurrentOnly(
        configText = configText,
        pathLabel = pathLabel,
        configPathLabel = configPathLabel,
        terms = emptyList(),
        warnings = warnings
    ).copy(warnings = warnings)
}

internal fun WasmBinaryProvider.repairHostAdapterConfigOrCurrentOnly(
    configText: String?,
    pathLabel: String?,
    configPathLabel: String?,
    terms: List<String>,
    warnings: MutableList<String>
): RepairHostAdapterConfigResult {
    val functions = listFunctionEntries()
    val functionsByIndex = functions.associateBy { it.funcidx }
    val requiredImports = functions
        .filter { it.import != null }
        .map { it.toHostAdapterRequiredImport() }
        .toMap()
        .toSortedMap()
    val entrypointCandidates = readExports().exports
        .filter { it.kind.toInt() == 0 }
        .mapNotNull { export ->
            val function = functionsByIndex[export.externIndex.toInt()] ?: return@mapNotNull null
            if (function.import != null) return@mapNotNull null
            HostAdapterEntrypoint(
                export = export.name,
                params = function.signature?.params.orEmpty(),
                results = function.signature?.ret.orEmpty()
            )
        }
        .sortedBy { it.export }
    val config = configText?.let { parseHostAdapterConfig(it, warnings) }
    if (configText == null) {
        warnings += "`host_adapter_repair` can compare stale adapter JSON when `configPath` points at the active host adapter config."
    }
    val requiredEntrypoint = selectHostAdapterEntrypoint(
        candidates = entrypointCandidates,
        terms = terms,
        configured = config?.entrypoint?.export
    )
    val repairModule = pathLabel ?: config?.module.orEmpty()
    val repairConfig = HostAdapterRepairConfig(
        module = repairModule,
        imports = requiredImports,
        entrypoint = requiredEntrypoint
    )
    val importRepair = hostAdapterImportRepair(
        requiredImports = requiredImports,
        staleImports = config?.imports.orEmpty()
    )
    return RepairHostAdapterConfigResult(
        module = HostAdapterConfigModuleIdentity(
            path = pathLabel,
            configPath = configPathLabel,
            staleModule = config?.module,
            repairModule = repairModule
        ),
        requiredImports = requiredImports,
        requiredEntrypoint = requiredEntrypoint,
        staleImports = config?.imports.orEmpty(),
        staleEntrypoint = config?.entrypoint,
        addedImports = importRepair.addedImports,
        fixedSignatures = importRepair.fixedSignatures,
        removedExtraImports = importRepair.removedExtraImports,
        unchangedImports = importRepair.unchangedImports,
        ignoredConfigFields = config?.ignoredTopLevelFields.orEmpty(),
        configMismatches = hostAdapterConfigMismatches(
            repairModule = repairModule,
            requiredImports = requiredImports,
            requiredEntrypoint = requiredEntrypoint,
            config = config
        ),
        repairConfig = repairConfig
    )
}

private fun parseHostAdapterConfig(text: String, warnings: MutableList<String>): ParsedHostAdapterConfig? {
    val root = runCatching {
        hostAdapterConfigJson.parseToJsonElement(text).jsonObject
    }.getOrElse { error ->
        warnings += "`configPath` did not contain parseable JSON: ${error.message ?: error::class.simpleName.orEmpty()}."
        return null
    }
    val knownFields = setOf("module", "imports", "entrypoint")
    val module = root["module"]?.jsonPrimitive?.content
    val imports = parseHostAdapterImports(root["imports"], warnings)
    val entrypoint = root["entrypoint"]?.jsonObject?.let { entrypoint ->
        HostAdapterEntrypoint(
            export = entrypoint["export"]?.jsonPrimitive?.content.orEmpty(),
            params = entrypoint["params"].stringList(),
            results = entrypoint["results"].stringList()
        )
    }
    return ParsedHostAdapterConfig(
        module = module,
        imports = imports,
        entrypoint = entrypoint,
        ignoredTopLevelFields = root.keys.filter { it !in knownFields }.sorted()
    )
}

private fun parseHostAdapterImports(element: JsonElement?, warnings: MutableList<String>): Map<String, HostAdapterSignature> {
    return when (element) {
        null -> emptyMap()
        is JsonObject -> element.mapValues { (_, value) ->
            parseHostAdapterSignature(value.jsonObject)
        }
        is JsonArray -> element.mapNotNull { item ->
            parseHostAdapterImportEntry(item, warnings)
        }.toMap()
        else -> {
            warnings += "`imports` must be a JSON object or array; got ${element::class.simpleName.orEmpty()}."
            emptyMap()
        }
    }.toSortedMap()
}

private fun parseHostAdapterImportEntry(
    element: JsonElement,
    warnings: MutableList<String>
): Pair<String, HostAdapterSignature>? {
    val obj = element as? JsonObject
    if (obj == null) {
        warnings += "`imports` array contains a non-object entry."
        return null
    }
    val module = obj["module"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    val name = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    if (module == null || name == null) {
        warnings += "`imports` array contains an entry without non-empty `module` and `name`."
        return null
    }
    val signature = (obj["signature"] as? JsonObject) ?: obj
    return "$module.$name" to parseHostAdapterSignature(signature)
}

private fun parseHostAdapterSignature(obj: JsonObject): HostAdapterSignature =
    HostAdapterSignature(
        params = obj["params"].stringList(),
        results = obj["results"].stringList().ifEmpty { obj["return"].stringList() }
    )

private fun JsonElement?.stringList(): List<String> {
    val array = this as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        element.jsonPrimitive.content
    }
}

private fun selectHostAdapterEntrypoint(
    candidates: List<HostAdapterEntrypoint>,
    terms: List<String>,
    configured: String?
): HostAdapterEntrypoint? {
    if (terms.isNotEmpty()) {
        candidates.firstOrNull { candidate ->
            terms.any { term -> candidate.export.lowercase().contains(term) }
        }?.let { return it }
    }
    configured?.let { configuredName ->
        candidates.firstOrNull { it.export == configuredName }?.let { return it }
    }
    return candidates.singleOrNull() ?: candidates.firstOrNull()
}

private fun hostAdapterConfigMismatches(
    repairModule: String,
    requiredImports: Map<String, HostAdapterSignature>,
    requiredEntrypoint: HostAdapterEntrypoint?,
    config: ParsedHostAdapterConfig?
): List<HostAdapterConfigMismatchResult> {
    if (config == null) return emptyList()
    val mismatches = mutableListOf<HostAdapterConfigMismatchResult>()
    mismatches.addIfDifferent("module", JsonPrimitive(repairModule), config.module.toJsonElement())
    requiredEntrypoint?.let { expected ->
        val actual = config.entrypoint
        mismatches.addIfDifferent("entrypoint.export", JsonPrimitive(expected.export), actual?.export.toJsonElement())
        mismatches.addIfDifferent("entrypoint.params", expected.params.toJsonArray(), actual?.params.toJsonArrayOrNull())
        mismatches.addIfDifferent("entrypoint.results", expected.results.toJsonArray(), actual?.results.toJsonArrayOrNull())
    }

    for ((name, expected) in requiredImports) {
        val actual = config.imports[name]
        if (actual == null) {
            mismatches.addIfDifferent("imports.$name", expected.toJsonObject(), null)
        } else {
            mismatches.addIfDifferent("imports.$name.params", expected.params.toJsonArray(), actual.params.toJsonArray())
            mismatches.addIfDifferent("imports.$name.results", expected.results.toJsonArray(), actual.results.toJsonArray())
        }
    }
    for ((name, actual) in config.imports) {
        if (name !in requiredImports) {
            mismatches.addIfDifferent("imports.$name", JsonNull, actual.toJsonObject())
        }
    }
    return mismatches.sortedBy { it.field }
}

private fun hostAdapterImportRepair(
    requiredImports: Map<String, HostAdapterSignature>,
    staleImports: Map<String, HostAdapterSignature>
): HostAdapterImportRepair {
    val addedImports = mutableMapOf<String, HostAdapterSignature>()
    val fixedSignatures = mutableMapOf<String, HostAdapterSignatureRepair>()
    val unchangedImports = mutableMapOf<String, HostAdapterSignature>()
    for ((name, expected) in requiredImports) {
        val actual = staleImports[name]
        when {
            actual == null -> addedImports[name] = expected
            actual == expected -> unchangedImports[name] = expected
            else -> fixedSignatures[name] = HostAdapterSignatureRepair(
                expected = expected,
                actual = actual
            )
        }
    }
    val removedExtraImports = staleImports
        .filterKeys { it !in requiredImports }
        .toSortedMap()
    return HostAdapterImportRepair(
        addedImports = addedImports.toSortedMap(),
        fixedSignatures = fixedSignatures.toSortedMap(),
        removedExtraImports = removedExtraImports,
        unchangedImports = unchangedImports.toSortedMap()
    )
}

private fun MutableList<HostAdapterConfigMismatchResult>.addIfDifferent(
    field: String,
    expected: JsonElement,
    actual: JsonElement?
) {
    val actualOrNull = actual ?: JsonNull
    if (expected != actualOrNull) {
        add(
            HostAdapterConfigMismatchResult(
                field = field,
                expectedJson = encodeHexanaJson(expected),
                actualJson = encodeHexanaJson(actualOrNull)
            )
        )
    }
}

private fun String?.toJsonElement(): JsonElement =
    this?.let { JsonPrimitive(it) } ?: JsonNull

private fun List<String>?.toJsonArrayOrNull(): JsonElement =
    this?.toJsonArray() ?: JsonNull

private fun List<String>.toJsonArray(): JsonElement =
    JsonArray(map(::JsonPrimitive))

private fun HostAdapterSignature.toJsonObject(): JsonObject = buildJsonObject {
    put("params", params.toJsonArray())
    put("results", results.toJsonArray())
}

private fun GeneralFunctionEntry.toHostAdapterRequiredImport(): Pair<String, HostAdapterSignature> {
    val import = requireNotNull(import)
    return "${import.module}.${import.name}" to HostAdapterSignature(
        params = signature?.params.orEmpty(),
        results = signature?.ret.orEmpty()
    )
}

private data class ParsedHostAdapterConfig(
    val module: String?,
    val imports: Map<String, HostAdapterSignature>,
    val entrypoint: HostAdapterEntrypoint?,
    val ignoredTopLevelFields: List<String>
)

private data class HostAdapterImportRepair(
    val addedImports: Map<String, HostAdapterSignature>,
    val fixedSignatures: Map<String, HostAdapterSignatureRepair>,
    val removedExtraImports: Map<String, HostAdapterSignature>,
    val unchangedImports: Map<String, HostAdapterSignature>
)

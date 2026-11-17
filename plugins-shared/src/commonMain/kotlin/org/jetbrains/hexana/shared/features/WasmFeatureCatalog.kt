package org.jetbrains.hexana.shared.features

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** URL the live feature catalog is fetched from. */
const val WASM_FEATURES_URL: String =
    "https://raw.githubusercontent.com/WebAssembly/website/refs/heads/main/features.json"

/**
 * The human-readable feature table [WASM_FEATURES_URL] backs. Shown as the Compatibility
 * tab's source link — a reader chasing a surprising verdict wants the page, not the JSON.
 */
const val WASM_FEATURES_PAGE_URL: String = "https://webassembly.org/features/"

/**
 * How a single runtime stands on a single feature, mirroring `#/definitions/status` in
 * WebAssembly's `features.schema.json`.
 */
sealed class FeatureStatus {
    /** `null` upstream: the feature does not apply to this kind of runtime. */
    object NotApplicable : FeatureStatus()

    /** `true` upstream: supported, but no version is recorded. */
    object Supported : FeatureStatus()

    /** `false` upstream: known not to be supported. */
    object Unsupported : FeatureStatus()

    /** `"flag"` upstream: available only behind a flag. */
    object FlagRequired : FeatureStatus()

    /** A version string upstream: supported from [version] onwards. */
    data class SinceVersion(val version: String) : FeatureStatus()
}

/**
 * One cell of the compatibility matrix. Upstream may wrap any status as `[status, note]`;
 * [note] carries that note, which is worth surfacing verbatim because it usually names the
 * exact flag or build option required.
 */
class FeatureCell(val status: FeatureStatus, val note: String? = null)

/** A feature (proposal) as described by `features.json`. */
class WasmFeature(
    val id: String,
    val description: String,
    val url: String,
    /** Standardization phase: `"1"`..`"5"`, or `"inactive"`. */
    val phase: String,
)

/** One of the categories runtimes are grouped under, in upstream display order. */
class WasmFeatureCategory(val name: String, val queryKey: String, val isDefault: Boolean)

/**
 * [WasmFeatureCategory.queryKey] of the category holding entries that are not execution
 * targets — assemblers, optimizers, language servers. Matched on the query key because that
 * is upstream's stable identifier; [TOOLS_CATEGORY_NAME] covers a catalog that omits the
 * `categories` array altogether, which the fixtures do.
 */
private const val TOOLS_QUERY_KEY = "tools"

private const val TOOLS_CATEGORY_NAME = "Tools"

/**
 * A runtime, browser or tool. Upstream keeps all of them under the `browsers` object and
 * distinguishes them by [categories]; the first category is the primary one.
 */
class WasmRuntimeInfo(
    val id: String,
    val url: String?,
    val categories: List<String>,
    val cells: Map<String, FeatureCell>,
) {
    val primaryCategory: String? get() = categories.firstOrNull()

    /**
     * The versions this runtime records a feature landing in, newest first. Offered as
     * suggestions in the settings UI; the field also accepts a version not listed here.
     */
    val knownVersions: List<String> by lazy {
        cells.values
            .mapNotNull { (it.status as? FeatureStatus.SinceVersion)?.version }
            .distinct()
            .sortedWith { a, b -> compareRuntimeVersions(b, a) }
    }
}

/**
 * A parsed `features.json`: the feature descriptions and documentation URLs, the runtimes,
 * and the per-(runtime, feature) support matrix.
 *
 * Parsing is deliberately lenient — an unrecognized status shape degrades to
 * [FeatureStatus.NotApplicable] and a malformed runtime or feature entry is skipped —
 * so an upstream schema addition cannot break an already-installed Hexana. The only hard
 * failure is JSON that does not parse at all, which yields `null` from [parse].
 */
class WasmFeatureCatalog(
    val features: Map<String, WasmFeature>,
    val runtimes: Map<String, WasmRuntimeInfo>,
    val categories: List<WasmFeatureCategory>,
) {
    /** The names under which [TOOLS_QUERY_KEY] appears in a runtime's `category` field. */
    private val toolCategories: Set<String> =
        categories.filter { it.queryKey == TOOLS_QUERY_KEY }.mapTo(mutableSetOf(TOOLS_CATEGORY_NAME)) { it.name }

    /**
     * The runtimes a module can actually be *run* on — the catalog minus its toolchain
     * entries. Upstream files assemblers, optimizers and language servers under `browsers`
     * next to the browsers, but "does Binaryen support GC" is not a question about where a
     * module will run, and Hexana's own entry there is not something to target either.
     *
     * An entry filed under Tools *and* a runtime category (`Owi`) is kept, being both.
     */
    val executionRuntimes: Map<String, WasmRuntimeInfo> by lazy {
        runtimes.filterValues { info -> info.categories.isEmpty() || info.categories.any { it !in toolCategories } }
    }

    fun feature(featureId: String): WasmFeature? = features[featureId]

    fun runtime(runtimeId: String): WasmRuntimeInfo? = runtimes[runtimeId]

    /** The matrix cell, or `null` when this runtime records nothing for this feature. */
    fun cell(runtimeId: String, featureId: String): FeatureCell? = runtimes[runtimeId]?.cells?.get(featureId)

    /**
     * [executionRuntimes] grouped by category, in upstream category order, with any runtime
     * whose category is unknown appended under its own name. Drives the settings UI
     * grouping, which is why tools are absent: they are not targets to declare.
     */
    fun runtimesByCategory(): List<Pair<String, List<String>>> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (category in categories) if (category.name !in toolCategories) grouped[category.name] = mutableListOf()
        for (runtime in executionRuntimes.values) {
            // Owi lists Tools first but is also a runtime; group it where it can run.
            val category = runtime.categories.firstOrNull { it !in toolCategories } ?: UNCATEGORIZED
            grouped.getOrPut(category) { mutableListOf() }.add(runtime.id)
        }
        return grouped.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value.toList() }
    }

    companion object {
        private const val UNCATEGORIZED = "Other"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The committed snapshot; the offline fallback and the fixture tests parse against. */
        val bundled: WasmFeatureCatalog by lazy {
            parse(BUNDLED_WASM_FEATURES_JSON)
                ?: error("bundled features.json does not parse; regenerate BundledWasmFeatures.kt")
        }

        /** Returns `null` when [text] is not parseable JSON with the expected top-level shape. */
        fun parse(text: String): WasmFeatureCatalog? {
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (_: Exception) {
                return null
            }
            val features = (root["features"] as? JsonObject)?.let(::parseFeatures) ?: return null
            val runtimes = (root["browsers"] as? JsonObject)?.let(::parseRuntimes) ?: return null
            val categories = (root["categories"] as? JsonArray)?.let(::parseCategories) ?: emptyList()
            return WasmFeatureCatalog(features, runtimes, categories)
        }

        private fun parseFeatures(node: JsonObject): Map<String, WasmFeature> {
            val result = LinkedHashMap<String, WasmFeature>()
            for ((id, value) in node) {
                val entry = value as? JsonObject ?: continue
                val description = entry.stringOrNull("description") ?: continue
                val url = entry.stringOrNull("url") ?: continue
                val phase = (entry["phase"] as? JsonPrimitive)?.content ?: continue
                result[id] = WasmFeature(id, description, url, phase)
            }
            return result
        }

        private fun parseRuntimes(node: JsonObject): Map<String, WasmRuntimeInfo> {
            val result = LinkedHashMap<String, WasmRuntimeInfo>()
            for ((id, value) in node) {
                val entry = value as? JsonObject ?: continue
                val cells = (entry["features"] as? JsonObject)?.let(::parseCells) ?: emptyMap()
                result[id] = WasmRuntimeInfo(
                    id = id,
                    url = entry.stringOrNull("url"),
                    categories = parseCategoryRef(entry["category"]),
                    cells = cells,
                )
            }
            return result
        }

        /** `category` is either one string or an array of them, primary first. */
        private fun parseCategoryRef(node: JsonElement?): List<String> = when (node) {
            is JsonPrimitive -> listOf(node.content)
            is JsonArray -> node.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }

        private fun parseCells(node: JsonObject): Map<String, FeatureCell> {
            val result = LinkedHashMap<String, FeatureCell>()
            for ((featureId, value) in node) {
                result[featureId] = parseCell(value)
            }
            return result
        }

        private fun parseCell(node: JsonElement): FeatureCell = when (node) {
            // `[status, note]`: the note applies to the status in the first slot.
            is JsonArray -> FeatureCell(
                status = node.firstOrNull()?.let(::parseStatus) ?: FeatureStatus.NotApplicable,
                note = (node.getOrNull(1) as? JsonPrimitive)?.contentOrNullIfNotString(),
            )
            else -> FeatureCell(parseStatus(node))
        }

        private fun parseStatus(node: JsonElement): FeatureStatus {
            if (node is JsonNull) return FeatureStatus.NotApplicable
            val primitive = node as? JsonPrimitive ?: return FeatureStatus.NotApplicable
            primitive.booleanOrNull?.let { return if (it) FeatureStatus.Supported else FeatureStatus.Unsupported }
            if (!primitive.isString) return FeatureStatus.NotApplicable
            val text = primitive.content
            return when {
                text == "flag" -> FeatureStatus.FlagRequired
                text.isBlank() -> FeatureStatus.NotApplicable
                else -> FeatureStatus.SinceVersion(text)
            }
        }

        private fun parseCategories(node: JsonArray): List<WasmFeatureCategory> = node.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry.stringOrNull("name") ?: return@mapNotNull null
            val queryKey = entry.stringOrNull("queryKey") ?: return@mapNotNull null
            WasmFeatureCategory(name, queryKey, (entry["default"] as? JsonPrimitive)?.booleanOrNull == true)
        }

        private fun JsonObject.stringOrNull(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNullIfNotString()

        private fun JsonPrimitive.contentOrNullIfNotString(): String? = if (isString) content else null
    }
}

package org.jetbrains.hexana.shared.features

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Project-relative directory Hexana keeps shared, editor-independent state in.
 *
 * The IntelliJ plugin and the VS Code extension both read and write here so a setting made
 * in one is honoured by the other. `ScriptTemplateWriter` (IntelliJ) and
 * `WasmEditorProvider.scriptsDirectory()` (VS Code) use the same directory for scripts.
 */
const val HEXANA_PROJECT_DIR: String = ".hexana"

/** File inside [HEXANA_PROJECT_DIR] holding the declared runtime targets. */
const val RUNTIME_TARGETS_FILE: String = "runtimes.json"

/** `.hexana/runtimes.json`, project-relative. */
const val RUNTIME_TARGETS_PATH: String = "$HEXANA_PROJECT_DIR/$RUNTIME_TARGETS_FILE"

/**
 * File inside [HEXANA_PROJECT_DIR] holding the JSON Schema for [RUNTIME_TARGETS_FILE].
 *
 * Written next to the settings file rather than served from a URL so the file stays
 * hand-editable offline, and so the runtime names it offers are the ones *this* Hexana
 * knows about — the schema is regenerated from the feature catalog on every save.
 */
const val RUNTIME_TARGETS_SCHEMA_FILE: String = "runtimes.schema.json"

/** `.hexana/runtimes.schema.json`, project-relative. */
const val RUNTIME_TARGETS_SCHEMA_PATH: String = "$HEXANA_PROJECT_DIR/$RUNTIME_TARGETS_SCHEMA_FILE"

/**
 * The `${'$'}schema` value written into [RUNTIME_TARGETS_FILE]: a sibling-relative reference, so
 * it resolves the same way in every editor and in a checkout at any path.
 */
const val RUNTIME_TARGETS_SCHEMA_REF: String = "./$RUNTIME_TARGETS_SCHEMA_FILE"

/** Schema version written into the file, so a future format change can be recognized. */
const val RUNTIME_TARGETS_SCHEMA_VERSION: Int = 1

/**
 * One runtime the project is expected to run on.
 *
 * @param runtime a key of the `browsers` object in `features.json` (`"Chrome"`,
 *   `"Wasmtime"`, `"Node.js"`, ...), used verbatim so there is no second naming vocabulary
 *   to keep in sync with upstream
 * @param version the version being targeted, or `null` for "any version" — which means no
 *   version gate is applied and the runtime counts as supporting whatever it supports at all
 */
class RuntimeTarget(val runtime: String, val version: String? = null) {
    override fun equals(other: Any?): Boolean {
        val b = other as? RuntimeTarget ?: return false
        return runtime == b.runtime && version == b.version
    }

    override fun hashCode(): Int = 31 * runtime.hashCode() + (version?.hashCode() ?: 0)

    override fun toString(): String = if (version.isNullOrBlank()) runtime else "$runtime $version"
}

/** Contents of `.hexana/runtimes.json`. */
class RuntimeTargets(
    val targets: List<RuntimeTarget> = emptyList(),
    val schemaVersion: Int = RUNTIME_TARGETS_SCHEMA_VERSION,
) {
    val isEmpty: Boolean get() = targets.isEmpty()

    companion object {
        val EMPTY = RuntimeTargets()
    }
}

private val runtimeTargetsJson = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

/** The `${'$'}schema` key, spelled once: a literal `${'$'}` needs escaping in Kotlin source. */
private const val SCHEMA_KEY: String = "${'$'}schema"

/**
 * Parses `.hexana/runtimes.json`.
 *
 * Lenient by design: the file is hand-editable and committed to version control, so a
 * malformed or truncated one degrades to [RuntimeTargets.EMPTY] (the feature simply goes
 * quiet) rather than surfacing an error on every file open. Entries with a blank runtime
 * name are dropped, and a duplicated runtime keeps its first entry.
 */
fun parseRuntimeTargets(text: String): RuntimeTargets {
    val root = try {
        runtimeTargetsJson.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return RuntimeTargets.EMPTY
    }
    val schemaVersion = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: RUNTIME_TARGETS_SCHEMA_VERSION
    val entries = (root["targets"] as? JsonArray) ?: return RuntimeTargets(emptyList(), schemaVersion)

    val seen = mutableSetOf<String>()
    val targets = entries.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val runtime = (entry["runtime"] as? JsonPrimitive)?.content?.trim()
        if (runtime.isNullOrEmpty() || !seen.add(runtime)) return@mapNotNull null
        val version = (entry["version"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        RuntimeTarget(runtime, version)
    }
    return RuntimeTargets(targets, schemaVersion)
}

/**
 * Renders `.hexana/runtimes.json`. Key order and indentation are fixed so re-saving an
 * unchanged selection produces a byte-identical file and the committed file diffs cleanly.
 */
fun serializeRuntimeTargets(targets: RuntimeTargets): String {
    val root = buildJsonObject {
        // First key: an editor reading top-down associates the file with its schema before
        // it sees anything the schema governs.
        put(SCHEMA_KEY, RUNTIME_TARGETS_SCHEMA_REF)
        put("version", targets.schemaVersion)
        put("targets", buildJsonArray {
            for (target in targets.targets) {
                add(buildJsonObject {
                    put("runtime", target.runtime)
                    target.version?.takeIf { it.isNotBlank() }?.let { put("version", it) }
                })
            }
        })
    }
    return runtimeTargetsJson.encodeToString(JsonObject.serializer(), root) + "\n"
}

/**
 * Renders `.hexana/runtimes.schema.json`, the JSON Schema [serializeRuntimeTargets] output
 * declares itself against.
 *
 * The point of the file is hand-editing: with the schema next to it, both IntelliJ and VS
 * Code complete the key names, complete the runtime names, and flag a typo — so the
 * declaration can be written or fixed in a text editor, or reviewed in a pull request,
 * without opening the settings UI.
 *
 * @param catalog the feature catalog whose runtime ids become the completion list for
 *   `runtime`. `null` (or a catalog that failed to load) leaves the field an unconstrained
 *   string: offering no names is better than pinning the wrong ones.
 */
fun serializeRuntimeTargetsSchema(catalog: WasmFeatureCatalog?): String {
    // The names the settings UI itself offers, so the two never disagree. Sorted rather than
    // left in catalog order: this is a completion list, and an upstream reordering of
    // features.json should not rewrite the file. Case-insensitively, so `wazero` sits among
    // the runtimes rather than after them.
    val runtimeIds = catalog?.executionRuntimes?.keys?.sortedBy { it.lowercase() }.orEmpty()

    val root = buildJsonObject {
        // Draft-07 rather than a later draft: it is what IntelliJ's and VS Code's bundled
        // JSON schema support implement most completely.
        put(SCHEMA_KEY, "http://json-schema.org/draft-07/schema#")
        put("title", "Hexana target runtimes")
        put(
            "description",
            "Runtimes and versions this project has to run on. Hexana checks every WebAssembly " +
                "binary against them and warns about proposals a declared runtime cannot run. " +
                "Read and written by both the IntelliJ plugin and the VS Code extension.",
        )
        put("type", "object")
        putJsonArray("required") { add("targets") }
        // Closed: the file has exactly these three keys, so a misspelled one is a mistake
        // worth pointing at rather than silently ignored state.
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject(SCHEMA_KEY) {
                put("type", "string")
                put("description", "Reference to this schema. Written by Hexana; safe to leave alone.")
            }
            putJsonObject("version") {
                put("type", "integer")
                put("minimum", 1)
                put("default", RUNTIME_TARGETS_SCHEMA_VERSION)
                put("description", "Format version of this file. Currently $RUNTIME_TARGETS_SCHEMA_VERSION.")
            }
            putJsonObject("targets") {
                put("type", "array")
                put(
                    "description",
                    "The runtimes to check against, in the order they appear as columns in the " +
                        "Compatibility tab. An empty list means no checking is performed.",
                )
                putJsonObject("items") { put("\$ref", "#/definitions/target") }
            }
        }
        putJsonObject("definitions") {
            putJsonObject("target") {
                put("type", "object")
                put("title", "Target runtime")
                putJsonArray("required") { add("runtime") }
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("runtime") {
                        put("type", "string")
                        put("minLength", 1)
                        put(
                            "description",
                            "Runtime name, spelled as WebAssembly's own features.json spells it " +
                                "(\"Chrome\", \"Node.js\", \"Wasmtime\"). A name the catalog does not " +
                                "know has no support data, so every verdict for it reads \"?\".",
                        )
                        if (runtimeIds.isNotEmpty()) {
                            putJsonArray("enum") { for (id in runtimeIds) add(id) }
                        }
                    }
                    putJsonObject("version") {
                        put("type", "string")
                        put("minLength", 1)
                        put(
                            "description",
                            "Version being targeted, as a string (\"120\", \"26.0\"). Omit it to " +
                                "target any version, which applies no version gate.",
                        )
                    }
                }
            }
        }
        // A worked example, which both IDEs surface in the file's documentation popup.
        putJsonArray("examples") {
            addJsonObject {
                put("version", RUNTIME_TARGETS_SCHEMA_VERSION)
                putJsonArray("targets") {
                    addJsonObject {
                        put("runtime", "Chrome")
                        put("version", "120")
                    }
                    addJsonObject { put("runtime", "Node.js") }
                }
            }
        }
    }
    return runtimeTargetsJson.encodeToString(JsonObject.serializer(), root) + "\n"
}

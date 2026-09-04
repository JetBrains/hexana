package org.jetbrains.hexana.shared.features

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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

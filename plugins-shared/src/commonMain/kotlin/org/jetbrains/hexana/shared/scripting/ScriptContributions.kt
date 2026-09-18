package org.jetbrains.hexana.shared.scripting

/**
 * Name of the `hexana` script global (v2 API surface).
 *
 * Bound by the host layer, not by [scriptGlobals]: the v1 global set is pinned
 * (`binary`, `open`, `print`, `table`, `row`) and hosts that support
 * contributions add `hexana` and `console` on top with their native value types.
 */
const val SCRIPT_HEXANA_GLOBAL: String = "hexana"

/** Name of the `console` script global. Bound by the host layer, like [SCRIPT_HEXANA_GLOBAL]. */
const val SCRIPT_CONSOLE_GLOBAL: String = "console"

/**
 * Column type in a user-defined slice.
 *
 * The type guides rendering (offset columns get hex view, funcidx columns get
 * navigation) and is part of the static metadata that is readable before any
 * rows are fetched.
 */
enum class SliceColumnType {
    STRING,
    INT,
    OFFSET,
    FUNCIDX,
    BOOL,
}

/** One column descriptor in a [SliceRegistration]. */
data class SliceColumn(
    val name: String,
    val type: SliceColumnType,
)

/** One parameter descriptor in a [SliceRegistration]. */
data class SliceParam(
    val name: String,
    val type: SliceColumnType,
    /** Default value as a [ScriptValue] primitive. */
    val default: ScriptValue,
)

/**
 * Static metadata for a slice registered via `hexana.defineSlice(...)`.
 *
 * The [rows] function is a live JS callable; it is held here while the
 * GraalJS context that owns it is open. It must never be serialized or
 * returned across the context lifetime.
 *
 * @param name slice name, must start with "user:"
 * @param formats binary formats this slice applies to (empty means all)
 * @param params parameter list with typed defaults
 * @param columns column schema used for rendering
 * @param rows JS function to call with (binary, params, {offset, limit})
 */
data class SliceRegistration(
    val name: String,
    val formats: List<String>,
    val params: List<SliceParam>,
    val columns: List<SliceColumn>,
    val rows: ScriptValue.Fn,
)

/** Severity of a console entry emitted by `console.log/error/debug`. */
enum class ConsoleSeverity { LOG, ERROR, DEBUG }

/** One entry written to the `console` global during a script run or page call. */
data class ScriptConsoleEntry(
    val severity: ConsoleSeverity,
    val text: String,
)

/**
 * Collects [SliceRegistration]s and [ScriptConsoleEntry]s emitted by a script.
 *
 * One instance is created per run or session. On the JVM it is accessed from
 * the single GraalJS executor thread only, so no synchronization is needed
 * inside the JS-facing callbacks. The snapshot helpers are called from the
 * owning thread after eval returns.
 *
 * The `hexana` and `console` globals are bound by the host layer (JVM:
 * `HexanaScriptRunner` and the script session) using the host's native value
 * types. What this class provides is the pure-Kotlin data model, validation of
 * duplicates, and the collected state.
 */
open class ScriptContributionCollector {

    private val _slices = mutableListOf<SliceRegistration>()
    private val _console = mutableListOf<ScriptConsoleEntry>()

    /** All registrations collected so far, in registration order. */
    val slices: List<SliceRegistration> get() = _slices

    /** All console entries collected so far, in emission order. */
    val console: List<ScriptConsoleEntry> get() = _console

    /** Console entries from [fromIndex] onward (inclusive). */
    fun consoleSince(fromIndex: Int): List<ScriptConsoleEntry> =
        if (fromIndex >= _console.size) emptyList() else _console.subList(fromIndex, _console.size)

    /**
     * Registers [registration]. Called by host-layer code after validating the
     * JS spec object using native host value types.
     *
     * @throws IllegalArgumentException on duplicate name
     */
    fun registerSlice(registration: SliceRegistration) {
        val duplicate = _slices.find { it.name == registration.name }
        if (duplicate != null) {
            throw IllegalArgumentException(
                "hexana.defineSlice: duplicate slice name \"${registration.name}\""
            )
        }
        _slices += registration
    }

    /**
     * Records a console entry. Called by host-layer code when the script calls
     * `console.log(...)` / `console.error(...)` / `console.debug(...)`.
     */
    fun addConsoleEntry(severity: ConsoleSeverity, text: String) {
        _console += ScriptConsoleEntry(severity, text)
    }

}

/**
 * Static slice metadata included in [org.jetbrains.hexana.mcp.scripting.HexanaScriptResult].
 *
 * The rows function is not included; it is only available while the GraalJS
 * context is alive (i.e., in a session).
 */
data class SliceRegistrationInfo(
    val name: String,
    val formats: List<String>,
    val params: List<SliceParam>,
    val columns: List<SliceColumn>,
)

/** Converts a live [SliceRegistration] to its serializable metadata form. */
fun SliceRegistration.toInfo(): SliceRegistrationInfo =
    SliceRegistrationInfo(name = name, formats = formats, params = params, columns = columns)

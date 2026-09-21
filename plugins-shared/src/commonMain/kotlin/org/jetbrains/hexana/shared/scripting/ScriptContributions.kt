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

// -------------------------------------------------------------------------
// Anchor model — shared with both slices and checks.
// -------------------------------------------------------------------------

/**
 * An anchor that ties a slice row or check finding to a location in the binary.
 *
 * Gives both [SliceRow] and [CheckFinding] a typed anchor rather than a bare
 * [Long]. Keep this minimal: add variants only when a new location kind is needed.
 */
sealed class ScriptAnchor {
    /** A single WebAssembly function, identified by its global function index. */
    data class Function(val funcidx: Long) : ScriptAnchor()

    /** A contiguous byte range in the binary file. */
    data class ByteRange(val offset: Long, val length: Long) : ScriptAnchor()
}

// -------------------------------------------------------------------------
// Check model
// -------------------------------------------------------------------------

/** Severity for a check definition and for individual findings. */
enum class CheckSeverity { ERROR, WARNING, INFO }

/**
 * A user-defined check registered via `hexana.defineCheck(...)`.
 *
 * The live [run] function is a JS callable; it must not be serialized or
 * returned after the GraalJS context that owns it is closed. Use
 * [CheckRegistrationInfo] for the serializable metadata form.
 *
 * @param id unique check identifier; must start with "user:"
 * @param severity default severity for findings emitted without an override
 * @param description optional human-readable description
 * @param run JS function to invoke as run(binary, report)
 */
data class CheckRegistration(
    val id: String,
    val severity: CheckSeverity,
    val description: String?,
    val run: ScriptValue.Fn,
)

/**
 * Static check metadata included in [org.jetbrains.hexana.mcp.scripting.HexanaScriptResult].
 *
 * The run function is not included; it is only available while the GraalJS context is alive.
 */
data class CheckRegistrationInfo(
    val id: String,
    val severity: CheckSeverity,
    val description: String?,
)

/** Converts a live [CheckRegistration] to its serializable metadata form. */
fun CheckRegistration.toInfo(): CheckRegistrationInfo =
    CheckRegistrationInfo(id = id, severity = severity, description = description)

/**
 * One finding emitted by a check via `report.fail(...)`, `report.warn(...)`, or `report.info(...)`.
 *
 * @param checkId the id of the check that emitted this finding
 * @param severity the severity at which the finding was reported
 * @param message human-readable description of the finding
 * @param anchor optional location in the binary; null means the finding is file-level
 */
data class CheckFinding(
    val checkId: String,
    val severity: CheckSeverity,
    val message: String,
    val anchor: ScriptAnchor? = null,
)

// -------------------------------------------------------------------------
// Flag model
// -------------------------------------------------------------------------

/**
 * The declared type for a flag value.
 *
 * - [STRING]: any string value
 * - [INT]: parsed as a long integer
 * - [BOOL]: "true"/"false" (case-insensitive) or presence flag
 * - [PATH]: a file path; the file is loaded as a binary handle and its path
 *   is added to the host's allowed file set
 * - [VALUES]: an enumerated set of string values declared via [FlagSpec.values]
 */
enum class FlagType { STRING, INT, BOOL, PATH, VALUES }

/**
 * One flag declared via `hexana.defineFlags({name: {short?, long, type | values, default}})`.
 *
 * @param short optional single-char short form (without leading `-`)
 * @param long the long form (without leading `--`)
 * @param type the value type; [FlagType.VALUES] requires [values] to be non-empty
 * @param values allowed string values for [FlagType.VALUES] flags
 * @param default raw default value as a string, or null
 */
data class FlagSpec(
    val short: String?,
    val long: String,
    val type: FlagType,
    val values: List<String>,
    val default: String?,
)

/**
 * A usage error detected during invocation parsing.
 *
 * The CLI maps these to exit code 2; the IDE tab shape uses defaults and
 * treats undeclared flags as null without error.
 */
sealed class ScriptUsageError {
    /** A flag key was present in argv but not declared via `hexana.defineFlags`. */
    data class UndeclaredFlag(val key: String) : ScriptUsageError()

    /** A flag value was not a member of the declared [FlagSpec.values] set. */
    data class InvalidFlagValue(val key: String, val value: String, val allowed: List<String>) : ScriptUsageError()

    /** A flag value could not be parsed as the declared type. */
    data class FlagTypeMismatch(val key: String, val value: String, val type: FlagType) : ScriptUsageError()
}

/**
 * The invocation context passed from the host to the script engine.
 *
 * [argv] is the raw argument vector (args[0] = script path).
 * [extraInputPaths] are the positional file arguments after the script path.
 *
 * In a tab context (IDE/VS Code): argv = [scriptPath], extraInputPaths = [] (the
 * primary binary is bound as `binary` by the host, not counted as an extra input).
 */
data class ScriptInvocation(
    val argv: List<String> = emptyList(),
    val extraInputPaths: List<String> = emptyList(),
)

/** Companion value for the empty/tab-like invocation. */
val EmptyScriptInvocation: ScriptInvocation = ScriptInvocation()

// -------------------------------------------------------------------------
// Severity of a console entry emitted by `console.log/error/debug`.
// -------------------------------------------------------------------------

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
    private val _checks = mutableListOf<CheckRegistration>()
    private val _console = mutableListOf<ScriptConsoleEntry>()

    /** All slice registrations collected so far, in registration order. */
    val slices: List<SliceRegistration> get() = _slices

    /** All check registrations collected so far, in registration order. */
    val checks: List<CheckRegistration> get() = _checks

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
     * Registers a check. Called by host-layer code after validating the JS spec object.
     *
     * @throws IllegalArgumentException on duplicate id or missing "user:" prefix
     */
    fun registerCheck(registration: CheckRegistration) {
        require(registration.id.startsWith("user:")) {
            "hexana.defineCheck: check id must start with \"user:\", got \"${registration.id}\""
        }
        val duplicate = _checks.find { it.id == registration.id }
        if (duplicate != null) {
            throw IllegalArgumentException(
                "hexana.defineCheck: duplicate check id \"${registration.id}\""
            )
        }
        _checks += registration
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

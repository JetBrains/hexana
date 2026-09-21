package org.jetbrains.hexana.mcp.scripting

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckRegistration
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ConsoleSeverity
import org.jetbrains.hexana.shared.scripting.FlagSpec
import org.jetbrains.hexana.shared.scripting.FlagType
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import org.jetbrains.hexana.shared.scripting.ScriptContributionCollector
import org.jetbrains.hexana.shared.scripting.ScriptInvocation
import org.jetbrains.hexana.shared.scripting.ScriptOutput
import org.jetbrains.hexana.shared.scripting.ScriptUsageError
import org.jetbrains.hexana.shared.scripting.ScriptValue
import org.jetbrains.hexana.shared.scripting.SliceColumn
import org.jetbrains.hexana.shared.scripting.SliceColumnType
import org.jetbrains.hexana.shared.scripting.SliceParam
import org.jetbrains.hexana.shared.scripting.SliceRegistration

/**
 * GraalJS-aware contribution collector.
 *
 * Provides [buildGraalHexanaProxy] and [buildGraalConsoleProxy] which return
 * raw Graal [ProxyObject]s. These are bound directly into the Graal context,
 * bypassing [ScriptValueGraalProxy]'s fromGuest conversion that would flatten
 * JS objects and functions to strings before they reach Kotlin.
 *
 * The live JS function from `spec.rows` is captured and wrapped in a
 * [ScriptValue.Fn] that delegates back into Graal, making it callable later
 * in session mode while the context is still open.
 */
internal class GraalScriptContributionCollector : ScriptContributionCollector() {

    /**
     * Raw Graal [Value] for each registered slice's `rows` function, keyed by slice name.
     *
     * Used by session paging to call the JS function directly, bypassing
     * [ScriptValueGraalProxy.fromGuest] which would flatten the return object to a string.
     * All accesses must be on the same executor thread as the GraalJS context.
     */
    val rawRowsFunctions: MutableMap<String, Value> = mutableMapOf()

    /**
     * Raw Graal [Value] for each registered check's `run` function, keyed by check id.
     * All accesses must be on the same executor thread as the GraalJS context.
     */
    val rawCheckFunctions: MutableMap<String, Value> = mutableMapOf()

    // -------------------------------------------------------------------------
    // Invocation context (args/inputs/flags)
    // -------------------------------------------------------------------------

    /**
     * The invocation context set by the host before eval. Provides raw argv and extra input paths.
     */
    var invocation: ScriptInvocation = ScriptInvocation()

    /**
     * Binary ScriptValues for extra inputs (inputs[1..n]). inputs[0] is the primary binary,
     * held by the session/runner as `binaryValue`. The host sets this before or after eval
     * (available to the proxy via lazy resolution on first access).
     */
    var inputBinaryValues: List<ScriptValue> = emptyList()

    /**
     * Primary binary ScriptValue for inputs[0]. Set by the host after globals are bound.
     */
    var primaryBinaryValue: ScriptValue = ScriptValue.Null

    /**
     * Flag declarations registered via `hexana.defineFlags(...)`. Populated during eval.
     * Flag resolution (argv parsing) happens lazily on first access to `hexana.flags`.
     *
     * Design note on flag timing: `hexana.defineFlags(...)` is called during eval, and
     * `hexana.flags` is accessed also during eval (after defineFlags). We resolve lazily:
     * on first access to `hexana.flags`, we parse argv against whatever declarations exist
     * at that point. This is safe because a script that calls `hexana.flags` before
     * `hexana.defineFlags` will get empty/null values, which is the same as "no declarations",
     * consistent with the tab-context contract. The CLI usage-error check happens post-eval
     * when all declarations are finalized.
     */
    private val _flagDeclarations = mutableMapOf<String, FlagSpec>()
    val flagDeclarations: Map<String, FlagSpec> get() = _flagDeclarations

    /**
     * Usage errors detected during argv parsing (post-eval, validated by the host/CLI).
     * An undeclared flag found in argv after all defineFlags calls is a usage error.
     */
    val usageErrors: MutableList<ScriptUsageError> = mutableListOf()

    /** Resolved flag values, computed lazily on first access to `hexana.flags`. */
    private var _resolvedFlags: Map<String, ScriptValue>? = null

    /**
     * Forces flag resolution against the final set of declarations so that
     * [usageErrors] (for example [ScriptUsageError.UndeclaredFlag]) are populated
     * even if the script never read `hexana.flags`. Called by the host post-eval.
     *
     * Value-format errors (missing value, bad int/bool) throw on lazy access from
     * the script and surface as script errors; here they are swallowed because the
     * goal of this pass is only to detect declaration-level usage errors.
     */
    fun resolveInvocationFlags() {
        if (invocation.argv.size <= 1) return
        try {
            getOrComputeResolvedFlags()
        } catch (_: IllegalArgumentException) {
            // Malformed value; if the script accessed hexana.flags this already
            // surfaced as a script error. Usage errors collected so far stand.
        }
    }

    /**
     * A [ProxyObject] for the `hexana` global.
     *
     * Members: `defineSlice`, `defineCheck`, `defineFlags`, `api`, `has`,
     * `args`, `inputs`, `flags`.
     *
     * `defineSlice` and `defineCheck` receive the raw JS spec [Value] so JS objects
     * and functions are captured before any ScriptValue conversion.
     */
    fun buildGraalHexanaProxy(): ProxyObject = object : ProxyObject {
        override fun getMember(key: String): Any? = when (key) {
            "defineSlice" -> defineSliceExecutable
            "defineCheck" -> defineCheckExecutable
            "defineFlags" -> defineFlagsExecutable
            "api" -> 2
            "has" -> hasFnExecutable
            "args" -> buildArgsArray()
            "inputs" -> buildInputsArray()
            "flags" -> buildFlagsProxy()
            else -> null
        }

        override fun getMemberKeys(): Any = ProxyArray.fromList(
            listOf("defineSlice", "defineCheck", "defineFlags", "api", "has", "args", "inputs", "flags")
        )

        override fun hasMember(key: String): Boolean =
            key in setOf("defineSlice", "defineCheck", "defineFlags", "api", "has", "args", "inputs", "flags")

        override fun putMember(key: String, value: Value) { /* read-only */ }
    }

    /**
     * A [ProxyObject] for the `console` global.
     *
     * Members: `log`, `error`, `debug`. Each converts args to strings and
     * appends a [ScriptConsoleEntry].
     */
    fun buildGraalConsoleProxy(): ProxyObject = object : ProxyObject {
        override fun getMember(key: String): Any? = when (key) {
            "log" -> consoleFnExecutable(ConsoleSeverity.LOG)
            "error" -> consoleFnExecutable(ConsoleSeverity.ERROR)
            "debug" -> consoleFnExecutable(ConsoleSeverity.DEBUG)
            else -> null
        }

        override fun getMemberKeys(): Any = ProxyArray.fromList(listOf("log", "error", "debug"))

        override fun hasMember(key: String): Boolean = key in setOf("log", "error", "debug")

        override fun putMember(key: String, value: Value) { /* read-only */ }
    }

    // -------------------------------------------------------------------------
    // Private executables and proxy builders
    // -------------------------------------------------------------------------

    private val defineSliceExecutable: ProxyExecutable = ProxyExecutable { args ->
        val spec = args.getOrNull(0)
            ?: throw IllegalArgumentException("hexana.defineSlice requires a spec argument")
        if (spec.isNull || !spec.hasMembers()) {
            throw IllegalArgumentException("hexana.defineSlice expects an object spec")
        }
        val registration = parseGraalSpec(spec)
        registerSlice(registration)
        null
    }

    private val defineCheckExecutable: ProxyExecutable = ProxyExecutable { args ->
        val spec = args.getOrNull(0)
            ?: throw IllegalArgumentException("hexana.defineCheck requires a spec argument")
        if (spec.isNull || !spec.hasMembers()) {
            throw IllegalArgumentException("hexana.defineCheck expects an object spec")
        }
        val registration = parseGraalCheckSpec(spec)
        registerCheck(registration)
        null
    }

    private val defineFlagsExecutable: ProxyExecutable = ProxyExecutable { args ->
        val spec = args.getOrNull(0)
            ?: throw IllegalArgumentException("hexana.defineFlags requires a spec argument")
        if (spec.isNull || !spec.hasMembers()) {
            throw IllegalArgumentException("hexana.defineFlags expects an object spec")
        }
        // Each key in spec is a logical name; value is { short?, long, type | values, default }
        // getMemberKeys() returns a Set<String> when called via the Java host API.
        @Suppress("UNCHECKED_CAST")
        val keys = spec.memberKeys as? Set<*> ?: emptySet<String>()
        for (rawKey in keys) {
            val name = rawKey.toString()
            val flagVal = spec.getMemberOrNull(name)
                ?: throw IllegalArgumentException("hexana.defineFlags: spec.$name is not readable")
            if (!flagVal.hasMembers()) {
                throw IllegalArgumentException("hexana.defineFlags: spec.$name must be an object")
            }
            val flagSpec = parseGraalFlagSpec(name, flagVal)
            _flagDeclarations[name] = flagSpec
            // Invalidate resolved cache when new flags are declared.
            _resolvedFlags = null
        }
        null
    }

    private val hasFnExecutable: ProxyExecutable = ProxyExecutable { args ->
        val feature = args.getOrNull(0)?.takeIf { it.isString }?.asString() ?: ""
        feature == "slices" || feature == "checks"
    }

    private fun buildArgsArray(): ProxyArray {
        val argv = invocation.argv
        return object : ProxyArray {
            override fun get(index: Long): Any? = if (index in 0 until argv.size) argv[index.toInt()] else null
            override fun set(index: Long, value: Value) { /* read-only */ }
            override fun getSize(): Long = argv.size.toLong()
        }
    }

    private fun buildInputsArray(): ProxyArray {
        // inputs[0] = primary binary; inputs[1..n] = extra inputs
        val allInputs = listOf(primaryBinaryValue) + inputBinaryValues
        return object : ProxyArray {
            override fun get(index: Long): Any? {
                val sv = allInputs.getOrNull(index.toInt()) ?: return null
                return ScriptValueGraalProxy.toGuest(sv)
            }
            override fun set(index: Long, value: Value) { /* read-only */ }
            override fun getSize(): Long = allInputs.size.toLong()
        }
    }

    private fun buildFlagsProxy(): ProxyObject {
        // Resolve flags lazily from argv against current declarations.
        val resolved = getOrComputeResolvedFlags()
        return object : ProxyObject {
            override fun getMember(key: String): Any? {
                val sv = resolved[key] ?: return null
                return ScriptValueGraalProxy.toGuest(sv)
            }
            override fun getMemberKeys(): Any = ProxyArray.fromList(resolved.keys.toList())
            override fun hasMember(key: String): Boolean = key in resolved
            override fun putMember(key: String, value: Value) { /* read-only */ }
        }
    }

    /**
     * Resolves argv against current [_flagDeclarations]. Caches the result.
     *
     * For each declared flag, looks for --long or -short in argv, validates the value,
     * and returns the resolved [ScriptValue]. For undeclared flags present in argv,
     * records a [ScriptUsageError.UndeclaredFlag] (detected post-eval by the host/CLI).
     *
     * Path-typed flags: the resolved file path string is returned as a [ScriptValue.Str].
     * The actual file loading (into a binary handle) happens in the host after this
     * call, using the resolved string. The path is exposed as a string here so the
     * engine remains UI-free.
     */
    private fun getOrComputeResolvedFlags(): Map<String, ScriptValue> {
        _resolvedFlags?.let { return it }

        val result = mutableMapOf<String, ScriptValue>()
        val argv = invocation.argv
        // Build a quick lookup: --long -> name, -short -> name
        val longToName = _flagDeclarations.entries.associate { (name, spec) -> spec.long to name }
        val shortToName = _flagDeclarations.entries
            .filter { (_, spec) -> spec.short != null }
            .associate { (name, spec) -> spec.short!! to name }

        // Parse argv for flag values. args[0] is the script path; skip it.
        val positional = mutableListOf<String>()
        var i = 1
        while (i < argv.size) {
            val arg = argv[i]
            when {
                arg.startsWith("--") -> {
                    val key = arg.removePrefix("--")
                    val name = longToName[key]
                    if (name == null) {
                        usageErrors += ScriptUsageError.UndeclaredFlag(key)
                        i++
                    } else {
                        val spec = _flagDeclarations[name]!!
                        if (spec.type == FlagType.BOOL) {
                            result[name] = ScriptValue.Bool(true)
                            i++
                        } else {
                            val value = argv.getOrNull(i + 1)
                                ?: throw IllegalArgumentException("Flag --${spec.long} requires a value")
                            result[name] = parseRawFlagValue(name, value, spec)
                            i += 2
                        }
                    }
                }
                arg.startsWith("-") && arg.length == 2 -> {
                    val key = arg.removePrefix("-")
                    val name = shortToName[key]
                    if (name == null) {
                        usageErrors += ScriptUsageError.UndeclaredFlag(key)
                        i++
                    } else {
                        val spec = _flagDeclarations[name]!!
                        if (spec.type == FlagType.BOOL) {
                            result[name] = ScriptValue.Bool(true)
                            i++
                        } else {
                            val value = argv.getOrNull(i + 1)
                                ?: throw IllegalArgumentException("Flag -${spec.short} requires a value")
                            result[name] = parseRawFlagValue(name, value, spec)
                            i += 2
                        }
                    }
                }
                else -> {
                    positional += arg
                    i++
                }
            }
        }

        // Fill in defaults for flags not supplied in argv.
        for ((name, spec) in _flagDeclarations) {
            if (name !in result) {
                val default = spec.default
                result[name] = if (default != null) parseRawFlagValue(name, default, spec)
                else ScriptValue.Null
            }
        }

        _resolvedFlags = result
        return result
    }

    private fun parseRawFlagValue(name: String, raw: String, spec: FlagSpec): ScriptValue {
        return when (spec.type) {
            FlagType.STRING -> ScriptValue.Str(raw)
            FlagType.PATH -> ScriptValue.Str(raw) // The host resolves path; here we expose the string
            FlagType.BOOL -> when (raw.lowercase()) {
                "true", "1", "yes" -> ScriptValue.Bool(true)
                "false", "0", "no" -> ScriptValue.Bool(false)
                else -> throw IllegalArgumentException(
                    "Flag --${spec.long}: expected bool (true/false), got \"$raw\""
                )
            }
            FlagType.INT -> {
                val n = raw.toLongOrNull()
                    ?: throw IllegalArgumentException("Flag --${spec.long}: expected integer, got \"$raw\"")
                ScriptValue.Num(n.toDouble())
            }
            FlagType.VALUES -> {
                // Deferred validation: the raw string is returned so the script can observe it
                // during eval (consistent with how the value was passed). The usage error is
                // surfaced post-eval; the CLI exits 2 and the IDE tab shape ignores it.
                if (raw !in spec.values) {
                    usageErrors += ScriptUsageError.InvalidFlagValue(name, raw, spec.values)
                }
                ScriptValue.Str(raw)
            }
        }
    }

    private fun consoleFnExecutable(severity: ConsoleSeverity): ProxyExecutable =
        ProxyExecutable { args ->
            val text = args.joinToString(" ") { v ->
                // Mirror what ScriptOutput.formatValue does for the basic types.
                when {
                    v.isNull -> "null"
                    v.isString -> v.asString()
                    v.isBoolean -> v.asBoolean().toString()
                    v.isNumber -> ScriptOutput.formatNumber(v.asDouble())
                    else -> v.toString()
                }
            }
            addConsoleEntry(severity, text)
            null
        }

    // -------------------------------------------------------------------------
    // Check execution
    // -------------------------------------------------------------------------

    /**
     * Runs all registered checks against [binaryValue] and returns the findings.
     *
     * Must be called on the GraalJS executor thread, after eval.
     * A check that throws yields a single error-severity finding; the other checks still run.
     */
    fun runChecks(binaryValue: Value): List<CheckFinding> {
        val findings = mutableListOf<CheckFinding>()
        for (check in checks) {
            val rawFn = rawCheckFunctions[check.id] ?: continue
            val checkFindings = mutableListOf<CheckFinding>()
            val report = buildReportProxy(check.id, check.severity, checkFindings)
            try {
                rawFn.execute(binaryValue, report)
            } catch (error: Exception) {
                val message = error.message ?: error::class.java.simpleName
                checkFindings.add(
                    CheckFinding(
                        checkId = check.id,
                        severity = CheckSeverity.ERROR,
                        message = message,
                        anchor = null,
                    )
                )
            }
            findings.addAll(checkFindings)
        }
        return findings
    }

    private fun buildReportProxy(
        checkId: String,
        defaultSeverity: CheckSeverity,
        findings: MutableList<CheckFinding>,
    ): ProxyObject {
        fun recordFinding(severity: CheckSeverity, args: Array<out Value>) {
            val message = args.getOrNull(0)?.takeIf { it.isString }?.asString()
                ?: args.getOrNull(0)?.toString()
                ?: "(no message)"
            val optionsVal = args.getOrNull(1)
            val anchor: ScriptAnchor? = if (optionsVal != null && !optionsVal.isNull && optionsVal.hasMembers()) {
                parseReportAnchor(optionsVal)
            } else null
            findings.add(CheckFinding(checkId = checkId, severity = severity, message = message, anchor = anchor))
        }
        return object : ProxyObject {
            override fun getMember(key: String): Any? = when (key) {
                "fail" -> ProxyExecutable { args -> recordFinding(CheckSeverity.ERROR, args); null }
                "warn" -> ProxyExecutable { args -> recordFinding(CheckSeverity.WARNING, args); null }
                "info" -> ProxyExecutable { args -> recordFinding(CheckSeverity.INFO, args); null }
                else -> null
            }
            override fun getMemberKeys(): Any = ProxyArray.fromList(listOf("fail", "warn", "info"))
            override fun hasMember(key: String): Boolean = key in setOf("fail", "warn", "info")
            override fun putMember(key: String, value: Value) { /* read-only */ }
        }
    }

    private fun parseReportAnchor(options: Value): ScriptAnchor? {
        val atVal = options.getMemberOrNull("at") ?: return null
        if (atVal.isNull) return null
        // at: { funcidx: number } or at: { offset: number, length: number }
        if (atVal.hasMembers()) {
            val funcidxVal = atVal.getMemberOrNull("funcidx")
            if (funcidxVal != null && funcidxVal.isNumber) {
                return ScriptAnchor.Function(funcidxVal.asLong())
            }
            val offsetVal = atVal.getMemberOrNull("offset")
            val lengthVal = atVal.getMemberOrNull("length")
            if (offsetVal != null && offsetVal.isNumber) {
                val length = if (lengthVal != null && lengthVal.isNumber) lengthVal.asLong() else 0L
                return ScriptAnchor.ByteRange(offsetVal.asLong(), length)
            }
        }
        // at: number — treat as byte offset with length 0
        if (atVal.isNumber) {
            return ScriptAnchor.ByteRange(atVal.asLong(), 0L)
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Spec parsing from raw Graal Values
    // -------------------------------------------------------------------------

    private fun parseGraalCheckSpec(spec: Value): CheckRegistration {
        val idStr = spec.getMemberOrNull("id")?.asString()
            ?: throw IllegalArgumentException("hexana.defineCheck: spec.id is required (string)")
        // "user:" prefix validation is done in registerCheck()

        val severityStr = spec.getMemberOrNull("severity")?.asString()
            ?: throw IllegalArgumentException("hexana.defineCheck: spec.severity is required (\"error\"|\"warning\"|\"info\")")
        val severity = when (severityStr.lowercase()) {
            "error" -> CheckSeverity.ERROR
            "warning" -> CheckSeverity.WARNING
            "info" -> CheckSeverity.INFO
            else -> throw IllegalArgumentException(
                "hexana.defineCheck: spec.severity must be \"error\", \"warning\", or \"info\""
            )
        }

        val description = spec.getMemberOrNull("description")?.takeIf { it.isString }?.asString()

        val runVal = spec.getMemberOrNull("run")
            ?: throw IllegalArgumentException("hexana.defineCheck: spec.run is required (function)")
        if (!runVal.canExecute()) {
            throw IllegalArgumentException("hexana.defineCheck: spec.run must be a function")
        }

        rawCheckFunctions[idStr] = runVal

        val runFn = ScriptValue.Fn(
            name = "${idStr}.run(...)",
            minArgs = 0,
            maxArgs = Int.MAX_VALUE,
            call = { _ -> ScriptValue.Null }, // Not used — host calls via rawCheckFunctions
        )

        return CheckRegistration(id = idStr, severity = severity, description = description, run = runFn)
    }

    private fun parseGraalFlagSpec(name: String, spec: Value): FlagSpec {
        val short = spec.getMemberOrNull("short")?.takeIf { it.isString }?.asString()
        val long = spec.getMemberOrNull("long")?.takeIf { it.isString }?.asString()
            ?: name // default long form = the name key

        // Determine type from "type" field or "values" array presence
        val valuesVal = spec.getMemberOrNull("values")
        val typeStr = spec.getMemberOrNull("type")?.takeIf { it.isString }?.asString()

        val (flagType, values) = when {
            valuesVal != null && !valuesVal.isNull && valuesVal.hasArrayElements() -> {
                val vals = (0 until valuesVal.arraySize).map { i -> valuesVal.getArrayElement(i).asString() }
                FlagType.VALUES to vals
            }
            typeStr != null -> {
                val ft = when (typeStr.lowercase()) {
                    "string" -> FlagType.STRING
                    "int" -> FlagType.INT
                    "bool" -> FlagType.BOOL
                    "path" -> FlagType.PATH
                    else -> throw IllegalArgumentException(
                        "hexana.defineFlags: $name.type \"$typeStr\" is unknown; valid: string, int, bool, path"
                    )
                }
                ft to emptyList()
            }
            else -> FlagType.STRING to emptyList()
        }

        val defaultVal = spec.getMemberOrNull("default")
        val default: String? = when {
            defaultVal == null || defaultVal.isNull -> null
            defaultVal.isString -> defaultVal.asString()
            defaultVal.isBoolean -> defaultVal.asBoolean().toString()
            defaultVal.isNumber -> {
                val d = defaultVal.asDouble()
                if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
            }
            else -> defaultVal.toString()
        }

        return FlagSpec(short = short, long = long, type = flagType, values = values, default = default)
    }

    private fun parseGraalSpec(spec: Value): SliceRegistration {
        val nameStr = spec.getMemberOrNull("name")?.asString()
            ?: throw IllegalArgumentException("hexana.defineSlice: spec.name is required (string)")
        require(nameStr.startsWith("user:")) {
            "hexana.defineSlice: slice name must start with \"user:\", got \"$nameStr\""
        }

        val formatsVal = spec.getMemberOrNull("formats")
        val formats: List<String> = when {
            formatsVal == null || formatsVal.isNull -> emptyList()
            formatsVal.hasArrayElements() -> {
                (0 until formatsVal.arraySize).map { i -> formatsVal.getArrayElement(i).asString() }
            }
            else -> throw IllegalArgumentException("hexana.defineSlice: spec.formats must be an array")
        }

        val columnsVal = spec.getMemberOrNull("columns")
            ?: throw IllegalArgumentException("hexana.defineSlice: spec.columns is required")
        if (!columnsVal.hasArrayElements()) {
            throw IllegalArgumentException("hexana.defineSlice: spec.columns must be an array")
        }
        if (columnsVal.arraySize == 0L) {
            throw IllegalArgumentException("hexana.defineSlice: spec.columns must not be empty")
        }
        val columns = (0 until columnsVal.arraySize).map { i ->
            parseGraalColumn(columnsVal.getArrayElement(i), i.toInt())
        }

        val paramsVal = spec.getMemberOrNull("params")
        val params: List<SliceParam> = when {
            paramsVal == null || paramsVal.isNull -> emptyList()
            paramsVal.hasArrayElements() -> {
                (0 until paramsVal.arraySize).map { i -> parseGraalParam(paramsVal.getArrayElement(i), i.toInt()) }
            }
            else -> throw IllegalArgumentException("hexana.defineSlice: spec.params must be an array")
        }

        val rowsVal = spec.getMemberOrNull("rows")
            ?: throw IllegalArgumentException("hexana.defineSlice: spec.rows is required (function)")
        if (!rowsVal.canExecute()) {
            throw IllegalArgumentException("hexana.defineSlice: spec.rows must be a function")
        }

        // Store the raw Graal Value for session paging (bypasses fromGuest flattening).
        rawRowsFunctions[nameStr] = rowsVal

        // Also wrap in a ScriptValue.Fn for any caller that uses the ScriptValue surface.
        // (Metadata-only callers; session paging uses rawRowsFunctions instead.)
        // ScriptValue.Fn is constructed directly because scriptFunction is internal.
        val rowsFn = ScriptValue.Fn(
            name = "${nameStr}.rows(...)",
            minArgs = 0,
            maxArgs = Int.MAX_VALUE,
            call = { args ->
                val graalArgs = args.all().map { sv -> ScriptValueGraalProxy.toGuest(sv) }.toTypedArray()
                val result = rowsVal.execute(*graalArgs)
                ScriptValueGraalProxy.fromGuest(result)
            },
        )

        return SliceRegistration(
            name = nameStr,
            formats = formats,
            params = params,
            columns = columns,
            rows = rowsFn,
        )
    }

    private fun parseGraalColumn(value: Value, index: Int): SliceColumn {
        if (!value.hasMembers()) {
            throw IllegalArgumentException("hexana.defineSlice: columns[$index] must be an object")
        }
        val nameStr = value.getMemberOrNull("name")?.asString()
            ?: throw IllegalArgumentException("hexana.defineSlice: columns[$index].name is required (string)")
        val typeStr = value.getMemberOrNull("type")?.asString()
            ?: throw IllegalArgumentException("hexana.defineSlice: columns[$index].type is required (string)")
        return SliceColumn(nameStr, parseColumnType(typeStr, "columns[$index].type"))
    }

    private fun parseGraalParam(value: Value, index: Int): SliceParam {
        if (!value.hasMembers()) {
            throw IllegalArgumentException("hexana.defineSlice: params[$index] must be an object")
        }
        val nameStr = value.getMemberOrNull("name")?.asString()
            ?: throw IllegalArgumentException("hexana.defineSlice: params[$index].name is required (string)")
        val typeStr = value.getMemberOrNull("type")?.asString()
            ?: throw IllegalArgumentException("hexana.defineSlice: params[$index].type is required (string)")
        val paramType = parseColumnType(typeStr, "params[$index].type")
        val defaultVal = value.getMemberOrNull("default")
        val default: ScriptValue = if (defaultVal == null || defaultVal.isNull) {
            ScriptValue.Null
        } else {
            ScriptValueGraalProxy.fromGuest(defaultVal)
        }
        return SliceParam(nameStr, paramType, default)
    }

    private fun parseColumnType(typeStr: String, context: String): SliceColumnType =
        when (typeStr.lowercase()) {
            "string" -> SliceColumnType.STRING
            "int" -> SliceColumnType.INT
            "offset" -> SliceColumnType.OFFSET
            "funcidx" -> SliceColumnType.FUNCIDX
            "bool" -> SliceColumnType.BOOL
            else -> throw IllegalArgumentException(
                "hexana.defineSlice: $context has unknown type \"$typeStr\"; " +
                    "valid values: string, int, offset, funcidx, bool"
            )
        }

    companion object {
        private fun Value.getMemberOrNull(key: String): Value? =
            if (hasMembers() && hasMember(key)) getMember(key) else null
    }
}

package org.jetbrains.hexana.mcp.scripting

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.shared.scripting.ConsoleSeverity
import org.jetbrains.hexana.shared.scripting.ScriptContributionCollector
import org.jetbrains.hexana.shared.scripting.ScriptOutput
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
     * A [ProxyObject] for the `hexana` global.
     *
     * Members: `defineSlice`, `api`, `has`.
     * `defineSlice` receives the raw JS spec [Value] so JS objects and functions
     * are captured before any ScriptValue conversion.
     */
    fun buildGraalHexanaProxy(): ProxyObject = object : ProxyObject {
        override fun getMember(key: String): Any? = when (key) {
            "defineSlice" -> defineSliceExecutable
            "api" -> 2
            "has" -> hasFnExecutable
            else -> null
        }

        override fun getMemberKeys(): Any = ProxyArray.fromList(listOf("defineSlice", "api", "has"))

        override fun hasMember(key: String): Boolean = key in setOf("defineSlice", "api", "has")

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
    // Private executables
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

    private val hasFnExecutable: ProxyExecutable = ProxyExecutable { args ->
        val feature = args.getOrNull(0)?.takeIf { it.isString }?.asString() ?: ""
        feature == "slices"
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
    // Spec parsing from raw Graal Values
    // -------------------------------------------------------------------------

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

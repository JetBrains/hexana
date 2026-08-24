package org.jetbrains.hexana.shared.scripting

/**
 * A host-neutral, lazily evaluated value handed to a user script.
 *
 * The scripting surface is defined once in this module as a [ScriptValue] tree
 * (see [scriptBinaryApi]) and bound by each host with a thin adapter:
 * GraalJS `Proxy*` objects on the JVM, plain JS objects with lazy getters in the
 * browser. Keeping the definition here is what makes the same script behave the
 * same way in the IDE, in VS Code, and in the CLI.
 *
 * [Obj] members and [Arr] elements are produced on access, never up front — a
 * `Functions` table can have hundreds of thousands of rows, and a script that
 * only reads `binary.format` must not pay for any of them.
 */
sealed interface ScriptValue {

    /** JavaScript `null`. */
    data object Null : ScriptValue

    /** A string. */
    class Str(val value: String) : ScriptValue

    /**
     * A number.
     *
     * The boxed [Number] is passed through to the host as-is where possible so
     * that integral values keep printing as `5` rather than `5.0`.
     */
    class Num(val value: Number) : ScriptValue

    /** A boolean. */
    class Bool(val value: Boolean) : ScriptValue

    /**
     * An object with named members.
     *
     * @param members member name to a factory invoked on each access
     * @param hostFallback when set, member lookups that miss [members] are
     *   resolved against this host object by the host adapter. This is what lets
     *   `binary.<format-specific member>` keep working for scripts written
     *   against the pre-v1 surface: curated names win, everything else falls
     *   through to the underlying provider.
     */
    class Obj(
        val members: Map<String, () -> ScriptValue>,
        val hostFallback: Any? = null,
    ) : ScriptValue

    /** An array of [size] elements, each produced by [at] on access. */
    class Arr(val size: Int, val at: (Int) -> ScriptValue) : ScriptValue

    /**
     * A callable function.
     *
     * @param name qualified name used in argument-validation messages
     * @param minArgs smallest accepted argument count
     * @param maxArgs largest accepted argument count
     */
    class Fn(
        val name: String,
        val minArgs: Int,
        val maxArgs: Int,
        val call: (ScriptArgs) -> ScriptValue,
    ) : ScriptValue

    /**
     * An opaque host object the script may read reflectively.
     *
     * Used only for the `binary.raw` escape hatch and for [Obj.hostFallback].
     * Each host wraps it with its own read-only property view; no host *behavior*
     * is exposed, so the GraalJS sandbox stays closed (no class lookup, no IO,
     * no process or thread creation).
     */
    class Host(val value: Any) : ScriptValue

    companion object {
        val EMPTY_ARRAY: Arr = Arr(0) { Null }

        fun of(value: String?): ScriptValue = if (value == null) Null else Str(value)

        fun of(value: Number?): ScriptValue = if (value == null) Null else Num(value)

        fun of(value: Boolean?): ScriptValue = if (value == null) Null else Bool(value)

        /** Wraps [items] as a lazily mapped array. */
        fun <T> array(items: List<T>, map: (T) -> ScriptValue): Arr =
            Arr(items.size) { index -> map(items[index]) }

        /** Wraps [items] as an array of strings. */
        fun strings(items: List<String>): Arr = array(items) { Str(it) }
    }
}

/**
 * Arguments passed to a [ScriptValue.Fn], with coercion helpers that fail with a
 * message naming the function and parameter.
 *
 * Failures surface to the user as an ordinary script error with a source
 * location, so `binary.bytes.hex("x")` reports what was wrong rather than
 * silently returning nothing.
 */
class ScriptArgs(
    private val functionName: String,
    private val values: List<ScriptValue>,
) {
    val size: Int get() = values.size

    fun int(index: Int, parameter: String): Int = number(index, parameter).toInt()

    fun intOr(index: Int, parameter: String, default: Int): Int =
        if (index >= values.size || values[index] is ScriptValue.Null) default else int(index, parameter)

    fun string(index: Int, parameter: String): String {
        val value = values.getOrNull(index)
            ?: fail("expects a string for '$parameter'")
        return when (value) {
            is ScriptValue.Str -> value.value
            is ScriptValue.Num -> value.value.toString()
            else -> fail("expects a string for '$parameter'")
        }
    }

    fun booleanOr(index: Int, default: Boolean): Boolean =
        when (val value = values.getOrNull(index)) {
            null, is ScriptValue.Null -> default
            is ScriptValue.Bool -> value.value
            is ScriptValue.Num -> value.value.toDouble() != 0.0
            else -> default
        }

    fun at(index: Int): ScriptValue = values.getOrNull(index) ?: ScriptValue.Null

    /** All arguments, for variadic functions such as `print` and `row`. */
    fun all(): List<ScriptValue> = values

    /**
     * Reads a byte-pattern argument, accepting either a string (its code units,
     * masked to bytes) or an array of numbers.
     */
    fun bytePattern(index: Int, parameter: String): ByteArray =
        when (val value = values.getOrNull(index)) {
            is ScriptValue.Str -> ByteArray(value.value.length) { (value.value[it].code and 0xFF).toByte() }
            is ScriptValue.Arr -> ByteArray(value.size) { position ->
                val element = value.at(position)
                if (element is ScriptValue.Num) {
                    (element.value.toInt() and 0xFF).toByte()
                } else {
                    fail("expects only numbers in '$parameter'")
                }
            }
            else -> fail("expects a string or a number array for '$parameter'")
        }

    private fun number(index: Int, parameter: String): Number {
        val value = values.getOrNull(index)
            ?: fail("expects a number for '$parameter'")
        return when (value) {
            is ScriptValue.Num -> value.value
            is ScriptValue.Bool -> if (value.value) 1 else 0
            else -> fail("expects a number for '$parameter'")
        }
    }

    private fun fail(problem: String): Nothing =
        throw IllegalArgumentException("$functionName $problem")
}

/** Builds a [ScriptValue.Fn] whose arguments are validated against [minArgs]/[maxArgs]. */
internal fun scriptFunction(
    name: String,
    minArgs: Int = 0,
    maxArgs: Int = minArgs,
    call: (ScriptArgs) -> ScriptValue,
): ScriptValue.Fn = ScriptValue.Fn(name, minArgs, maxArgs, call)

/**
 * Validates an argument count against a [ScriptValue.Fn]'s accepted range and
 * invokes it. Host adapters call this instead of [ScriptValue.Fn.call] so every
 * host reports the same arity errors.
 */
fun ScriptValue.Fn.invoke(arguments: List<ScriptValue>): ScriptValue {
    if (arguments.size < minArgs || arguments.size > maxArgs) {
        val expected = if (minArgs == maxArgs) {
            "$minArgs argument(s)"
        } else {
            "$minArgs to $maxArgs argument(s)"
        }
        throw IllegalArgumentException("$name expects $expected, got ${arguments.size}")
    }
    return call(ScriptArgs(name, arguments))
}

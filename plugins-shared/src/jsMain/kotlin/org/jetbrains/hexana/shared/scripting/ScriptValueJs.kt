package org.jetbrains.hexana.shared.scripting

/**
 * Binds the shared [ScriptValue] surface into plain JavaScript objects.
 *
 * This is the browser half of the "one API, three hosts" split: the surface is
 * defined in [scriptBinaryApi] and [ScriptOutput], and this class only translates
 * it. Before it existed, the VS Code webview and the webapp worker each
 * hand-maintained their own per-format wrappers and exposed different subsets of
 * the binary than the JVM runtime did.
 *
 * Kotlin/JS mangles property names (`strings` becomes something like `w3u`), so
 * Kotlin objects cannot be handed to a user script directly. Everything is
 * rebuilt as plain objects with clean names and lazy getters, memoized after the
 * first read.
 *
 * @param wrapHost wraps a [ScriptValue.Host] — the `binary.raw` escape hatch and
 *   the fall-through target for uncurated members. Each host supplies its own
 *   strategy, because the format-specific shape cannot be enumerated in common
 *   code.
 */
class ScriptValueJsBinder(private val wrapHost: (Any) -> dynamic) {

    /** Converts a [ScriptValue] into something a user script can read. */
    fun toJs(value: ScriptValue): dynamic = when (value) {
        is ScriptValue.Null -> null
        is ScriptValue.Str -> value.value
        is ScriptValue.Bool -> value.value
        // Kotlin/JS represents Long as an object rather than a JS number, so it
        // has to be narrowed here or arithmetic in the script would break.
        is ScriptValue.Num -> if (value.value is Long) (value.value as Long).toDouble() else value.value
        is ScriptValue.Obj -> objectToJs(value)
        is ScriptValue.Arr -> arrayToJs(value)
        is ScriptValue.Fn -> functionToJs(value)
        is ScriptValue.Host -> wrapHost(value.value)
    }

    /** Binds every global from [scriptGlobals], by global name. */
    fun globalsToJs(globals: Map<String, ScriptValue>): Map<String, dynamic> =
        globals.mapValues { (_, value) -> toJs(value) }

    private fun objectToJs(value: ScriptValue.Obj): dynamic {
        // Uncurated members fall through to the format-specific provider via the
        // prototype chain, which is what keeps pre-v1 scripts working.
        val fallback = value.hostFallback?.let { wrapHost(it) }
        val target = if (fallback != null) createWithPrototype(fallback) else js("({})")
        for ((name, factory) in value.members) {
            defineLazyMember(target, name) { toJs(factory()) }
        }
        return target
    }

    private fun arrayToJs(value: ScriptValue.Arr): dynamic {
        val array = js("new Array(0)")
        array.length = value.size
        for (index in 0 until value.size) {
            defineLazyElement(array, index) { toJs(value.at(index)) }
        }
        return array
    }

    private fun functionToJs(value: ScriptValue.Fn): dynamic {
        @Suppress("UNUSED_VARIABLE")
        val invoke: (Array<dynamic>) -> dynamic = { arguments ->
            toJs(value.invoke(arguments.map(::fromJs)))
        }
        return js("function() { return invoke(Array.prototype.slice.call(arguments)); }")
    }

    /** Converts a script-supplied argument back into a [ScriptValue]. */
    private fun fromJs(value: dynamic): ScriptValue = when {
        value == null || value == undefined -> ScriptValue.Null
        jsTypeOf(value) == "string" -> ScriptValue.Str(value as String)
        jsTypeOf(value) == "boolean" -> ScriptValue.Bool(value as Boolean)
        jsTypeOf(value) == "number" -> ScriptValue.Num(value as Double)
        isJsArray(value) -> {
            val size = (value.length as Int)
            ScriptValue.Arr(size) { index -> fromJs(value[index]) }
        }
        // Matches what `print` did for objects before this API existed.
        else -> ScriptValue.Str(jsToString(value))
    }

    private companion object {

        fun createWithPrototype(prototype: dynamic): dynamic {
            @Suppress("UNUSED_VARIABLE")
            val proto = prototype
            return js("Object.create(proto)")
        }

        /**
         * Defines a getter that computes once and then replaces itself with the
         * resolved value, so repeatedly reading `binary.strings` in a loop does
         * not rebuild the wrapper each time.
         */
        fun defineLazyMember(target: dynamic, name: String, compute: () -> dynamic) {
            @Suppress("UNUSED_VARIABLE")
            val memberName = name

            @Suppress("UNUSED_VARIABLE")
            val getter = compute
            js(
                """
                Object.defineProperty(target, memberName, {
                    get: function() {
                        var resolved = getter();
                        Object.defineProperty(target, memberName, {
                            value: resolved, writable: false, enumerable: true, configurable: true
                        });
                        return resolved;
                    },
                    enumerable: true,
                    configurable: true
                })
                """
            )
        }

        fun defineLazyElement(array: dynamic, index: Int, compute: () -> dynamic) {
            @Suppress("UNUSED_VARIABLE")
            val elementIndex = index

            @Suppress("UNUSED_VARIABLE")
            val getter = compute
            js(
                """
                Object.defineProperty(array, elementIndex, {
                    get: function() {
                        var resolved = getter();
                        Object.defineProperty(array, elementIndex, {
                            value: resolved, writable: false, enumerable: true, configurable: true
                        });
                        return resolved;
                    },
                    enumerable: true,
                    configurable: true
                })
                """
            )
        }

        fun isJsArray(value: dynamic): Boolean {
            @Suppress("UNUSED_VARIABLE")
            val candidate = value
            return js("Array.isArray(candidate)") as Boolean
        }

        fun jsToString(value: dynamic): String {
            @Suppress("UNUSED_VARIABLE")
            val candidate = value
            return js("String(candidate)") as String
        }
    }
}

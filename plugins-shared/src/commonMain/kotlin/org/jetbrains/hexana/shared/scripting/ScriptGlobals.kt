package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.providers.BinaryProvider

/**
 * A second binary, parsed on a script's request by [ScriptBinaryOpener].
 *
 * @param provider the parsed binary
 * @param fileName file name for `open(path).name`, when the host knows it
 * @param filePath resolved path for `open(path).path`. Hosts report the path they
 *   actually read, not the string the script passed, so that a relative argument
 *   still yields a `path`/`dir` a later `open(...)` can build on.
 */
class ScriptOpenedBinary(
    val provider: BinaryProvider,
    val fileName: String? = null,
    val filePath: String? = null,
)

/**
 * Loads an additional binary for a running script.
 *
 * This is the one capability the scripting surface cannot define for itself:
 * reading a file needs the host. A host that can read files supplies an
 * implementation and `open(path)` works; a host that cannot passes `null` to
 * [scriptGlobals] and `open(path)` fails with [SCRIPT_OPEN_UNSUPPORTED] instead
 * of resolving to whatever `open` happens to mean in that environment — in a
 * browser that would be `window.open`.
 *
 * Implementations decide what a path may point at. On the JVM that decision is
 * the same allowed-roots policy that already governs the binary a caller passes
 * in, so `open` reaches exactly the files the host would have loaded anyway.
 */
fun interface ScriptBinaryOpener {

    /**
     * Loads the binary at [path].
     *
     * @throws Exception when the path cannot be read; the message is shown to the
     *   user as an ordinary script error, so it must read as one
     */
    fun open(path: String): ScriptOpenedBinary
}

/** Name of the `binary` script global. */
const val SCRIPT_BINARY_GLOBAL: String = "binary"

/** Name of the `open` script global. */
const val SCRIPT_OPEN_GLOBAL: String = "open"

/** Reported by `open(path)` in a host that cannot read local files. */
const val SCRIPT_OPEN_UNSUPPORTED: String =
    "needs local file access, which this host does not have. It works in the IntelliJ IDEA " +
        "script tab, in the run-script CLI, and in the run_binary_script MCP tool."

/**
 * Every global a user script gets, by the name it is bound under.
 *
 * Hosts bind what this returns and nothing else, so a global cannot exist in one
 * host and be missing in another — which is how `binary` and the output globals
 * diverged before the surface was defined in one place.
 *
 * @param provider the binary open in the editor, surfaced as `binary`
 * @param output receives `print`/`table`/`row`
 * @param fileName file name of [provider], when the host knows it
 * @param filePath path of [provider], when the host has one
 * @param opener loads the binaries `open(path)` asks for; `null` in a host
 *   without file access, which makes `open(path)` fail with a message saying so
 */
fun scriptGlobals(
    provider: BinaryProvider,
    output: ScriptOutput,
    fileName: String? = null,
    filePath: String? = null,
    opener: ScriptBinaryOpener? = null,
): Map<String, ScriptValue> {
    val globals = LinkedHashMap<String, ScriptValue>()
    globals[SCRIPT_BINARY_GLOBAL] = scriptBinaryApi(provider, fileName, filePath)
    globals[SCRIPT_OPEN_GLOBAL] = scriptOpenBinding(opener)
    globals.putAll(output.bindings())
    return globals
}

/**
 * Builds the `open(path)` global over [opener].
 *
 * The returned object is the same shape as `binary` — deliberately, so that
 * everything a script can ask about the open file it can also ask about the one
 * it opened, and a comparison script needs no second API to learn.
 */
internal fun scriptOpenBinding(opener: ScriptBinaryOpener?): ScriptValue.Fn =
    scriptFunction("open(path)", minArgs = 1) { args ->
        val path = args.string(0, "path")
        val resolved = opener
            ?: throw UnsupportedOperationException("open(\"$path\") — $SCRIPT_OPEN_UNSUPPORTED")
        val opened = resolved.open(path)
        scriptBinaryApi(opened.provider, opened.fileName, opened.filePath)
    }

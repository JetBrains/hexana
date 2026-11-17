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
 * Loads an additional binary for a running script, within the `open(path)` call.
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

/**
 * How a host fulfils `open(path)`.
 *
 * Reading a file is the one capability the scripting surface cannot define for
 * itself, and the hosts do not agree on how it is done: the JVM reads inside the
 * call, while a webview can only ask another process and be answered later. The
 * policy is what lets one definition of `open` serve both, rather than each host
 * binding a global of its own.
 */
sealed interface ScriptOpenPolicy {

    /**
     * No local file access at all — the browser web app, which only ever has the
     * bytes the page handed it. `open(path)` fails with
     * [SCRIPT_OPEN_UNSUPPORTED]; it stays *bound* rather than absent, because an
     * unbound `open` in a browser resolves to `window.open` and a script asking
     * for a file would navigate instead of failing.
     */
    data object Unsupported : ScriptOpenPolicy

    /** The host reads the file within the call: the IntelliJ tab, the CLI, MCP. */
    class Inline(val opener: ScriptBinaryOpener) : ScriptOpenPolicy

    /**
     * The host fulfils the call outside the script's own stack.
     *
     * `open(path)` evaluates to an object carrying [SCRIPT_OPEN_REQUEST_MEMBER],
     * which the host's script driver recognises, answers, and feeds back as the
     * value of the call. The VS Code webview works this way: it reads files
     * through a round-trip to the extension host, so nothing it can do inside the
     * call would produce the binary.
     */
    data object Deferred : ScriptOpenPolicy
}

/** Name of the `binary` script global. */
const val SCRIPT_BINARY_GLOBAL: String = "binary"

/** Name of the `open` script global. */
const val SCRIPT_OPEN_GLOBAL: String = "open"

/** Reported by `open(path)` in a host that cannot read local files. */
const val SCRIPT_OPEN_UNSUPPORTED: String =
    "needs local file access, which this host does not have. It works in the IntelliJ IDEA " +
        "script tab, in a VS Code script tab, in the run-script CLI, and in the " +
        "run_binary_script MCP tool."

/**
 * Member carrying the requested path on the object a [ScriptOpenPolicy.Deferred]
 * `open(path)` evaluates to.
 *
 * Deliberately a plain member rather than a host type: the driver that has to
 * recognise the request is JavaScript, and a Kotlin class handed across that
 * boundary is neither inspectable nor stable.
 */
const val SCRIPT_OPEN_REQUEST_MEMBER: String = "__hexanaOpenRequest"

/**
 * Sits behind a deferred `open(path)` request object, so that reading anything
 * *other* than [SCRIPT_OPEN_REQUEST_MEMBER] off it fails loudly.
 *
 * A request only becomes a binary if the host's driver gets to see it, which in a
 * generator-driven host means the call has to sit where the script can pause —
 * not inside a nested function, where `yield` is not even legal. Without this,
 * such a call would hand the script an object with no members and the script
 * would print `undefined` instead of being told what to fix. The host adapter
 * turns this into whatever its language can throw from a property read.
 */
class ScriptOpenRequestBlocked internal constructor(val path: String) {

    /** What the host adapter should report when the script reads through it. */
    val message: String
        get() = "open(\"$path\") — in this host `open` must be called where the script can pause " +
            "for it: at the top level of the script, not inside a function or a callback."
}

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
 * @param open how this host fulfils `open(path)`
 */
fun scriptGlobals(
    provider: BinaryProvider,
    output: ScriptOutput,
    fileName: String? = null,
    filePath: String? = null,
    open: ScriptOpenPolicy = ScriptOpenPolicy.Unsupported,
): Map<String, ScriptValue> {
    val globals = LinkedHashMap<String, ScriptValue>()
    globals[SCRIPT_BINARY_GLOBAL] = scriptBinaryApi(provider, fileName, filePath)
    globals[SCRIPT_OPEN_GLOBAL] = scriptOpenBinding(open)
    globals.putAll(output.bindings())
    return globals
}

/**
 * Builds the `open(path)` global for [policy].
 *
 * What an [ScriptOpenPolicy.Inline] host returns is the same shape as `binary` —
 * deliberately, so that everything a script can ask about the open file it can
 * also ask about the one it opened, and a comparison script needs no second API
 * to learn. A [ScriptOpenPolicy.Deferred] host returns the request instead and
 * substitutes that same shape once it has the bytes.
 *
 * Argument validation and the failure text live here for every policy, so a bad
 * call reads identically in every host.
 */
internal fun scriptOpenBinding(policy: ScriptOpenPolicy): ScriptValue.Fn =
    scriptFunction("open(path)", minArgs = 1) { args ->
        val path = args.string(0, "path")
        when (policy) {
            ScriptOpenPolicy.Unsupported ->
                throw UnsupportedOperationException("open(\"$path\") — $SCRIPT_OPEN_UNSUPPORTED")

            is ScriptOpenPolicy.Inline -> {
                val opened = policy.opener.open(path)
                scriptBinaryApi(opened.provider, opened.fileName, opened.filePath)
            }

            ScriptOpenPolicy.Deferred -> ScriptValue.Obj(
                members = linkedMapOf(SCRIPT_OPEN_REQUEST_MEMBER to { ScriptValue.Str(path) }),
                // Anything but the request member means the request never reached
                // the driver; see ScriptOpenRequestBlocked.
                hostFallback = ScriptOpenRequestBlocked(path),
            )
        }
    }

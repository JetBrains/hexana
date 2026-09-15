package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.shared.scripting.ScriptApiReference
import org.jetbrains.hexana.shared.scripting.ScriptOutput
import org.jetbrains.hexana.shared.scripting.ScriptOutputBlock
import org.jetbrains.hexana.shared.scripting.filterScriptOutputBlocks
import org.jetbrains.hexana.shared.scripting.parseScriptOutputBlocks
import org.jetbrains.hexana.shared.scripting.ScriptValueJsBinder
import org.jetbrains.hexana.shared.scripting.ScriptYieldTransform
import org.jetbrains.hexana.shared.scripting.scriptBinaryApi
import org.jetbrains.hexana.shared.scripting.scriptGlobals
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import kotlin.js.Promise

/** Executes a user script outside the Compose UI thread. */
interface ScriptExecution {
    fun start(
        scriptContent: String,
        onLine: (String) -> Unit,
        onComplete: (String?) -> Unit,
    ): ScriptExecutionHandle
}

interface ScriptExecutionHandle {
    fun cancel()
}

private object NoopScriptExecutionHandle : ScriptExecutionHandle {
    override fun cancel() = Unit
}

private class ScriptRunScope {
    var generation: Int = 0
}

/**
 * Wraps a format-specific provider value into a plain JS object so that user
 * scripts can access properties by their original Kotlin names.
 *
 * This is the implementation of the `binary.raw` escape hatch and of the
 * fall-through for members the curated surface does not define — not the primary
 * script API. The curated, format-agnostic surface comes from
 * [scriptBinaryApi] via [ScriptValueJsBinder]; only shapes that cannot be
 * enumerated in common code are wrapped here.
 *
 * Kotlin/JS IR mangles property names to short tokens (e.g. `w3u` for `strings`),
 * so passing the Kotlin object directly to a `new Function(...)` body would fail
 * with "undefined". This function builds a plain JS object in Kotlin code — where
 * property access compiles correctly — and defines lazy getters under clean names.
 * Wrapping is recursive: nested objects and lists are also wrapped.
 */
private fun wrapForJs(value: Any?): dynamic {
    if (value == null) return null
    if (value is String || value is Number || value is Boolean) return value
    if (value is List<*>) {
        return wrapListLazy(value)
    }
    // For known types, build a plain JS object with explicit property mappings
    val obj = js("({})")
    when (value) {
        is WasmBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapWasmBinaryProvider(obj, value) }
        is WasmComponentBinaryProvider -> wrapBinaryProvider(obj, value)
        is ElfBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapElfBinaryProvider(obj, value) }
        is MachOBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapMachOBinaryProvider(obj, value) }
        is FatMachOBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapFatMachOBinaryProvider(obj, value) }
        is PeBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapPeBinaryProvider(obj, value) }
        is CoffBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapCoffBinaryProvider(obj, value) }
        is ArchiveBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapArchiveBinaryProvider(obj, value) }
        is ZipBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapZipBinaryProvider(obj, value) }
        is TarBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapTarBinaryProvider(obj, value) }
        is ClassFileBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapClassFileBinaryProvider(obj, value) }
        is DexBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapDexBinaryProvider(obj, value) }
        is GenericBinaryProvider -> wrapBinaryProvider(obj, value).also { wrapGenericBinaryProvider(obj, value) }
        is BinaryProvider -> wrapBinaryProvider(obj, value)
        is BinaryStringRecord -> wrapBinaryStringRecord(obj, value)
        is BinarySizeNode -> wrapBinarySizeNode(obj, value)
        else -> return wrapUnknownObject(value)
    }
    return obj
}

/**
 * Wraps a Kotlin List lazily: items are only wrapped when accessed by index.
 * This avoids the O(n) upfront cost of wrapping every element.
 */
private fun wrapListLazy(list: List<*>): dynamic {
    val size = list.size
    val getItem: (Int) -> dynamic = { i -> wrapForJs(list[i]) }
    val arr = js("new Array(0)")
    arr.length = size
    for (i in 0 until size) {
        defineIndexedLazyProp(arr, i, getItem)
    }
    return arr
}

private fun defineIndexedLazyProp(arr: dynamic, index: Int, getItem: (Int) -> dynamic) {
    @Suppress("UNUSED_VARIABLE")
    val idx = index
    @Suppress("UNUSED_VARIABLE")
    val getter: () -> dynamic = { getItem(index) }
    js("""
        Object.defineProperty(arr, idx, {
            get: function() {
                var val_ = getter();
                Object.defineProperty(arr, idx, { value: val_, writable: false, enumerable: true, configurable: true });
                return val_;
            },
            enumerable: true,
            configurable: true
        })
    """)
}

/** Define a lazy getter on a plain JS object. */
private fun defineLazyProp(obj: dynamic, name: String, getter: () -> Any?) {
    @Suppress("UNUSED_VARIABLE")
    val n = name
    @Suppress("UNUSED_VARIABLE")
    val g: () -> dynamic = { wrapForJs(getter()) }
    js("Object.defineProperty(obj, n, { get: g, enumerable: true })")
}

/** Wrap base BinaryProvider properties. */
private fun wrapBinaryProvider(obj: dynamic, p: BinaryProvider) {
    defineLazyProp(obj, "bytes") { p.bytes }
    defineLazyProp(obj, "sizeAnalysis") { p.sizeAnalysis }
    defineLazyProp(obj, "strings") { p.strings }
}

private fun wrapGenericBinaryProvider(obj: dynamic, p: GenericBinaryProvider) {
    defineLazyProp(obj, "nativeFormat") { p.nativeFormat }
}

private fun wrapZipBinaryProvider(obj: dynamic, p: ZipBinaryProvider) {
    defineLazyProp(obj, "zipEntries") { p.zipEntries }
    defineLazyProp(obj, "displayEntries") { p.displayEntries }
}

private fun wrapTarBinaryProvider(obj: dynamic, p: TarBinaryProvider) {
    defineLazyProp(obj, "tarEntries") { p.tarEntries }
    defineLazyProp(obj, "displayEntries") { p.displayEntries }
}

private fun wrapNativeBinaryProvider(obj: dynamic, p: NativeBinaryProvider) {
    defineLazyProp(obj, "nativeFormat") { p.nativeFormat }
    defineLazyProp(obj, "nativeFunctions") { p.nativeFunctions }
    defineLazyProp(obj, "nativeImageInfo") { p.nativeImageInfo }
}

private fun wrapElfBinaryProvider(obj: dynamic, p: ElfBinaryProvider) {
    wrapNativeBinaryProvider(obj, p)
    defineLazyProp(obj, "header") { p.header }
    defineLazyProp(obj, "sections") { p.sections }
    defineLazyProp(obj, "programHeaders") { p.programHeaders }
    defineLazyProp(obj, "symbols") { p.symbols }
    defineLazyProp(obj, "relocations") { p.relocations }
    defineLazyProp(obj, "dynamicEntries") { p.dynamicEntries }
}

private fun wrapMachOBinaryProvider(obj: dynamic, p: MachOBinaryProvider) {
    wrapNativeBinaryProvider(obj, p)
    defineLazyProp(obj, "header") { p.header }
    defineLazyProp(obj, "loadCommands") { p.loadCommands }
    defineLazyProp(obj, "segments") { p.segments }
    defineLazyProp(obj, "sections") { p.sections }
    defineLazyProp(obj, "uuid") { p.uuid }
    defineLazyProp(obj, "symtab") { p.symtab }
    defineLazyProp(obj, "dysymtab") { p.dysymtab }
    defineLazyProp(obj, "symbols") { p.symbols }
    defineLazyProp(obj, "dylibs") { p.dylibs }
    defineLazyProp(obj, "relocations") { p.relocations }
}

private fun wrapFatMachOBinaryProvider(obj: dynamic, p: FatMachOBinaryProvider) {
    wrapNativeBinaryProvider(obj, p)
    defineLazyProp(obj, "fatHeader") { p.fatHeader }
    defineLazyProp(obj, "architectures") { p.architectures }
}

private fun wrapPeBinaryProvider(obj: dynamic, p: PeBinaryProvider) {
    wrapNativeBinaryProvider(obj, p)
    defineLazyProp(obj, "dosHeader") { p.dosHeader }
    defineLazyProp(obj, "coffHeader") { p.coffHeader }
    defineLazyProp(obj, "optionalHeader") { p.optionalHeader }
    defineLazyProp(obj, "sections") { p.sections }
    defineLazyProp(obj, "imports") { p.imports }
    defineLazyProp(obj, "exports") { p.exports }
    defineLazyProp(obj, "baseRelocations") { p.baseRelocations }
    defineLazyProp(obj, "debugEntries") { p.debugEntries }
    defineLazyProp(obj, "coffSymbols") { p.coffSymbols }
}

private fun wrapCoffBinaryProvider(obj: dynamic, p: CoffBinaryProvider) {
    wrapNativeBinaryProvider(obj, p)
    defineLazyProp(obj, "coffHeader") { p.coffHeader }
    defineLazyProp(obj, "sections") { p.sections }
    defineLazyProp(obj, "coffSymbols") { p.coffSymbols }
}

private fun wrapArchiveBinaryProvider(obj: dynamic, p: ArchiveBinaryProvider) {
    wrapNativeBinaryProvider(obj, p)
    defineLazyProp(obj, "members") { p.members }
}

private fun wrapWasmBinaryProvider(obj: dynamic, p: WasmBinaryProvider) {
    defineLazyProp(obj, "parsedModule") { p.parsedModule }
    defineLazyProp(obj, "wasmModule") { p.wasmModule }
    defineLazyProp(obj, "imports") { p.imports }
    defineLazyProp(obj, "exports") { p.exports }
    defineLazyProp(obj, "data") { p.data }
    defineLazyProp(obj, "nameSection") { p.nameSection }
    defineLazyProp(obj, "codeRecords") { p.codeRecords }
    defineLazyProp(obj, "functions") { p.functions }
}

private fun wrapClassFileBinaryProvider(obj: dynamic, p: ClassFileBinaryProvider) {
    defineLazyProp(obj, "parsedClassFile") { p.parsedClassFile }
}

private fun wrapDexBinaryProvider(obj: dynamic, p: DexBinaryProvider) {
    defineLazyProp(obj, "parsedDexFile") { p.parsedDexFile }
}

private fun wrapBinaryStringRecord(obj: dynamic, r: BinaryStringRecord) {
    obj.value = r.value
    obj.offset = r.offset
    obj.size = r.size
    obj.index = r.index
    obj.tag = r.tag
}

private fun wrapBinarySizeNode(obj: dynamic, n: BinarySizeNode) {
    obj.name = n.name
    obj.size = n.size
    obj.offset = n.offset
    defineLazyProp(obj, "children") { n.children }
}

/**
 * Fallback wrapper for unknown objects: walks prototype chain looking for
 * Kotlin/JS IR mangled getters (get_propName_hash) and exposes them under
 * clean names. Also handles plain JS objects with own enumerable properties.
 */
private fun wrapUnknownObject(value: Any?): dynamic {
    if (value == null) return null
    @Suppress("UNUSED_VARIABLE")
    val wrapInput = value.asDynamic()
    @Suppress("UNUSED_VARIABLE")
    val wrapFn: (dynamic) -> dynamic = { v -> wrapForJs(v) }
    return js("""
        (function(v, wrap) {
            if (v == null) return v;
            var t = typeof v;
            if (t === 'string' || t === 'number' || t === 'boolean') return v;
            if (typeof v.toArray === 'function') {
                var arr = v.toArray();
                var result = [];
                for (var i = 0; i < arr.length; i++) result.push(wrap(arr[i]));
                return result;
            }
            if (Array.isArray(v)) {
                var result2 = [];
                for (var j = 0; j < v.length; j++) result2.push(wrap(v[j]));
                return result2;
            }
            var wrapper = {};
            var foundAny = false;
            var seen = {};
            var getterPattern = /^get_(.+?)_[a-z0-9]+$/;
            // Walk prototype chain for Kotlin/JS IR mangled getters
            var p = Object.getPrototypeOf(v);
            while (p && p !== Object.prototype) {
                var names = Object.getOwnPropertyNames(p);
                for (var i = 0; i < names.length; i++) {
                    var jsName = names[i];
                    if (jsName === 'constructor') continue;
                    var match = jsName.match(getterPattern);
                    if (match) {
                        var cleanName = match[1];
                        if (!seen[cleanName] && typeof v[jsName] === 'function') {
                            seen[cleanName] = true;
                            foundAny = true;
                            (function(cn, src, fnName) {
                                Object.defineProperty(wrapper, cn, {
                                    get: function() { return wrap(src[fnName]()); },
                                    enumerable: true
                                });
                            })(cleanName, v, jsName);
                        }
                        continue;
                    }
                }
                p = Object.getPrototypeOf(p);
            }
            if (foundAny) return wrapper;
            // Fallback: expose own enumerable properties (plain JS objects)
            var keys = Object.keys(v);
            if (keys.length === 0) return v;
            for (var i = 0; i < keys.length; i++) {
                (function(k) {
                    Object.defineProperty(wrapper, k, {
                        get: function() { return wrap(v[k]); },
                        enumerable: true
                    });
                })(keys[i]);
            }
            return wrapper;
        })(wrapInput, wrapFn)
    """)
}

/**
 * Number of output calls between automatic yields to the browser
 * event loop. Keeping this small enough lets the polling coroutine in
 * [ScriptTabContent] drain accumulated lines into the Compose state,
 * so the first rows appear almost immediately while the rest of the
 * script is still running.
 */
private const val YIELD_EVERY = 100

/** Interval at which streamed script output is published to the view. */
private const val OUTPUT_POLL_MILLIS = 50L

/** Drains between re-segmentations while a script is still running. */
private const val DRAINS_PER_RESEGMENT = 5

/**
 * Height a table block shows before scrolling within itself.
 *
 * Capped against the viewport rather than a fixed pixel height: one table must
 * not fill the pane and hide the text around it, and a fixed height is too tall
 * on a short editor and needlessly short on a big display. Tables shorter than
 * the cap still shrink to their content. The webview fills the editor group, so
 * `vh` tracks the pane closely enough for a cap.
 */
private const val TABLE_BLOCK_MAX_BODY_HEIGHT = "clamp(120px, 45vh, 640px)"

/**
 * Executes a user script with the shared Hexana scripting globals — `binary`
 * from [scriptBinaryApi], `print`, `table` and `row` from [ScriptOutput], and
 * `open` — on the main thread. `open(path)` reports that it is unavailable here:
 * the webview has no synchronous file access.
 *
 * The globals are built by [ScriptValueJsBinder] from the same definition the
 * JVM runtime binds, so a script written for the IDE or the CLI behaves the same
 * here. `binary.raw`, and any member the curated surface does not define, resolve
 * through [wrapForJs] against the format-specific provider.
 *
 * Nothing is materialized up front: only the properties the script actually
 * touches are resolved, so the first output lines appear almost immediately.
 *
 * The user script is wrapped in a JS generator function where every output call
 * is replaced with `yield <call>`. An async driver loop steps through the
 * generator, yielding to the browser event loop every [YIELD_EVERY] steps via
 * `setTimeout(0)`, which lets the polling coroutine drain accumulated lines into
 * the UI while the script is still running.
 *
 * Returns a [Promise] that resolves to `null` on success or an error message.
 */
private fun executeScriptAsync(
    scriptContent: String,
    provider: BinaryProvider,
    fileName: String?,
    onLine: (String) -> Unit,
): Promise<String?> {
    return Promise { resolve, _ ->
        val binder = ScriptValueJsBinder(::wrapForJs)
        // `opener = null`: the webview has no synchronous file access, so
        // `open(path)` reports that rather than resolving to `window.open`.
        val globals = binder.globalsToJs(
            scriptGlobals(provider = provider, output = ScriptOutput(onLine), fileName = fileName)
        )

        // All three output globals are covered, not just print: a script that only
        // builds a table with table/row would otherwise never yield and would
        // freeze the webview until it finished.
        val generatorBody = ScriptYieldTransform.apply(scriptContent)

        @Suppress("UNUSED_VARIABLE")
        val yieldEvery = YIELD_EVERY

        @Suppress("UNUSED_VARIABLE")
        val genBody = generatorBody

        @Suppress("UNUSED_VARIABLE")
        val binary = globals.getValue("binary")

        @Suppress("UNUSED_VARIABLE")
        val openBinary = globals.getValue("open")

        @Suppress("UNUSED_VARIABLE")
        val print = globals.getValue("print")

        @Suppress("UNUSED_VARIABLE")
        val table = globals.getValue("table")

        @Suppress("UNUSED_VARIABLE")
        val row = globals.getValue("row")

        // Create a GeneratorFunction, run it, and drive it from an
        // async loop that yields to the event loop every YIELD_EVERY steps.
        js("""
            (function() {
                var GeneratorFunction = Object.getPrototypeOf(function*(){}).constructor;
                var gen;
                try {
                    gen = new GeneratorFunction('binary', 'open', 'print', 'table', 'row', genBody);
                } catch(e) {
                    resolve(e.message || 'Script compilation failed');
                    return;
                }
                var iter = gen(binary, openBinary, print, table, row);
                var count = 0;
                function step() {
                    try {
                        while (true) {
                            var r = iter.next();
                            if (r.done) { resolve(null); return; }
                            count++;
                            if (count % yieldEvery === 0) {
                                setTimeout(step, 0);
                                return;
                            }
                        }
                    } catch(e) {
                        resolve(e.message || 'Script execution failed');
                    }
                }
                setTimeout(step, 0);
            })()
        """)
    }
}

/**
 * Content composable for a script tab. Shows the script path with a
 * refresh button, executes the script, and renders output as a table
 * (CSV/TSV) or plain text with search.
 */
@Composable
internal fun ScriptTabContent(
    scriptPath: String,
    scriptContent: String,
    provider: BinaryProvider,
    onRequestRefresh: () -> Unit,
    scriptExecution: ScriptExecution? = null,
    binaryFileName: String? = null,
) {
    val host = LocalHost.current
    val outputLines = remember { mutableStateListOf<String>() }
    var isRunning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var executionKey by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("") }
    // Segmentation is O(output), so it is re-run on its own slower cadence than
    // the 50ms line drain: a script streaming many lines would otherwise re-parse
    // everything twenty times a second.
    var outputRevision by remember { mutableStateOf(0) }

    // Shared buffer: the print callback appends here from the setTimeout,
    // and a polling coroutine drains it into the reactive outputLines list.
    val pendingLines = remember { mutableListOf<String>() }
    var scriptDone by remember { mutableStateOf(false) }
    var scriptError by remember { mutableStateOf<String?>(null) }
    val runScope = remember { ScriptRunScope() }

    // Re-execute whenever scriptContent or executionKey changes
    LaunchedEffect(scriptContent, executionKey, scriptExecution) {
        if (scriptContent.isBlank()) return@LaunchedEffect
        val runGeneration = ++runScope.generation
        outputLines.clear()
        // Re-segment immediately: the parsed blocks are remembered against this
        // revision, so without it a rerun keeps showing the previous run's output
        // until the first drain bumps it.
        outputRevision++
        pendingLines.clear()
        error = null
        scriptError = null
        scriptDone = false
        isRunning = true

        fun acceptLine(line: String) {
            if (runScope.generation == runGeneration) pendingLines.add(line)
        }
        fun complete(errorMessage: String?) {
            if (runScope.generation == runGeneration) {
                scriptError = errorMessage
                scriptDone = true
            }
        }
        val handle = scriptExecution?.start(scriptContent, ::acceptLine, ::complete)
            ?: run {
                executeScriptAsync(scriptContent, provider, binaryFileName, ::acceptLine).then(::complete)
                NoopScriptExecutionHandle
            }

        try {
            // Poll for new lines until the script finishes.
            var drains = 0
            while (!scriptDone) {
                delay(OUTPUT_POLL_MILLIS)
                if (pendingLines.isNotEmpty()) {
                    outputLines.addAll(pendingLines)
                    pendingLines.clear()
                }
                if (++drains % DRAINS_PER_RESEGMENT == 0) outputRevision++
            }
            // Drain any remaining lines
            if (pendingLines.isNotEmpty()) {
                outputLines.addAll(pendingLines)
                pendingLines.clear()
            }
            outputRevision++
            error = scriptError
            isRunning = false
        } finally {
            handle.cancel()
            if (runScope.generation == runGeneration) runScope.generation++
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            height(100.percent)
            property("min-height", "0")
        }
    }) {
        // Header row: refresh button + script path
        Div({
            style {
                display(DisplayStyle.Flex)
                property("align-items", "center")
                property("gap", "6px")
                property("padding", "4px 8px")
                property("flex-shrink", "0")
                property("border-bottom", "1px solid var(--hexana-border, #333)")
            }
        }) {
            // Refresh button
            Div({
                classes("hexana-script-refresh")
                attr("title", "Reload and re-run script")
                style {
                    property("cursor", if (isRunning) "default" else "pointer")
                    property("opacity", if (isRunning) "0.4" else "1")
                    property("font-size", "14px")
                    property("user-select", "none")
                    property("padding", "2px 4px")
                }
                if (!isRunning) {
                    onClick {
                        onRequestRefresh()
                        executionKey++
                    }
                }
            }) {
                Text("\u21BB") // ↻ refresh symbol
            }
            // Opening the script from here attaches the type definitions first,
            // so the editor resolves the scripting globals.
            A(href = "#", {
                attr("title", "Open the script for editing")
                onClick { event ->
                    event.preventDefault()
                    host.openScriptForEditing(scriptPath, ScriptApiReference.jsConfig())
                }
                style {
                    property("font-size", "12px")
                    property("color", "var(--vscode-textLink-foreground, #3794ff)")
                    property("cursor", "pointer")
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("white-space", "nowrap")
                }
            }) {
                Text(scriptPath)
            }
        }

        // Re-segmenting on every drain keeps a streaming table growing in place
        // instead of appearing only once the script finishes.
        val blocks = remember(outputRevision) { parseScriptOutputBlocks(outputLines.toList()) }
        // A script whose entire output is one table gets the full table component,
        // with sorting and its own search — that is the shape most analysis scripts
        // produce, and nothing about it needs a document layout.
        val singleTable = blocks.singleOrNull() as? ScriptOutputBlock.Table
        val visibleBlocks = remember(blocks, filter) { filterScriptOutputBlocks(blocks, filter) }

        if (singleTable == null && blocks.isNotEmpty() && error == null) {
            // One filter for the whole pane, kept outside the scroll area: the
            // output is a document of several blocks, so a search box per table
            // would be both noisy and unable to reach the text between them.
            Div({
                style {
                    property("padding", "4px 8px")
                    property("flex-shrink", "0")
                }
            }) {
                Input(org.jetbrains.compose.web.attributes.InputType.Text) {
                    classes("hexana-dialog-input")
                    value(filter)
                    attr("placeholder", "Filter output\u2026")
                    onInput { event -> filter = event.value }
                    style { property("width", "100%") }
                }
            }
        }

        // Content area
        Div({
            style {
                property("flex", "1")
                property("min-height", "0")
                property("overflow-y", "auto")
            }
        }) {
            if (error != null) {
                Div({
                    style {
                        property("padding", "8px 12px")
                        property("color", "var(--vscode-errorForeground, #f44)")
                        property("font-size", "12px")
                        property("white-space", "pre-wrap")
                    }
                }) {
                    Text("Error: ${error}")
                }
            } else if (isRunning && outputLines.isEmpty()) {
                Div({
                    style {
                        property("padding", "8px 12px")
                        property("font-size", "12px")
                    }
                }) {
                    Text("Running script\u2026")
                }
            } else if (outputLines.isNotEmpty()) {
                ScriptBlocksOutput(visibleBlocks, singleTable)
            }

            if (isRunning && outputLines.isNotEmpty()) {
                Div({
                    style {
                        property("padding", "4px 12px")
                        property("font-size", "11px")
                        property("color", "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))")
                    }
                }) {
                    Text("Running\u2026")
                }
            }
        }
    }
}

/**
 * Renders text and tables in the order the script produced them.
 *
 * @param visible blocks after the pane filter has been applied
 * @param singleTable set when the whole output is one table, which is rendered
 *   with the full searchable/sortable table component instead
 */
@Composable
private fun ScriptBlocksOutput(
    visible: List<ScriptOutputBlock>,
    singleTable: ScriptOutputBlock.Table?,
) {
    if (singleTable != null) {
        ScriptSingleTableBlock(singleTable)
        return
    }

    if (visible.isEmpty()) {
        Div({
            style {
                property("padding", "8px 12px")
                property("font-size", "12px")
                property("opacity", "0.75")
            }
        }) {
            Text("No output matches the filter.")
        }
        return
    }

    for (block in visible) {
        when (block) {
            is ScriptOutputBlock.Text -> ScriptTextBlock(block)
            is ScriptOutputBlock.Table -> ScriptTableBlock(block)
        }
    }
}

@Composable
private fun ScriptTextBlock(block: ScriptOutputBlock.Text) {
    Div({
        style {
            property("padding", "4px 12px")
            property("font-family", "var(--vscode-editor-font-family, monospace)")
            property("font-size", "12px")
            property("white-space", "pre-wrap")
            property("word-break", "break-word")
        }
    }) {
        // Blank lines are the script's own spacing, so they are preserved as
        // empty rows rather than collapsed away.
        Text(block.lines.joinToString("\n"))
    }
}

/**
 * A table block inside the document flow.
 *
 * Uses the same sortable table component as every other tab, sized to its
 * content instead of to the pane so that several tables and the text between
 * them lay out in one scroll region. Its own search bar is left hidden: the pane
 * has one filter for the whole document, and a search box per table would be
 * noisy and could not reach the text between them.
 */
@Composable
private fun ScriptTableBlock(block: ScriptOutputBlock.Table) {
    Div({
        style {
            property("padding", "4px 12px 12px 12px")
        }
    }) {
        SearchableTable(
            headers = block.columns,
            maxBodyHeight = TABLE_BLOCK_MAX_BODY_HEIGHT,
            // Several tables share this pane, and the shortcut is bound on the
            // window, so they would all open their search at once.
            enableFindShortcut = false,
        ) {
            ScriptTableRows(block)
        }
    }
}

/** The whole output is one table: let it fill the pane and show its own search. */
@Composable
private fun ScriptSingleTableBlock(block: ScriptOutputBlock.Table) {
    SearchableTable(
        headers = block.columns,
        alwaysShowSearch = true,
    ) {
        ScriptTableRows(block)
    }
}

@Composable
private fun ScriptTableRows(block: ScriptOutputBlock.Table) {
    for (row in block.rows) {
        PanelTableRow {
            for (index in block.columns.indices) {
                Td({ classes("panel-td") }) {
                    Text(row.getOrElse(index) { "" }.trim())
                }
            }
        }
    }
}

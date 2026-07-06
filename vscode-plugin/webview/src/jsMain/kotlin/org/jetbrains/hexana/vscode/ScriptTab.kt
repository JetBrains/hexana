package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import kotlin.js.Promise

/**
 * Detects whether the first two lines look like CSV or TSV.
 * Returns the delimiter character if detected, or `null` for plain text.
 */
private fun detectDelimiter(lines: List<String>): Char? {
    if (lines.size < 2) return null
    for (delim in charArrayOf('\t', ',')) {
        val cols0 = lines[0].split(delim)
        val cols1 = lines[1].split(delim)
        if (cols0.size >= 2 && cols0.size == cols1.size) return delim
    }
    return null
}

/**
 * Wraps a Kotlin/JS value into a plain JS object so that user scripts can
 * access properties by their original Kotlin names (e.g. `binary.strings`).
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
 * Asynchronously materializes a lazily-wrapped JS object into a plain,
 * structured-cloneable object that can be transferred to a Web Worker.
 *
 * Unlike a synchronous deep-clone, this function yields to the browser
 * event loop every [YIELD_INTERVAL] items when processing arrays, so the
 * UI stays responsive during materialization of large datasets.
 */
private fun materializeForTransferAsync(wrapped: dynamic): Promise<dynamic> {
    @Suppress("UNUSED_VARIABLE")
    val input = wrapped
    return js("""
        (function() {
            var YIELD_INTERVAL = 500;
            function yieldToBrowser() {
                return new Promise(function(r) { setTimeout(r, 0); });
            }
            function deepClone(v) {
                if (v == null || typeof v !== 'object') return Promise.resolve(v);
                if (Array.isArray(v)) {
                    return cloneArray(v);
                }
                return cloneObject(v);
            }
            function cloneArray(arr) {
                var result = new Array(arr.length);
                var i = 0;
                function next() {
                    var end = Math.min(i + YIELD_INTERVAL, arr.length);
                    var chain = Promise.resolve();
                    while (i < end) {
                        (function(idx) {
                            chain = chain.then(function() {
                                return deepClone(arr[idx]).then(function(val) { result[idx] = val; });
                            });
                        })(i);
                        i++;
                    }
                    if (i < arr.length) {
                        return chain.then(yieldToBrowser).then(next);
                    }
                    return chain.then(function() { return result; });
                }
                return next();
            }
            function cloneObject(v) {
                var result = {};
                // Collect all property keys (own enumerable + getter-defined)
                var allKeys = {};
                var keys = Object.keys(v);
                for (var i = 0; i < keys.length; i++) allKeys[keys[i]] = true;
                var descs = Object.getOwnPropertyDescriptors(v);
                var dkeys = Object.keys(descs);
                for (var i = 0; i < dkeys.length; i++) {
                    if (descs[dkeys[i]].get) allKeys[dkeys[i]] = true;
                }
                var propNames = Object.keys(allKeys);
                var chain = Promise.resolve();
                for (var i = 0; i < propNames.length; i++) {
                    (function(k) {
                        chain = chain.then(function() {
                            try {
                                var val = v[k];
                                return deepClone(val).then(function(cloned) { result[k] = cloned; });
                            } catch(e) {
                                result[k] = null;
                                return Promise.resolve();
                            }
                        });
                    })(propNames[i]);
                }
                return chain.then(function() { return result; });
            }
            return deepClone(input);
        })()
    """).unsafeCast<Promise<dynamic>>()
}

/**
 * Executes a JavaScript script string with `binary` (BinaryProvider) and
 * `print` (line collector) bindings in a Web Worker so the UI thread is
 * never blocked.
 *
 * The wrapped binary is materialized asynchronously (yielding to the event
 * loop for large arrays) and then transferred to the worker.
 * The worker posts `print` lines back via `postMessage`.
 *
 * Returns a [Promise] that resolves to `null` on success or an error message.
 */
private fun executeScriptAsync(
    scriptContent: String,
    provider: BinaryProvider,
    onLine: (String) -> Unit,
): Promise<String?> {
    return Promise { resolve, _ ->
        val wrappedBinary = wrapForJs(provider)

        materializeForTransferAsync(wrappedBinary).then<dynamic> { plainBinary: dynamic ->
            // Build a worker from a Blob URL
            @Suppress("UNUSED_VARIABLE")
            val workerCode = """
                self.onmessage = function(e) {
                    var binary = e.data.binary;
                    var scriptBody = e.data.script;
                    var lines = [];
                    function print(text) {
                        lines.push(String(text));
                        if (lines.length >= 100) {
                            self.postMessage({ type: 'lines', lines: lines });
                            lines = [];
                        }
                    }
                    try {
                        var fn = new Function('binary', 'print', scriptBody);
                        fn(binary, print);
                        if (lines.length > 0) self.postMessage({ type: 'lines', lines: lines });
                        self.postMessage({ type: 'done', error: null });
                    } catch(err) {
                        if (lines.length > 0) self.postMessage({ type: 'lines', lines: lines });
                        self.postMessage({ type: 'done', error: err.message || 'Script execution failed' });
                    }
                };
            """
            val worker = js("new Worker(URL.createObjectURL(new Blob([workerCode], { type: 'application/javascript' })))")

            worker.onmessage = { event: dynamic ->
                val data = event.data
                val type = data.type as String
                if (type == "lines") {
                    val arr = data.lines
                    val len = arr.length as Int
                    for (i in 0 until len) {
                        onLine(arr[i] as String)
                    }
                } else if (type == "done") {
                    val err = data.error
                    resolve(err?.toString())
                    worker.terminate()
                }
                Unit
            }

            worker.onerror = { event: dynamic ->
                resolve(event.message?.toString() ?: "Worker error")
                worker.terminate()
                Unit
            }

            @Suppress("UNUSED_VARIABLE")
            val scriptBody = scriptContent
            @Suppress("UNUSED_VARIABLE")
            val binaryData = plainBinary
            js("worker.postMessage({ binary: binaryData, script: scriptBody })")
        }
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
) {
    val outputLines = remember { mutableStateListOf<String>() }
    var isRunning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var executionKey by remember { mutableStateOf(0) }

    // Shared buffer: the print callback appends here from the setTimeout,
    // and a polling coroutine drains it into the reactive outputLines list.
    val pendingLines = remember { mutableListOf<String>() }
    var scriptDone by remember { mutableStateOf(false) }
    var scriptError by remember { mutableStateOf<String?>(null) }

    // Re-execute whenever scriptContent or executionKey changes
    LaunchedEffect(scriptContent, executionKey) {
        if (scriptContent.isBlank()) return@LaunchedEffect
        outputLines.clear()
        pendingLines.clear()
        error = null
        scriptError = null
        scriptDone = false
        isRunning = true

        // Launch script in a Web Worker
        val promise = executeScriptAsync(scriptContent, provider) { line ->
            pendingLines.add(line)
        }
        promise.then { err -> scriptError = err; scriptDone = true }

        // Poll for new lines until the script finishes
        while (!scriptDone) {
            delay(50)
            if (pendingLines.isNotEmpty()) {
                outputLines.addAll(pendingLines)
                pendingLines.clear()
            }
        }
        // Drain any remaining lines
        if (pendingLines.isNotEmpty()) {
            outputLines.addAll(pendingLines)
            pendingLines.clear()
        }
        error = scriptError
        isRunning = false
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
            Span({
                style {
                    property("font-size", "12px")
                    property("color", "var(--hexana-fg)")
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("white-space", "nowrap")
                }
            }) {
                Text(scriptPath)
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
                val delimiter = remember(outputLines.size) {
                    if (outputLines.size >= 2) detectDelimiter(outputLines) else null
                }
                if (delimiter != null) {
                    ScriptTableOutput(outputLines, delimiter)
                } else {
                    ScriptTextOutput(outputLines)
                }
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

@Composable
private fun ScriptTableOutput(lines: List<String>, delimiter: Char) {
    val headers = remember(lines.firstOrNull()) {
        lines.firstOrNull()?.split(delimiter)?.map { it.trim() } ?: emptyList()
    }
    val dataRows = remember(lines.size) {
        if (lines.size > 1) lines.subList(1, lines.size) else emptyList()
    }

    SearchableTable(
        headers = headers,
        alwaysShowSearch = true,
    ) {
        for (row in dataRows) {
            val cells = row.split(delimiter)
            PanelTableRow {
                for ((i, header) in headers.withIndex()) {
                    Td({
                        classes("panel-td")
                    }) {
                        Text(cells.getOrElse(i) { "" }.trim())
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptTextOutput(lines: List<String>) {
    SearchableTable(
        headers = listOf("Output"),
        alwaysShowSearch = true,
    ) {
        for (line in lines) {
            PanelTableRow {
                Td({
                    classes("panel-td")
                }) {
                    Text(line)
                }
            }
        }
    }
}

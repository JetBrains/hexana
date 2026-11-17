package org.jetbrains.hexana.shared

/**
 * Generates the HTML page served by the browser runner.
 *
 * The page loads the `.wasm` file from the same origin, builds import
 * stubs dynamically from the module's declared imports, instantiates
 * the module, and calls the requested export.
 *
 * This function is shared between the IntelliJ plugin
 * (`BrowserCommandLineState`) and the VS Code extension
 * (`WasmRunConfigurations`) so that both produce identical output.
 *
 * @param wasmFileName the basename of the `.wasm` file (served at `/<wasmFileName>`)
 * @param exportName   the export function to invoke (e.g. `_start`)
 * @param programArgs  whitespace-separated program arguments passed to the export
 */
fun generateBrowserRunnerHtml(wasmFileName: String, exportName: String, programArgs: String): String {
    val argsArray = if (programArgs.isNotBlank()) {
        programArgs.trim().split(Regex("\\s+"))
            .joinToString(", ") { "\"$it\"" }
    } else {
        ""
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <title>WebAssembly Runner — $wasmFileName</title>
            <style>
                body { font-family: system-ui, sans-serif; margin: 2rem; background: #1e1e1e; color: #d4d4d4; }
                h1 { color: #569cd6; font-size: 1.4rem; }
                pre { background: #252526; padding: 1rem; border-radius: 4px; overflow-x: auto; white-space: pre-wrap; }
                .info { color: #9cdcfe; }
                .error { color: #f44747; }
                .success { color: #6a9955; }
            </style>
        </head>
        <body>
            <h1>WebAssembly Runner</h1>
            <p class="info">Module: <code>$wasmFileName</code> | Export: <code>$exportName</code></p>
            <pre id="output"></pre>
            <script>
                const output = document.getElementById('output');
                function log(msg, cls) {
                    const span = document.createElement('span');
                    span.className = cls || '';
                    span.textContent = msg + '\n';
                    output.appendChild(span);
                    console.log(msg);
                }

                (async () => {
                    try {
                        const response = await fetch('$wasmFileName');
                        const bytes = await response.arrayBuffer();

                        // Build import stubs dynamically from the module's declared imports
                        const module = await WebAssembly.compile(bytes);
                        const imports = WebAssembly.Module.imports(module);
                        const importObject = {};
                        for (const { module: mod, name, kind } of imports) {
                            if (!importObject[mod]) {
                                importObject[mod] = {};
                            }
                            if (kind === 'function') {
                                importObject[mod][name] = (...args) => {
                                    log(mod + '.' + name + '(' + args.join(', ') + ')', 'info');
                                    return 0;
                                };
                            } else if (kind === 'memory') {
                                importObject[mod][name] = new WebAssembly.Memory({ initial: 256 });
                            } else if (kind === 'table') {
                                importObject[mod][name] = new WebAssembly.Table({ initial: 0, element: 'anyfunc' });
                            } else if (kind === 'global') {
                                importObject[mod][name] = new WebAssembly.Global({ value: 'i32', mutable: true }, 0);
                            }
                        }
                        const instance = await WebAssembly.instantiate(module, importObject);

                        const exportFn = instance.exports['$exportName'];
                        if (typeof exportFn === 'function') {
                            log('Calling export: $exportName($argsArray)');
                            const result = exportFn($argsArray);
                            log('Result: ' + result, 'success');
                        } else if (exportFn !== undefined) {
                            log('Export "$exportName" is not a function, value: ' + exportFn, 'info');
                        } else {
                            const available = Object.keys(instance.exports).join(', ');
                            log('Export "$exportName" not found. Available exports: ' + available, 'error');
                        }
                    } catch (e) {
                        log('Error: ' + e.message, 'error');
                        console.error(e);
                    }
                })();
            </script>
        </body>
        </html>
    """.trimIndent()
}

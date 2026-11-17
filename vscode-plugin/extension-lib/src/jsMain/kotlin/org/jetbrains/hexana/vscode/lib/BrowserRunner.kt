@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.generateBrowserRunnerHtml

/**
 * Generates the HTML page for the browser-based Wasm runner.
 *
 * Delegates to the shared [generateBrowserRunnerHtml] so that both
 * the IntelliJ plugin and the VS Code extension produce identical
 * output.
 *
 * @param wasmFileName the basename of the `.wasm` file
 * @param exportName   the export function to invoke
 * @param programArgs  whitespace-separated program arguments
 */
@JsExport
fun generateBrowserHtml(wasmFileName: String, exportName: String, programArgs: String): String =
    generateBrowserRunnerHtml(wasmFileName, exportName, programArgs)

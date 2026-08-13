import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";

interface EmbeddedWebviewAssets {
    readonly scriptBase64: string;
    readonly wasmPrinterBase64: string;
}

const embeddedAssetsByExtensionPath = new Map<string, EmbeddedWebviewAssets>();

/**
 * Builds the HTML content loaded into the custom-editor webview.
 *
 * The page embeds the Kotlin/JS production bundle and Wasm printer, attaches
 * the Compose for Web root to `<div id="root">`, and listens for `message`
 * events that provide the binary payload.
 */
export function getWebviewContent(webview: vscode.Webview, extensionUri: vscode.Uri): string {
    const assets = readEmbeddedWebviewAssets(extensionUri);
    const cssUri = webview.asWebviewUri(
        vscode.Uri.joinPath(extensionUri, "media", "editor.css"),
    );
    const nonce = getNonce();

    return /* html */`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none';
                   style-src ${webview.cspSource} 'unsafe-inline';
                   script-src 'nonce-${nonce}' blob: 'wasm-unsafe-eval' 'unsafe-eval';
                   font-src ${webview.cspSource};
                   connect-src ${webview.cspSource} blob: https://api.osv.dev;
                   img-src ${webview.cspSource} data:;">
    <link rel="stylesheet" href="${cssUri}">
    <title>Hexana Wasm Editor</title>
</head>
<body>
    <div id="root"></div>
    <script nonce="${nonce}">
        /*
         * Keep these assets embedded even though loading them with a normal
         * <script src="..."> and webview.asWebviewUri(...) looks simpler.
         * This is a compatibility boundary, not a workaround for Kotlin/JS,
         * webpack, Terser, or Chromium's JavaScript parser.
         *
         * VS Code 1.131 changed local webview resource delivery from sending a
         * complete ArrayBuffer to streaming 256 KiB file chunks through a
         * transferable ReadableStream. In the Electron host, those chunks were
         * enqueued as Uint8Array views over pooled backing buffers without first
         * copying the view into an independently owned ArrayBuffer. On Windows,
         * the transferred view could therefore lose its byte offset/extent or
         * observe reused backing storage. A valid multi-megabyte JavaScript file
         * then arrived in the webview with duplicated or displaced chunks. The
         * resulting syntax error consistently appeared at 512 KiB, but changing
         * Kotlin labels, minification, or line wrapping only changed which valid
         * token happened to be damaged at that boundary.
         *
         * We confirmed this by archiving the installed bundle and parsing those
         * exact bytes successfully with Chrome 148.0.7778.280, while fetch() in
         * the VS Code 1.131 webview returned different and sometimes nearly
         * doubled lengths for the same file. VS Code fixed the underlying issue
         * in 1.132.1 by copying every streamed chunk before transfer:
         * https://github.com/microsoft/vscode/commit/06a2bc84d0555f4c7ebd176809673e61fa49c6ff
         *
         * Hexana still supports affected VS Code 1.131 installations. Encoding
         * both large generated assets in webview.html sends them through the
         * webview-content channel instead of the broken local-resource stream.
         * Both files must take this path: webview.js and
         * wasmprinter_wrapper.wasm are each large enough to be split into
         * multiple 256 KiB chunks, so leaving either one behind
         * asWebviewUri(...) would preserve the same corruption risk.
         *
         * The bootstrap reconstructs byte-exact Blob URLs in the renderer, so
         * the normal browser script and WebAssembly loaders remain in use. The
         * transported base64 representation costs about one third more bytes,
         * but that overhead is preferable to silently changing executable bytes.
         * The renderer removes the bootstrap element after decoding and revokes
         * the Blob URLs when their useful lifetime ends; the extension host keeps
         * one encoded copy per extension path so opening another editor does not
         * reread and re-encode both multi-megabyte files.
         *
         * Do not replace this with formatting changes, pin an older VS Code, or
         * convert these Blob URLs back to asWebviewUri resources. Removing the
         * workaround is safe only after Hexana's minimum supported VS Code
         * version is newer than every release containing the stream-transfer bug.
         */
        (() => {
            const bootstrapElement = document.currentScript;
            const createAssetUrl = (encoded, mimeType) => {
                const decoded = atob(encoded);
                const bytes = new Uint8Array(decoded.length);
                for (let index = 0; index < decoded.length; index++) {
                    bytes[index] = decoded.charCodeAt(index);
                }
                return URL.createObjectURL(new Blob([bytes], { type: mimeType }));
            };

            const wasmPrinterUrl = createAssetUrl(
                "${assets.wasmPrinterBase64}",
                "application/wasm",
            );
            window.__wasmPrinterUri = wasmPrinterUrl;
            window.addEventListener("pagehide", () => URL.revokeObjectURL(wasmPrinterUrl), { once: true });

            const webviewScriptUrl = createAssetUrl(
                "${assets.scriptBase64}",
                "text/javascript",
            );
            const webviewScript = document.createElement("script");
            webviewScript.nonce = "${nonce}";
            webviewScript.src = webviewScriptUrl;
            webviewScript.addEventListener("load", () => URL.revokeObjectURL(webviewScriptUrl), { once: true });
            webviewScript.addEventListener("error", () => {
                URL.revokeObjectURL(webviewScriptUrl);
                console.error("[Hexana] Failed to load the embedded webview bundle");
            }, { once: true });
            document.body.appendChild(webviewScript);

            // Drop the multi-megabyte base64 source from the DOM after decoding.
            bootstrapElement?.remove();
        })();
    </script>
</body>
</html>`;
}

function readEmbeddedWebviewAssets(extensionUri: vscode.Uri): EmbeddedWebviewAssets {
    const extensionPath = extensionUri.fsPath;
    const cached = embeddedAssetsByExtensionPath.get(extensionPath);
    if (cached) {
        return cached;
    }

    const outputPath = path.join(
        extensionPath,
        "webview",
        "build",
        "kotlin-webpack",
        "js",
        "productionExecutable",
    );
    const assets = {
        scriptBase64: fs.readFileSync(path.join(outputPath, "webview.js")).toString("base64"),
        wasmPrinterBase64: fs.readFileSync(path.join(outputPath, "wasmprinter_wrapper.wasm")).toString("base64"),
    };
    embeddedAssetsByExtensionPath.set(extensionPath, assets);
    return assets;
}

function getNonce(): string {
    const possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let text = "";
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}

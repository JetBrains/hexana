import * as vscode from "vscode";

/**
 * Builds the HTML content loaded into the custom-editor webview.
 *
 * The page embeds the Kotlin/JS bundle produced by
 * `:vscode-plugin:webview:jsBrowserProductionWebpack` and a thin boot
 * stylesheet.  The Kotlin entry point is expected to attach a Compose for Web
 * root to `<div id="root">` and listen for `message` events to receive the
 * binary payload.
 */
export function getWebviewContent(webview: vscode.Webview, extensionUri: vscode.Uri): string {
    const webviewDistPath = vscode.Uri.joinPath(
        extensionUri, "webview", "build", "dist", "js", "productionExecutable",
    );
    const scriptUri = webview.asWebviewUri(
        vscode.Uri.joinPath(webviewDistPath, "webview.js"),
    );
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
                   script-src 'nonce-${nonce}';
                   font-src ${webview.cspSource};
                   img-src ${webview.cspSource} data:;">
    <link rel="stylesheet" href="${cssUri}">
    <title>Hexana Wasm Editor</title>
</head>
<body>
    <div id="root"></div>
    <script nonce="${nonce}" src="${scriptUri}"></script>
</body>
</html>`;
}

function getNonce(): string {
    const possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let text = "";
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}

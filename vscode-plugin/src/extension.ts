import * as path from "path";
import * as vscode from "vscode";
import { WasmEditorProvider } from "./WasmEditorProvider";
import type * as ExtensionLib from "./extension-lib";

// eslint-disable-next-line @typescript-eslint/no-var-requires
const extensionLib: typeof ExtensionLib | null = (() => {
    try {
        const mod = require("../extension-lib/build/kotlin-webpack/js/productionExecutable/extension-lib.js");
        return mod.hexanaExtensionLib.org.jetbrains.hexana.vscode.lib;
    } catch {
        console.warn("[Hexana] extension-lib not found — analytics disabled");
        return null;
    }
})();

export function activate(context: vscode.ExtensionContext): void {
    // Initialize analytics before registering providers so that events
    // captured during the first file open are not lost.
    if (extensionLib) {
        const storagePath = path.join(context.globalStorageUri.fsPath, "hexana-analytics-id");
        extensionLib.initAnalytics(storagePath, vscode.env.isTelemetryEnabled, vscode.env.appName, vscode.version);

        // Re-evaluate consent when the user toggles VS Code telemetry.
        context.subscriptions.push(
            vscode.env.onDidChangeTelemetryEnabled((enabled) => {
                // Flush and shut down the previous client before re-initializing
                // so pending events are not lost and connections are released.
                extensionLib.shutdownAnalytics();
                extensionLib.initAnalytics(storagePath, enabled, vscode.env.appName, vscode.version);
            }),
        );
    }

    context.subscriptions.push(WasmEditorProvider.register(context));
}

export function deactivate(): void {
    if (extensionLib) {
        extensionLib.shutdownAnalytics();
    }
}

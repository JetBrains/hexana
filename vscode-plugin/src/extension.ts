import * as https from "https";
import * as path from "path";
import * as vscode from "vscode";
import { registerWasmDebugAdapter } from "./WasmDebugAdapter";
import { registerWasmDebugView } from "./WasmDebugView";
import { WasmEditorProvider } from "./WasmEditorProvider";
import type * as ExtensionLib from "./extension-lib";
import { registerHexanaMcpServerCommands } from "./mcpServerDownloader";
import { initWasiSdkManager, registerWasiSdkCommands } from "./wasiSdkManager";
import { registerMcpServerDefinitionProvider } from "./mcpServerProvider";
import { registerNativeBinaryDetector } from "./NativeBinaryFileTypeDetector";

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

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    const locationAllowed = await checkLocation();
    if (!locationAllowed) {
        return;
    }

    // Initialize analytics before registering providers so that events
    // captured during the first file open are not lost.
    if (extensionLib) {
        const storagePath = path.join(context.globalStorageUri.fsPath, "hexana-analytics-id");
        extensionLib.initAnalytics(storagePath, isHexanaTelemetryEnabled(), vscode.env.appName, vscode.version);

        const reinitAnalytics = () => {
            // Flush and shut down the previous client before re-initializing
            // so pending events are not lost and connections are released.
            extensionLib.shutdownAnalytics();
            extensionLib.initAnalytics(storagePath, isHexanaTelemetryEnabled(), vscode.env.appName, vscode.version);
        };

        // Re-evaluate consent when the user toggles VS Code telemetry.
        context.subscriptions.push(
            vscode.env.onDidChangeTelemetryEnabled(() => reinitAnalytics()),
        );

        // Re-evaluate consent when the user toggles the Hexana statistics setting.
        context.subscriptions.push(
            vscode.workspace.onDidChangeConfiguration((e) => {
                if (e.affectsConfiguration("hexana.enableStatistics")) {
                    reinitAnalytics();
                }
            }),
        );
    }

    context.subscriptions.push(WasmEditorProvider.register(context));
    context.subscriptions.push(WasmEditorProvider.registerCompareCommand(context));
    context.subscriptions.push(WasmEditorProvider.registerCompareBinaryCommand(context));
    registerWasmDebugAdapter(context);
    registerWasmDebugView(context);
    registerHexanaMcpServerCommands(context);
    initWasiSdkManager(context);
    registerWasiSdkCommands(context);
    try {
        registerMcpServerDefinitionProvider(context, {
            isTelemetryEnabled: isHexanaTelemetryEnabled,
        });
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        console.warn(`[Hexana] MCP integration disabled: ${message}`);
    }

    // Register content-based binary file type detection for extensionless files.
    registerNativeBinaryDetector(context);

    // Ensure .wasm files are associated with the Hexana custom editor so that
    // VS Code does not show the "binary file not displayed" warning.  The
    // `configurationDefaults` in package.json provides the default, but VS Code
    // may ignore it on first activation because the custom editor is not yet
    // registered when the association is evaluated.  Writing the setting
    // programmatically at activation time guarantees the association is present
    // for all subsequent file opens.
    ensureWasmEditorAssociation();

    // If a .wasm file was opened before the extension activated, VS Code may
    // have shown the binary-file placeholder instead of the custom editor.
    // Re-open any such tabs with our custom editor now that it is registered.
    reopenWasmBinaryTabs();
}

export function deactivate(): void {
    if (extensionLib) {
        extensionLib.shutdownAnalytics();
    }
}

function checkLocation(): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
        const req = https.get("https://download.jetbrains.com/check-location", (res) => {
            // Consume the response body to free resources.
            res.resume();
            if (res.statusCode === 451) {
                vscode.window.showErrorMessage(
                    "Unavailable For Legal Reasons: We are sorry but we are currently unable " +
                    "to provide our products or services to you due to export control regulations",
                );
                resolve(false);
            } else {
                resolve(true);
            }
        });
        req.on("error", () => {
            // Network failures should not prevent the plugin from loading.
            resolve(true);
        });
        req.end();
    });
}

function isHexanaTelemetryEnabled(): boolean {
    const hexanaConfig = vscode.workspace.getConfiguration("hexana");
    return vscode.env.isTelemetryEnabled && hexanaConfig.get<boolean>("enableStatistics", true);
}

/**
 * Ensures that `workbench.editorAssociations` maps `*.wasm` to the Hexana
 * custom editor in the user's global settings.  This prevents VS Code from
 * showing the "binary file not displayed" placeholder for `.wasm` files.
 *
 * The function only writes the setting once — it will not overwrite a value
 * the user has already configured for `*.wasm`.
 */
function ensureWasmEditorAssociation(): void {
    const config = vscode.workspace.getConfiguration("workbench");
    const inspected = config.inspect<Record<string, string>>("editorAssociations");

    // Ensure all supported binary extensions are associated with the Hexana editor.
    const userValue = inspected?.globalValue ?? {};
    const patterns = ["*.wasm", "*.jar", "*.zip", "*.war", "*.apk"];
    const merged: Record<string, string> = { ...userValue };
    let changed = false;
    for (const pattern of patterns) {
        if (merged[pattern] === undefined) {
            merged[pattern] = "hexana.wasmEditor";
            changed = true;
        }
    }
    if (!changed) {
        return;
    }
    config.update("editorAssociations", merged, vscode.ConfigurationTarget.Global);
}

/**
 * Scans all currently open tab groups for `.wasm` tabs that VS Code opened
 * with the default text/binary editor (showing the "binary file not
 * displayed" placeholder) and re-opens them with the Hexana custom editor,
 * closing the stale binary-warning tabs so the user doesn't end up with
 * duplicate tabs for the same file.
 *
 * This handles the race where the file was opened before the extension
 * finished activating and registering its custom editor provider.
 */
function reopenWasmBinaryTabs(): void {
    // Collect stale tabs first — iterating while mutating the tab list is
    // unreliable.
    const staleTabs: { tab: vscode.Tab; uri: vscode.Uri }[] = [];

    for (const group of vscode.window.tabGroups.all) {
        for (const tab of group.tabs) {
            const input = tab.input;
            // TabInputText is used for the built-in text/binary editor.
            // Our custom editor uses TabInputCustom instead.
            const p = input instanceof vscode.TabInputText ? input.uri.path.toLowerCase() : "";
            if (input instanceof vscode.TabInputText && (p.endsWith(".wasm") || p.endsWith(".jar") || p.endsWith(".zip") || p.endsWith(".war") || p.endsWith(".apk"))) {
                staleTabs.push({ tab, uri: input.uri });
            }
        }
    }

    for (const { tab, uri } of staleTabs) {
        // Close the binary-warning tab first, then open with the custom
        // editor.  Closing first avoids the stale-reference problem where
        // the tab object captured in a closure no longer matches any live
        // tab after `vscode.openWith` rearranges the tab bar.
        vscode.window.tabGroups.close(tab).then(() => {
            vscode.commands.executeCommand("vscode.openWith", uri, "hexana.wasmEditor");
        });
    }
}

import * as vscode from "vscode";
import { WasmEditorProvider } from "./WasmEditorProvider";

export function activate(context: vscode.ExtensionContext): void {
    context.subscriptions.push(WasmEditorProvider.register(context));
}

export function deactivate(): void {
    // nothing to dispose
}

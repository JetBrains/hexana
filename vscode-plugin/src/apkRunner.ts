import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { execFile, spawn } from "child_process";
import * as vscode from "vscode";
import { extensionLib } from "./WasmRunConfigurations";

/**
 * Trivial "Run APK on an existing Android emulator": reuse a running emulator or boot an
 * existing AVD, `adb install`, then launch the launcher activity. No SDK/AVD provisioning.
 *
 * SDK layout, commands, output parsing and messages come from the shared Kotlin
 * `AndroidEmulatorRun` (via extension-lib), the same logic the IntelliJ plugin uses; only
 * process execution and the VS Code UI live here.
 */

export interface AndroidSdk {
    readonly root: string;
    readonly adb: string;
    readonly emulator: string;
}

const isWindows = process.platform === "win32";

function isExecutable(file: string): boolean {
    try {
        fs.accessSync(file, isWindows ? fs.constants.F_OK : fs.constants.X_OK);
        return fs.statSync(file).isFile();
    } catch {
        return false;
    }
}

/** Finds an SDK with `adb` and the emulator package, or `null`. File checks only — no processes. */
export function detectAndroidSdk(): AndroidSdk | null {
    const lib = extensionLib;
    if (!lib) {
        return null;
    }
    const keys = ["ANDROID_HOME", "ANDROID_SDK_ROOT", "LOCALAPPDATA"].filter((k) => process.env[k]);
    const candidates = lib.androidSdkRootCandidates(keys, keys.map((k) => process.env[k] ?? ""), os.homedir());
    const root = candidates.find((c) => {
        try {
            return fs.statSync(lib.androidPlatformToolsDir(c)).isDirectory();
        } catch {
            return false;
        }
    });
    if (!root) {
        return null;
    }
    const adb = lib.androidAdbPath(root, isWindows);
    const emulator = lib.androidEmulatorPath(root, isWindows);
    if (!isExecutable(adb) || !isExecutable(emulator)) {
        return null;
    }
    return { root, adb, emulator };
}

/** Runs a tool and resolves with stdout+stderr, or `null` on spawn failure or timeout. */
function runTool(command: string, args: string[], timeoutMs = 30_000): Promise<string | null> {
    return new Promise((resolve) => {
        execFile(command, args, { timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024 }, (error, stdout, stderr) => {
            const output = `${stdout ?? ""}${stderr ?? ""}`;
            // A non-zero exit still carries output worth parsing (e.g. `adb install` failures).
            if (error && (error as NodeJS.ErrnoException).code === "ENOENT") {
                resolve(null);
            } else if (error && error.killed) {
                resolve(null);
            } else {
                resolve(output);
            }
        });
    });
}

class ApkRunError extends Error {}

type EmulatorTarget = { kind: "running"; serial: string } | { kind: "boot"; avd: string };

/**
 * Reuses a running emulator if there is one; otherwise lets the user pick an AVD (when there is
 * more than one) to boot. Progress and the result are shown as VS Code notifications.
 */
export async function runApkOnEmulator(apkPath: string): Promise<void> {
    const lib = extensionLib;
    const sdk = detectAndroidSdk();
    const apkName = path.basename(apkPath);
    if (!lib || !sdk) {
        vscode.window.showErrorMessage("Android SDK with the emulator was not found.");
        return;
    }

    const devices = await runTool(sdk.adb, lib.androidDevicesArgs());
    const running = devices ? lib.androidParseOnlineEmulators(devices)[0] : undefined;
    let target: EmulatorTarget;
    if (running) {
        target = { kind: "running", serial: running };
    } else {
        const avdOutput = await runTool(sdk.emulator, lib.androidListAvdsArgs(), 15_000);
        const avds = avdOutput ? lib.androidParseAvdList(avdOutput) : [];
        if (avds.length === 0) {
            vscode.window.showWarningMessage(lib.androidNoAvdMessage(apkName));
            return;
        }
        const avd = avds.length === 1
            ? avds[0]
            : await vscode.window.showQuickPick(avds, { title: `Run ${apkName} on Emulator` });
        if (!avd) {
            return;
        }
        target = { kind: "boot", avd };
    }

    const label = target.kind === "running" ? target.serial : target.avd;
    await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `Running ${apkName} on ${label}`, cancellable: true },
        async (progress, token) => {
            try {
                const message = await runApk(sdk, apkPath, target, progress, token);
                vscode.window.showInformationMessage(message);
            } catch (e) {
                if (e instanceof ApkRunError) {
                    vscode.window.showErrorMessage(e.message);
                } else if (!token.isCancellationRequested) {
                    vscode.window.showErrorMessage(`Failed to run ${apkName}: ${e}`);
                }
            }
        },
    );
}

async function runApk(
    sdk: AndroidSdk,
    apkPath: string,
    target: EmulatorTarget,
    progress: vscode.Progress<{ message?: string }>,
    token: vscode.CancellationToken,
): Promise<string> {
    const lib = extensionLib!;
    const apkName = path.basename(apkPath);

    let serial: string;
    if (target.kind === "running") {
        serial = target.serial;
    } else {
        progress.report({ message: `Starting emulator ${target.avd}…` });
        // Detached: the emulator outlives this task and the VS Code window.
        spawn(sdk.emulator, lib.androidBootArgs(target.avd), { detached: true, stdio: "ignore" }).unref();
        serial = await waitForBoot(sdk, progress, token);
    }

    progress.report({ message: `Installing ${apkName}…` });
    const install = await runTool(sdk.adb, lib.androidInstallArgs(serial, apkPath), lib.androidInstallTimeoutMs);
    if (install === null || !lib.androidIsInstallSuccess(install)) {
        throw new ApkRunError(lib.androidInstallFailedMessage(install));
    }

    const packageName = await readPackageName(sdk, apkPath);
    if (!packageName) {
        return lib.androidUnknownPackageMessage(apkName, serial);
    }

    progress.report({ message: `Launching ${packageName}…` });
    const launch = await runTool(sdk.adb, lib.androidLaunchArgs(serial, packageName));
    if (launch === null || !lib.androidHasLauncherActivity(launch)) {
        return lib.androidNoLauncherMessage(packageName, serial);
    }
    return lib.androidLaunchedMessage(packageName, serial);
}

async function readPackageName(sdk: AndroidSdk, apkPath: string): Promise<string | null> {
    const lib = extensionLib!;
    let versions: string[] = [];
    try {
        versions = fs.readdirSync(lib.androidBuildToolsDir(sdk.root));
    } catch {
        return null;
    }
    const aapt = lib.androidAaptCandidates(sdk.root, versions, isWindows).find(isExecutable);
    if (!aapt) {
        return null;
    }
    const output = await runTool(aapt, lib.androidPackageNameArgs(aapt, apkPath));
    return output === null ? null : lib.androidParsePackageName(output);
}

async function waitForBoot(
    sdk: AndroidSdk,
    progress: vscode.Progress<{ message?: string }>,
    token: vscode.CancellationToken,
): Promise<string> {
    const lib = extensionLib!;
    const deadline = Date.now() + lib.androidBootTimeoutMs;
    while (Date.now() < deadline) {
        if (token.isCancellationRequested) {
            throw new Error("cancelled");
        }
        const devices = await runTool(sdk.adb, lib.androidDevicesArgs());
        const serial = devices ? lib.androidParseOnlineEmulators(devices)[0] : undefined;
        if (serial) {
            progress.report({ message: `Waiting for ${serial} to boot…` });
            const booted = await runTool(sdk.adb, lib.androidBootCompletedArgs(serial));
            if (booted !== null && lib.androidIsBootCompleted(booted)) {
                return serial;
            }
        }
        await new Promise((r) => setTimeout(r, lib.androidBootPollIntervalMs));
    }
    throw new ApkRunError(lib.androidBootTimeoutMessage);
}

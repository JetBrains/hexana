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
    for (const root of candidates) {
        try {
            if (!fs.statSync(lib.androidPlatformToolsDir(root)).isDirectory()) {
                continue;
            }
        } catch {
            continue;
        }
        const adb = lib.androidAdbPath(root, isWindows);
        const emulator = lib.androidEmulatorPath(root, isWindows);
        if (isExecutable(adb) && isExecutable(emulator)) {
            return { root, adb, emulator };
        }
    }
    return null;
}

/**
 * Runs a tool and resolves with stdout+stderr, or `null` on spawn failure, timeout or abort.
 * Aborting [signal] kills the process.
 */
function runTool(command: string, args: string[], timeoutMs = 30_000, signal?: AbortSignal): Promise<string | null> {
    return new Promise((resolve) => {
        // `windowsHide`: without it every call (boot polling runs one every 2s) flashes a console window on Windows.
        const options = { timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024, signal, windowsHide: true };
        execFile(command, args, options, (error, stdout, stderr) => {
            const output = `${stdout ?? ""}${stderr ?? ""}`;
            // A non-zero exit still carries output worth parsing (e.g. `adb install` failures).
            if (error && (error as NodeJS.ErrnoException).code === "ENOENT") {
                resolve(null);
            } else if (error && (error.killed || error.name === "AbortError")) {
                resolve(null);
            } else {
                resolve(output);
            }
        });
    });
}

/** Starts the emulator detached (it outlives this task and the VS Code window); rejects if it fails to spawn. */
function startEmulator(sdk: AndroidSdk, avd: string): Promise<void> {
    const lib = extensionLib!;
    return new Promise((resolve, reject) => {
        const child = spawn(sdk.emulator, lib.androidBootArgs(avd), { detached: true, stdio: "ignore", windowsHide: true });
        child.once("error", (e) => reject(new ApkRunError(`Failed to start emulator ${avd}: ${e.message}`)));
        child.once("spawn", () => {
            child.unref();
            resolve();
        });
    });
}

class ApkRunError extends Error {}

class ApkRunCancelled extends Error {}

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
            const abort = new AbortController();
            const subscription = token.onCancellationRequested(() => abort.abort());
            try {
                const message = await runApk(sdk, apkPath, target, progress, token, abort.signal);
                vscode.window.showInformationMessage(message);
            } catch (e) {
                if (e instanceof ApkRunCancelled || token.isCancellationRequested) {
                    // Cancelled by the user: nothing to report.
                } else if (e instanceof ApkRunError) {
                    vscode.window.showErrorMessage(e.message);
                } else {
                    vscode.window.showErrorMessage(`Failed to run ${apkName}: ${e}`);
                }
            } finally {
                subscription.dispose();
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
    signal: AbortSignal,
): Promise<string> {
    const lib = extensionLib!;
    const apkName = path.basename(apkPath);
    const checkCancelled = () => {
        if (token.isCancellationRequested) {
            throw new ApkRunCancelled();
        }
    };

    let serial: string;
    if (target.kind === "running") {
        serial = target.serial;
    } else {
        progress.report({ message: `Starting emulator ${target.avd}…` });
        await startEmulator(sdk, target.avd);
        serial = await waitForBoot(sdk, target.avd, progress, token, signal);
    }

    checkCancelled();
    progress.report({ message: `Installing ${apkName}…` });
    const install = await runTool(sdk.adb, lib.androidInstallArgs(serial, apkPath), lib.androidInstallTimeoutMs, signal);
    checkCancelled();
    if (install === null || !lib.androidIsInstallSuccess(install)) {
        throw new ApkRunError(lib.androidInstallFailedMessage(install));
    }

    const packageName = await readPackageName(sdk, apkPath, signal);
    checkCancelled();
    if (!packageName) {
        return lib.androidUnknownPackageMessage(apkName, serial);
    }

    progress.report({ message: `Launching ${packageName}…` });
    const launch = await runTool(sdk.adb, lib.androidLaunchArgs(serial, packageName), undefined, signal);
    checkCancelled();
    if (launch !== null && lib.androidIsLaunchSuccess(launch)) {
        return lib.androidLaunchedMessage(packageName, serial);
    }
    if (launch !== null && !lib.androidHasLauncherActivity(launch)) {
        return lib.androidNoLauncherMessage(packageName, serial);
    }
    throw new ApkRunError(lib.androidLaunchFailedMessage(packageName, serial, launch));
}

async function readPackageName(sdk: AndroidSdk, apkPath: string, signal: AbortSignal): Promise<string | null> {
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
    const output = await runTool(aapt, lib.androidPackageNameArgs(aapt, apkPath), undefined, signal);
    return output === null ? null : lib.androidParsePackageName(output);
}

/** Waits for the emulator running [avd] — not just any emulator that comes online meanwhile. */
async function waitForBoot(
    sdk: AndroidSdk,
    avd: string,
    progress: vscode.Progress<{ message?: string }>,
    token: vscode.CancellationToken,
    signal: AbortSignal,
): Promise<string> {
    const lib = extensionLib!;
    const deadline = Date.now() + lib.androidBootTimeoutMs;
    while (Date.now() < deadline) {
        if (token.isCancellationRequested) {
            throw new ApkRunCancelled();
        }
        const devices = await runTool(sdk.adb, lib.androidDevicesArgs(), undefined, signal);
        let serial: string | undefined;
        for (const candidate of devices ? lib.androidParseOnlineEmulators(devices) : []) {
            const name = await runTool(sdk.adb, lib.androidAvdNameArgs(candidate), undefined, signal);
            if (name !== null && lib.androidParseAvdName(name) === avd) {
                serial = candidate;
                break;
            }
        }
        if (serial) {
            progress.report({ message: `Waiting for ${serial} to boot…` });
            const booted = await runTool(sdk.adb, lib.androidBootCompletedArgs(serial), undefined, signal);
            if (booted !== null && lib.androidIsBootCompleted(booted)) {
                return serial;
            }
        }
        await new Promise((r) => setTimeout(r, lib.androidBootPollIntervalMs));
    }
    throw new ApkRunError(lib.androidBootTimeoutMessage);
}

@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.AndroidEmulatorRun

// ── Android emulator exports ─────────────────────────────────────────────────
//
// Thin wrappers around [AndroidEmulatorRun] so the TypeScript APK runner shares
// SDK layout, commands, output parsing and messages with the IntelliJ plugin.

@JsExport
val androidBootTimeoutMs: Double get() = AndroidEmulatorRun.BOOT_TIMEOUT_MS.toDouble()

@JsExport
val androidBootPollIntervalMs: Double get() = AndroidEmulatorRun.BOOT_POLL_INTERVAL_MS.toDouble()

@JsExport
val androidInstallTimeoutMs: Double get() = AndroidEmulatorRun.INSTALL_TIMEOUT_MS.toDouble()

@JsExport
val androidBootTimeoutMessage: String get() = AndroidEmulatorRun.BOOT_TIMEOUT_MESSAGE

/**
 * SDK roots to probe, in order. [envKeys]/[envValues] are parallel arrays of the
 * relevant environment variables (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, `LOCALAPPDATA`).
 */
@JsExport
fun androidSdkRootCandidates(envKeys: Array<String>, envValues: Array<String>, userHome: String?): Array<String> =
    AndroidEmulatorRun.sdkRootCandidates(envKeys.zip(envValues).toMap(), userHome).toTypedArray()

@JsExport
fun androidPlatformToolsDir(root: String): String = AndroidEmulatorRun.platformToolsDir(root)

@JsExport
fun androidAdbPath(root: String, isWindows: Boolean): String = AndroidEmulatorRun.adbPath(root, isWindows)

@JsExport
fun androidEmulatorPath(root: String, isWindows: Boolean): String = AndroidEmulatorRun.emulatorPath(root, isWindows)

@JsExport
fun androidBuildToolsDir(root: String): String = AndroidEmulatorRun.buildToolsDir(root)

@JsExport
fun androidAaptCandidates(root: String, buildToolsVersions: Array<String>, isWindows: Boolean): Array<String> =
    AndroidEmulatorRun.aaptCandidates(root, buildToolsVersions.toList(), isWindows).toTypedArray()

@JsExport
fun androidListAvdsArgs(): Array<String> = AndroidEmulatorRun.listAvdsArgs.toTypedArray()

@JsExport
fun androidBootArgs(avd: String): Array<String> = AndroidEmulatorRun.bootArgs(avd).toTypedArray()

@JsExport
fun androidDevicesArgs(): Array<String> = AndroidEmulatorRun.devicesArgs.toTypedArray()

@JsExport
fun androidAvdNameArgs(serial: String): Array<String> = AndroidEmulatorRun.avdNameArgs(serial).toTypedArray()

@JsExport
fun androidBootCompletedArgs(serial: String): Array<String> = AndroidEmulatorRun.bootCompletedArgs(serial).toTypedArray()

@JsExport
fun androidInstallArgs(serial: String, apkPath: String): Array<String> =
    AndroidEmulatorRun.installArgs(serial, apkPath).toTypedArray()

@JsExport
fun androidPackageNameArgs(aaptPath: String, apkPath: String): Array<String> =
    AndroidEmulatorRun.packageNameArgs(aaptPath, apkPath).toTypedArray()

@JsExport
fun androidLaunchArgs(serial: String, packageName: String): Array<String> =
    AndroidEmulatorRun.launchArgs(serial, packageName).toTypedArray()

@JsExport
fun androidParseAvdList(output: String): Array<String> = AndroidEmulatorRun.parseAvdList(output).toTypedArray()

@JsExport
fun androidParseOnlineEmulators(output: String): Array<String> =
    AndroidEmulatorRun.parseOnlineEmulators(output).toTypedArray()

@JsExport
fun androidParseAvdName(output: String): String? = AndroidEmulatorRun.parseAvdName(output)

@JsExport
fun androidIsBootCompleted(output: String): Boolean = AndroidEmulatorRun.isBootCompleted(output)

@JsExport
fun androidParsePackageName(output: String): String? = AndroidEmulatorRun.parsePackageName(output)

@JsExport
fun androidIsInstallSuccess(output: String): Boolean = AndroidEmulatorRun.isInstallSuccess(output)

@JsExport
fun androidIsLaunchSuccess(output: String): Boolean = AndroidEmulatorRun.isLaunchSuccess(output)

@JsExport
fun androidHasLauncherActivity(output: String): Boolean = AndroidEmulatorRun.hasLauncherActivity(output)

@JsExport
fun androidNoAvdMessage(apkName: String): String = AndroidEmulatorRun.noAvdMessage(apkName)

@JsExport
fun androidInstallFailedMessage(output: String?): String = AndroidEmulatorRun.installFailedMessage(output)

@JsExport
fun androidUnknownPackageMessage(apkName: String, serial: String): String =
    AndroidEmulatorRun.unknownPackageMessage(apkName, serial)

@JsExport
fun androidNoLauncherMessage(packageName: String, serial: String): String =
    AndroidEmulatorRun.noLauncherMessage(packageName, serial)

@JsExport
fun androidLaunchFailedMessage(packageName: String, serial: String, output: String?): String =
    AndroidEmulatorRun.launchFailedMessage(packageName, serial, output)

@JsExport
fun androidLaunchedMessage(packageName: String, serial: String): String =
    AndroidEmulatorRun.launchedMessage(packageName, serial)

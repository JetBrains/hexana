@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.WasiSdkReleases

// ── WasiSdk exports ──────────────────────────────────────────────────────────
//
// Thin wrappers around [WasiSdkReleases] so the TypeScript wasi-sdk manager
// can delegate the shared logic without duplicating it.

/** Oldest wasi-sdk version that bundles a working LLDB binary. */
@JsExport
val wasiSdkMinVersionWithLldb: Int get() = WasiSdkReleases.MIN_VERSION_WITH_LLDB

/**
 * Parses a GitHub tag name to a version number.
 * Returns null for rc/prerelease tags, non-matching tags, or versions
 * below [WasiSdkReleases.MIN_VERSION_WITH_LLDB].
 */
@JsExport
fun wasiSdkParseTag(tag: String): Int? = WasiSdkReleases.parseTag(tag)

/**
 * Picks the asset name matching [version]/[os]/[arch] from [assetNames],
 * or returns null if none matches.
 *
 * @param os one of "macos", "linux", "windows"
 * @param arch one of "x86_64", "arm64"
 */
@JsExport
fun wasiSdkSelectAsset(assetNames: Array<String>, version: Int, os: String, arch: String): String? =
    WasiSdkReleases.selectAsset(assetNames.toList(), version, os, arch)

/**
 * Extracts the hex digest from a "sha256:<hex>" string.
 * Returns null for null, blank, or non-sha256 input.
 */
@JsExport
fun wasiSdkParseSha256Digest(digest: String?): String? = WasiSdkReleases.parseSha256Digest(digest)

/**
 * Directory name for an installed wasi-sdk version (e.g. "wasi-sdk-33.0").
 */
@JsExport
fun wasiSdkInstallDirName(version: Int): String = WasiSdkReleases.installDirName(version)

/**
 * Top-level directory name inside a wasi-sdk tarball
 * (e.g. "wasi-sdk-33.0-arm64-macos").
 *
 * @param os one of "macos", "linux", "windows"
 * @param arch one of "x86_64", "arm64"
 */
@JsExport
fun wasiSdkArchiveTopLevelDir(version: Int, os: String, arch: String): String =
    WasiSdkReleases.archiveTopLevelDir(version, os, arch)

/**
 * Relative path to the lldb binary inside an installed wasi-sdk directory
 * ("bin/lldb" or "bin/lldb.exe" on Windows).
 *
 * @param os one of "macos", "linux", "windows"
 */
@JsExport
fun wasiSdkLldbRelativePath(os: String): String = WasiSdkReleases.lldbRelativePath(os)

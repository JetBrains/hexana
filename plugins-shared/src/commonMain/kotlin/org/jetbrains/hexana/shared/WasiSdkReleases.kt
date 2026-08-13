package org.jetbrains.hexana.shared

/**
 * Pure logic for working with wasi-sdk release tags and asset names.
 *
 * Dependency-free so that it compiles on JVM, JS, and WasmJs targets.
 */
object WasiSdkReleases {

    /** Oldest wasi-sdk version that bundles a working LLDB binary. */
    const val MIN_VERSION_WITH_LLDB: Int = 31

    /**
     * Parses a GitHub tag name to a version number.
     *
     * Returns null for:
     * - rc/prerelease tags (e.g. "wasi-sdk-34-rc.1")
     * - non-matching tags
     * - versions below [MIN_VERSION_WITH_LLDB]
     */
    fun parseTag(tag: String): Int? {
        // Accept only "wasi-sdk-<N>" — no suffix (rc, alpha, etc.)
        if (!tag.startsWith("wasi-sdk-")) return null
        val rest = tag.removePrefix("wasi-sdk-")
        // Must be a plain integer with no trailing characters
        val version = rest.toIntOrNull() ?: return null
        if (version < MIN_VERSION_WITH_LLDB) return null
        return version
    }

    /**
     * Returns the expected asset file name for a given version, OS, and arch.
     *
     * Example: `expectedAssetName(33, "macos", "arm64")` -> `"wasi-sdk-33.0-arm64-macos.tar.gz"`
     *
     * @param os one of "macos", "linux", "windows"
     * @param arch one of "x86_64", "arm64"
     */
    fun expectedAssetName(version: Int, os: String, arch: String): String =
        "wasi-sdk-$version.0-$arch-$os.tar.gz"

    /**
     * Picks the asset name from a release's asset list that matches
     * the given version, OS, and arch, or returns null if none matches.
     */
    fun selectAsset(assetNames: List<String>, version: Int, os: String, arch: String): String? {
        val expected = expectedAssetName(version, os, arch)
        return assetNames.firstOrNull { it == expected }
    }

    /**
     * Extracts the hex digest from a "sha256:<hex>" string.
     * Returns null for null, blank, or non-sha256 input.
     */
    fun parseSha256Digest(digest: String?): String? {
        if (digest.isNullOrBlank()) return null
        if (!digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:")
        if (hex.isBlank()) return null
        return hex
    }

    /**
     * Directory name for an installed wasi-sdk version, e.g. "wasi-sdk-33.0".
     * This is the name used on disk under the managed storage root.
     */
    fun installDirName(version: Int): String = "wasi-sdk-$version.0"

    /**
     * Top-level directory name inside a wasi-sdk tarball,
     * e.g. "wasi-sdk-33.0-arm64-macos".
     */
    fun archiveTopLevelDir(version: Int, os: String, arch: String): String =
        "wasi-sdk-$version.0-$arch-$os"

    /**
     * Relative path to the lldb binary inside an installed wasi-sdk directory.
     * Uses "bin/lldb.exe" on Windows, "bin/lldb" elsewhere.
     */
    fun lldbRelativePath(os: String): String =
        if (os == "windows") "bin/lldb.exe" else "bin/lldb"
}

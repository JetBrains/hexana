package org.jetbrains.hexana.shared

/**
 * Pure, platform-agnostic utilities for matching DWARF debug-info
 * file paths against IDE/editor file paths.
 *
 * When a debugger (LLDB) stops inside a Wasm module, the source
 * file path comes from DWARF metadata embedded in the binary.
 * These paths are often build-time absolute paths or relative paths
 * that do not match the local project layout. The functions in this
 * file implement heuristic and precise matching strategies so both
 * `idea-plugin` and `vscode-plugin` can reliably map DWARF paths
 * to local source files.
 */

// ── Path matching ───────────────────────────────────────────────────

/**
 * Checks whether [stoppedPath] (the resolved file path from
 * the stop location) matches the [breakpointPath] (the full
 * IDE/editor path stored when the breakpoint was registered).
 *
 * An exact match is tried first. When the stopped path comes
 * from DWARF (possibly relative), it is also accepted if the
 * breakpoint path ends with it preceded by a path separator,
 * so that DWARF "src/lib.rs" matches IDE
 * "/home/user/calculator/src/lib.rs" but not
 * "/home/user/adder/src/lib.rs".
 */
fun pathMatchesBreakpointLocation(stoppedPath: String, breakpointPath: String): Boolean {
    val normStopped = stoppedPath.replace('\\', '/')
    val normBp = breakpointPath.replace('\\', '/')
    if (normStopped == normBp) return true
    // stoppedPath is a suffix of breakpointPath (DWARF relative resolved to IDE absolute)
    if (normBp.endsWith("/$normStopped")) return true
    // breakpointPath is a suffix of stoppedPath (IDE path is a suffix of resolved path)
    if (normStopped.endsWith("/$normBp")) return true
    return false
}

// ── Compilation-unit path matching ──────────────────────────────────

/**
 * Heuristic check: do the CU and IDE paths likely refer to
 * the same source file?
 *
 * This is a **fallback** used when the project base path is
 * not available. The primary verification should use
 * [cuPathEndsWithProjectRelative] which is always precise.
 *
 * The heuristic counts trailing path components that match
 * between the CU and IDE paths. In the standard Rust/Wasm
 * layout `<crate>/src/<file>`, a matching tail of ≥ 3
 * components (crate + src + file) confirms same-crate
 * identity. A shorter tail (e.g. just `src/lib.rs`) means
 * the paths diverge at the crate directory level →
 * inconsistent.
 *
 * Examples:
 *
 * - CU: `/build/calculator/src/lib.rs`, IDE: `/Users/dev/calculator/src/lib.rs`
 *   → 3 matching components (`calculator/src/lib.rs`) → true ✓
 *
 * - CU: `/build/adder/src/lib.rs`, IDE: `/Users/dev/calculator/src/lib.rs`
 *   → 2 matching components (`src/lib.rs`) → false ✗
 *
 * - CU: `src/lib.rs` (short), IDE: `/Users/dev/calculator/src/lib.rs`
 *   → CU has ≤ 2 components → optimistic true ✓
 */
fun cuPathMatchesIdePath(
    cuPath: String,
    idePath: String,
): Boolean {
    val normCu = cuPath.replace('\\', '/')
    val normIde = idePath.replace('\\', '/')

    if (normCu == normIde) return true
    if (normIde.endsWith("/$normCu") || normCu.endsWith("/$normIde")) return true

    val cuComponents = normCu.split('/').filter { it.isNotEmpty() }
    val ideComponents = normIde.split('/').filter { it.isNotEmpty() }

    // If either path has ≤ 2 components (e.g. "src/lib.rs"),
    // there is at most one non-filename component and we
    // cannot reliably distinguish crate directories — be
    // optimistic.
    if (cuComponents.size <= 2 || ideComponents.size <= 2) return true

    // Count how many trailing path components match between
    // the CU and IDE paths (walking from the filename toward
    // the root).
    val minLen = minOf(cuComponents.size, ideComponents.size)
    var matchingFromEnd = 0
    for (i in 1..minLen) {
        if (cuComponents[cuComponents.size - i] == ideComponents[ideComponents.size - i]) {
            matchingFromEnd++
        } else {
            break
        }
    }

    // If the shorter path is fully consumed without a
    // mismatch, one is a path-suffix of the other.
    if (matchingFromEnd >= minLen) return true

    // The matching tail covers the filename and possibly some
    // shared intermediate directories. For the paths to refer
    // to the same file the matching tail must include the
    // crate-identifying directory. In the common Rust/Wasm
    // layout `<crate>/src/<file>`, the crate directory is
    // exactly 2 levels above the filename, so a matching tail
    // of 3 components (crate + src + file) confirms same-crate
    // identity.
    return matchingFromEnd >= 3
}

/**
 * Checks whether the CU path ends with the given project-
 * relative path at a path-separator boundary.
 *
 * This is the most reliable verification: the project-relative
 * path (e.g. `calculator/src/lib.rs`) is derived from the IDE
 * path by stripping the project base directory and always
 * includes the crate directory. The CU path (build-time
 * absolute) must end with this crate-identifying suffix for
 * the paths to refer to the same file.
 */
fun cuPathEndsWithProjectRelative(cuPath: String, projectRelative: String): Boolean {
    val normCu = cuPath.replace('\\', '/')
    val normRel = projectRelative.replace('\\', '/')
    return normCu.endsWith("/$normRel") || normCu == normRel
}

/**
 * Backward-compatible wrapper.
 *
 * Delegates to [cuPathMatchesIdePath].
 * The [rawDwarfPath] parameter is no longer used; the match is
 * based solely on the CU and IDE full paths.
 */
fun cuPathConsistentWithIdePath(
    cuPath: String,
    idePath: String,
    @Suppress("UNUSED_PARAMETER") rawDwarfPath: String,
): Boolean = cuPathMatchesIdePath(cuPath, idePath)

// ── Crate name extraction ───────────────────────────────────────────

/**
 * Extracts crate name from Rust component-model mangled symbols.
 * E.g. `_$LT$calculator..Component...` → `calculator`.
 */
private val CRATE_FROM_COMPONENT_PATTERN =
    "\\${'$'}LT\\${'$'}(\\w+)\\.\\.".toRegex()

/**
 * Extracts crate name from plain Rust symbols: `<crate>::<rest>`.
 */
private val CRATE_FROM_PLAIN_PATTERN =
    """^(\w+)::""".toRegex()

/**
 * Extracts the Rust crate name from a mangled/demangled
 * function symbol as reported by LLDB.
 *
 * Rust Wasm component-model symbols look like:
 * ```
 * _$LT$calculator..Component$u20$as$u20$...$GT$::calculate::h...
 * ```
 * The first `..`-delimited segment after `$LT$` is the crate
 * name (`calculator`).  For plain Rust symbols without the
 * component-model wrapper, the first `::` segment is the
 * crate name.
 *
 * @return the extracted crate name in lowercase, or `null`
 *         when the function name does not follow a recognised
 *         Rust mangling pattern.
 */
fun extractCrateName(functionName: String): String? {
    // Component-model mangled pattern: _$LT$<crate>..
    val componentMatch = CRATE_FROM_COMPONENT_PATTERN.find(functionName)
    if (componentMatch != null) {
        return componentMatch.groupValues[1].lowercase()
    }
    // Plain Rust symbol: <crate>::<func>::<hash>
    val plainMatch = CRATE_FROM_PLAIN_PATTERN.find(functionName)
    if (plainMatch != null) {
        return plainMatch.groupValues[1].lowercase()
    }
    return null
}

/**
 * Checks whether the crate name extracted from the LLDB
 * function symbol appears as a path component in the IDE
 * breakpoint file path.
 *
 * E.g. function `_$LT$calculator..Component...` →
 * crate `calculator` → IDE path
 * `/Users/dev/project/calculator/src/lib.rs` contains
 * `/calculator/` → true.
 *
 * This is useful when CU path verification is inconclusive
 * (build-time paths don't match IDE project layout) because
 * the Rust crate name in the symbol table is always
 * authoritative.
 */
fun functionNameMatchesIdePath(
    functionName: String,
    idePath: String,
): Boolean {
    val crate = extractCrateName(functionName) ?: return false
    val normIde = idePath.replace('\\', '/').lowercase()
    // The crate name must appear as a directory component in
    // the IDE path. Rust normalises crate names by replacing
    // hyphens with underscores (e.g. crate "hello-world"
    // becomes `hello_world` in symbol names), so we compare
    // with both the original crate name and a hyphen variant.
    if (normIde.contains("/$crate/")) return true
    val hyphenated = crate.replace('_', '-')
    if (hyphenated != crate && normIde.contains("/$hyphenated/")) return true
    return false
}

// ── CU hash suffix stripping ────────────────────────────────────────

/**
 * Matches the opaque hash/CGU suffix that LLDB appends to
 * compilation-unit paths for classic Wasm JIT frames:
 * `/@/<identifier>` at the end of the path.
 *
 * Examples:
 * `/path/src/lib.rs/@/9le4nom1b1hdoeh9b7lbjqyuq`
 * → stripped to `/path/src/lib.rs`
 * `/path/debug_add.rs/@/debug_add.d8f51c9c58bd83ee-cgu.0`
 * → stripped to `/path/debug_add.rs`
 */
private val CU_HASH_SUFFIX_PATTERN =
    """/@/[A-Za-z0-9._-]+$""".toRegex()

/**
 * Strips the opaque `/@/<hash>` suffix from a compilation-unit
 * path if present.
 */
fun stripCuHashSuffix(cuPath: String): String =
    cuPath.replace(CU_HASH_SUFFIX_PATTERN, "")

// ── Project-relative path extraction ────────────────────────────────

/**
 * Extracts the project-relative portion of an absolute IDE path
 * using the given project base path.
 *
 * E.g. for base `/Users/dev/project` and IDE path
 * `/Users/dev/project/calculator/src/lib.rs`, returns
 * `calculator/src/lib.rs`.
 *
 * @return the relative path, or `null` if the IDE path is not
 *         under the project base directory.
 */
fun extractProjectRelativePath(basePath: String, idePath: String): String? {
    val normBase = basePath.replace('\\', '/')
    val normIde = idePath.replace('\\', '/')
    if (normIde.startsWith("$normBase/")) {
        return normIde.removePrefix("$normBase/")
    }
    return null
}

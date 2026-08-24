package org.jetbrains.hexana.providers.firmware

/**
 * Heuristic text-based parsers for DTS `compatible` properties.
 *
 * This is NOT a full DTS grammar. It matches the same spirit and style as
 * [parseDtsMemoryRegions] in `MemoryBudget.kt`: line-scanning with simple
 * string operations, no regex complexity beyond what stdlib Regex provides.
 *
 * All functions are pure (String in, value out) and operate on the merged
 * `zephyr.dts` text produced by a Zephyr build.
 */

private val COMPATIBLE_RE = Regex("""compatible\s*=([^;]+);""")

/** Splits a DTS compatible property value into individual string tokens. */
private fun splitCompatibleValue(rawValue: String): List<String> {
    // rawValue looks like: `"nordic,nrf52840-dk-nrf52840", "nordic,nrf52840"`
    // Extract all double-quoted strings.
    val results = mutableListOf<String>()
    var i = 0
    while (i < rawValue.length) {
        val start = rawValue.indexOf('"', i)
        if (start < 0) break
        val end = rawValue.indexOf('"', start + 1)
        if (end < 0) break
        results += rawValue.substring(start + 1, end)
        i = end + 1
    }
    return results
}

/**
 * Parses the `compatible` property of the DTS root node (`/ { ... }`).
 *
 * The root-node compatible is the first `compatible` property that appears at
 * depth 1 (directly inside the root `/ {` block, before any named child node
 * opens its own brace). Returns an empty list when no root compatible is found.
 *
 * Example input fragment:
 * ```
 * / {
 *     compatible = "nordic,nrf52840-dk-nrf52840";
 *     chosen { ... };
 * };
 * ```
 * Returns: `["nordic,nrf52840-dk-nrf52840"]`
 */
public fun parseDtsRootCompatible(dtsText: String): List<String> {
    val lines = dtsText.lines()
    var inRoot = false
    var depth = 0

    for (line in lines) {
        val trimmed = line.trim()

        if (!inRoot) {
            // Look for the root node opening: `/ {`
            if (trimmed == "/ {" || trimmed.startsWith("/ {")) {
                inRoot = true
                depth = 1
            }
            continue
        }

        // Track brace depth to stay within the root node body at depth 1.
        val opens = trimmed.count { it == '{' }
        val closes = trimmed.count { it == '}' }

        // A compatible line at exactly depth 1 (root scope, not a child node) is what we want.
        // We check BEFORE updating depth so we catch the line itself.
        if (depth == 1 && trimmed.startsWith("compatible")) {
            val match = COMPATIBLE_RE.find(trimmed)
            if (match != null) {
                val tokens = splitCompatibleValue(match.groupValues[1])
                if (tokens.isNotEmpty()) return tokens
            }
        }

        depth += opens - closes
        if (depth <= 0) break // Left the root block.
    }
    return emptyList()
}

/**
 * Parses `compatible` properties of `cpu@N` nodes inside a `cpus { ... }` block.
 *
 * Returns all compatible strings found across all cpu nodes, in document order.
 *
 * Example input fragment:
 * ```
 * cpus {
 *     cpu@0 {
 *         compatible = "arm,cortex-m4f";
 *     };
 * };
 * ```
 * Returns: `["arm,cortex-m4f"]`
 */
public fun parseDtsCpuCompatible(dtsText: String): List<String> {
    val lines = dtsText.lines()
    val results = mutableListOf<String>()
    var inCpus = false
    var cpusDepth = 0
    var inCpuNode = false
    var cpuNodeDepth = 0

    for (line in lines) {
        val trimmed = line.trim()
        val opens = trimmed.count { it == '{' }
        val closes = trimmed.count { it == '}' }

        if (!inCpus) {
            // Match `cpus {` — no address part, so just look for cpus node.
            if (Regex("""^(?:\w+\s*:\s*)?cpus\s*\{""").containsMatchIn(trimmed)) {
                inCpus = true
                cpusDepth = 1
            }
            continue
        }

        // Inside cpus block.
        if (!inCpuNode) {
            // Match cpu@N {
            if (Regex("""^(?:\w+\s*:\s*)?cpu@[0-9a-fA-F]+\s*\{""").containsMatchIn(trimmed)) {
                inCpuNode = true
                cpuNodeDepth = cpusDepth + 1 // relative depth after entering this node
                // opens already counted on this line; adjust cpusDepth below.
            }
        } else {
            // Inside a cpu node: look for compatible property.
            if (trimmed.startsWith("compatible")) {
                val match = COMPATIBLE_RE.find(trimmed)
                if (match != null) {
                    results += splitCompatibleValue(match.groupValues[1])
                }
            }
        }

        cpusDepth += opens - closes

        // When we exit the cpuNode depth, clear the flag.
        if (inCpuNode && cpusDepth < cpuNodeDepth) {
            inCpuNode = false
        }

        // When we exit the cpus block entirely, stop.
        if (cpusDepth <= 0) break
    }
    return results
}

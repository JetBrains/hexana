package org.jetbrains.hexana.providers.firmware

/**
 * Parses Zephyr Kconfig output files (`.config`) for board and arbitrary key values.
 *
 * This is a line-scan parser, not a full Kconfig grammar. It handles CRLF and LF
 * line endings and expects the standard `KEY=value` or `KEY="quoted value"` format.
 */

/**
 * Extracts the board identifier from a Zephyr `.config` file.
 *
 * Looks for the line `CONFIG_BOARD="<boardId>"` (quoted form). Returns the
 * board identifier string with quotes stripped, or null if the key is absent or
 * has an empty value.
 *
 * Example matching line: `CONFIG_BOARD="qemu_cortex_m3"`
 */
public fun parseConfigBoard(configText: String): String? =
    parseConfigValue(configText, "CONFIG_BOARD")

/**
 * Extracts the value of an arbitrary Kconfig key from `.config` text.
 *
 * Handles both quoted (`KEY="value"`) and unquoted (`KEY=value`) forms. Returns
 * the stripped value, or null if the key is absent or the value is empty.
 *
 * @param configText The full text of a Zephyr `.config` file.
 * @param key The Kconfig variable name, e.g. `"CONFIG_BOARD"`.
 */
public fun parseConfigValue(configText: String, key: String): String? {
    val prefix = "$key="
    for (rawLine in configText.lineSequence()) {
        val line = rawLine.trimEnd()
        if (!line.startsWith(prefix)) continue
        var value = line.removePrefix(prefix)
        // Strip surrounding double-quotes if present.
        if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
            value = value.substring(1, value.length - 1)
        }
        return value.ifEmpty { null }
    }
    return null
}

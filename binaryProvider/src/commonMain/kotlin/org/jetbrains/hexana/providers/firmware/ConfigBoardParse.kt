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
 * Zephyr v3.7+ hardware model splits the id: `CONFIG_BOARD="mps2"` plus
 * `CONFIG_BOARD_QUALIFIERS="an521/cpu0"`, with the full id in
 * `CONFIG_BOARD_TARGET="mps2/an521/cpu0"`. Preference order:
 * `CONFIG_BOARD_TARGET`, then `CONFIG_BOARD` joined with
 * `CONFIG_BOARD_QUALIFIERS`, then bare `CONFIG_BOARD`.
 * Returns null if no board key is present or all values are empty.
 */
public fun parseConfigBoard(configText: String): String? {
    parseConfigValue(configText, "CONFIG_BOARD_TARGET")?.let { return it }
    val board = parseConfigValue(configText, "CONFIG_BOARD") ?: return null
    val qualifiers = parseConfigValue(configText, "CONFIG_BOARD_QUALIFIERS")
    return if (qualifiers != null) "$board/$qualifiers" else board
}

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

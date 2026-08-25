package org.jetbrains.hexana.ui.panel

import org.jetbrains.hexana.shared.scripting.ScriptApiReference
import java.io.File

/**
 * Writes the type-definition sidecars that make an editor resolve the scripting
 * globals in a `.js` script.
 *
 * Deliberately free of IntelliJ APIs so the file decisions — what is written,
 * what is refreshed, what is left alone — are testable without the platform.
 * Callers own the VFS refresh afterwards.
 */
internal object ScriptSidecars {

    /**
     * Ensures [directory] carries the scripting types.
     *
     * @return the files written, empty when everything was already up to date
     */
    fun write(directory: File): List<File> {
        val written = mutableListOf<File>()

        // The definitions come from the compiled-in reference rather than a
        // bundled resource, so they are always the ones this plugin documents and
        // they refresh when the plugin updates.
        val types = File(directory, ScriptApiReference.TYPES_FILE_NAME)
        val expected = ScriptApiReference.dts()
        if (types.readTextOrNull() != expected) {
            types.writeText(expected)
            written += types
        }

        // A standalone `.js` file has no automatic association with a sibling
        // `.d.ts`, so editors that work project-at-a-time need this. An existing
        // config is the user's and already governs how the folder is analysed.
        val jsConfig = File(directory, ScriptApiReference.JS_CONFIG_FILE_NAME)
        val hasProjectConfig = jsConfig.exists() || File(directory, TS_CONFIG_FILE_NAME).exists()
        if (!hasProjectConfig) {
            jsConfig.writeText(ScriptApiReference.jsConfig())
            written += jsConfig
        }

        return written
    }

    private const val TS_CONFIG_FILE_NAME = "tsconfig.json"

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}

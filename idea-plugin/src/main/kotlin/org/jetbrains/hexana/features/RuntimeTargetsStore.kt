package org.jetbrains.hexana.features

import org.jetbrains.hexana.shared.features.HEXANA_PROJECT_DIR
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_FILE
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.jetbrains.hexana.shared.features.parseRuntimeTargets
import org.jetbrains.hexana.shared.features.serializeRuntimeTargets
import java.io.File

/**
 * Reads and writes `.hexana/runtimes.json` inside a project directory.
 *
 * Split out from the project service — like `ScriptSidecars` next to
 * `ScriptTemplateWriter` — so the file format and the read/write behaviour can be tested
 * against a temporary directory with no IDE running. The caller owns VFS refreshing.
 */
object RuntimeTargetsStore {

    /** The settings file for [projectBasePath], whether or not it exists yet. */
    fun file(projectBasePath: String): File =
        File(File(projectBasePath, HEXANA_PROJECT_DIR), RUNTIME_TARGETS_FILE)

    /**
     * Reads the declared targets, or [RuntimeTargets.EMPTY] when the file is absent or
     * unreadable. An unreadable file is not an error worth reporting: the compatibility
     * table simply has nothing to check against.
     */
    fun read(projectBasePath: String): RuntimeTargets {
        val file = file(projectBasePath)
        if (!file.isFile) return RuntimeTargets.EMPTY
        return try {
            parseRuntimeTargets(file.readText())
        } catch (_: Exception) {
            RuntimeTargets.EMPTY
        }
    }

    /**
     * Writes the declared targets, creating `.hexana/` if needed, and returns the file
     * written.
     *
     * Skips the write when the rendered content already matches, so applying an unchanged
     * settings page does not dirty a file that is under version control. (The same
     * read-compare-write discipline the VS Code extension uses for its `jsconfig.json`.)
     */
    fun write(projectBasePath: String, targets: RuntimeTargets): File {
        val file = file(projectBasePath)
        val content = serializeRuntimeTargets(targets)
        if (file.isFile && runCatching { file.readText() }.getOrNull() == content) return file
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }
}

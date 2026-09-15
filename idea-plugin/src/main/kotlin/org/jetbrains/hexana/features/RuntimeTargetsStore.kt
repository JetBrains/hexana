package org.jetbrains.hexana.features

import org.jetbrains.hexana.shared.features.HEXANA_PROJECT_DIR
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_FILE
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_SCHEMA_FILE
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

    /** The JSON Schema sibling of [file], whether or not it exists yet. */
    fun schemaFile(projectBasePath: String): File =
        File(File(projectBasePath, HEXANA_PROJECT_DIR), RUNTIME_TARGETS_SCHEMA_FILE)

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
     * [schema] is the generated JSON Schema the settings file references; it is written to
     * [schemaFile] so an editor can complete and validate a hand-edit. Passing `null` leaves
     * any existing schema alone — the settings file is still usable without one, and the
     * reference simply does not resolve.
     *
     * Skips the write when the rendered content already matches, per file, so applying an
     * unchanged settings page does not dirty files that are under version control. (The same
     * read-compare-write discipline the VS Code extension uses for its `jsconfig.json`.)
     */
    fun write(projectBasePath: String, targets: RuntimeTargets, schema: String? = null): File {
        val file = file(projectBasePath)
        // The schema goes first so the reference in the settings file never points at a file
        // that is not there yet, however briefly.
        if (schema != null) writeIfChanged(schemaFile(projectBasePath), schema)
        writeIfChanged(file, serializeRuntimeTargets(targets))
        return file
    }

    private fun writeIfChanged(file: File, content: String) {
        if (file.isFile && runCatching { file.readText() }.getOrNull() == content) return
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}

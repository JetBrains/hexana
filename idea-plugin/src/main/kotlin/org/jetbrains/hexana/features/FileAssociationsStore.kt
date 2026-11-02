package org.jetbrains.hexana.features

import org.jetbrains.hexana.shared.features.FILE_ASSOCIATIONS_FILE
import org.jetbrains.hexana.shared.features.FILE_ASSOCIATIONS_SCHEMA_FILE
import org.jetbrains.hexana.shared.features.FileAssociations
import org.jetbrains.hexana.shared.features.HEXANA_PROJECT_DIR
import org.jetbrains.hexana.shared.features.parseFileAssociations
import org.jetbrains.hexana.shared.features.serializeFileAssociations
import org.jetbrains.hexana.shared.features.serializeFileAssociationsSchema
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reads and writes `.hexana/file_associations.json` inside a project directory.
 *
 * The [RuntimeTargetsStore] counterpart, split from the service for the same reason: the
 * file behaviour is testable against a temporary directory with no IDE running. The caller
 * owns VFS refreshing.
 */
object FileAssociationsStore {

    /** The settings file for [projectBasePath], whether or not it exists yet. */
    fun file(projectBasePath: String): File =
        File(File(projectBasePath, HEXANA_PROJECT_DIR), FILE_ASSOCIATIONS_FILE)

    /** The JSON Schema sibling of [file], whether or not it exists yet. */
    fun schemaFile(projectBasePath: String): File =
        File(File(projectBasePath, HEXANA_PROJECT_DIR), FILE_ASSOCIATIONS_SCHEMA_FILE)

    /** The declared associations, or [FileAssociations.EMPTY] when absent or unreadable. */
    fun read(projectBasePath: String): FileAssociations {
        val file = file(projectBasePath)
        if (!file.isFile) return FileAssociations.EMPTY
        return try {
            parseFileAssociations(file.readText())
        } catch (_: Exception) {
            FileAssociations.EMPTY
        }
    }

    /**
     * Writes [associations] and the schema next to them, creating `.hexana/` if needed, and
     * returns the settings file. Skips each write whose content already matches, so an
     * unchanged apply leaves version-controlled files alone.
     */
    fun write(projectBasePath: String, associations: FileAssociations): File {
        val file = file(projectBasePath)
        // Schema first, so the settings file's reference never points at nothing.
        writeIfChanged(schemaFile(projectBasePath), serializeFileAssociationsSchema())
        writeIfChanged(file, serializeFileAssociations(associations))
        return file
    }

    /**
     * Written to a sibling and moved into place, so a reader never sees half a file. The VS
     * Code extension watches the same path and reads it as soon as it changes, and a
     * truncated file parses as "nothing associated" — every open editor would flip to
     * automatic detection and back.
     */
    private fun writeIfChanged(file: File, content: String) {
        if (file.isFile && runCatching { file.readText() }.getOrNull() == content) return
        val directory = file.parentFile
        directory?.mkdirs()
        val temp = File.createTempFile(".${file.name}", ".tmp", directory)
        try {
            temp.writeText(content)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}

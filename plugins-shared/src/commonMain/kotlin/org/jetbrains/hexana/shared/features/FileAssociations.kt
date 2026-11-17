package org.jetbrains.hexana.shared.features

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.shared.scripting.PROVIDER_SCRIPTS_DIR
import org.jetbrains.hexana.shared.scripting.ProviderRegistrationInfo
import org.jetbrains.hexana.shared.scripting.USER_CONTRIBUTION_PREFIX

/** File inside [HEXANA_PROJECT_DIR] holding the parser chosen by hand for individual files. */
const val FILE_ASSOCIATIONS_FILE: String = "file_associations.json"

/** `.hexana/file_associations.json`, project-relative. */
const val FILE_ASSOCIATIONS_PATH: String = "$HEXANA_PROJECT_DIR/$FILE_ASSOCIATIONS_FILE"

/**
 * File inside [HEXANA_PROJECT_DIR] holding the JSON Schema for [FILE_ASSOCIATIONS_FILE].
 *
 * Written next to the settings file for the same reasons as [RUNTIME_TARGETS_SCHEMA_FILE]:
 * hand-editing offline, and completing the parser ids *this* Hexana knows.
 */
const val FILE_ASSOCIATIONS_SCHEMA_FILE: String = "file_associations.schema.json"

/** `.hexana/file_associations.schema.json`, project-relative. */
const val FILE_ASSOCIATIONS_SCHEMA_PATH: String = "$HEXANA_PROJECT_DIR/$FILE_ASSOCIATIONS_SCHEMA_FILE"

/** The `${'$'}schema` value written into [FILE_ASSOCIATIONS_FILE], sibling-relative. */
const val FILE_ASSOCIATIONS_SCHEMA_REF: String = "./$FILE_ASSOCIATIONS_SCHEMA_FILE"

/** Schema version written into the file, so a future format change can be recognized. */
const val FILE_ASSOCIATIONS_SCHEMA_VERSION: Int = 1

/**
 * What an association names: a built-in [BinaryParser], or a provider a script in
 * `.hexana/providers/` registered under a `user:` id.
 */
sealed interface ParserRef {
    /** The id `.hexana/file_associations.json` stores. */
    val id: String

    data class Builtin(val parser: BinaryParser) : ParserRef {
        override val id: String get() = parser.id
    }

    /**
     * A provider defined by `hexana.defineProvider`. Kept by id alone: whether a script
     * registering it is loaded is only known at resolution time, and an association to one
     * that is not must survive — the script may simply not have loaded yet.
     */
    data class Scripted(override val id: String) : ParserRef

    companion object {
        /** The reference stored as [id], or `null` for an id that is neither built in nor `user:`. */
        fun fromId(id: String): ParserRef? {
            BinaryParser.fromId(id)?.let { return Builtin(it) }
            return if (id.startsWith(USER_CONTRIBUTION_PREFIX) && id.length > USER_CONTRIBUTION_PREFIX.length) {
                Scripted(id)
            } else {
                null
            }
        }
    }
}

/** This parser as an association target. */
fun BinaryParser.ref(): ParserRef = ParserRef.Builtin(this)

/**
 * Contents of `.hexana/file_associations.json`: which parser — built in or scripted — opens a given file,
 * overriding the automatic detection.
 *
 * Keys are project-relative paths with `/` separators (see [fileAssociationKey]), so the
 * file means the same thing on every platform and in every checkout. A file with no entry
 * is detected automatically.
 */
class FileAssociations(
    associations: Map<String, ParserRef> = emptyMap(),
    val schemaVersion: Int = FILE_ASSOCIATIONS_SCHEMA_VERSION,
) {
    /** Sorted by path, so iteration order — and therefore the file — is stable. */
    val associations: Map<String, ParserRef> =
        associations.entries.sortedBy { it.key }.associate { it.key to it.value }

    val isEmpty: Boolean get() = associations.isEmpty()

    /** The parser chosen for [key], or `null` when the file is detected automatically. */
    fun parserFor(key: String?): ParserRef? = key?.let { associations[it] }

    /** A copy with [key] bound to [parser], or unbound when [parser] is `null`. */
    fun with(key: String, parser: ParserRef?): FileAssociations {
        val updated = associations.toMutableMap()
        if (parser == null) updated.remove(key) else updated[key] = parser
        return FileAssociations(updated, schemaVersion)
    }

    override fun equals(other: Any?): Boolean =
        other is FileAssociations && associations == other.associations

    override fun hashCode(): Int = associations.hashCode()

    override fun toString(): String = "FileAssociations($associations)"

    companion object {
        val EMPTY = FileAssociations()
    }
}

private val fileAssociationsJson = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

/** The `${'$'}schema` key; a literal `${'$'}` needs escaping in Kotlin source. */
private const val SCHEMA_KEY: String = "${'$'}schema"

/**
 * Normalizes a path as written in the file or handed over by a host: `\` becomes `/`, and
 * a leading `./` and any trailing `/` are dropped. Returns `null` for a path that cannot
 * name a file inside the project — empty, absolute, or climbing out with `..`.
 */
fun normalizeFileAssociationKey(path: String): String? {
    var key = path.trim().replace('\\', '/')
    while (key.startsWith("./")) key = key.removePrefix("./")
    key = key.trimEnd('/')
    if (key.isEmpty() || key.startsWith("/") || (key.length > 1 && key[1] == ':')) return null
    if (key.split('/').any { it == ".." }) return null
    return key
}

/**
 * The key [filePath] is stored under in a project rooted at [projectBasePath], or `null`
 * when the file is outside the project — there is then no stable path to record, and
 * hosts do not offer the choice.
 *
 * Both arguments are absolute paths in either separator style.
 */
fun fileAssociationKey(projectBasePath: String, filePath: String): String? {
    val base = projectBasePath.replace('\\', '/').trimEnd('/')
    val file = filePath.replace('\\', '/')
    if (base.isEmpty() || !file.startsWith("$base/")) return null
    return normalizeFileAssociationKey(file.substring(base.length + 1))
}

/**
 * Parses `.hexana/file_associations.json`.
 *
 * Lenient for the reasons [parseRuntimeTargets] is: the file is hand-edited and committed,
 * so a malformed one degrades to [FileAssociations.EMPTY] — every file is detected
 * automatically — rather than erroring on every open. An entry whose path cannot be
 * normalized, or whose parser id this Hexana does not know, is dropped.
 */
fun parseFileAssociations(text: String): FileAssociations {
    if (text.isBlank()) return FileAssociations.EMPTY
    val root = try {
        fileAssociationsJson.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return FileAssociations.EMPTY
    }
    val schemaVersion = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: FILE_ASSOCIATIONS_SCHEMA_VERSION
    val entries = root["associations"] as? JsonObject ?: return FileAssociations(emptyMap(), schemaVersion)

    val associations = linkedMapOf<String, ParserRef>()
    for ((path, value) in entries) {
        val key = normalizeFileAssociationKey(path) ?: continue
        val parser = (value as? JsonPrimitive)?.content?.trim()?.let(ParserRef::fromId) ?: continue
        // Two spellings of one path (`a/b` and `./a/b`): the first wins, as in runtimes.json.
        associations.getOrPut(key) { parser }
    }
    return FileAssociations(associations, schemaVersion)
}

/**
 * Renders `.hexana/file_associations.json`. Paths are sorted and the layout fixed, so
 * re-saving an unchanged set is byte-identical and two people adding different files
 * produce a clean merge more often than not.
 */
fun serializeFileAssociations(associations: FileAssociations): String {
    val root = buildJsonObject {
        put(SCHEMA_KEY, FILE_ASSOCIATIONS_SCHEMA_REF)
        put("version", associations.schemaVersion)
        putJsonObject("associations") {
            for ((path, parser) in associations.associations) put(path, parser.id)
        }
    }
    return fileAssociationsJson.encodeToString(JsonObject.serializer(), root) + "\n"
}

/**
 * Renders `.hexana/file_associations.schema.json`, the JSON Schema
 * [serializeFileAssociations] output declares itself against, so IntelliJ and VS Code
 * complete the parser ids, show what each one is, and flag a typo.
 */
fun serializeFileAssociationsSchema(scriptedProviders: List<ProviderRegistrationInfo> = emptyList()): String {
    val root = buildJsonObject {
        // Draft-07 for the reason given in serializeRuntimeTargetsSchema.
        put(SCHEMA_KEY, "http://json-schema.org/draft-07/schema#")
        put("title", "Hexana file parser associations")
        put(
            "description",
            "Which parser Hexana opens a file with, overriding automatic format detection. " +
                "Read and written by both the IntelliJ plugin and the VS Code extension.",
        )
        put("type", "object")
        putJsonArray("required") { add("associations") }
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject(SCHEMA_KEY) {
                put("type", "string")
                put("description", "Reference to this schema. Written by Hexana; safe to leave alone.")
            }
            putJsonObject("version") {
                put("type", "integer")
                put("minimum", 1)
                put("default", FILE_ASSOCIATIONS_SCHEMA_VERSION)
                put("description", "Format version of this file. Currently $FILE_ASSOCIATIONS_SCHEMA_VERSION.")
            }
            putJsonObject("associations") {
                put("type", "object")
                put(
                    "description",
                    "Project-relative file path (with / separators) mapped to the parser that opens " +
                        "it. Files not listed are detected automatically.",
                )
                putJsonObject("propertyNames") {
                    put("minLength", 1)
                    // Relative, and not climbing out of the project.
                    put("pattern", "^(?![/\\\\])(?![A-Za-z]:)(?!(.*[/\\\\])?\\.\\.([/\\\\]|$)).+$")
                }
                putJsonObject("additionalProperties") { put("\$ref", "#/definitions/parser") }
            }
        }
        putJsonObject("definitions") {
            putJsonObject("parser") {
                put("title", "Parser")
                put(
                    "description",
                    "Parser id: a built-in parser (\"generic\" shows the raw bytes without any format " +
                        "parsing), or the user: id of a provider defined by a script in $PROVIDER_SCRIPTS_DIR.",
                )
                putJsonArray("anyOf") {
                    addJsonObject {
                        put("type", "string")
                        putJsonArray("enum") { for (parser in BinaryParser.entries) add(parser.id) }
                        // Non-standard but honoured by both editors' completion popups.
                        putJsonArray("enumDescriptions") { for (parser in BinaryParser.entries) add(parser.displayName) }
                    }
                    // The providers loaded when the schema was written, so they complete too.
                    // Sorted, so loading scripts in another order does not rewrite the file.
                    val known = scriptedProviders.sortedBy { it.id }
                    if (known.isNotEmpty()) {
                        addJsonObject {
                            put("type", "string")
                            putJsonArray("enum") { for (provider in known) add(provider.id) }
                            putJsonArray("enumDescriptions") { for (provider in known) add(provider.name) }
                        }
                    }
                    // Any other user: id is still valid: its script may not be loaded here.
                    addJsonObject {
                        put("type", "string")
                        put("pattern", "^user:.+$")
                    }
                }
            }
        }
        putJsonArray("examples") {
            addJsonObject {
                put("version", FILE_ASSOCIATIONS_SCHEMA_VERSION)
                putJsonObject("associations") {
                    put("firmware/boot.img", BinaryParser.ELF.id)
                    put("data/blob.dat", BinaryParser.GENERIC.id)
                }
            }
        }
    }
    return fileAssociationsJson.encodeToString(JsonObject.serializer(), root) + "\n"
}

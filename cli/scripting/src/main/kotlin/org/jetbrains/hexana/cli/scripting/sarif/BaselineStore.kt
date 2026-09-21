package org.jetbrains.hexana.cli.scripting.sarif

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Per-check baseline files stored as `<safe-name>.json` under [dir].
 *
 * The check id is stored as a top-level `"checkId"` field inside the JSON document
 * alongside `"fingerprints"`. The filename is treated as opaque: classification reads
 * the id from the file content, not from the filename. This makes the store correct on
 * Windows, where ':' in a check id (e.g. `user:foo`) would be replaced with '_' in the
 * filename and would otherwise collide with an id that contains '_' literally.
 */
class BaselineStore(private val dir: Path) {

    private val isWindows: Boolean =
        System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)

    private fun checkIdToFileName(checkId: String): String {
        val safe = if (isWindows) checkId.replace(':', '_') else checkId
        return "$safe.json"
    }

    private fun fileForCheck(checkId: String): Path = dir.resolve(checkIdToFileName(checkId))

    /** Reads fingerprints map (fp -> message) from the file that stores [checkId]. */
    private fun readFingerprints(checkId: String): Map<String, String> {
        return try {
            val text = fileForCheck(checkId).readText()
            val root = Json.parseToJsonElement(text).jsonObject
            val fps = root["fingerprints"]?.jsonObject ?: return emptyMap()
            fps.entries.associate { (fp, obj) ->
                fp to (obj.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "")
            }
        } catch (_: NoSuchFileException) {
            emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Reads all stored (checkId, fingerprints) entries by scanning every `.json` file under
     * [dir] and reading the `"checkId"` field from the document content.
     *
     * Files whose content cannot be parsed or that lack a `"checkId"` field are silently skipped.
     */
    private fun readAllStoredEntries(): List<Pair<String, Map<String, String>>> {
        val files = try {
            dir.toFile().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return files.mapNotNull { file ->
            try {
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                val checkId = root["checkId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val fps = root["fingerprints"]?.jsonObject ?: return@mapNotNull null
                val fingerprints = fps.entries.associate { (fp, obj) ->
                    fp to (obj.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "")
                }
                checkId to fingerprints
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Classifies [findings] relative to stored baselines.
     *
     * - Fingerprint present in baseline file: [BaselineState.UNCHANGED]
     * - Not present: [BaselineState.NEW]
     * - Fingerprints in the baseline with no matching current finding: synthesized [BaselineState.ABSENT] finding
     * - Missing dir or file: everything [BaselineState.NEW]
     */
    fun classify(findings: List<CheckFinding>): List<SarifFinding> {
        val byCheckId = findings.groupBy { it.checkId }
        val result = mutableListOf<SarifFinding>()

        // Build a map of stored checkId -> fingerprints from file content (not filenames).
        val storedByCheckId = readAllStoredEntries().associate { (id, fps) -> id to fps }

        val allCheckIds = (byCheckId.keys + storedByCheckId.keys).toSet()

        for (checkId in allCheckIds.sorted()) {
            val stored = storedByCheckId[checkId] ?: emptyMap()
            val current = byCheckId[checkId] ?: emptyList()

            val currentFps = current.map { it to findingFingerprint(it) }

            for ((finding, fp) in currentFps) {
                val state = if (fp in stored) BaselineState.UNCHANGED else BaselineState.NEW
                result.add(SarifFinding(finding = finding, baselineState = state))
            }

            val currentFpSet = currentFps.map { it.second }.toSet()
            for ((fp, message) in stored) {
                if (fp !in currentFpSet) {
                    val absentFinding = CheckFinding(
                        checkId = checkId,
                        severity = CheckSeverity.INFO,
                        message = message,
                        anchor = null,
                    )
                    result.add(
                        SarifFinding(
                            finding = absentFinding,
                            baselineState = BaselineState.ABSENT,
                            fingerprintOverride = fp,
                        )
                    )
                }
            }
        }

        return result
    }

    /**
     * Rewrites the baseline file for every check ID present in [findings].
     * Other check files are left untouched. Byte-idempotent on the same input.
     *
     * The JSON document has keys sorted alphabetically: `"checkId"` then `"fingerprints"`.
     */
    fun update(findings: List<CheckFinding>) {
        val byCheckId = findings.groupBy { it.checkId }
        if (byCheckId.isEmpty()) return

        dir.createDirectories()
        for ((checkId, checkFindings) in byCheckId.entries.sortedBy { it.key }) {
            val fingerprints = checkFindings
                .map { findingFingerprint(it) to it.message }
                .sortedBy { it.first }

            val fpsObject = buildJsonObject {
                for ((fp, msg) in fingerprints) {
                    put(fp, buildJsonObject {
                        put("message", msg)
                    })
                }
            }
            // Keys are ordered: "checkId" before "fingerprints" (alphabetical, byte-idempotent).
            val root = buildJsonObject {
                put("checkId", checkId)
                put("fingerprints", fpsObject)
            }

            val text = Json.encodeToString(JsonObject.serializer(), root) + "\n"
            fileForCheck(checkId).writeText(text)
        }
    }
}

package org.jetbrains.hexana.nativebin.vuln

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jetbrains.hexana.HexanaLog

/**
 * A single OSV (Open Source Vulnerability) advisory record, reduced to the fields
 * Hexana needs to map a SBOM component to known vulnerabilities. The schema is the
 * OSV format (https://ossf.github.io/osv-schema/) used by both the OSV.dev API and
 * the offline per-ecosystem database export.
 */
data class OsvRecord(
    val id: String,
    val aliases: List<String>,
    val summary: String?,
    val severities: List<OsvSeverity>,
    val affected: List<OsvAffected>,
    val references: List<String>,
) {
    /** The CVE id if this advisory has one (as its id or among its aliases). */
    val cve: String?
        get() = listOf(id).plus(aliases).firstOrNull { it.startsWith("CVE-", ignoreCase = true) }

    /** The first CVSS vector string, if any (type `CVSS_V2`/`CVSS_V3`/`CVSS_V4`). */
    val cvssVector: String?
        get() = severities.firstOrNull { it.type.startsWith("CVSS", ignoreCase = true) }?.score
}

data class OsvSeverity(val type: String, val score: String)

data class OsvAffected(
    val ecosystem: String?,
    /** Maven package as `groupId:artifactId`. */
    val packageName: String?,
    val ranges: List<OsvRange>,
    val versions: List<String>,
)

data class OsvRange(val type: String, val events: List<OsvEvent>)

data class OsvEvent(val kind: Kind, val version: String) {
    enum class Kind { Introduced, Fixed, LastAffected }
}

/** Parses OSV JSON records (single object, or the array/object forms in the GCS export). */
object OsvParser {

    fun parseRecord(json: String): OsvRecord? {
        val element = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        return (element as? JsonObject)?.let(::parseRecord)
    }

    fun parseRecord(o: JsonObject): OsvRecord? {
        val id = o.string("id") ?: return null
        return OsvRecord(
            id = id,
            aliases = o.array("aliases")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
            summary = o.string("summary"),
            severities = parseSeverities(o.array("severity")),
            affected = parseAffected(o.array("affected")),
            references = o.array("references")
                ?.mapNotNull { (it as? JsonObject)?.string("url") }
                .orEmpty(),
        )
    }

    private fun parseSeverities(arr: JsonArray?): List<OsvSeverity> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val type = o.string("type") ?: return@mapNotNull null
            OsvSeverity(type, o.string("score") ?: return@mapNotNull null)
        }
    }

    private fun parseAffected(arr: JsonArray?): List<OsvAffected> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val pkg = o.obj("package")
            OsvAffected(
                ecosystem = pkg?.string("ecosystem"),
                packageName = pkg?.string("name"),
                ranges = parseRanges(o.array("ranges")),
                versions = o.array("versions")
                    ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                    .orEmpty(),
            )
        }
    }

    private fun parseRanges(arr: JsonArray?): List<OsvRange> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val events = o.array("events")?.mapNotNull { ev -> (ev as? JsonObject)?.let(::parseEvent) }.orEmpty()
            OsvRange(type = o.string("type") ?: "ECOSYSTEM", events = events)
        }
    }

    private fun parseEvent(o: JsonObject): OsvEvent? = when {
        o.has("introduced") -> OsvEvent(OsvEvent.Kind.Introduced, o.string("introduced") ?: "0")
        o.has("fixed") -> o.string("fixed")?.let { OsvEvent(OsvEvent.Kind.Fixed, it) }
        o.has("last_affected") -> o.string("last_affected")?.let { OsvEvent(OsvEvent.Kind.LastAffected, it) }
        else -> null
    }

    fun logSkip(context: String, e: Throwable) {
        HexanaLog.LOG.warn("OSV parse skipped: $context", e)
    }

    // --- gson helpers ---
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}

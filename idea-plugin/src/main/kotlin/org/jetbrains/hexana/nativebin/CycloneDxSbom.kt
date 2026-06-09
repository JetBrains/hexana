package org.jetbrains.hexana.nativebin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jetbrains.hexana.HexanaLog

/**
 * A parsed CycloneDX SBOM, reduced to the fields the viewer renders. CycloneDX
 * has evolved across 1.4 → 1.6 (notably the `metadata.tools` shape and license
 * encodings), so [CycloneDxParser] reads the JSON tree defensively rather than
 * binding to a strict schema.
 */
data class CycloneDxSbom(
    val bomFormat: String?,
    val specVersion: String?,
    val serialNumber: String?,
    val version: Int?,
    val metadataTimestamp: String?,
    val mainComponent: CdxComponent?,
    val tools: List<String>,
    val components: List<CdxComponent>,
    val dependencyCount: Int,
    val vulnerabilities: List<CdxVulnerability>,
)

data class CdxComponent(
    val type: String?,
    val group: String?,
    val name: String?,
    val version: String?,
    val purl: String?,
    val licenses: List<String>,
)

data class CdxVulnerability(
    val id: String?,
    val severity: String?,
)

/**
 * Parses CycloneDX JSON (the format GraalVM Native Image embeds) into a
 * [CycloneDxSbom]. Uses gson's tree API — gson ships with the plugin, unlike the
 * `compileOnly` kotlinx-serialization — and tolerates missing/optional fields.
 */
object CycloneDxParser {

    fun parse(json: String): CycloneDxSbom? {
        val root = try {
            val element = JsonParser.parseString(json)
            if (!element.isJsonObject) return null
            element.asJsonObject
        } catch (e: Exception) {
            HexanaLog.LOG.warn("CycloneDX parse failed", e)
            return null
        }

        val components = root.array("components")
            ?.mapNotNull { (it as? JsonObject)?.let(::parseComponent) }
            .orEmpty()
        val bomFormat = root.string("bomFormat")
        // Be lenient, but reject obvious non-CycloneDX JSON: accept when it either
        // declares the CycloneDX format or actually carries a components array.
        if (bomFormat?.equals("CycloneDX", ignoreCase = true) != true && components.isEmpty()) {
            return null
        }

        val metadata = root.obj("metadata")
        return CycloneDxSbom(
            bomFormat = bomFormat,
            specVersion = root.string("specVersion"),
            serialNumber = root.string("serialNumber"),
            version = root.intOrNull("version"),
            metadataTimestamp = metadata?.string("timestamp"),
            mainComponent = metadata?.obj("component")?.let(::parseComponent),
            tools = parseTools(metadata),
            components = components,
            dependencyCount = root.array("dependencies")?.size() ?: 0,
            vulnerabilities = parseVulnerabilities(root.array("vulnerabilities")),
        )
    }

    private fun parseComponent(o: JsonObject): CdxComponent = CdxComponent(
        type = o.string("type"),
        group = o.string("group"),
        name = o.string("name"),
        version = o.string("version"),
        purl = o.string("purl"),
        licenses = parseLicenses(o.array("licenses")),
    )

    /** `licenses` entries are `{license:{id|name}}` or `{expression:"…"}`. */
    private fun parseLicenses(arr: JsonArray?): List<String> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            o.obj("license")?.let { it.string("id") ?: it.string("name") }
                ?: o.string("expression")
        }
    }

    /**
     * `metadata.tools` is an array of `{name,version}` in 1.4, but an object with
     * `components`/`services` arrays in 1.5+. Handle both.
     */
    private fun parseTools(metadata: JsonObject?): List<String> {
        val toolsElement = metadata?.get("tools") ?: return emptyList()
        val out = mutableListOf<String>()
        when {
            toolsElement.isJsonArray -> toolsElement.asJsonArray.forEach { e ->
                (e as? JsonObject)?.let { out.addTool(it) }
            }
            toolsElement.isJsonObject -> {
                val obj = toolsElement.asJsonObject
                obj.array("components")?.forEach { (it as? JsonObject)?.let { c -> out.addTool(c) } }
                obj.array("services")?.forEach { (it as? JsonObject)?.let { c -> out.addTool(c) } }
            }
        }
        return out
    }

    private fun MutableList<String>.addTool(o: JsonObject) {
        val name = o.string("name") ?: return
        val version = o.string("version")
        add(if (version != null) "$name $version" else name)
    }

    /** `vulnerabilities[].ratings[].severity`; first rating wins for the summary. */
    private fun parseVulnerabilities(arr: JsonArray?): List<CdxVulnerability> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val severity = o.array("ratings")
                ?.firstNotNullOfOrNull { (it as? JsonObject)?.string("severity") }
            CdxVulnerability(id = o.string("id"), severity = severity)
        }
    }

    // --- gson tree helpers ---------------------------------------------------

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
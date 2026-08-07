package org.jetbrains.hexana.providers.sbom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A parsed CycloneDX SBOM, reduced to the fields the viewer renders. CycloneDX
 * has evolved across 1.4 to 1.6 (notably the `metadata.tools` shape and license
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
    val dependencies: List<CdxDependency>,
    val vulnerabilities: List<CdxVulnerability>,
) {
    val dependencyCount: Int get() = dependencies.size

    /** Every component flattened, including nested class-level children. */
    fun allComponents(): List<CdxComponent> = components.flatMap { it.flatten() }
}

/**
 * A CycloneDX component. For a default `--enable-sbom=embed` binary this is a flat
 * library entry. For a `--enable-sbom=class-level` binary the top-level library
 * carries nested [children] — module to source-file leaves whose [properties] list
 * the classes/methods that survived native-image's dead-code elimination.
 */
data class CdxComponent(
    val type: String?,
    val group: String?,
    val name: String?,
    val version: String?,
    val purl: String?,
    val bomRef: String?,
    val licenses: List<String>,
    val properties: List<CdxProperty>,
    val children: List<CdxComponent>,
) {
    val kind: CdxComponentKind
        get() = if (type.equals("file", ignoreCase = true)) CdxComponentKind.SourceFile else CdxComponentKind.Library

    fun propertyValue(name: String): String? =
        properties.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    /** Class names from the `Classes` property of a class-level leaf (best-effort split). */
    fun classes(): List<String> = splitNames(propertyValue("Classes"))

    /** Method descriptors from the `Methods` property of a class-level leaf (best-effort split). */
    fun methods(): List<String> = splitNames(propertyValue("Methods"))

    /** Classes in this component and all nested children (the post-DCE class set). */
    fun allClasses(): List<String> = classes() + children.flatMap { it.allClasses() }

    fun flatten(): List<CdxComponent> = listOf(this) + children.flatMap { it.flatten() }
}

enum class CdxComponentKind { Library, SourceFile }

data class CdxProperty(val name: String, val value: String)

/** A CycloneDX dependency-graph edge: [ref] dependsOn the listed bom-refs. */
data class CdxDependency(val ref: String, val dependsOn: List<String>)

data class CdxVulnerability(
    val id: String?,
    val severity: String?,
)

private fun splitNames(value: String?): List<String> =
    value?.split('\n', ',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/**
 * Parses CycloneDX JSON (the format GraalVM Native Image embeds) into a
 * [CycloneDxSbom]. Uses kotlinx.serialization's tree API (JsonElement) so it
 * works on all KMP targets. Does not require the @Serializable annotation or
 * code-gen — plain tree-walking only.
 */
object CycloneDxParser {

    fun parse(json: String): CycloneDxSbom? {
        val root = try {
            val element = Json.parseToJsonElement(json)
            element as? JsonObject ?: return null
        } catch (_: Exception) {
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
            dependencies = parseDependencies(root.array("dependencies")),
            vulnerabilities = parseVulnerabilities(root.array("vulnerabilities")),
        )
    }

    private fun parseComponent(o: JsonObject): CdxComponent = CdxComponent(
        type = o.string("type"),
        group = o.string("group"),
        name = o.string("name"),
        version = o.string("version"),
        purl = o.string("purl"),
        bomRef = o.string("bom-ref"),
        licenses = parseLicenses(o.array("licenses")),
        properties = parseProperties(o.array("properties")),
        children = o.array("components")?.mapNotNull { (it as? JsonObject)?.let(::parseComponent) }.orEmpty(),
    )

    private fun parseProperties(arr: JsonArray?): List<CdxProperty> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val name = o.string("name") ?: return@mapNotNull null
            CdxProperty(name, o.string("value") ?: "")
        }
    }

    /** `dependencies[]` entries: `{ "ref": "<bom-ref>", "dependsOn": ["<bom-ref>", ...] }`. */
    private fun parseDependencies(arr: JsonArray?): List<CdxDependency> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val ref = o.string("ref") ?: return@mapNotNull null
            val dependsOn = o.array("dependsOn")
                ?.mapNotNull { e -> runCatching { e.jsonPrimitive.contentOrNull }.getOrNull() }
                .orEmpty()
            CdxDependency(ref, dependsOn)
        }
    }

    /** `licenses` entries are `{license:{id|name}}` or `{expression:"..."}`. */
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
        when (toolsElement) {
            is JsonArray -> toolsElement.forEach { e ->
                (e as? JsonObject)?.let { out.addTool(it) }
            }
            is JsonObject -> {
                toolsElement.array("components")?.forEach { (it as? JsonObject)?.let { c -> out.addTool(c) } }
                toolsElement.array("services")?.forEach { (it as? JsonObject)?.let { c -> out.addTool(c) } }
            }
            else -> Unit
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

    // --- kotlinx.serialization.json tree helpers ----------------------------

    private fun JsonObject.string(key: String): String? =
        get(key)?.let { e -> runCatching { e.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.let { e -> runCatching { e.jsonPrimitive.intOrNull }.getOrNull() }

    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.let { e -> runCatching { e.jsonArray }.getOrNull() }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.let { e -> runCatching { e.jsonObject }.getOrNull() }
}
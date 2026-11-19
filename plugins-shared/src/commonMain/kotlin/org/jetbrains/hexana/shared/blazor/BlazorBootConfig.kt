package org.jetbrains.hexana.shared.blazor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What a boot manifest resource is for, in the order the Blazor App tab groups them. */
enum class BlazorResourceKind(val label: String) {
    WasmNative("Runtime (Wasm)"),
    CoreAssembly("Core assembly"),
    Assembly("Assembly"),
    LazyAssembly("Lazy assembly"),
    Satellite("Satellite assembly"),
    Pdb("PDB"),
    Icu("ICU data"),
    JsModule("JS module"),
    Other("Other"),
}

/**
 * One file the boot manifest lists. [fileName] is the name on disk (fingerprinted by the SDK, e.g.
 * `app.ol0snvssxq.wasm`), [virtualPath] the name the runtime asks for (`app.wasm`), [integrity] the
 * Subresource Integrity value the browser checks the download against (`sha256-<base64>`).
 */
data class BlazorBootResource(
    val kind: BlazorResourceKind,
    val virtualPath: String,
    val fileName: String,
    val integrity: String?,
    /** Culture of a satellite assembly. */
    val culture: String? = null,
)

/**
 * A Blazor WebAssembly boot manifest: which files the runtime downloads, with their hashes, plus the
 * app-wide settings that decide what loads. Two encodings exist, and [parse] reads both:
 *
 * - .NET 10+: embedded in `dotnet.<fingerprint>.js` between `/*json-start*/` and `/*json-end*/`;
 *   every resource group is an array of `{virtualPath, name, hash}`.
 * - .NET 8/9: `blazor.boot.json`; every resource group maps the file name to its hash, and a
 *   `fingerprinting` map gives the virtual path of fingerprinted names.
 */
data class BlazorBootConfig(
    val mainAssemblyName: String?,
    /** `sharded`, `all`, `invariant`, `hybrid` or `custom`: which ICU data loads. */
    val globalizationMode: String?,
    /** 0 in Release; -1 or a positive level when debugging is enabled. */
    val debugLevel: Int?,
    /** Whether the IL linker trimmed the app; .NET 10 Debug builds omit it. */
    val linkerEnabled: Boolean?,
    val applicationEnvironment: String?,
    val resources: List<BlazorBootResource>,
    /** Which encoding the manifest came in; the label follows the manifest file name in the tab. */
    val source: Source,
) {
    enum class Source(val label: String) {
        EmbeddedInDotnetJs("embedded, .NET 10+"),
        BlazorBootJson(".NET 8/9"),
    }

    companion object {
        private const val JSON_START = "/*json-start*/"
        private const val JSON_END = "/*json-end*/"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** True when [text] is a `dotnet.js` that embeds the manifest; cheap, no parsing. */
        fun embedsManifest(text: String): Boolean = text.contains(JSON_START) && text.contains(JSON_END)

        /** Parses `blazor.boot.json` or a `dotnet.js` with an embedded manifest; null for anything else. */
        fun parse(text: String): BlazorBootConfig? {
            val start = text.indexOf(JSON_START)
            val (body, source) = if (start >= 0) {
                val end = text.indexOf(JSON_END, start)
                if (end < 0) return null
                text.substring(start + JSON_START.length, end) to Source.EmbeddedInDotnetJs
            } else {
                text to Source.BlazorBootJson
            }
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            val resourcesObject = root["resources"] as? JsonObject ?: return null
            val fingerprinting = (resourcesObject["fingerprinting"] as? JsonObject)
                ?.mapValues { (_, value) -> value.string() }
                .orEmpty()
            val resources = buildList {
                for ((key, group) in resourcesObject) {
                    if (key == "hash" || key == "fingerprinting") continue
                    if (key == "satelliteResources" && group is JsonObject) {
                        for ((culture, entries) in group) addGroup(BlazorResourceKind.Satellite, entries, fingerprinting, culture)
                    } else {
                        addGroup(kindOf(key), group, fingerprinting, culture = null)
                    }
                }
            }
            return BlazorBootConfig(
                mainAssemblyName = root["mainAssemblyName"].string(),
                globalizationMode = root["globalizationMode"].string(),
                debugLevel = (root["debugLevel"] as? JsonPrimitive)?.intOrNull,
                linkerEnabled = (root["linkerEnabled"] as? JsonPrimitive)?.booleanOrNull,
                applicationEnvironment = root["applicationEnvironment"].string(),
                resources = resources,
                source = source,
            )
        }

        private fun MutableList<BlazorBootResource>.addGroup(
            kind: BlazorResourceKind,
            group: JsonElement,
            fingerprinting: Map<String, String?>,
            culture: String?,
        ) {
            when (group) {
                // .NET 10: [{ "virtualPath": "app.wasm", "name": "app.ol0snvssxq.wasm", "hash": "sha256-…" }]
                is JsonArray -> for (entry in group) {
                    val obj = entry as? JsonObject ?: continue
                    val name = obj["name"].string() ?: continue
                    add(BlazorBootResource(kind, obj["virtualPath"].string() ?: unfingerprinted(name), name, obj["hash"].string(), culture))
                }
                // .NET 8/9: { "app.rlv1gfuon1.wasm": "sha256-…" }
                is JsonObject -> for ((name, hash) in group) {
                    add(BlazorBootResource(kind, fingerprinting[name] ?: unfingerprinted(name), name, (hash as? JsonPrimitive)?.contentOrNull, culture))
                }
                else -> Unit
            }
        }

        /** The SDK's content fingerprint: ten base-36 characters before the extension. */
        private val FINGERPRINT = Regex("""^(.+)\.[a-z0-9]{10}(\.[A-Za-z0-9]+)$""")

        /**
         * The name without its fingerprint (`dotnet.native.a6k1euv12n.wasm` → `dotnet.native.wasm`),
         * for resources the manifest gives no virtual path: the runtime and JS modules in .NET 10,
         * anything outside the `fingerprinting` map in .NET 8/9.
         */
        internal fun unfingerprinted(name: String): String =
            FINGERPRINT.matchEntire(name)?.let { it.groupValues[1] + it.groupValues[2] } ?: name

        private fun kindOf(key: String): BlazorResourceKind = when (key) {
            "wasmNative" -> BlazorResourceKind.WasmNative
            "coreAssembly" -> BlazorResourceKind.CoreAssembly
            "assembly" -> BlazorResourceKind.Assembly
            "lazyAssembly" -> BlazorResourceKind.LazyAssembly
            "pdb", "corePdb" -> BlazorResourceKind.Pdb
            "icu" -> BlazorResourceKind.Icu
            "libraryInitializers", "modulesAfterConfigLoaded", "modulesAfterRuntimeReady" -> BlazorResourceKind.JsModule
            else -> if (key.startsWith("jsModule")) BlazorResourceKind.JsModule else BlazorResourceKind.Other
        }

        private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

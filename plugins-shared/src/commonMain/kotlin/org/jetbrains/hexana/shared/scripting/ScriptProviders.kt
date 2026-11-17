package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.providers.StructureRange

/**
 * Project-relative directory holding provider scripts: every `*.js` file directly inside it
 * is loaded for the project, and the providers and tabs it registers become available to
 * every binary. Inside `.hexana/` so it sits next to the `file_associations.json` that
 * assigns those providers to files.
 */
const val PROVIDER_SCRIPTS_DIR: String = ".hexana/providers"

/** Prefix every script-defined provider id, tab name, slice name and check id carries. */
const val USER_CONTRIBUTION_PREFIX: String = "user:"

/**
 * A binary provider registered via `hexana.defineProvider(...)`.
 *
 * A scripted provider is not a parser of its own: it names a built-in [base] parser to read
 * the file with (`generic` by default), and adds what its [parse] callback reports on top —
 * regions that tint the hex view and fill the Structure view — under its own [name]. The
 * base parser's tabs stay, which is what lets a script extend an existing format ("ELF plus
 * our boot header") instead of having to re-implement it.
 *
 * The live [parse] function belongs to the script runtime that registered it and must not
 * outlive it; [ProviderRegistrationInfo] is the serializable form.
 *
 * @param id unique id, starting with [USER_CONTRIBUTION_PREFIX]; what
 *   `.hexana/file_associations.json` refers to
 * @param name label shown in the format badge and the parser menu
 * @param parse called as `parse(binary)` once per opened file, or `null` for a provider
 *   that only renames its base and contributes tabs
 */
data class ProviderRegistration(
    val id: String,
    val name: String,
    val base: BinaryParser,
    val description: String?,
    val parse: ScriptValue.Fn?,
)

/** Static provider metadata: everything but the live callback. */
data class ProviderRegistrationInfo(
    val id: String,
    val name: String,
    val base: BinaryParser,
    val description: String?,
)

fun ProviderRegistration.toInfo(): ProviderRegistrationInfo =
    ProviderRegistrationInfo(id = id, name = name, base = base, description = description)

/**
 * One region a provider's `parse()` reported.
 *
 * @param tab name of a tab the region corresponds to; when set, the hex view's section
 *   navigation can switch to it, as it does for built-in formats
 */
data class ProviderRegion(
    val name: String,
    val offset: Long,
    val length: Long,
    val tab: String? = null,
)

/**
 * What a provider's `parse()` returned, after validation.
 *
 * @param regions every region that lies inside the file, in the order returned
 * @param dropped how many regions were rejected (outside the file, empty, or malformed),
 *   so a host can say so instead of silently showing fewer than the script produced
 */
data class ProviderParseResult(
    val regions: List<ProviderRegion>,
    val dropped: Int = 0,
)

/**
 * The [regions] as hex-view ranges for a [fileSize]-byte file.
 *
 * The hex viewers binary-search a sorted list of ranges, so a region a script reports past
 * the end of the file must never reach them; the bound is written to avoid overflowing on
 * huge offsets, as the built-in providers' `structureRangeOrNull` does.
 */
fun ProviderParseResult.structureRanges(fileSize: Int): List<StructureRange> =
    regions.mapNotNull { region ->
        if (region.length <= 0L || region.offset < 0L || region.offset > fileSize.toLong() - region.length) {
            null
        } else {
            StructureRange(region.offset, region.length, region.name, region.tab)
        }
    }

/**
 * Builds a [ProviderParseResult] from loosely typed region fields, as each host reads them
 * off its own JS values. A region missing a name, or with a non-numeric or negative offset
 * or length, counts as dropped rather than failing the whole parse.
 */
class ProviderParseResultBuilder {
    private val regions = mutableListOf<ProviderRegion>()
    private var dropped = 0

    /** Adds one region; [offset] and [length] accept any JS number the host passes through. */
    fun region(name: String?, offset: Number?, length: Number?, tab: String?) {
        val start = offset?.toDouble()
        val size = length?.toDouble()
        if (name.isNullOrBlank() || start == null || size == null ||
            start.isNaN() || size.isNaN() || start < 0 || size < 0 ||
            start != start.toLong().toDouble() || size != size.toLong().toDouble()
        ) {
            dropped++
            return
        }
        regions += ProviderRegion(name, start.toLong(), size.toLong(), tab?.takeIf { it.isNotBlank() })
    }

    fun build(): ProviderParseResult = ProviderParseResult(regions.toList(), dropped)
}

/**
 * Format ids a tab's `appliesTo` (or a slice's `formats`) is matched against for a binary:
 * the id of the built-in parser that read it — the same ids `.hexana/file_associations.json`
 * uses — plus the scripted provider's id when one is applied.
 */
fun scriptFormatIds(baseParser: BinaryParser, scriptedProviderId: String?): Set<String> =
    setOfNotNull(baseParser.id, scriptedProviderId)

/**
 * Whether a tab or slice declared for [formats] applies to a binary with [formatIds].
 * An empty list means every binary, as `defineSlice` has always documented; `"*"` says the
 * same explicitly.
 */
fun appliesToFormats(formats: List<String>, formatIds: Set<String>): Boolean =
    formats.isEmpty() || "*" in formats || formats.any { it in formatIds }

/**
 * Placeholders in [providerScriptTemplate] a host substitutes with JS string literals once it
 * knows the provider's id and name — the VS Code extension asks for the name itself, after the
 * webview (which owns the template) has already handed the text over.
 */
const val PROVIDER_ID_PLACEHOLDER: String = "__HEXANA_PROVIDER_ID__"
const val PROVIDER_NAME_PLACEHOLDER: String = "__HEXANA_PROVIDER_NAME__"

/**
 * The file-name stem, and the part of the `user:` id after the prefix, for a provider named
 * [name]: lowercase, with every run of other characters folded into one `-`. `provider` when
 * nothing usable is left.
 */
fun providerScriptSlug(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "provider" }

/**
 * The stub a new provider script starts from, with [idLiteral] and [nameLiteral] inserted
 * verbatim — pass JS string literals ([jsStringLiteral]), or the placeholders above.
 *
 * Minimal but runnable: it registers the provider and reports one region, so assigning it to a
 * file shows the provider working before a line is changed; the tab is left as a comment.
 */
fun providerScriptTemplate(idLiteral: String, nameLiteral: String): String = """
    |// A binary provider. Assign it to a file from the format badge's parser menu ("Scripts"), or
    |// in .hexana/file_associations.json under its id. Every .js file in this folder is loaded for
    |// the project; hexana-scripting.d.ts next to it gives completion for the API.
    |
    |hexana.defineProvider({
    |  id: $idLiteral,
    |  name: $nameLiteral,
    |  // Built-in parser the file is read with first ("elf", "pe", "wasm", ...); its tabs stay.
    |  base: "generic",
    |  parse(binary) {
    |    // Regions tint the hex view and fill the Structure view.
    |    return {
    |      regions: [
    |        { name: "Header", offset: 0, length: Math.min(16, binary.size) },
    |      ],
    |    };
    |  },
    |});
    |
    |// A tab for files opened with this provider:
    |// hexana.defineTab({
    |//   name: "user:Fields",
    |//   appliesTo: [$idLiteral],
    |//   columns: [{ name: "Field", type: "string" }, { name: "Offset", type: "offset" }],
    |//   rows(binary) { return { rows: [{ cells: ["Magic", 0] }] }; },
    |// });
    |""".trimMargin()

/** The stub for a provider [id] named [name]. */
fun providerScript(id: String, name: String): String =
    providerScriptTemplate(jsStringLiteral(id), jsStringLiteral(name))

/** [value] as a double-quoted JS string literal. */
fun jsStringLiteral(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
    append('"')
}

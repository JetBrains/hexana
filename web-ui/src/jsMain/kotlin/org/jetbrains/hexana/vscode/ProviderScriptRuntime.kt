package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.StructureRange
import org.jetbrains.hexana.providers.binaryParserOf
import org.jetbrains.hexana.shared.scripting.ProviderParseResultBuilder
import org.jetbrains.hexana.shared.scripting.ProviderRegistrationInfo
import org.jetbrains.hexana.shared.scripting.ScriptValue
import org.jetbrains.hexana.shared.scripting.ScriptValueJsBinder
import org.jetbrains.hexana.shared.scripting.SliceColumn
import org.jetbrains.hexana.shared.scripting.SliceColumnType
import org.jetbrains.hexana.shared.scripting.SliceParam
import org.jetbrains.hexana.shared.scripting.SliceRegistrationInfo
import org.jetbrains.hexana.shared.scripting.USER_CONTRIBUTION_PREFIX
import org.jetbrains.hexana.shared.scripting.appliesToFormats
import org.jetbrains.hexana.shared.scripting.scriptBinaryApi
import org.jetbrains.hexana.shared.scripting.scriptFormatIds
import org.jetbrains.hexana.shared.scripting.structureRanges

/** One provider script from `.hexana/providers/`, as the host read it. */
class ProviderScriptSource(val path: String, val text: String)

/** A provider a script registered, with its live `parse` (a JS function, or `null`). */
class BrowserScriptedProvider(val info: ProviderRegistrationInfo, internal val parse: dynamic)

/** A `defineTab` tab a script registered, with its live `rows` function. */
class BrowserScriptTab(val info: SliceRegistrationInfo, internal val rows: dynamic)

/** What one script registered; [error] says why it registered nothing usable, if so. */
class BrowserProviderScript(
    val source: ProviderScriptSource,
    val providers: List<BrowserScriptedProvider>,
    val tabs: List<BrowserScriptTab>,
    val error: String? = null,
)

/**
 * Everything the project's provider scripts registered — the browser counterpart of the
 * JVM's `ProviderScriptCatalog`.
 *
 * @param untrusted the host did not send the scripts because the workspace is not trusted
 */
class BrowserProviderCatalog(val scripts: List<BrowserProviderScript>, val untrusted: Boolean = false) {
    /** Every provider; an id is owned by the first script, in name order, that defined it. */
    val providers: List<ProviderRegistrationInfo> by lazy {
        val seen = mutableSetOf<String>()
        scripts.flatMap { it.providers }.map { it.info }.filter { seen.add(it.id) }
    }

    fun provider(id: String): Pair<BrowserProviderScript, BrowserScriptedProvider>? {
        for (script in scripts) {
            script.providers.firstOrNull { it.info.id == id }?.let { return script to it }
        }
        return null
    }

    companion object {
        val EMPTY = BrowserProviderCatalog(emptyList())
    }
}

/**
 * A tab attached to one file, with the `binary` its `rows()` receive. A class rather than a
 * pair because composables cannot take `dynamic`: the Compose compiler rejects it, so JS
 * values cross into composition only inside a holder like this one.
 */
class BoundScriptTab(val tab: BrowserScriptTab, internal val binary: dynamic)

/** What the scripts contribute to one opened file. */
class BrowserProviderBindings(
    /** The scripted provider in effect, if any. */
    val provider: ProviderRegistrationInfo?,
    val ranges: List<StructureRange>,
    /** Why [ranges] is missing or incomplete; `null` when `parse()` reported cleanly. */
    val error: String?,
    val tabs: List<BoundScriptTab>,
) {
    companion object {
        val EMPTY = BrowserProviderBindings(null, emptyList(), null, emptyList())
    }
}

private val binder = ScriptValueJsBinder(::wrapForJs)

/** The `binary` a callback receives: the same surface script tabs bind. */
internal fun scriptBinaryJs(provider: BinaryProvider, fileName: String?, filePath: String?): dynamic =
    binder.toJs(scriptBinaryApi(provider, fileName = fileName, filePath = filePath?.takeIf { it.isNotBlank() }))

/**
 * Learns what [sources] register by evaluating each once against an empty binary, as the JVM's
 * metadata pass does: registration must not depend on any one file.
 */
fun loadProviderCatalog(sources: List<ProviderScriptSource>, untrusted: Boolean): BrowserProviderCatalog {
    if (sources.isEmpty()) return BrowserProviderCatalog(emptyList(), untrusted)
    val empty = scriptBinaryJs(GenericBinaryProvider(ByteArray(0).toCommonByteBuffer()), null, null)
    return BrowserProviderCatalog(sources.map { evaluateProviderScript(it, empty) }, untrusted)
}

/**
 * Evaluates the scripts that matter for [provider] — the one defining [scriptedId], and those
 * with tabs that apply — against the file itself, and collects their contributions, as
 * `bindProviderScripts` does on the JVM.
 *
 * Scripts run on the webview's only thread with no timeout; a script that never returns hangs
 * this editor, as a script tab would. The sandbox is the webview's: no Node, no file access.
 */
fun bindBrowserProviderScripts(
    catalog: BrowserProviderCatalog,
    provider: BinaryProvider,
    fileName: String?,
    filePath: String?,
    scriptedId: String?,
): BrowserProviderBindings {
    val formatIds = scriptFormatIds(binaryParserOf(provider), scriptedId)
    val owner = scriptedId?.let { catalog.provider(it)?.first }
    var scripted: ProviderRegistrationInfo? = null
    var ranges = emptyList<StructureRange>()
    var error: String? = null
    val tabs = mutableListOf<BoundScriptTab>()
    val binaryJs by lazy { scriptBinaryJs(provider, fileName, filePath) }

    for (meta in catalog.scripts) {
        val definesProvider = meta === owner
        if (!definesProvider && meta.tabs.none { appliesToFormats(it.info.formats, formatIds) }) continue
        val script = evaluateProviderScript(meta.source, binaryJs)
        if (definesProvider) {
            val registered = script.providers.firstOrNull { it.info.id == scriptedId }
            scripted = registered?.info ?: catalog.provider(scriptedId!!)?.second?.info
            when {
                script.error != null -> error = "${meta.source.path.substringAfterLast('/')}: ${script.error}"
                registered == null -> error = "$scriptedId is not registered when the script sees this file."
                else -> try {
                    val result = parseResult(registered.parse, binaryJs)
                    ranges = result.structureRanges(provider.bytes.limit())
                    val dropped = result.dropped + (result.regions.size - ranges.size)
                    if (dropped > 0) error = "parse() reported $dropped region(s) that are empty, malformed or outside the file."
                } catch (e: Throwable) {
                    error = "$scriptedId.parse() failed: ${e.message ?: "script error"}"
                }
            }
        }
        script.tabs.filter { appliesToFormats(it.info.formats, formatIds) }.mapTo(tabs) { BoundScriptTab(it, binaryJs) }
    }
    return BrowserProviderBindings(scripted, ranges, error, tabs)
}

/** One page of a tab's rows, read off the JS result like the JVM session reads it. */
class BrowserTabPage(val rows: List<Pair<Long?, List<String>>>, val nextOffset: Int?)

/** Calls a tab's `rows(binary, params, paging)` for one page. Throws what the script throws. */
fun fetchBrowserTabPage(tab: BrowserScriptTab, binary: dynamic, offset: Int, limit: Int): BrowserTabPage {
    val params: dynamic = js("({})")
    for (param in tab.info.params) params[param.name] = binder.toJs(param.default)
    val paging: dynamic = js("({})")
    paging.offset = offset
    paging.limit = limit
    val rowsFn = tab.rows
    val result: dynamic = rowsFn(binary, params, paging)
    if (result == null || result == undefined) return BrowserTabPage(emptyList(), null)
    val rowsJs: dynamic = result.rows
    val rows = mutableListOf<Pair<Long?, List<String>>>()
    if (isJsArray(rowsJs)) {
        val count = rowsJs.length as Int
        for (i in 0 until count) {
            val row: dynamic = rowsJs[i]
            val cellsJs: dynamic = row?.cells
            val cells = if (isJsArray(cellsJs)) {
                (0 until (cellsJs.length as Int)).map { j -> cellText(cellsJs[j]) }
            } else {
                emptyList()
            }
            val anchor = (row?.anchor as? Number)?.toLong()
            rows += anchor to cells
        }
    }
    val next = (result.nextOffset as? Number)?.toInt()
    return BrowserTabPage(rows, next)
}

private fun cellText(value: dynamic): String = when {
    value == null || value == undefined -> ""
    else -> value.toString() as String
}

private fun isJsArray(value: dynamic): Boolean = js("Array").isArray(value) as Boolean

/** Reads `parse()`'s return: `{ regions }`, the array itself, or nothing. */
private fun parseResult(parse: dynamic, binary: dynamic) = ProviderParseResultBuilder().apply {
    if (parse == null || parse == undefined) return@apply
    val result: dynamic = parse(binary)
    val regions: dynamic = when {
        result == null || result == undefined -> return@apply
        isJsArray(result) -> result
        else -> result.regions
    }
    if (regions == null || regions == undefined) return@apply
    if (!isJsArray(regions)) throw IllegalArgumentException("regions must be an array")
    for (i in 0 until (regions.length as Int)) {
        val region: dynamic = regions[i]
        region(
            name = region?.name as? String,
            offset = region?.offset as? Number,
            length = region?.length as? Number,
            tab = region?.tab as? String,
        )
    }
}.build()

/**
 * Evaluates one script with a `hexana` global that collects `defineProvider` / `defineTab`.
 * The other v2 members exist so a script written for the JVM does not fail here: slices and
 * checks are accepted and ignored (they belong to script tabs), flags are empty.
 */
private fun evaluateProviderScript(source: ProviderScriptSource, binary: dynamic): BrowserProviderScript {
    val providers = mutableListOf<BrowserScriptedProvider>()
    val tabs = mutableListOf<BrowserScriptTab>()
    val hexana: dynamic = js("({})")
    hexana.api = 2
    hexana.args = js("[]")
    hexana.inputs = js("[]")
    hexana.inputs.push(binary)
    hexana.flags = js("({})")
    hexana.has = { feature: String -> feature in setOf("slices", "checks", "tabs", "providers") }
    hexana.defineProvider = { spec: dynamic ->
        val provider = parseProviderSpec(spec)
        require(providers.none { it.info.id == provider.info.id }) {
            "hexana.defineProvider: duplicate provider id \"${provider.info.id}\""
        }
        providers += provider
    }
    hexana.defineTab = { spec: dynamic ->
        val tab = parseTabSpec(spec)
        require(tabs.none { it.info.name == tab.info.name }) {
            "hexana.defineTab: duplicate tab name \"${tab.info.name}\""
        }
        tabs += tab
    }
    hexana.defineSlice = { _: dynamic -> }
    hexana.defineCheck = { _: dynamic -> }
    hexana.defineFlags = { _: dynamic -> }
    val console: dynamic = js("({ log: function() {}, error: function() {}, debug: function() {} })")

    return try {
        val compiled: dynamic = js("Function")("hexana", "console", "binary", source.text)
        compiled(hexana, console, binary)
        BrowserProviderScript(source, providers, tabs)
    } catch (e: Throwable) {
        BrowserProviderScript(source, emptyList(), emptyList(), e.message ?: "script error")
    }
}

private fun parseProviderSpec(spec: dynamic): BrowserScriptedProvider {
    val api = "hexana.defineProvider"
    if (spec == null || spec == undefined || jsTypeOf(spec) != "object") throw IllegalArgumentException("$api expects an object spec")
    val id = spec.id as? String ?: throw IllegalArgumentException("$api: spec.id is required (string)")
    require(id.startsWith(USER_CONTRIBUTION_PREFIX) && id.length > USER_CONTRIBUTION_PREFIX.length) {
        "$api: provider id must start with \"user:\", got \"$id\""
    }
    val name = (spec.name as? String)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$api: spec.name is required (non-empty string)")
    val baseId = spec.base
    val base = if (baseId == null || baseId == undefined) {
        BinaryParser.GENERIC
    } else {
        val text = baseId as? String ?: throw IllegalArgumentException("$api: spec.base must be a string")
        BinaryParser.fromId(text) ?: throw IllegalArgumentException(
            "$api: spec.base \"$text\" is not a built-in parser; valid values: " +
                BinaryParser.entries.joinToString(", ") { it.id },
        )
    }
    val parse: dynamic = spec.parse
    if (parse != null && parse != undefined && jsTypeOf(parse) != "function") {
        throw IllegalArgumentException("$api: spec.parse must be a function")
    }
    return BrowserScriptedProvider(
        ProviderRegistrationInfo(id, name, base, spec.description as? String),
        if (parse == undefined) null else parse,
    )
}

private fun parseTabSpec(spec: dynamic): BrowserScriptTab {
    val api = "hexana.defineTab"
    if (spec == null || spec == undefined || jsTypeOf(spec) != "object") throw IllegalArgumentException("$api expects an object spec")
    val name = spec.name as? String ?: throw IllegalArgumentException("$api: spec.name is required (string)")
    require(name.startsWith(USER_CONTRIBUTION_PREFIX)) { "$api: tab name must start with \"user:\", got \"$name\"" }
    val appliesTo: dynamic = spec.appliesTo
    if (!isJsArray(appliesTo)) throw IllegalArgumentException("$api: spec.appliesTo is required (array of format or provider ids)")
    val formats = (0 until (appliesTo.length as Int)).map { appliesTo[it].toString() as String }
    require(formats.isNotEmpty()) { "$api: spec.appliesTo must not be empty; use [\"*\"] for every binary" }
    val columnsJs: dynamic = spec.columns
    if (!isJsArray(columnsJs) || (columnsJs.length as Int) == 0) {
        throw IllegalArgumentException("$api: spec.columns is required (non-empty array)")
    }
    val columns = (0 until (columnsJs.length as Int)).map { i ->
        val column: dynamic = columnsJs[i]
        val columnName = column?.name as? String ?: throw IllegalArgumentException("$api: columns[$i].name is required (string)")
        SliceColumn(columnName, columnType(column.type as? String, "$api: columns[$i].type"))
    }
    val paramsJs: dynamic = spec.params
    val params = if (isJsArray(paramsJs)) {
        (0 until (paramsJs.length as Int)).map { i ->
            val param: dynamic = paramsJs[i]
            val paramName = param?.name as? String ?: throw IllegalArgumentException("$api: params[$i].name is required (string)")
            SliceParam(paramName, columnType(param.type as? String, "$api: params[$i].type"), defaultValue(param.default))
        }
    } else {
        emptyList()
    }
    val rows: dynamic = spec.rows
    if (jsTypeOf(rows) != "function") throw IllegalArgumentException("$api: spec.rows is required (function)")
    return BrowserScriptTab(SliceRegistrationInfo(name, formats, params, columns, isTab = true), rows)
}

private fun columnType(type: String?, context: String): SliceColumnType =
    SliceColumnType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) }
        ?: throw IllegalArgumentException(
            "$context has unknown type \"$type\"; valid values: string, int, offset, funcidx, bool",
        )

private fun defaultValue(value: dynamic): ScriptValue = when (jsTypeOf(value)) {
    "string" -> ScriptValue.Str(value as String)
    "number" -> ScriptValue.Num((value as Number).toDouble())
    "boolean" -> ScriptValue.Bool(value as Boolean)
    else -> ScriptValue.Null
}

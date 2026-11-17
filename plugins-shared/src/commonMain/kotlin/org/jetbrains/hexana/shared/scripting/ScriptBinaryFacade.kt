package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.resolveFunctionName
import org.jetbrains.hexana.wasm.sections.WasmExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

private const val DEFAULT_FUNCTIONS_LIMIT = 1000

/**
 * `binary.functions({query?, offset?, limit?})` curated facade.
 *
 * Returns a paginated list of functions from a Wasm binary. Non-Wasm providers
 * return an empty page immediately.
 *
 * Supported query options:
 * - `name` (string): case-insensitive substring match on the resolved name.
 * - `unnamed` (boolean): when true, only functions without a resolved name are returned.
 * - `minBodySize` (number): only functions whose body size is at least this many bytes.
 *   Import functions have no body and are excluded when this filter is active.
 * - `offset` (number): zero-based start index into the filtered list.
 * - `limit` (number): maximum items to return, default 1000.
 *
 * Result shape: `{ items: FunctionItem[], total: number, nextOffset?: number }`.
 */
internal fun scriptFunctionsValue(provider: BinaryProvider): ScriptValue =
    scriptFunction("binary.functions(options?)", minArgs = 0, maxArgs = 1) { args ->
        if (provider !is WasmBinaryProvider) return@scriptFunction emptyFunctionsPage()

        val opts = optionsObj(args, 0)
        val nameFilter = opts?.strOpt("name")
        val unnamedOnly = opts?.boolOpt("unnamed") ?: false
        val minBodySize = opts?.intOpt("minBodySize")
        val offset = opts?.intOpt("offset") ?: 0
        val limit = opts?.intOpt("limit") ?: DEFAULT_FUNCTIONS_LIMIT

        val namemap = provider.nameSection?.functionSubsection?.namemap
        val importFuncCount = provider.importFuncCount
        val imports = provider.imports.filter { it.type is WasmTypeIndexExternalType }
        val codeOffsets = provider.codeOffsets

        data class RawItem(
            val funcidx: Int,
            val name: String?,
            val source: String,
            val importModule: String?,
            val importName: String?,
            val codeOffset: Int?,
            val codeSize: Int?,
        )

        val all = buildList {
            imports.forEachIndexed { i, rec ->
                val resolved = resolveFunctionName(namemap, i).takeIf { it.isNotEmpty() }
                if (minBodySize != null && minBodySize > 0) return@forEachIndexed
                if (unnamedOnly && resolved != null) return@forEachIndexed
                if (nameFilter != null && (resolved == null || !resolved.contains(nameFilter, ignoreCase = true))) {
                    return@forEachIndexed
                }
                add(RawItem(i, resolved, "import", rec.prefix, rec.name, null, null))
            }
            codeOffsets.forEachIndexed { codeIdx, co ->
                val globalIdx = importFuncCount + codeIdx
                val resolved = resolveFunctionName(namemap, globalIdx).takeIf { it.isNotEmpty() }
                if (minBodySize != null && co.size < minBodySize) return@forEachIndexed
                if (unnamedOnly && resolved != null) return@forEachIndexed
                if (nameFilter != null && (resolved == null || !resolved.contains(nameFilter, ignoreCase = true))) {
                    return@forEachIndexed
                }
                add(RawItem(globalIdx, resolved, "code", null, null, co.offset, co.size))
            }
        }

        val total = all.size
        val page = all.drop(offset).take(limit)
        val nextOffset = (offset + page.size).takeIf { it < total }

        ScriptValue.Obj(
            linkedMapOf(
                "items" to {
                    ScriptValue.array(page) { item ->
                        ScriptValue.Obj(
                            buildLinkedMap {
                                put("funcidx") { ScriptValue.Num(item.funcidx) }
                                put("name") { ScriptValue.of(item.name) }
                                put("source") { ScriptValue.Str(item.source) }
                                if (item.source == "import") {
                                    put("importModule") { ScriptValue.of(item.importModule) }
                                    put("importName") { ScriptValue.of(item.importName) }
                                } else {
                                    put("codeOffset") { ScriptValue.Num(item.codeOffset!!) }
                                    put("codeSize") { ScriptValue.Num(item.codeSize!!) }
                                }
                            }
                        )
                    }
                },
                "total" to { ScriptValue.Num(total) },
                "nextOffset" to { ScriptValue.of(nextOffset) },
            )
        )
    }

/**
 * `binary.imports()` curated facade.
 *
 * Returns an array of all import entries from a Wasm binary.
 * Each entry: `{ module: string, name: string, kind: string, index: number }`.
 * Non-Wasm providers return an empty array.
 */
internal fun scriptImportsValue(provider: BinaryProvider): ScriptValue =
    scriptFunction("binary.imports()", minArgs = 0, maxArgs = 0) { _ ->
        if (provider !is WasmBinaryProvider) return@scriptFunction ScriptValue.EMPTY_ARRAY
        // Imports occupy the front of each kind's index space, so the running
        // per-kind ordinal IS the imported entity's index (funcidx for "func").
        val kindCounters = mutableMapOf<String, Int>()
        val entries = provider.imports.map { record ->
            val kind = record.type.wasmKindName()
            val index = kindCounters.getOrElse(kind) { 0 }
            kindCounters[kind] = index + 1
            ImportEntry(record, kind, index)
        }
        ScriptValue.array(entries) { entry -> entry.toScriptValue() }
    }

/**
 * `binary.exports()` curated facade.
 *
 * Returns an array of all export entries from a Wasm binary.
 * Each entry: `{ name: string, kind: string, index: number }`.
 * Non-Wasm providers return an empty array.
 */
internal fun scriptExportsValue(provider: BinaryProvider): ScriptValue =
    scriptFunction("binary.exports()", minArgs = 0, maxArgs = 0) { _ ->
        if (provider !is WasmBinaryProvider) return@scriptFunction ScriptValue.EMPTY_ARRAY
        ScriptValue.array(provider.exports) { record -> record.toScriptValue() }
    }

// -- Mappers -----------------------------------------------------------------

private class ImportEntry(val record: WasmImportRecord, val kind: String, val index: Int)

private fun ImportEntry.toScriptValue(): ScriptValue = ScriptValue.Obj(
    linkedMapOf(
        "module" to { ScriptValue.Str(record.prefix) },
        "name" to { ScriptValue.Str(record.name) },
        "kind" to { ScriptValue.Str(kind) },
        "index" to { ScriptValue.Num(index) },
    )
)

private fun WasmExportRecordEntry.toScriptValue(): ScriptValue = ScriptValue.Obj(
    linkedMapOf(
        "name" to { ScriptValue.Str(name) },
        "kind" to { ScriptValue.Str(externType.wasmKindName()) },
        "index" to { ScriptValue.Num(externIndex().toLong()) },
    )
)

private fun WasmExportRecordEntry.externIndex(): UInt = when (this) {
    is WasmFunctionExportRecordEntry -> index
    is WasmTableExportRecordEntry -> index
    is WasmMemoryExportRecordEntry -> index
    is WasmGlobalExportRecordEntry -> index
    is WasmTagExportRecordEntry -> index
    else -> 0u
}

private fun org.jetbrains.hexana.wasm.sections.WasmExternalType.wasmKindName(): String = when (this) {
    is WasmTypeIndexExternalType -> "func"
    is WasmTableExternalType -> "table"
    is WasmMemType -> "memory"
    is WasmGlobalType -> "global"
    else -> "extern"
}

private fun Byte.wasmKindName(): String = when (toInt()) {
    0 -> "func"
    1 -> "table"
    2 -> "memory"
    3 -> "global"
    4 -> "tag"
    else -> "extern"
}

// -- Helpers -----------------------------------------------------------------

private fun emptyFunctionsPage(): ScriptValue = ScriptValue.Obj(
    linkedMapOf(
        "items" to { ScriptValue.EMPTY_ARRAY },
        "total" to { ScriptValue.Num(0) },
        "nextOffset" to { ScriptValue.Null },
    )
)

/** Reads the optional first argument as an Obj, or null when absent. */
private fun optionsObj(args: ScriptArgs, index: Int): OptionsReader? {
    val value = args.at(index)
    return if (value is ScriptValue.Obj) OptionsReader(value) else null
}

private class OptionsReader(private val obj: ScriptValue.Obj) {
    fun strOpt(key: String): String? {
        val v = obj.members[key]?.invoke() ?: return null
        return if (v is ScriptValue.Str) v.value.takeIf { it.isNotEmpty() } else null
    }

    fun boolOpt(key: String): Boolean {
        val v = obj.members[key]?.invoke() ?: return false
        return if (v is ScriptValue.Bool) v.value else false
    }

    fun intOpt(key: String): Int? {
        val v = obj.members[key]?.invoke() ?: return null
        return if (v is ScriptValue.Num) v.value.toInt() else null
    }
}

private fun buildLinkedMap(block: LinkedMapBuilder.() -> Unit): Map<String, () -> ScriptValue> {
    val builder = LinkedMapBuilder()
    builder.block()
    return builder.map
}

private class LinkedMapBuilder {
    val map = linkedMapOf<String, () -> ScriptValue>()
    fun put(key: String, factory: () -> ScriptValue) { map[key] = factory }
}

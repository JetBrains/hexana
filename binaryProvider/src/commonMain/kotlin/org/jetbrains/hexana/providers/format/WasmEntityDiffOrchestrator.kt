package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.EntityDiffEntry
import org.jetbrains.hexana.wasm.analysis.diff.EntityDiffResult
import org.jetbrains.hexana.wasm.analysis.diff.EntityInput
import org.jetbrains.hexana.wasm.analysis.diff.EntityKind
import org.jetbrains.hexana.wasm.analysis.diff.MatchKey
import org.jetbrains.hexana.wasm.analysis.diff.WasmEntityDiff
import org.jetbrains.hexana.wasm.sections.WasmExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmExternalType
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import org.jetbrains.hexana.wasm.sections.WasmLimits
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord

/** A newly-added or removed import, with host-namespace classification. */
public data class ImportDelta(
    val module: String,
    val field: String,
    val kind: String,
    val isHost: Boolean,
)

/** Name-enriched entity diff plus the supply-chain import deltas. */
public data class WasmEntityDiffResult(
    val entities: EntityDiffResult,
    val newImports: List<ImportDelta>,
    val removedImports: List<ImportDelta>,
)

/**
 * Builds an entity-level diff (all kinds: imports/exports/functions/globals/
 * tables/memories/types/data/element) between two core WASM modules, plus the
 * supply-chain new/removed-import lists.
 *
 * v2a matching is by stable key (import `module.field`, export name, resolved
 * function name, name-section name or index for the rest) with a compact `detail`
 * string deciding MODIFIED vs IDENTICAL. No content hashing / MOVED / fuzzy.
 */
public object WasmEntityDiffOrchestrator {

    public fun diff(left: WasmBinaryProvider, right: WasmBinaryProvider): WasmEntityDiffResult? {
        if (!left.canProvideWasmModule() || !right.canProvideWasmModule()) return null

        // All kinds except FUNCTION use v2a key/detail matching. Functions use the
        // v2b content-hash matcher (MOVED + churn-free pairing), emitted directly.
        val byKind = mapOf(
            EntityKind.IMPORT to (imports(left) to imports(right)),
            EntityKind.EXPORT to (exports(left) to exports(right)),
            EntityKind.GLOBAL to (globals(left) to globals(right)),
            EntityKind.TABLE to (tables(left) to tables(right)),
            EntityKind.MEMORY to (memories(left) to memories(right)),
            EntityKind.TYPE to (types(left) to types(right)),
            EntityKind.DATA to (dataSegments(left) to dataSegments(right)),
            EntityKind.ELEMENT to (elements(left) to elements(right)),
        )
        val generic = WasmEntityDiff.diff(byKind)
        val functionEntries = functionEntries(left, right)
        val entities = EntityDiffResult(generic.entries + functionEntries)

        val leftKeys = left.imports.map { it.prefix to it.name }.toSet()
        val rightKeys = right.imports.map { it.prefix to it.name }.toSet()
        val newImports = right.imports
            .filter { (it.prefix to it.name) !in leftKeys }
            .map { it.toDelta() }
        val removedImports = left.imports
            .filter { (it.prefix to it.name) !in rightKeys }
            .map { it.toDelta() }

        return WasmEntityDiffResult(entities, newImports, removedImports)
    }

    // --- per-kind enumeration -> EntityInput ---

    private fun imports(p: WasmBinaryProvider): List<EntityInput> =
        p.imports.map { rec ->
            val (kind, detail) = importKindAndDetail(rec.type)
            EntityInput(MatchKey("imp:${rec.prefix} ${rec.name}"), "${rec.prefix}.${rec.name}", "$kind $detail")
        }

    private fun exports(p: WasmBinaryProvider): List<EntityInput> =
        p.exports.map { rec ->
            EntityInput(MatchKey("exp:${rec.name}"), rec.name, "kind=${rec.externType} #${exportIndex(rec)}")
        }

    private fun exportIndex(rec: WasmExportRecordEntry): String = when (rec) {
        is WasmFunctionExportRecordEntry -> rec.index.toString()
        is WasmTableExportRecordEntry -> rec.index.toString()
        is WasmMemoryExportRecordEntry -> rec.index.toString()
        is WasmGlobalExportRecordEntry -> rec.index.toString()
        is WasmTagExportRecordEntry -> rec.index.toString()
        else -> "?"
    }

    /**
     * Function diff entries from the v2b content-hash matcher (IDENTICAL / MOVED /
     * MODIFIED + ADDED/REMOVED), carrying index delta + confidence. Replaces the
     * v2a per-side name/index keying for functions.
     */
    private fun functionEntries(left: WasmBinaryProvider, right: WasmBinaryProvider): List<EntityDiffEntry> {
        val result = WasmFunctionMatchProvider.match(left, right) ?: return emptyList()
        val leftRes = WasmSymbolResolver(left)
        val rightRes = WasmSymbolResolver(right)
        val leftImp = left.importFuncCount
        val rightImp = right.importFuncCount
        val leftSigs = left.functions
        val rightSigs = right.functions

        fun name(res: WasmSymbolResolver, imp: Int, codeIdx: Int) =
            res.resolve(SymbolKind.FUNCTION, imp + codeIdx) ?: "\$func${imp + codeIdx}"

        val out = ArrayList<EntityDiffEntry>(result.matches.size + result.unmatchedLeft.size + result.unmatchedRight.size)
        for (m in result.matches) {
            out.add(
                EntityDiffEntry(
                    kind = EntityKind.FUNCTION,
                    key = MatchKey("fn:${m.leftIdx}->${m.rightIdx}"),
                    classification = m.classification,
                    name = name(rightRes, rightImp, m.rightIdx),
                    detailA = signatureDetail(leftSigs.getOrNull(m.leftIdx)),
                    detailB = signatureDetail(rightSigs.getOrNull(m.rightIdx)),
                    indexDelta = m.indexDelta,
                    confidence = m.confidence,
                ),
            )
        }
        for (li in result.unmatchedLeft) {
            out.add(
                EntityDiffEntry(
                    EntityKind.FUNCTION, MatchKey("fn:rm:$li"), DiffClass.REMOVED,
                    name(leftRes, leftImp, li), signatureDetail(leftSigs.getOrNull(li)), null,
                ),
            )
        }
        for (ri in result.unmatchedRight) {
            out.add(
                EntityDiffEntry(
                    EntityKind.FUNCTION, MatchKey("fn:add:$ri"), DiffClass.ADDED,
                    name(rightRes, rightImp, ri), null, signatureDetail(rightSigs.getOrNull(ri)),
                ),
            )
        }
        return out
    }

    private fun globals(p: WasmBinaryProvider): List<EntityInput> {
        val module = p.wasmModule ?: return emptyList()
        val section = module.globalSection ?: return emptyList()
        val names = p.nameSection?.globalsNameSection?.namemap
        return with(section) { p.bytes.scan() }.mapIndexed { i, rec ->
            val name = names?.get(i)
            val mut = if (rec.type.mutable) "mut" else "const"
            val key = name?.let { MatchKey("glob:name:$it") } ?: MatchKey("glob:idx:$i")
            EntityInput(key, name ?: "global $i", "${rec.type.valueType} $mut")
        }
    }

    private fun tables(p: WasmBinaryProvider): List<EntityInput> {
        val module = p.wasmModule ?: return emptyList()
        val section = module.tableSection ?: return emptyList()
        val names = p.nameSection?.tablesNameSection?.namemap
        return with(section) { p.bytes.scanTable() }.mapIndexed { i, rec ->
            val name = names?.get(i)
            val key = name?.let { MatchKey("tbl:name:$it") } ?: MatchKey("tbl:idx:$i")
            val detail = "${rec.tableType.referenceType} ${limits(rec.tableType.limits)}"
            EntityInput(key, name ?: "table $i", detail)
        }
    }

    private fun memories(p: WasmBinaryProvider): List<EntityInput> {
        val module = p.wasmModule ?: return emptyList()
        val section = module.memorySection ?: return emptyList()
        val names = p.nameSection?.memoriesNameSection?.namemap
        return with(section) { p.bytes.scan() }.mapIndexed { i, rec ->
            val name = names?.get(i)
            EntityInput(name?.let { MatchKey("mem:name:$it") } ?: MatchKey("mem:idx:$i"), name ?: "memory $i", limits(rec.limits))
        }
    }

    private fun types(p: WasmBinaryProvider): List<EntityInput> {
        val module = p.wasmModule ?: return emptyList()
        val section = module.typesSection ?: return emptyList()
        val names = p.nameSection?.typesSection?.namemap
        val flat = with(section) { p.bytes.parseTypes() }
            .flatMap { if (it is WasmRecursiveTypeRecord) it.entries else listOf(it) }
        return flat.mapIndexed { i, rec ->
            val name = names?.get(i)
            EntityInput(MatchKey("type:idx:$i"), name ?: "type $i", typeDetail(rec))
        }
    }

    private fun dataSegments(p: WasmBinaryProvider): List<EntityInput> =
        p.data.mapIndexed { i, rec ->
            EntityInput(MatchKey("data:idx:$i"), "data $i", rec::class.simpleName ?: "data")
        }

    private fun elements(p: WasmBinaryProvider): List<EntityInput> {
        val module = p.wasmModule ?: return emptyList()
        val section = module.elementSection ?: return emptyList()
        return with(section) { p.bytes.scanElements() }.mapIndexed { i, seg ->
            EntityInput(MatchKey("elem:idx:$i"), "element $i", seg::class.simpleName ?: "element")
        }
    }

    // --- detail rendering ---

    private fun importKindAndDetail(type: WasmExternalType): Pair<String, String> = when (type) {
        is WasmTypeIndexExternalType -> "func" to "type=${type.index}"
        is WasmTableExternalType -> "table" to "${type.referenceType} ${limits(type.limits)}"
        is WasmMemType -> "memory" to limits(type.limits)
        is WasmGlobalType -> "global" to "${type.valueType} ${if (type.mutable) "mut" else "const"}"
        else -> "extern" to "type=${type.type}"
    }

    private fun importKind(type: WasmExternalType): String = importKindAndDetail(type).first

    private fun signatureDetail(sig: WasmTypeRecord?): String = when (sig) {
        null -> "?"
        is WasmSectionFunctionTypeRecord ->
            "func(${sig.params.joinToString(",")})->(${sig.retTypes.joinToString(",")})"
        else -> typeDetail(sig)
    }

    private fun typeDetail(rec: WasmTypeRecord): String = when (rec) {
        is WasmSectionFunctionTypeRecord ->
            "func(${rec.params.joinToString(",")})->(${rec.retTypes.joinToString(",")})"
        else -> rec::class.simpleName ?: "type"
    }

    private fun limits(l: WasmLimits<*>): String =
        "min=${l.min}" + (l.max?.let { " max=$it" } ?: "")

    private fun WasmImportRecord.toDelta(): ImportDelta =
        ImportDelta(prefix, name, importKind(type), WasmHostImportClassifier.isHostImport(prefix, name))
}

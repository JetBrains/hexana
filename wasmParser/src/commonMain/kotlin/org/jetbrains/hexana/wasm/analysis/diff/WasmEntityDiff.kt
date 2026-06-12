package org.jetbrains.hexana.wasm.analysis.diff

/** The kind of WASM entity an [EntityDiffEntry] describes. */
public enum class EntityKind { IMPORT, EXPORT, FUNCTION, GLOBAL, TABLE, MEMORY, TYPE, DATA, ELEMENT }

/**
 * Input to the entity diff for a single entity on one side. [key] is the stable
 * match key (e.g. import `module.field`, export name, resolved function name);
 * [detail] is a compact rendering of the entity's compared content — two matched
 * entities with differing [detail] are classified [DiffClass.MODIFIED].
 */
public data class EntityInput(
    val key: MatchKey,
    val name: String?,
    val detail: String?,
)

/**
 * One classified entity in an entity diff. [detailA]/[detailB] are the base/head
 * detail strings; the absent side is `null` ([DiffClass.ADDED] has no `detailA`,
 * [DiffClass.REMOVED] has no `detailB`).
 *
 * v2a has no [DiffClass]-`MOVED`: matching is by stable key, so a renamed-but-
 * key-stable entity is IDENTICAL/MODIFIED. Content-hash MOVED detection is v2b.
 */
public data class EntityDiffEntry(
    val kind: EntityKind,
    val key: MatchKey,
    val classification: DiffClass,
    val name: String?,
    val detailA: String?,
    val detailB: String?,
    /** Index shift (head − base), set by the function matcher for MOVED rows. */
    val indexDelta: Int? = null,
    /** Match confidence [0,1], set by the function matcher. */
    val confidence: Float? = null,
)

/** Result of an entity diff across all kinds. */
public data class EntityDiffResult(val entries: List<EntityDiffEntry>) {
    /** Per-kind classification counts, for summary headers. */
    public fun summary(): Map<EntityKind, Map<DiffClass, Int>> =
        entries.groupBy { it.kind }
            .mapValues { (_, kindEntries) ->
                kindEntries.groupingBy { it.classification }.eachCount()
            }
}

/**
 * Aligns two modules' entities, per kind, into a classified flat list.
 *
 * Pure: the caller supplies stable keys, display names, and comparable detail
 * strings (see [EntityInput]). Matching reuses the same bucket primitive as
 * [WasmSizeDiff] — match by key within a kind, classify matched pairs by whether
 * their detail differs, leftovers become added/removed.
 */
public object WasmEntityDiff {

    public fun diff(byKind: Map<EntityKind, Pair<List<EntityInput>, List<EntityInput>>>): EntityDiffResult {
        val entries = ArrayList<EntityDiffEntry>()
        for ((kind, sides) in byKind) {
            diffKind(kind, sides.first, sides.second, entries)
        }
        return EntityDiffResult(entries)
    }

    private fun diffKind(
        kind: EntityKind,
        lefts: List<EntityInput>,
        rights: List<EntityInput>,
        out: MutableList<EntityDiffEntry>,
    ) {
        // Bucket right-side *indices* by key (EntityInput is a data class, so a
        // structural-equality set would conflate two identical entities; indices
        // give per-instance identity and preserve duplicate-key 1:1 matching).
        val rightBuckets = HashMap<MatchKey, ArrayDeque<Int>>()
        rights.forEachIndexed { i, r -> rightBuckets.getOrPut(r.key) { ArrayDeque() }.addLast(i) }
        val matched = BooleanArray(rights.size)

        for (l in lefts) {
            val ri = rightBuckets[l.key]?.removeFirstOrNull()
            if (ri != null) {
                matched[ri] = true
                val r = rights[ri]
                val cls = if (l.detail != r.detail) DiffClass.MODIFIED else DiffClass.IDENTICAL
                out.add(EntityDiffEntry(kind, l.key, cls, r.name ?: l.name, l.detail, r.detail))
            } else {
                out.add(EntityDiffEntry(kind, l.key, DiffClass.REMOVED, l.name, l.detail, null))
            }
        }
        rights.forEachIndexed { i, r ->
            if (!matched[i]) out.add(EntityDiffEntry(kind, r.key, DiffClass.ADDED, r.name, null, r.detail))
        }
    }
}

package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.containsRange
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.analysis.diff.match.FnvHash

/** Whether an embedded submodule is a core WASM module or a nested component. */
enum class SubmoduleKind { CORE_MODULE, COMPONENT }

/** How a submodule compares between the two components (base ↔ head). */
enum class SubmoduleStatus { IDENTICAL, MODIFIED, ADDED, REMOVED }

/**
 * One side's reference to an embedded submodule.
 *
 * [contentOffset]/[contentSize] address the submodule's bytes **in the parent
 * component buffer**: the slice `[contentOffset, contentOffset + contentSize)` is a
 * self-contained binary that begins with the `\0asm` preamble (verified for both
 * core modules and nested components), so the UI can `slice` it and hand it straight
 * to the existing core/component diff pipeline. [contentHash] is an FNV-1a fingerprint
 * of those bytes, used only to pair "the same" submodule within a single diff run.
 */
class SubmoduleRef(
    val kind: SubmoduleKind,
    val contentOffset: Int,
    val contentSize: Int,
    val label: String,
    val contentHash: Long,
)

/** A matched (or one-sided) submodule between the two components. */
class SubmodulePairing(
    val kind: SubmoduleKind,
    val status: SubmoduleStatus,
    val left: SubmoduleRef?,
    val right: SubmoduleRef?,
)

/** Immutable result of comparing the submodule sets of two component-model binaries. */
class ComponentDiffResult(val pairings: List<SubmodulePairing>) {
    /** Submodules that are not byte-identical between the two sides. */
    val differingCount: Int get() = pairings.count { it.status != SubmoduleStatus.IDENTICAL }
}

/**
 * Headless diff of two Component Model binaries at the *submodule* granularity.
 *
 * It enumerates each side's embedded submodules (core modules + the nested component,
 * if any), content-hashes them, and pairs them so the caller can see which differ and
 * open a per-submodule comparison. The heavy per-submodule comparison itself is NOT done
 * here — the caller slices the two submodule byte ranges and feeds them to the existing
 * core/component diff (see `HexanaFileEditor.initComponentDiffPanel`), so this engine
 * stays cheap (one pass + one hash per submodule) and the deep diff is recomputed lazily
 * only for the pair the user clicks.
 *
 * Pure Kotlin so it compiles on every KMP target; reuse from MCP / the VS Code panel is
 * possible later. Hashing is deterministic only within a single run, which is all the
 * pairing needs.
 */
object WasmComponentDiffOrchestrator {

    fun diff(left: WasmComponentBinaryProvider, right: WasmComponentBinaryProvider): ComponentDiffResult? {
        if (!left.canProvideWasmModule() || !right.canProvideWasmModule()) return null
        return ComponentDiffResult(pair(enumerate(left), enumerate(right)))
    }

    /** Builds the submodule refs for one component: core modules in order, then the nested component. */
    private fun enumerate(provider: WasmComponentBinaryProvider): List<SubmoduleRef> {
        val buf = provider.bytes // independent cursor; slices are absolute, position-free
        val refs = ArrayList<SubmoduleRef>()
        provider.modules.forEachIndexed { i, module ->
            if (buf.containsRange(module.contentOffset, module.contentSize)) {
                refs.add(refOf(buf, SubmoduleKind.CORE_MODULE, module.contentOffset, module.contentSize, "module[$i]"))
            }
        }
        // The parser currently exposes at most one nested component section per level. Drilling
        // into it re-parses the slice fresh, so deeper nesting is still reachable one level at a time.
        provider.wasmModule?.componentSection?.let { section ->
            if (buf.containsRange(section.contentOffset, section.contentSize)) {
                refs.add(refOf(buf, SubmoduleKind.COMPONENT, section.contentOffset, section.contentSize, "component[0]"))
            }
        }
        return refs
    }

    private fun refOf(
        buf: CommonByteBuffer,
        kind: SubmoduleKind,
        offset: Int,
        size: Int,
        label: String,
    ): SubmoduleRef = SubmoduleRef(kind, offset, size, label, hashSlice(buf.slice(offset, size)))

    /**
     * Pairs submodules within each kind: equal content first (handles insertion / removal /
     * reorder), then positional among the remainder, then the leftovers are one-sided.
     */
    private fun pair(left: List<SubmoduleRef>, right: List<SubmoduleRef>): List<SubmodulePairing> {
        val result = ArrayList<SubmodulePairing>()
        for (kind in SubmoduleKind.values()) {
            result.addAll(
                pairWithinKind(
                    kind,
                    left.filter { it.kind == kind },
                    right.filter { it.kind == kind },
                ),
            )
        }
        return result
    }

    private fun pairWithinKind(
        kind: SubmoduleKind,
        left: List<SubmoduleRef>,
        right: List<SubmoduleRef>,
    ): List<SubmodulePairing> {
        val out = ArrayList<SubmodulePairing>()
        val rightUsed = BooleanArray(right.size)
        val leftMatched = BooleanArray(left.size)

        // Pass 1: equal content → IDENTICAL. Hash equality plus size equality makes a false
        // match astronomically unlikely; we never claim IDENTICAL on a hash collision alone.
        for (li in left.indices) {
            val l = left[li]
            for (ri in right.indices) {
                if (rightUsed[ri]) continue
                val r = right[ri]
                if (r.contentHash == l.contentHash && r.contentSize == l.contentSize) {
                    out.add(SubmodulePairing(kind, SubmoduleStatus.IDENTICAL, l, r))
                    rightUsed[ri] = true
                    leftMatched[li] = true
                    break
                }
            }
        }

        // Pass 2: positional pairing of the remainder (submodule order in a component is stable).
        val remL = left.filterIndexed { i, _ -> !leftMatched[i] }
        val remR = right.filterIndexed { i, _ -> !rightUsed[i] }
        val common = minOf(remL.size, remR.size)
        for (i in 0 until common) {
            out.add(SubmodulePairing(kind, SubmoduleStatus.MODIFIED, remL[i], remR[i]))
        }
        // Pass 3: leftovers are one-sided.
        for (i in common until remL.size) out.add(SubmodulePairing(kind, SubmoduleStatus.REMOVED, remL[i], null))
        for (i in common until remR.size) out.add(SubmodulePairing(kind, SubmoduleStatus.ADDED, null, remR[i]))
        return out
    }

    /**
     * FNV-1a over a slice, packing 8 bytes per [FnvHash.update] call (the primitive mixes a
     * whole Long), so a multi-MB embedded module hashes in one linear pass without
     * materializing an intermediate [ByteArray].
     */
    private fun hashSlice(slice: CommonByteBuffer): Long {
        val h = FnvHash()
        val n = slice.size
        var i = 0
        while (i + 8 <= n) {
            var packed = 0L
            for (k in 0 until 8) {
                packed = packed or ((slice[i + k].toLong() and 0xFF) shl (k * 8))
            }
            h.update(packed)
            i += 8
        }
        if (i < n) {
            var packed = 0L
            var k = 0
            while (i < n) {
                packed = packed or ((slice[i].toLong() and 0xFF) shl (k * 8))
                i++
                k++
            }
            h.update(packed)
        }
        return h.value()
    }
}

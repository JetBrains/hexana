package org.jetbrains.hexana.wasm.analysis.exceptions

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes

/**
 * How the `throw` / `throw_ref` at a given site is handled WITHIN its own
 * function (intra-procedural, E1 of the exception-flow spec). Sound: it walks
 * the explicit `try_table` nesting the parser already gives us; it does not
 * follow calls (that is E2's call-graph propagation).
 */
sealed class ThrowResolution {
    /**
     * Caught by an enclosing `try_table` in this function. [handlerOffset] is
     * the try_table's byte offset (navigation target); [byCatchAll] is true
     * when the matching clause is `catch_all` / `catch_all_ref` rather than a
     * tag-specific `catch`.
     */
    data class CaughtHere(val handlerOffset: Int, val byCatchAll: Boolean) : ThrowResolution()

    /** No enclosing handler in this function — the exception escapes to callers. */
    data object Escapes : ThrowResolution()

    /**
     * A `throw_ref` whose tag is not statically known: it can only be shown as
     * caught by an enclosing `catch_all(_ref)` on this path, otherwise it
     * escapes. [byCatchAll] distinguishes the two. Over-approximation, labeled.
     */
    data class Dynamic(val handlerOffset: Int?, val byCatchAll: Boolean) : ThrowResolution()
}

/** A throw site in a function: its byte offset, static tag (null = throw_ref), resolution. */
data class ThrowSite(val offset: Int, val tagIdx: Int?, val resolution: ThrowResolution)

/**
 * Intra-procedural exception-flow analysis over one function's opcode tree.
 * Tags are compared by index in the module's tag index space (imported tags
 * first); a `catch_all` matches any tag, a matching `catch $t` catches tag t.
 */
object ExceptionFlow {

    /**
     * Resolves every `throw` / `throw_ref` in [opcodes], threading the stack
     * of enclosing `try_table` catch-clause sets as it descends. The innermost
     * enclosing try_table with a matching clause is the handler.
     */
    fun analyze(opcodes: List<OpCode>): List<ThrowSite> {
        val sites = ArrayList<ThrowSite>()

        // Each frame is one enclosing try_table: its byte offset, the set of
        // tag indices it catches, and whether it has a catch_all(_ref).
        data class Frame(val offset: Int, val caughtTags: Set<Int>, val hasCatchAll: Boolean)

        fun resolveStatic(tagIdx: Int, stack: List<Frame>): ThrowResolution {
            // Innermost enclosing handler wins.
            for (frame in stack.asReversed()) {
                if (tagIdx in frame.caughtTags) return ThrowResolution.CaughtHere(frame.offset, byCatchAll = false)
                if (frame.hasCatchAll) return ThrowResolution.CaughtHere(frame.offset, byCatchAll = true)
            }
            return ThrowResolution.Escapes
        }

        fun resolveDynamic(stack: List<Frame>): ThrowResolution {
            // throw_ref: only a catch_all(_ref) can be proven to catch it here.
            for (frame in stack.asReversed()) {
                if (frame.hasCatchAll) return ThrowResolution.Dynamic(frame.offset, byCatchAll = true)
            }
            return ThrowResolution.Dynamic(null, byCatchAll = false)
        }

        fun walk(ops: List<OpCode>, stack: List<Frame>) {
            for (op in ops) {
                when (op) {
                    is OpCodes.Flow.Trow -> {
                        val tag = op.value.toInt()
                        sites.add(ThrowSite(op.offset, tag, resolveStatic(tag, stack)))
                    }
                    is OpCodes.Flow.ThrowRef -> {
                        // ThrowRef is an offset-less singleton opcode, so its
                        // site offset is unknown (-1) — the E1 WAT popup keys
                        // off the row's offset and thus attaches to static
                        // `throw` rows only; throw_ref is the dynamic case.
                        sites.add(ThrowSite(offset = -1, tagIdx = null, resolution = resolveDynamic(stack)))
                    }
                    is OpCodes.Flow.TryTable -> {
                        val caught = HashSet<Int>()
                        var catchAll = false
                        for (c in op.catches) {
                            when (c) {
                                is OpCodes.Flow.CatchType.Catch -> caught.add(c.tagIdx.toInt())
                                is OpCodes.Flow.CatchType.CatchRef -> caught.add(c.tagIdx.toInt())
                                is OpCodes.Flow.CatchType.CatchAll -> catchAll = true
                                is OpCodes.Flow.CatchType.CatchAllRef -> catchAll = true
                            }
                        }
                        walk(op.code, stack + Frame(op.offset, caught, catchAll))
                    }
                    is OpCodes.Flow.Block -> walk(op.value, stack)
                    else -> {}
                }
            }
        }
        walk(opcodes, emptyList())
        return sites
    }

    /**
     * The exception behaviour of one function, all E2's call-graph
     * propagation needs from a single function body:
     *  - [localTags]/[localDynamic]: exceptions thrown directly and not caught
     *    locally (the E1 escape set);
     *  - [calls]: each direct call site with the catch coverage of the
     *    try_tables enclosing it, so propagation knows which of a callee's
     *    escaping tags this function re-catches vs lets through;
     *  - [hasUnguardedIndirectOrImportRisk] is set when a `call_indirect` /
     *    `return_call_indirect` sits under no `catch_all` — the callee is
     *    unknown, so it may throw an unknown exception that escapes here.
     */
    fun functionEffect(opcodes: List<OpCode>): FunctionEffect {
        val escape = escapeSet(opcodes)
        val calls = ArrayList<GuardedCall>()
        var indirectRisk = false

        data class Frame(val caughtTags: Set<Int>, val hasCatchAll: Boolean)

        fun walk(ops: List<OpCode>, stack: List<Frame>) {
            // Coverage visible at this depth: union of caught tags + any catch_all.
            val coveredTags = HashSet<Int>()
            var catchAll = false
            for (f in stack) {
                coveredTags.addAll(f.caughtTags)
                if (f.hasCatchAll) catchAll = true
            }
            for (op in ops) {
                when (op) {
                    is OpCodes.Flow.Call -> calls.add(GuardedCall(op.value.toInt(), coveredTags.toSet(), catchAll))
                    is OpCodes.Flow.ReturnCall -> calls.add(GuardedCall(op.value.toInt(), coveredTags.toSet(), catchAll))
                    is OpCodes.Flow.CallIndirect -> if (!catchAll) indirectRisk = true
                    is OpCodes.Flow.ReturnCallIndirect -> if (!catchAll) indirectRisk = true
                    is OpCodes.Flow.TryTable -> {
                        val caught = HashSet<Int>()
                        var ca = false
                        for (c in op.catches) {
                            when (c) {
                                is OpCodes.Flow.CatchType.Catch -> caught.add(c.tagIdx.toInt())
                                is OpCodes.Flow.CatchType.CatchRef -> caught.add(c.tagIdx.toInt())
                                is OpCodes.Flow.CatchType.CatchAll -> ca = true
                                is OpCodes.Flow.CatchType.CatchAllRef -> ca = true
                            }
                        }
                        walk(op.code, stack + Frame(caught, ca))
                    }
                    is OpCodes.Flow.Block -> walk(op.value, stack)
                    else -> {}
                }
            }
        }
        walk(opcodes, emptyList())
        return FunctionEffect(escape.tags, escape.dynamicThrow, calls, indirectRisk)
    }

    /**
     * The function's escape set: the static tags it can throw without catching
     * locally, and whether an uncaught `throw_ref` (dynamic) can escape. This
     * is the per-function input the E2 call-graph propagation consumes.
     */
    fun escapeSet(opcodes: List<OpCode>): EscapeSet {
        val tags = HashSet<Int>()
        var dynamic = false
        for (site in analyze(opcodes)) {
            when (val r = site.resolution) {
                is ThrowResolution.Escapes -> site.tagIdx?.let { tags.add(it) }
                is ThrowResolution.Dynamic -> if (r.handlerOffset == null) dynamic = true
                is ThrowResolution.CaughtHere -> {}
            }
        }
        return EscapeSet(tags, dynamic)
    }
}

/** Tags a function throws uncaught locally, plus whether a dynamic throw_ref escapes. */
data class EscapeSet(val tags: Set<Int>, val dynamicThrow: Boolean)

/** A direct call site and the catch coverage of the try_tables enclosing it. */
data class GuardedCall(val calleeFuncIdx: Int, val caughtTags: Set<Int>, val underCatchAll: Boolean)

/** One function's exception behaviour, the per-function input to E2 propagation. */
data class FunctionEffect(
    val localTags: Set<Int>,
    val localDynamic: Boolean,
    val calls: List<GuardedCall>,
    val hasUnguardedIndirectOrImportRisk: Boolean,
)

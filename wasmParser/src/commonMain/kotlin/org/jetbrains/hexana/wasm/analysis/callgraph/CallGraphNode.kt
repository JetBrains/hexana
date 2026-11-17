package org.jetbrains.hexana.wasm.analysis.callgraph

sealed class CallGraphNode(val id: Int, val name: String) {
    class VirtualRoot : CallGraphNode(VIRTUAL_ROOT_ID, "<root>")
    class ImportedFunction(id: Int, name: String, val module: String) : CallGraphNode(id, name)
    class DefinedFunction(id: Int, name: String, val funcIndex: Int) : CallGraphNode(id, name)
    class ComponentFunction(id: Int, name: String, val componentPath: String) : CallGraphNode(id, name)

    /**
     * Synthetic hub for indirect calls, one per canonical signature: every
     * function containing a call_indirect / return_call_indirect of that
     * signature gets one edge to its hub, which fans out to the table
     * functions matching the signature. Keeps the edge count linear
     * (indirect call sites + table size, partitioned by signature) instead
     * of multiplicative (indirect callers × table size), which exploded to
     * hundreds of millions of edges on large C++/Emscripten modules.
     *
     * A hub with no successors is a real finding, not an artifact: a
     * `call_indirect` whose signature has no table entries can only trap.
     *
     * Hub ids descend from [INDIRECT_CALL_HUB_ID_BASE] (-2, -3, …) in
     * signature discovery order. Function ids are never negative, so the
     * ranges cannot collide.
     */
    class IndirectCallHub(id: Int, val signatureText: String) :
        CallGraphNode(id, "<indirect: $signatureText>")

    companion object {
        const val VIRTUAL_ROOT_ID = -1
        const val INDIRECT_CALL_HUB_ID_BASE = -2
    }
}

package org.jetbrains.hexana.wasm.analysis.callgraph

sealed class CallGraphNode(val id: Int, val name: String) {
    class VirtualRoot : CallGraphNode(VIRTUAL_ROOT_ID, "<root>")
    class ImportedFunction(id: Int, name: String, val module: String) : CallGraphNode(id, name)
    class DefinedFunction(id: Int, name: String, val funcIndex: Int) : CallGraphNode(id, name)
    class ComponentFunction(id: Int, name: String, val componentPath: String) : CallGraphNode(id, name)

    /**
     * Synthetic hub for indirect calls: every function containing call_indirect /
     * return_call_indirect gets one edge to this node, which fans out to every
     * element-table function. Keeps the edge count linear (indirect callers + table
     * size) instead of multiplicative (indirect callers × table size), which
     * exploded to hundreds of millions of edges on large C++/Emscripten modules.
     */
    class IndirectCallHub : CallGraphNode(INDIRECT_CALL_HUB_ID, "<indirect calls>")

    companion object {
        const val VIRTUAL_ROOT_ID = -1
        const val INDIRECT_CALL_HUB_ID = -2
    }
}
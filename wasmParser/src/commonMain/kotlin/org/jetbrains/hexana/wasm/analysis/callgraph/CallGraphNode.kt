package org.jetbrains.hexana.wasm.analysis.callgraph

sealed class CallGraphNode(val id: Int, val name: String) {
    class VirtualRoot : CallGraphNode(VIRTUAL_ROOT_ID, "<root>")
    class ImportedFunction(id: Int, name: String, val module: String) : CallGraphNode(id, name)
    class DefinedFunction(id: Int, name: String, val funcIndex: Int) : CallGraphNode(id, name)
    class ComponentFunction(id: Int, name: String, val componentPath: String) : CallGraphNode(id, name)

    companion object {
        const val VIRTUAL_ROOT_ID = -1
    }
}
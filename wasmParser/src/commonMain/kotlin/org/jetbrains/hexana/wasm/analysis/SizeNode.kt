package org.jetbrains.hexana.wasm.analysis

sealed class SizeNode(
    name: String,
    size: Int,
    offset: Int,
    children: List<BinarySizeNode> = emptyList()
) : BinarySizeNode(name, size, offset, children) {

    class Module(name: String, size: Int, offset: Int, children: List<BinarySizeNode>)
        : SizeNode(name, size, offset, children)

    class Section(name: String, size: Int, offset: Int, val sectionId: Int?, children: List<SizeNode>)
        : SizeNode(name, size, offset, children)

    class Function(name: String, size: Int, offset: Int, val funcIndex: Int, children: List<SizeNode> = emptyList())
        : SizeNode(name, size, offset, children)

    class DataSegment(name: String, size: Int, offset: Int, val segmentIndex: Int, children: List<SizeNode> = emptyList())
        : SizeNode(name, size, offset, children)

    class EmbeddedModule(name: String, size: Int, offset: Int, children: List<SizeNode>)
        : SizeNode(name, size, offset, children)

    class EmbeddedComponent(name: String, size: Int, offset: Int, children: List<SizeNode>)
        : SizeNode(name, size, offset, children)

    class InstructionCategory(name: String, size: Int, offset: Int, val category: OpcodeCategory)
        : SizeNode(name, size, offset)

    class FunctionPart(name: String, size: Int, offset: Int, val kind: PartKind)
        : SizeNode(name, size, offset)

    class CallTarget(name: String, offset: Int, val targetIndex: Int)
        : SizeNode(name, 0, offset)

    class DataContent(name: String, size: Int, offset: Int, val kind: ContentKind)
        : SizeNode(name, size, offset)

    class CustomSection(name: String, size: Int, offset: Int)
        : SizeNode(name, size, offset)

    class Generic(name: String, size: Int, offset: Int)
        : SizeNode(name, size, offset)
}
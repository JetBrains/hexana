package org.jetbrains.hexana.wasm.analysis

sealed class SizeNode(
    val name: String,
    val size: Int,
    val offset: Int,
    val children: List<SizeNode> = emptyList()
) {
    fun percentage(parentSize: Int): Double =
        if (parentSize == 0) 0.0 else (size.toDouble() / parentSize) * 100.0

    class Module(name: String, size: Int, offset: Int, children: List<SizeNode>)
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
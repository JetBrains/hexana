package org.jetbrains.hexana.wasm.analysis

import org.jetbrains.hexana.io.CommonByteBuffer

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

    /**
     * An embedded core module. [slice] is its self-contained byte view (begins with `\0asm`),
     * carried so the size panel can build the module's breakdown LAZILY on expand rather than
     * eagerly recursing every embedded module up front (which is prohibitively heavy for a
     * composed component embedding multi-MB modules).
     */
    class EmbeddedModule(
        name: String, size: Int, offset: Int,
        children: List<SizeNode> = emptyList(),
        val slice: CommonByteBuffer? = null,
    ) : SizeNode(name, size, offset, children)

    /** A nested component. [slice] is its self-contained byte view, for lazy expansion (see [EmbeddedModule]). */
    class EmbeddedComponent(
        name: String, size: Int, offset: Int,
        children: List<SizeNode> = emptyList(),
        val slice: CommonByteBuffer? = null,
    ) : SizeNode(name, size, offset, children)

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
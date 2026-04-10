package org.jetbrains.hexana.wasm.analysis.cfg

data class BasicBlock(
    val id: Int,
    val startIndex: Int,
    val endIndex: Int,
    val kind: BlockKind
)

enum class BlockKind { Entry, Normal, LoopHeader, IfBranch, ElseBranch, Exit }
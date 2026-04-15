package org.jetbrains.hexana.wasm.analysis.cfg

import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraphBuilder
import org.jetbrains.hexana.wasm.analysis.graph.DominatorTree
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.OpCodeGCBreak
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord

/**
 * Builds an intra-function control flow graph from WASM structured control flow.
 *
 * The WASM opcode tree is walked recursively. Structured control instructions
 * (block, loop, if/else) create new basic blocks connected by edges.
 * After CFG construction, dominators are computed via [DominatorTree].
 */
object CfgBuilder {

    fun build(record: WasmCodeRecord, funcIndex: Int): CfgResult {
        val ctx = BuildContext()

        val entryBlock = ctx.newBlock(BlockKind.Entry, opcodeIndex = 0)
        val exitBlock = ctx.newBlock(BlockKind.Exit, opcodeIndex = -1)
        ctx.exitBlockId = exitBlock.id

        // The function body itself acts as an implicit block.
        // br 0 at the top level targets the implicit function-level label,
        // which means "return" (jump to after the function body = exit).
        val labelStack = mutableListOf(LabelEntry(isLoop = false, brTargetId = exitBlock.id))

        val lastBlockId = ctx.processOpcodeList(
            record.opCodes, entryBlock.id, opcodeOffset = 0, labelStack
        )

        // If the last block in the function body has not been terminated,
        // connect it to the exit block (implicit fall-through at end of function).
        if (lastBlockId != TERMINATED && lastBlockId != exitBlock.id) {
            ctx.addEdge(lastBlockId, exitBlock.id)
        }

        ctx.finalizeBlocks()

        val graphBuilder = DirectedGraphBuilder()
        for (block in ctx.blocks) {
            graphBuilder.addNode(block.id, "bb${block.id}")
        }
        for ((from, to) in ctx.edges) {
            graphBuilder.addEdge(from, to)
        }
        val graph = graphBuilder.build(root = entryBlock.id)
        val dominators = DominatorTree.compute(graph)

        return CfgResult(funcIndex, ctx.blocks.toList(), graph, dominators)
    }

    /** Sentinel: the current path has been terminated (br, return, unreachable). */
    private const val TERMINATED = -1

    private data class LabelEntry(val isLoop: Boolean, val brTargetId: Int)

    private class BuildContext {
        private var nextId = 0
        private val mutableBlocks = mutableListOf<MutableBlock>()
        private val blockById = mutableMapOf<Int, MutableBlock>()
        val edges = mutableListOf<Pair<Int, Int>>()
        var exitBlockId: Int = -1

        val blocks: List<BasicBlock> get() = mutableBlocks.map { it.toBasicBlock() }

        fun newBlock(kind: BlockKind, opcodeIndex: Int): BasicBlock {
            val id = nextId++
            val mb = MutableBlock(id, opcodeIndex, opcodeIndex, kind)
            mutableBlocks.add(mb)
            blockById[id] = mb
            return mb.toBasicBlock()
        }

        fun newMutableBlock(kind: BlockKind, opcodeIndex: Int): MutableBlock {
            val id = nextId++
            val mb = MutableBlock(id, opcodeIndex, opcodeIndex, kind)
            mutableBlocks.add(mb)
            blockById[id] = mb
            return mb
        }

        fun addEdge(from: Int, to: Int) {
            edges.add(from to to)
        }

        fun updateBlockEnd(blockId: Int, endIndex: Int) {
            blockById[blockId]?.endIndex = endIndex
        }

        fun finalizeBlocks() {
            // no-op; blocks are already tracked
        }

        /**
         * Process a list of opcodes (a body of a block/loop/if/else or the function top-level).
         *
         * @param opcodes the opcode list to process
         * @param currentBlockId the block that is "current" when we start processing
         * @param opcodeOffset the global opcode index offset for the first opcode in this list
         * @param labelStack the current label stack (innermost last)
         * @return the block ID that is current after processing, or [TERMINATED]
         */
        fun processOpcodeList(
            opcodes: List<OpCode>,
            currentBlockId: Int,
            opcodeOffset: Int,
            labelStack: MutableList<LabelEntry>
        ): Int {
            var curBlockId = currentBlockId
            var idx = opcodeOffset

            for ((i, opcode) in opcodes.withIndex()) {
                if (curBlockId == TERMINATED) {
                    // Dead code after an unconditional terminator. We could create
                    // unreachable blocks, but for simplicity we skip them.
                    idx++
                    continue
                }

                when (opcode) {
                    is OpCodes.Flow.Loop -> {
                        // Loop: creates a loop header block. br targeting this label
                        // jumps back to the loop header.
                        val loopHeader = newMutableBlock(BlockKind.LoopHeader, idx)
                        addEdge(curBlockId, loopHeader.id)
                        updateBlockEnd(curBlockId, idx)

                        // Merge block after the loop (where we go when we fall through
                        // the end of the loop body or br targets the enclosing block).
                        val mergeBlock = newMutableBlock(BlockKind.Normal, idx)

                        // Push label: for loop, br target is the loop header itself
                        labelStack.add(LabelEntry(isLoop = true, brTargetId = loopHeader.id))

                        val lastInLoop = processOpcodeList(
                            opcode.value, loopHeader.id, idx, labelStack
                        )
                        labelStack.removeLast()

                        // Fall-through from end of loop body goes to merge
                        if (lastInLoop != TERMINATED) {
                            addEdge(lastInLoop, mergeBlock.id)
                        }

                        mergeBlock.startIndex = idx + 1
                        curBlockId = mergeBlock.id
                    }

                    is OpCodes.Flow.If -> {
                        // If: the current block conditionally branches.
                        updateBlockEnd(curBlockId, idx)

                        // Find if there's an Else in the If's body
                        val elseIndex = opcode.value.indexOfFirst { it is OpCodes.Flow.Else }
                        val hasElse = elseIndex >= 0
                        val thenOpcodes = if (hasElse) opcode.value.subList(0, elseIndex) else opcode.value
                        val elseOpcodes = if (hasElse) {
                            (opcode.value[elseIndex] as OpCodes.Flow.Else).value
                        } else {
                            emptyList()
                        }

                        val thenBlock = newMutableBlock(BlockKind.IfBranch, idx)
                        val mergeBlock = newMutableBlock(BlockKind.Normal, idx)

                        // Push label: br from inside if targets the merge block (like block)
                        labelStack.add(LabelEntry(isLoop = false, brTargetId = mergeBlock.id))

                        // Edge: condition true -> then block
                        addEdge(curBlockId, thenBlock.id)

                        val lastInThen = processOpcodeList(thenOpcodes, thenBlock.id, idx, labelStack)
                        if (lastInThen != TERMINATED) {
                            addEdge(lastInThen, mergeBlock.id)
                        }

                        if (hasElse) {
                            val elseBlock = newMutableBlock(BlockKind.ElseBranch, idx)
                            addEdge(curBlockId, elseBlock.id)

                            val lastInElse = processOpcodeList(elseOpcodes, elseBlock.id, idx, labelStack)
                            if (lastInElse != TERMINATED) {
                                addEdge(lastInElse, mergeBlock.id)
                            }
                        } else {
                            // No else: condition false falls through to merge
                            addEdge(curBlockId, mergeBlock.id)
                        }

                        labelStack.removeLast()

                        mergeBlock.startIndex = idx + 1
                        curBlockId = mergeBlock.id
                    }

                    is OpCodes.Flow.Block -> {
                        // Generic block (not Loop or If, since those extend Block but are matched above).
                        // br targeting this label jumps to the merge block after.
                        updateBlockEnd(curBlockId, idx)

                        val bodyBlock = newMutableBlock(BlockKind.Normal, idx)
                        val mergeBlock = newMutableBlock(BlockKind.Normal, idx)

                        addEdge(curBlockId, bodyBlock.id)

                        labelStack.add(LabelEntry(isLoop = false, brTargetId = mergeBlock.id))
                        val lastInBody = processOpcodeList(opcode.value, bodyBlock.id, idx, labelStack)
                        labelStack.removeLast()

                        if (lastInBody != TERMINATED) {
                            addEdge(lastInBody, mergeBlock.id)
                        }

                        mergeBlock.startIndex = idx + 1
                        curBlockId = mergeBlock.id
                    }

                    is OpCodes.Flow.Br -> {
                        val depth = opcode.value.toInt()
                        val targetLabel = labelStack[labelStack.size - 1 - depth]
                        updateBlockEnd(curBlockId, idx + 1)
                        addEdge(curBlockId, targetLabel.brTargetId)
                        curBlockId = TERMINATED
                    }

                    is OpCodes.Flow.BrIf -> {
                        val depth = opcode.value.toInt()
                        val targetLabel = labelStack[labelStack.size - 1 - depth]
                        updateBlockEnd(curBlockId, idx + 1)
                        addEdge(curBlockId, targetLabel.brTargetId)

                        // Fall-through: a new block for the case when condition is false
                        val fallThrough = newMutableBlock(BlockKind.Normal, idx + 1)
                        addEdge(curBlockId, fallThrough.id)
                        curBlockId = fallThrough.id
                    }

                    is OpCodes.Flow.BrTable -> {
                        updateBlockEnd(curBlockId, idx + 1)
                        val targets = opcode.value
                        val seen = mutableSetOf<Int>()
                        for (depth in targets) {
                            if (depth < 0 || depth >= labelStack.size) continue
                            val targetLabel = labelStack[labelStack.size - 1 - depth]
                            if (seen.add(targetLabel.brTargetId)) {
                                addEdge(curBlockId, targetLabel.brTargetId)
                            }
                        }
                        curBlockId = TERMINATED
                    }

                    is OpCodes.Flow.Return -> {
                        updateBlockEnd(curBlockId, idx + 1)
                        addEdge(curBlockId, exitBlockId)
                        curBlockId = TERMINATED
                    }

                    is OpCodes.ParametricInstructions.Unreachable -> {
                        updateBlockEnd(curBlockId, idx + 1)
                        curBlockId = TERMINATED
                    }

                    is OpCodes.Flow.Trow, is OpCodes.Flow.ThrowRef -> {
                        updateBlockEnd(curBlockId, idx + 1)
                        curBlockId = TERMINATED
                    }

                    is OpCodeGCBreak -> {
                        // BrOnCastFail - conditional branch similar to BrIf
                        val depth = opcode.label
                        if (depth >= 0 && depth < labelStack.size) {
                            val targetLabel = labelStack[labelStack.size - 1 - depth]
                            updateBlockEnd(curBlockId, idx + 1)
                            addEdge(curBlockId, targetLabel.brTargetId)

                            val fallThrough = newMutableBlock(BlockKind.Normal, idx + 1)
                            addEdge(curBlockId, fallThrough.id)
                            curBlockId = fallThrough.id
                        } else {
                            updateBlockEnd(curBlockId, idx + 1)
                        }
                    }

                    is OpCodes.Flow.BrOnNull, is OpCodes.Flow.BrOnNonNull -> {
                        // Conditional branches
                        val depth = when (opcode) {
                            is OpCodes.Flow.BrOnNull -> opcode.value.toInt()
                            is OpCodes.Flow.BrOnNonNull -> opcode.value.toInt()
                            else -> error("unreachable")
                        }
                        if (depth >= 0 && depth < labelStack.size) {
                            val targetLabel = labelStack[labelStack.size - 1 - depth]
                            updateBlockEnd(curBlockId, idx + 1)
                            addEdge(curBlockId, targetLabel.brTargetId)

                            val fallThrough = newMutableBlock(BlockKind.Normal, idx + 1)
                            addEdge(curBlockId, fallThrough.id)
                            curBlockId = fallThrough.id
                        } else {
                            updateBlockEnd(curBlockId, idx + 1)
                        }
                    }

                    else -> {
                        // Normal instruction: extend current block
                        updateBlockEnd(curBlockId, idx + 1)
                    }
                }
                idx++
            }

            return curBlockId
        }
    }

    private class MutableBlock(
        val id: Int,
        var startIndex: Int,
        var endIndex: Int,
        val kind: BlockKind
    ) {
        fun toBasicBlock() = BasicBlock(id, startIndex, endIndex, kind)
    }
}
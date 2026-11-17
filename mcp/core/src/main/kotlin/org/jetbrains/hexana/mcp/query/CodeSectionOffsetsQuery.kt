package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.functions.CodeSectionOffsetResult
import org.jetbrains.hexana.mcp.model.functions.ListCodeSectionOffsetsResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

fun WasmBinaryProvider.readCodeSectionOffsets(): ListCodeSectionOffsetsResult {
    return ListCodeSectionOffsetsResult(
        functions = codeOffsets.mapIndexed { codeIndex, entry ->
            CodeSectionOffsetResult(
                funcidx = importFuncCount + codeIndex,
                offset = entry.offset.toLong(),
                size = entry.size.toLong()
            )
        }
    )
}

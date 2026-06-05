/**
 * Hand-written helpers consumed by the generated `HexanaToolset.kt`.
 *
 * This file is NOT regenerated. The `:idea-plugin:generateHexanaToolset` task only writes
 * `src/main/gen/.../HexanaToolset.kt`; if you delete or rename anything here, update the
 * call sites emitted by `mcp/tools-generator/.../IdeaToolsetMain.kt` (see the `MemberName`
 * constants at the bottom of that file) in the same change.
 */
package org.jetbrains.hexana.mcp

import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector

/**
 * Shared helpers consumed by [HexanaToolset]. Kept hand-written because
 * they wrap IntelliJ VirtualFile resolution and `mcpFail` — behaviour, not shape.
 */

internal fun VirtualFile.requireCoreWasmBinaryProvider(): WasmBinaryProvider {
    val buffer = contentsToByteArray().toCommonByteBuffer()
    if (WasmBinaryProviderSelector.select(buffer) !is BinaryProviderType.Wasm) {
        mcpFail("Failed to parse .wasm file")
    }
    return WasmBinaryProvider(buffer).takeIf(WasmBinaryProvider::canProvideWasmModule)
        ?: mcpFail("Failed to parse .wasm file")
}

internal fun requireWasmFile(path: String): VirtualFile {
    val file = resolveWasmVirtualFile(path) ?: mcpFail("File not found")
    if (file.fileType != WasmFileType.INSTANCE) {
        mcpFail("Not a .wasm file")
    }
    return file
}

internal fun requireTextFile(path: String): String {
    val file = resolveProjectVirtualFile(path) ?: mcpFail("File not found")
    if (!file.isValid || file.isDirectory) {
        mcpFail("File not found")
    }
    return file.contentsToByteArray().toString(Charsets.UTF_8)
}

internal fun requireClosedRange(entriesRange: Map<String, Int>, fieldName: String): IntRange {
    val from = entriesRange["from"] ?: mcpFail("Invalid $fieldName: missing `from`")
    val to = entriesRange["to"] ?: mcpFail("Invalid $fieldName: missing `to`")
    if (to < from) {
        mcpFail("Invalid $fieldName: `to` must be >= `from`")
    }
    return from..to
}

internal fun requireHalfOpenSegmentRange(segmentRange: Map<String, Int>): IntRange {
    val from = segmentRange["from"] ?: mcpFail("Invalid segmentRange no `from`")
    val toExclusive = segmentRange["to"] ?: Int.MAX_VALUE
    if (from > toExclusive) {
        mcpFail("Invalid segmentRange `to` must be >= `from`")
    }
    return from until toExclusive
}

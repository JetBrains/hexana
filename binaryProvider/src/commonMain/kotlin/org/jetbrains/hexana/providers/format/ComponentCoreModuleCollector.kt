package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.containsRange
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.components.WasmComponentModuleSection
import org.jetbrains.hexana.wasm.components.WasmComponentSection
import org.jetbrains.hexana.wasm.sections.WasmSection

/**
 * A core module embedded somewhere in a component, recovered as a self-contained byte slice.
 *
 * [bytes] is a (zero-copy) slice that begins with the `\0asm` preamble and parses as a core
 * `WasmModule` — ready to hand to a [org.jetbrains.hexana.providers.WasmBinaryProvider].
 * [path] is a human label like `module[0]` or `component[1]/module[0]` showing where it sits.
 */
class EmbeddedCoreModule(val bytes: CommonByteBuffer, val path: String, val size: Int)

/**
 * Collects **all** core modules embedded in a Component Model binary, recursively descending into
 * nested components.
 *
 * Necessary because the renderable code in a composed component (e.g. `wac`/`wasm-tools compose`
 * output) lives in core modules nested *inside* sub-components, not as direct children — so
 * `WasmComponentBinaryProvider.modules` alone returns nothing. This walks the raw section list
 * (`wasmModule.sections`), which exposes every `component` (id 4) and `module` (id 1) section,
 * sidestepping the parser's single-`componentSection` limitation that would miss all but one
 * nested component per level.
 *
 * Pure Kotlin (compiles on every KMP target). Recursion parses each nested component's *section
 * table* only — core function bodies stay unparsed until a returned slice is opened.
 */
object ComponentCoreModuleCollector {

    private const val MAX_DEPTH = 8
    const val DEFAULT_MAX_MODULES = 256

    fun collect(provider: WasmComponentBinaryProvider, maxModules: Int = DEFAULT_MAX_MODULES): List<EmbeddedCoreModule> {
        val out = ArrayList<EmbeddedCoreModule>()
        val sections = provider.wasmModule?.sections ?: return out
        collectSections(provider.bytes, sections, prefix = "", depth = 0, out = out, maxModules = maxModules)
        return out
    }

    private fun collectFrom(
        buffer: CommonByteBuffer,
        prefix: String,
        depth: Int,
        out: MutableList<EmbeddedCoreModule>,
        maxModules: Int,
    ) {
        if (depth > MAX_DEPTH || out.size >= maxModules) return
        // Re-parse the nested component's section table (not its core bodies) to descend further.
        val sections = WasmComponentBinaryProvider(buffer).wasmModule?.sections ?: return
        collectSections(buffer, sections, prefix, depth, out, maxModules)
    }

    private fun collectSections(
        buffer: CommonByteBuffer,
        sections: List<WasmSection>,
        prefix: String,
        depth: Int,
        out: MutableList<EmbeddedCoreModule>,
        maxModules: Int,
    ) {
        var moduleIdx = 0
        var componentIdx = 0
        for (section in sections) {
            if (out.size >= maxModules) return
            when (section) {
                is WasmComponentModuleSection -> {
                    val index = moduleIdx++
                    if (!buffer.containsRange(section.contentOffset, section.contentSize)) continue
                    out.add(
                        EmbeddedCoreModule(
                            bytes = buffer.slice(section.contentOffset, section.contentSize),
                            path = "${prefix}module[$index]",
                            size = section.contentSize,
                        ),
                    )
                }
                is WasmComponentSection -> {
                    val index = componentIdx++
                    if (!buffer.containsRange(section.contentOffset, section.contentSize)) continue
                    collectFrom(
                        buffer = buffer.slice(section.contentOffset, section.contentSize),
                        prefix = "${prefix}component[$index]/",
                        depth = depth + 1,
                        out = out,
                        maxModules = maxModules,
                    )
                }
                else -> { /* not a module or component section */ }
            }
        }
    }
}

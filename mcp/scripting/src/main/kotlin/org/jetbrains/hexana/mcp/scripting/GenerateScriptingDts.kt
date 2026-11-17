package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.shared.scripting.ScriptApiReference
import java.io.File

/**
 * Standalone entry point that generates the two bundled scripting references and
 * writes them to one or more output directories supplied as command-line
 * arguments.
 *
 * - `hexana-scripting.d.ts` — the curated API, rendered from
 *   [ScriptApiReference]. This is what the Add Script Tab dialogs link to.
 * - `hexana-scripting-raw.d.ts` — the reflective dump of the format-specific
 *   provider types behind `binary.raw`, from [BinaryProviderApiSummary].
 *
 * Usage: `java … GenerateScriptingDtsKt <outputDir1> [<outputDir2> …]`
 *
 * Both plugins bundle the result, so this runs from a module that needs only a
 * plain JVM toolchain — packaging the VS Code extension must not drag in the
 * IntelliJ platform build.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "At least one output directory must be specified." }
    val files = mapOf(
        BinaryProviderApiSummary.CURATED_DTS_NAME to ScriptApiReference.dts(),
        BinaryProviderApiSummary.RAW_DTS_NAME to BinaryProviderApiSummary.generateRawDts(),
    )
    for (dir in args) {
        val outDir = File(dir)
        outDir.mkdirs()
        for ((name, content) in files) {
            val outFile = File(outDir, name)
            outFile.writeText(content)
            println("Wrote ${outFile.absolutePath} (${content.length} chars)")
        }
    }
}

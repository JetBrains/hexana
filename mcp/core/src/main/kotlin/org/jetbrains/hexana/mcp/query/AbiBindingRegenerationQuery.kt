package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.model.artifacts.AbiBindingMismatchResult
import org.jetbrains.hexana.mcp.model.artifacts.AbiBindingModuleIdentity
import org.jetbrains.hexana.mcp.model.artifacts.AbiBindingSignature
import org.jetbrains.hexana.mcp.model.artifacts.AbiReplacementBindingsResult
import org.jetbrains.hexana.mcp.model.artifacts.PlanAbiBindingRegenerationResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

fun WasmBinaryProvider.planAbiBindingRegeneration(
    bindingText: String,
    pathLabel: String? = null,
    bindingPathLabel: String? = null
): PlanAbiBindingRegenerationResult {
    val warnings = mutableListOf<String>()
    val currentImports = currentAbiBindingImports()
    val currentExports = currentAbiBindingExports()
    val binding = parseAbiBindingFile(bindingText, warnings)
    val replacementModule = binding.module ?: pathLabel.orEmpty()
    val replacement = AbiReplacementBindingsResult(
        module = replacementModule,
        imports = currentImports,
        exports = currentExports
    )
    return PlanAbiBindingRegenerationResult(
        module = AbiBindingModuleIdentity(
            path = pathLabel,
            bindingPath = bindingPathLabel,
            staleModule = binding.module,
            replacementModule = replacementModule
        ),
        imports = currentImports,
        exports = currentExports,
        staleImports = binding.imports,
        staleExports = binding.exports,
        bindingMismatches = abiBindingMismatches(
            currentImports = currentImports,
            currentExports = currentExports,
            staleImports = binding.imports,
            staleExports = binding.exports
        ),
        replacementBindings = replacement,
        warnings = warnings
    )
}

internal fun WasmBinaryProvider.planAbiBindingRegenerationOrCurrentOnly(
    bindingText: String?,
    pathLabel: String?,
    bindingPathLabel: String?,
    warnings: MutableList<String>
): PlanAbiBindingRegenerationResult {
    if (bindingText != null) {
        return planAbiBindingRegeneration(
            bindingText = bindingText,
            pathLabel = pathLabel,
            bindingPathLabel = bindingPathLabel
        ).also { warnings += it.warnings }
    }

    warnings += "`abi_binding_regeneration` can compare stale bindings when `bindingPath` points at a common Python binding file with MODULE, IMPORTS, and EXPORTS literals."
    val currentImports = currentAbiBindingImports()
    val currentExports = currentAbiBindingExports()
    val replacementModule = pathLabel.orEmpty()
    return PlanAbiBindingRegenerationResult(
        module = AbiBindingModuleIdentity(
            path = pathLabel,
            bindingPath = bindingPathLabel,
            replacementModule = replacementModule
        ),
        imports = currentImports,
        exports = currentExports,
        staleImports = emptyMap(),
        staleExports = emptyMap(),
        bindingMismatches = emptyList(),
        replacementBindings = AbiReplacementBindingsResult(
            module = replacementModule,
            imports = currentImports,
            exports = currentExports
        )
    )
}

private fun WasmBinaryProvider.currentAbiBindingImports(): Map<String, AbiBindingSignature> =
    listFunctionEntries()
        .filter { it.import != null }
        .associate { function ->
            val import = requireNotNull(function.import)
            "${import.module}.${import.name}" to function.signature.toAbiBindingSignature()
        }
        .toSortedMap()

private fun WasmBinaryProvider.currentAbiBindingExports(): Map<String, AbiBindingSignature> {
    val functionsByIndex = listFunctionEntries().associateBy { it.funcidx }
    return readExports().exports
        .filter { it.kind.toInt() == 0 }
        .mapNotNull { export ->
            val function = functionsByIndex[export.externIndex.toInt()] ?: return@mapNotNull null
            export.name to function.signature.toAbiBindingSignature()
        }
        .toMap()
        .toSortedMap()
}

private data class ParsedAbiBinding(
    val module: String?,
    val imports: Map<String, AbiBindingSignature>,
    val exports: Map<String, AbiBindingSignature>
)

private fun parseAbiBindingFile(text: String, warnings: MutableList<String>): ParsedAbiBinding {
    val module = Regex("""(?m)^\s*MODULE\s*=\s*(['"])(.*?)\1""").find(text)?.groupValues?.getOrNull(2)
    val imports = parseAbiBindingSignatureMap(text, "IMPORTS", warnings)
    val exports = parseAbiBindingSignatureMap(text, "EXPORTS", warnings)
    if (module == null) warnings += "`bindingPath` did not contain a parseable MODULE string."
    if (imports == null) warnings += "`bindingPath` did not contain a parseable IMPORTS signature map."
    if (exports == null) warnings += "`bindingPath` did not contain a parseable EXPORTS signature map."
    return ParsedAbiBinding(
        module = module,
        imports = imports.orEmpty().toSortedMap(),
        exports = exports.orEmpty().toSortedMap()
    )
}

private fun parseAbiBindingSignatureMap(
    text: String,
    variableName: String,
    warnings: MutableList<String>
): Map<String, AbiBindingSignature>? {
    val body = extractPythonDictLiteral(text, variableName) ?: return null
    val entryRegex = Regex(
        """(['"])(.*?)\1\s*:\s*\{\s*(['"])params\3\s*:\s*\[(.*?)\]\s*,\s*(['"])results\5\s*:\s*\[(.*?)\]\s*\}""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    val entries = entryRegex.findAll(body).associate { match ->
        val name = match.groupValues[2]
        val params = parsePythonStringList(match.groupValues[4])
        val results = parsePythonStringList(match.groupValues[6])
        name to AbiBindingSignature(params = params, results = results)
    }
    if (entries.isEmpty() && Regex("""\S""").containsMatchIn(body)) {
        warnings += "`$variableName` exists but no {'params': [...], 'results': [...]} entries were parsed."
    }
    return entries
}

private fun extractPythonDictLiteral(text: String, variableName: String): String? {
    val assignment = Regex("""(?m)^\s*$variableName\s*=""").find(text) ?: return null
    val start = text.indexOf('{', assignment.range.last + 1)
    if (start < 0) return null
    var depth = 0
    var quote: Char? = null
    var escaped = false
    for (index in start until text.length) {
        val ch = text[index]
        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == quote) {
                quote = null
            }
            continue
        }
        when (ch) {
            '\'', '"' -> quote = ch
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return text.substring(start + 1, index)
                }
            }
        }
    }
    return null
}

private fun parsePythonStringList(body: String): List<String> =
    Regex("""(['"])(.*?)\1""").findAll(body).map { it.groupValues[2] }.toList()

private fun abiBindingMismatches(
    currentImports: Map<String, AbiBindingSignature>,
    currentExports: Map<String, AbiBindingSignature>,
    staleImports: Map<String, AbiBindingSignature>,
    staleExports: Map<String, AbiBindingSignature>
): List<AbiBindingMismatchResult> {
    val exportMismatches = (currentExports.keys + staleExports.keys).sorted().mapNotNull { name ->
        val expected = currentExports[name]
        val actual = staleExports[name]
        if (expected == actual) null else AbiBindingMismatchResult("EXPORTS.$name", expected, actual)
    }
    val importMismatches = (currentImports.keys + staleImports.keys).sorted().mapNotNull { name ->
        val expected = currentImports[name]
        val actual = staleImports[name]
        if (expected == actual) null else AbiBindingMismatchResult("IMPORTS.$name", expected, actual)
    }
    return exportMismatches + importMismatches
}

private fun ParameterSignature?.toAbiBindingSignature(): AbiBindingSignature =
    AbiBindingSignature(
        params = this?.params.orEmpty(),
        results = this?.ret.orEmpty()
    )

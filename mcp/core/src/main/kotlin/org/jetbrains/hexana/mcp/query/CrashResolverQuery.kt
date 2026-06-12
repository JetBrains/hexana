package org.jetbrains.hexana.mcp.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.Import
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.model.functions.FunctionCodeRangeResult
import org.jetbrains.hexana.mcp.model.functions.FunctionImportResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSourceLocationResult
import org.jetbrains.hexana.mcp.model.functions.ResolveFunctionSymbolsResult
import org.jetbrains.hexana.mcp.model.functions.ResolveSourceLocationsResult
import org.jetbrains.hexana.mcp.model.functions.ResolveWasmFramesResult
import org.jetbrains.hexana.mcp.model.functions.ResolvedFunctionSymbolResult
import org.jetbrains.hexana.mcp.model.functions.ResolvedSourceLocationResult
import org.jetbrains.hexana.mcp.model.functions.ResolvedWasmFrameResult
import org.jetbrains.hexana.mcp.model.functions.SourceLocationInput
import org.jetbrains.hexana.mcp.model.functions.WasmFrameInput
import org.jetbrains.hexana.providers.HexanaSourceLocation
import org.jetbrains.hexana.providers.HexanaSourceLocationProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.WasmDwarfNameIndex
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val SYMBOL_NAME_SECTION = "name_section"
private const val SYMBOL_DWARF_NAME = "dwarf_name"
private const val SYMBOL_DWARF_LINKAGE_NAME = "dwarf_linkage_name"
private const val SYMBOL_EXPORT = "export"
private const val SYMBOL_FUNCIDX_FALLBACK = "funcidx_fallback"
private const val SYMBOL_IMPORT = "import"

fun WasmBinaryProvider.resolveWasmFrames(
    frames: List<WasmFrameInput>,
    sourceMapText: String? = null,
    sourceMapPath: String? = null,
): ResolveWasmFramesResult {
    val context = CrashResolverContext(this, sourceMapText, sourceMapPath)
    return ResolveWasmFramesResult(
        frames = frames.mapIndexed { index, frame ->
            val symbol = context.resolveFunction(frame.funcidx)
            val warnings = buildList {
                addAll(symbol.warnings)
                addAll(context.validateCodeOffset(frame.funcidx, frame.codeOffset))
            }
            val sourceLocation = context.resolveLocation(
                funcidx = frame.funcidx,
                codeOffset = frame.codeOffset,
                warnings = warnings,
                warnWhenMissing = false,
            )
            ResolvedWasmFrameResult(
                frameIndex = index,
                funcidx = frame.funcidx,
                inputCodeOffset = frame.codeOffset,
                module = frame.module,
                rawLabel = frame.label,
                origin = symbol.origin,
                symbol = symbol.symbol,
                symbolSource = symbol.symbolSource,
                signature = symbol.signature,
                importInfo = symbol.importInfo,
                exportedAs = symbol.exportedAs,
                codeRange = symbol.codeRange,
                sourceLocation = sourceLocation?.location,
                warnings = warnings + sourceLocation?.warnings.orEmpty(),
            )
        }
    )
}

fun WasmBinaryProvider.resolveFunctionSymbols(
    indices: List<Int>,
    sourceMapText: String? = null,
    sourceMapPath: String? = null,
): ResolveFunctionSymbolsResult {
    val context = CrashResolverContext(this, sourceMapText, sourceMapPath)
    return ResolveFunctionSymbolsResult(
        symbols = indices.map { funcidx ->
            val symbol = context.resolveFunction(funcidx)
            val sourceLocation = context.resolveLocation(
                funcidx = funcidx,
                codeOffset = null,
                warnings = emptyList(),
                warnWhenMissing = false,
            )
            ResolvedFunctionSymbolResult(
                funcidx = funcidx,
                origin = symbol.origin,
                symbol = symbol.symbol,
                symbolSource = symbol.symbolSource,
                typeidx = symbol.typeidx,
                signature = symbol.signature,
                importInfo = symbol.importInfo,
                exportedAs = symbol.exportedAs,
                codeRange = symbol.codeRange,
                sourceLocation = sourceLocation?.location,
                warnings = symbol.warnings + sourceLocation?.warnings.orEmpty(),
            )
        }
    )
}

fun WasmBinaryProvider.resolveSourceLocations(
    locations: List<SourceLocationInput>,
    sourceMapText: String? = null,
    sourceMapPath: String? = null,
): ResolveSourceLocationsResult {
    val context = CrashResolverContext(this, sourceMapText, sourceMapPath)
    return ResolveSourceLocationsResult(
        locations = locations.mapIndexed { index, input ->
            val warnings = buildList {
                if (input.funcidx == null && input.codeOffset == null) {
                    add("missing_funcidx_or_code_offset")
                }
                if (input.funcidx != null) {
                    addAll(context.resolveFunction(input.funcidx).warnings)
                }
                addAll(context.validateCodeOffset(input.funcidx, input.codeOffset))
            }
            val resolved = context.resolveLocation(
                funcidx = input.funcidx,
                codeOffset = input.codeOffset,
                warnings = warnings,
                warnWhenMissing = true,
            )
            ResolvedSourceLocationResult(
                queryIndex = index,
                funcidx = input.funcidx ?: input.codeOffset?.let(context::funcidxForCodeOffset),
                codeOffset = input.codeOffset ?: input.funcidx?.let(context::codeOffsetForFuncidx),
                codeRange = input.funcidx?.let(context::codeRangeForFuncidx)
                    ?: input.codeOffset?.let(context::funcidxForCodeOffset)?.let(context::codeRangeForFuncidx),
                sourceLocation = resolved?.location,
                source = resolved?.source,
                warnings = warnings + resolved?.warnings.orEmpty(),
            )
        }
    )
}

private class CrashResolverContext(
    private val provider: WasmBinaryProvider,
    sourceMapText: String?,
    sourceMapPath: String?,
) {
    private val entriesByIndex: Map<Int, GeneralFunctionEntry> by lazy {
        provider.listFunctionEntries().associateBy { it.funcidx }
    }
    private val nameSectionNames: Map<Int, String> by lazy {
        provider.nameSection?.functionSubsection?.namemap.orEmpty()
    }
    private val exportNamesByIndex: Map<Int, List<String>> by lazy {
        provider.exports
            .filterIsInstance<WasmFunctionExportRecordEntry>()
            .groupBy(
                keySelector = { it.index.toInt() },
                valueTransform = { it.name },
            )
    }
    private val dwarfIndex: WasmDwarfNameIndex by lazy { WasmDwarfNameIndex(provider) }
    private val sourceMap: SourceMapProviderParseResult by lazy {
        parseSourceMapProvider(sourceMapText, sourceMapPath)
    }

    fun resolveFunction(funcidx: Int): ResolvedSymbol {
        val entry = entriesByIndex[funcidx]
        if (entry == null) {
            return ResolvedSymbol(
                origin = "unknown",
                symbol = fallbackName(funcidx),
                symbolSource = SYMBOL_FUNCIDX_FALLBACK,
                warnings = listOf("invalid_funcidx"),
            )
        }

        val import = entry.import
        val symbol = when {
            import != null -> ResolvedName(import.name, SYMBOL_IMPORT)
            nameSectionNames[funcidx] != null -> ResolvedName(nameSectionNames.getValue(funcidx), SYMBOL_NAME_SECTION)
            dwarfIndex.nameFor(funcidx) != null -> ResolvedName(dwarfIndex.nameFor(funcidx)!!, SYMBOL_DWARF_NAME)
            dwarfIndex.linkageNameFor(funcidx) != null ->
                ResolvedName(dwarfIndex.linkageNameFor(funcidx)!!, SYMBOL_DWARF_LINKAGE_NAME)
            exportNamesByIndex[funcidx]?.firstOrNull() != null ->
                ResolvedName(exportNamesByIndex.getValue(funcidx).first(), SYMBOL_EXPORT)
            else -> ResolvedName(fallbackName(funcidx), SYMBOL_FUNCIDX_FALLBACK)
        }

        return ResolvedSymbol(
            origin = if (import != null) "import" else "local",
            symbol = symbol.name,
            symbolSource = symbol.source,
            typeidx = entry.typeidx,
            signature = entry.signature?.toFunctionSignatureResult(),
            importInfo = import?.toFunctionImportResult(),
            exportedAs = entry.exportedAs,
            codeRange = codeRangeForFuncidx(funcidx),
        )
    }

    fun validateCodeOffset(funcidx: Int?, codeOffset: Long?): List<String> {
        if (funcidx == null || codeOffset == null) return emptyList()
        val range = codeRangeForFuncidx(funcidx) ?: return emptyList()
        return if (codeOffset < range.offset || codeOffset >= range.offset + range.size) {
            listOf("code_offset_outside_function_range")
        } else {
            emptyList()
        }
    }

    fun resolveLocation(
        funcidx: Int?,
        codeOffset: Long?,
        warnings: List<String>,
        warnWhenMissing: Boolean,
    ): ResolvedLocation? {
        if ("missing_funcidx_or_code_offset" in warnings) {
            return null
        }

        val offset = codeOffset ?: funcidx?.let(::codeOffsetForFuncidx)
        if (offset == null) {
            val origin = funcidx?.let { entriesByIndex[it]?.import }
            return if (origin != null && warnWhenMissing) {
                ResolvedLocation(null, null, listOf("import_has_no_code_offset"))
            } else if (warnWhenMissing) {
                ResolvedLocation(null, null, listOf("code_offset_not_found"))
            } else {
                null
            }
        }
        if (offset !in 0..Int.MAX_VALUE.toLong()) {
            return ResolvedLocation(null, null, listOf("code_offset_out_of_int_range"))
        }

        sourceMap.provider?.getSourceLocationForOffset(offset.toInt())?.let { location ->
            return ResolvedLocation(location.toResult(), "source_map", sourceMap.warnings)
        }

        val dwarfLocation = provider.sourceLocationProvider.getSourceLocationForOffset(offset.toInt())
        if (dwarfLocation != null) {
            return ResolvedLocation(dwarfLocation.toResult(), "dwarf", sourceMap.warnings)
        }

        return if (warnWhenMissing || sourceMap.warnings.isNotEmpty()) {
            ResolvedLocation(null, null, sourceMap.warnings + if (warnWhenMissing) listOf("source_location_not_found") else emptyList())
        } else {
            null
        }
    }

    fun funcidxForCodeOffset(codeOffset: Long): Int? {
        val codeIndex = provider.getCodeRecordIndexAtByteOffset(codeOffset)
        return if (codeIndex >= 0) provider.importFuncCount + codeIndex else null
    }

    fun codeOffsetForFuncidx(funcidx: Int): Long? =
        codeRangeForFuncidx(funcidx)?.offset

    fun codeRangeForFuncidx(funcidx: Int): FunctionCodeRangeResult? {
        val localCodeIndex = funcidx - provider.importFuncCount
        val entry = provider.codeOffsets.getOrNull(localCodeIndex) ?: return null
        return FunctionCodeRangeResult(
            offset = entry.offset.toLong(),
            size = entry.size.toLong(),
        )
    }
}

private data class ResolvedName(
    val name: String,
    val source: String,
)

private data class ResolvedSymbol(
    val origin: String,
    val symbol: String,
    val symbolSource: String,
    val typeidx: Int? = null,
    val signature: FunctionSignatureResult? = null,
    val importInfo: FunctionImportResult? = null,
    val exportedAs: List<String> = emptyList(),
    val codeRange: FunctionCodeRangeResult? = null,
    val warnings: List<String> = emptyList(),
)

private data class ResolvedLocation(
    val location: FunctionSourceLocationResult?,
    val source: String?,
    val warnings: List<String> = emptyList(),
)

private fun ParameterSignature.toFunctionSignatureResult(): FunctionSignatureResult =
    FunctionSignatureResult(params = params, ret = ret)

private fun Import.toFunctionImportResult(): FunctionImportResult =
    FunctionImportResult(module = module, name = name)

private fun HexanaSourceLocation.toResult(): FunctionSourceLocationResult =
    FunctionSourceLocationResult(file = filePath, line = line, column = column)

private fun fallbackName(funcidx: Int): String = "func$funcidx"

private data class SourceMapProviderParseResult(
    val provider: HexanaSourceLocationProvider?,
    val warnings: List<String> = emptyList(),
)

private fun parseSourceMapProvider(sourceMapText: String?, sourceMapPath: String?): SourceMapProviderParseResult {
    if (sourceMapText == null) return SourceMapProviderParseResult(provider = null)
    return try {
        SourceMapProviderParseResult(
            provider = SimpleWasmSourceMap.parse(
                sourceMapPath = sourceMapPath?.let { Path.of(it) },
                text = sourceMapText,
            )
        )
    } catch (_: InvalidPathException) {
        SourceMapProviderParseResult(provider = null, warnings = listOf("source_map_path_invalid"))
    } catch (_: Throwable) {
        SourceMapProviderParseResult(provider = null, warnings = listOf("source_map_parse_failed"))
    }
}

private data class SimpleWasmSourceMap(
    private val sourceMapPath: Path?,
    private val sources: List<String>,
    private val mappings: List<SourceMapEntry>,
) : HexanaSourceLocationProvider {
    override fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation? {
        val entry = mappings.lastOrNull { it.byteOffset <= byteOffset } ?: return null
        val source = sources.getOrNull(entry.fileIndex) ?: return null
        val file = try {
            val path = Path.of(source)
            if (path.isAbsolute) path else sourceMapPath?.resolveSibling(source) ?: path
        } catch (_: InvalidPathException) {
            return null
        }
        return HexanaSourceLocation(
            filePath = file.toString(),
            line = entry.line,
            column = entry.column,
        )
    }

    companion object {
        fun parse(sourceMapPath: Path?, text: String): SimpleWasmSourceMap {
            val root = Json.parseToJsonElement(text).jsonObject
            val sources = root["sources"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            val mappings = root["mappings"]?.jsonPrimitive?.contentOrNull
                ?.let(::parseMappings)
                .orEmpty()
            return SimpleWasmSourceMap(sourceMapPath, sources, mappings)
        }

        private fun parseMappings(text: String): List<SourceMapEntry> {
            if (';' in text) return emptyList()
            val fields = IntArray(5)
            val result = mutableListOf<SourceMapEntry>()
            var sorted = true
            var previousOffset = 0
            for (segment in text.split(',')) {
                if (segment.isEmpty()) continue
                val iterator = CharIterator(segment)
                var fieldIndex = 0
                while (iterator.hasNext() && fieldIndex < fields.size) {
                    val decoded = Base64Vlq.decode(iterator)
                    fields[fieldIndex] += decoded
                    if (fieldIndex == 0) {
                        sorted = sorted && fields[fieldIndex] >= previousOffset
                        previousOffset = fields[fieldIndex]
                    }
                    fieldIndex++
                }
                if (fieldIndex >= 4) {
                    result += SourceMapEntry(
                        byteOffset = fields[0],
                        fileIndex = fields[1],
                        line = fields[2],
                        column = fields[3],
                    )
                }
            }
            return if (sorted) result else result.sortedBy { it.byteOffset }
        }
    }
}

private data class SourceMapEntry(
    val byteOffset: Int,
    val fileIndex: Int,
    val line: Int,
    val column: Int,
)

private class CharIterator(private val content: CharSequence) {
    private var current = 0
    fun hasNext(): Boolean = current < content.length
    fun next(): Char = content[current++]
}

private object Base64Vlq {
    private const val VLQ_BASE_SHIFT = 5
    private const val VLQ_BASE_MASK = (1 shl VLQ_BASE_SHIFT) - 1
    private const val VLQ_CONTINUATION_BIT = 1 shl VLQ_BASE_SHIFT
    private const val BASE64_MAP = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val decodeMap = IntArray(256) { -1 }.also { map ->
        BASE64_MAP.forEachIndexed { index, char -> map[char.code] = index }
    }

    fun decode(iterator: CharIterator): Int {
        var result = 0
        var shift = 0
        var digit: Int
        do {
            val char = iterator.next()
            digit = decodeMap.getOrElse(char.code) { -1 }
            if (digit < 0) error("Invalid Base64 VLQ character")
            result += (digit and VLQ_BASE_MASK) shl shift
            shift += VLQ_BASE_SHIFT
        } while ((digit and VLQ_CONTINUATION_BIT) != 0)

        val negative = (result and 1) == 1
        val value = result shr 1
        return if (negative) -value else value
    }
}

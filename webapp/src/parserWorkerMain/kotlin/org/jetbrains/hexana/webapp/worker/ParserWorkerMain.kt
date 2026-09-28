package org.jetbrains.hexana.webapp.worker

import org.jetbrains.hexana.shared.inspections.inspectBinary
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.javabytecode.JavaBytecodeInstruction
import org.jetbrains.hexana.javabytecode.JavaBytecodeOperands
import org.jetbrains.hexana.javabytecode.JavaClassAccess
import org.jetbrains.hexana.javabytecode.JavaCodeAttributeParser
import org.jetbrains.hexana.javabytecode.JavaConstantPool
import org.jetbrains.hexana.javabytecode.JavaConstantPoolEntry
import org.jetbrains.hexana.javabytecode.JavaMethodAccess
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.ArrowBinaryProvider
import org.jetbrains.hexana.providers.ArscBinaryProvider
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
import org.jetbrains.hexana.providers.CoffBinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.FatMachOBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.ParquetBinaryProvider
import org.jetbrains.hexana.providers.PcapBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.ProtobufBinaryProvider
import org.jetbrains.hexana.providers.StructureRange
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.providers.classifyArchiveMember
import org.jetbrains.hexana.providers.createBinaryProvider
import org.jetbrains.hexana.pcap.PcapDissector
import org.jetbrains.hexana.providers.NativeImageInfo
import org.jetbrains.hexana.providers.sbom.CycloneDxParser
import org.jetbrains.hexana.providers.sbom.extractSbomJson
import org.jetbrains.hexana.shared.componentExternDescriptionLabel
import org.jetbrains.hexana.shared.componentSortIndexLabel
import org.jetbrains.hexana.shared.exportKindLabel
import org.jetbrains.hexana.shared.fileTypeDescription
import org.jetbrains.hexana.shared.hasWasiCliRunExport
import org.jetbrains.hexana.shared.TableTabChildRows
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.shared.scripting.ScriptOutput
import org.jetbrains.hexana.shared.scripting.ScriptValueJsBinder
import org.jetbrains.hexana.shared.scripting.scriptGlobals
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.components.ComponentInstanceExpression
import org.jetbrains.hexana.wasm.components.InlineInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.InlineInstanceExpression
import org.jetbrains.hexana.wasm.components.CorruptInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.ModuleInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.UnknownInstanceCoreExpression
import org.jetbrains.hexana.webapp.MAX_RANGE_LABEL_CHARS
import org.jetbrains.hexana.webapp.ParserWorkerComponentGraph
import org.jetbrains.hexana.webapp.ParserWorkerComponentGraphEdge
import org.jetbrains.hexana.webapp.ParserWorkerComponentGraphNode
import org.jetbrains.hexana.webapp.ParserWorkerDiagnostic
import org.jetbrains.hexana.webapp.ParserWorkerNativeImage
import org.jetbrains.hexana.webapp.ParserWorkerPresentation
import org.jetbrains.hexana.webapp.ParserWorkerRange
import org.jetbrains.hexana.webapp.ParserWorkerRecord
import org.jetbrains.hexana.webapp.ParserWorkerSummary
import org.jetbrains.hexana.webapp.ParserWorkerTable
import org.jetbrains.hexana.webapp.ParserWorkerTableFilter
import org.jetbrains.hexana.webapp.ParserWorkerToolbar
import org.jetbrains.hexana.webapp.ParserWorkerAction
import org.jetbrains.hexana.webapp.MAX_PARSER_WORKER_RECORDS_PER_COLLECTION
import org.jetbrains.hexana.webapp.MAX_PARSER_WORKER_TABLES
import org.jetbrains.hexana.webapp.componentGraphSourceId
import org.jetbrains.hexana.webapp.encodeBoundedParserWorkerSuccess
import org.jetbrains.hexana.webapp.isValidParserWorkerFileName
import org.jetbrains.hexana.webapp.isValidParserWorkerRequestId
import org.jetbrains.hexana.webapp.PARSER_WORKER_PROTOCOL_VERSION
import org.jetbrains.hexana.webapp.sanitizeParserWorkerPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer

private const val SCOPE_MARKER = "DedicatedWorkerGlobalScope"
private const val MAX_WORKER_TABLE_TREE_DEPTH = 32
private const val SCRIPT_WORKER_PROTOCOL_VERSION = 1
private const val MAX_SCRIPT_SOURCE_CHARS = 256 * 1024
private const val MAX_SCRIPT_OUTPUT_LINES = 1_024
private const val MAX_SCRIPT_OUTPUT_LINE_CHARS = 4_096
private const val MAX_SCRIPT_ERROR_CHARS = 1_024
private val parserWorkerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private data class WorkerPresentationResult(
    val presentation: ParserWorkerPresentation,
    val producerRecordLimited: Boolean = false,
)

private data class MaterializedWorkerTables(
    val tables: List<ParserWorkerTable>,
    val producerRecordLimited: Boolean,
)

private data class ClassFileWorkerTables(
    val tables: List<ParserWorkerTable>,
    val producerRecordLimited: Boolean,
)

fun main() {
    val workerScope = js("self")
    val isDedicatedWorker = js(
        "typeof DedicatedWorkerGlobalScope !== 'undefined' && self instanceof DedicatedWorkerGlobalScope"
    ).unsafeCast<Boolean>()
    val documentAbsent = js("typeof document === 'undefined'").unsafeCast<Boolean>()

    workerScope.onmessage = { event: dynamic ->
        val request = event.data
        val plainRequest = isPlainObject(request)
        val rawRequestId = if (plainRequest) request.requestId as? String else null
        val rawFileName = if (plainRequest) request.fileName as? String else null
        val requestId = rawRequestId?.takeIf(::isValidParserWorkerRequestId).orEmpty()
        if (!isDedicatedWorker || !documentAbsent) {
            postError(workerScope, requestId, "INVALID_SCOPE", "Parser worker scope is unavailable.")
        } else {
            when (request.kind as? String) {
                "parse" -> {
                    if (
                        request.protocolVersion != PARSER_WORKER_PROTOCOL_VERSION ||
                        !isValidParserWorkerRequestId(rawRequestId ?: "") ||
                        (request.fileName != null && (rawFileName == null || !isValidParserWorkerFileName(rawFileName))) ||
                        !js("request.buffer instanceof ArrayBuffer").unsafeCast<Boolean>()
                    ) {
                        postError(workerScope, requestId, "INVALID_REQUEST", "Parser worker request is invalid.")
                    } else {
                        parserWorkerScope.launch {
                            parse(workerScope, requestId, request.buffer.unsafeCast<ArrayBuffer>(), rawFileName)
                        }
                    }
                }
                "script" -> {
                    val script = request.script as? String
                    if (
                        request.protocolVersion != SCRIPT_WORKER_PROTOCOL_VERSION ||
                        !isValidParserWorkerRequestId(rawRequestId ?: "") ||
                        rawFileName == null || !isValidParserWorkerFileName(rawFileName) ||
                        script == null || script.length > MAX_SCRIPT_SOURCE_CHARS ||
                        !js("request.buffer instanceof ArrayBuffer").unsafeCast<Boolean>()
                    ) {
                        postScriptDone(workerScope, requestId, "Script worker request is invalid.")
                    } else {
                        parserWorkerScope.launch {
                            runScript(workerScope, requestId, request.buffer.unsafeCast<ArrayBuffer>(), rawFileName, script)
                        }
                    }
                }
                else -> postError(workerScope, requestId, "INVALID_REQUEST", "Parser worker request is invalid.")
            }
        }
    }
}

private fun isPlainObject(value: dynamic): Boolean =
    value != null && js("Object.prototype.toString.call(value) === '[object Object]'").unsafeCast<Boolean>()

private suspend fun parse(workerScope: dynamic, requestId: String, input: ArrayBuffer, fileName: String?) {
    val startedAt = now()
    try {
        val bytes = input.toCommonByteBuffer()
        // The shared factory picks the provider; the presentation is then chosen from
        // what it built, so the worker never re-decides the format itself.
        val result = when (val provider = createBinaryProvider(bytes, fileName)) {
            is WasmBinaryProvider -> corePresentation(provider, input.byteLength, startedAt)?.let(::WorkerPresentationResult)
            is WasmComponentBinaryProvider -> componentPresentation(provider, input.byteLength, startedAt)?.let(::WorkerPresentationResult)
            else -> nonWasmPresentation(provider, input.byteLength, startedAt, fileName)
        } ?: return postError(workerScope, requestId, "PARSE_FAILED", "The input could not be parsed safely.")
        val sanitized = sanitizeParserWorkerPresentation(result.presentation)
        workerScope.postMessage(
            encodeBoundedParserWorkerSuccess(
                requestId,
                sanitized.presentation,
                omittedRecordCount = sanitized.omittedRecordCount,
                producerRecordLimited = result.producerRecordLimited,
            ),
        )
    } catch (_: Throwable) {
        postError(workerScope, requestId, "PARSE_FAILED", "The input could not be parsed safely.")
    }
}

private suspend fun runScript(
    workerScope: dynamic,
    requestId: String,
    input: ArrayBuffer,
    fileName: String,
    source: String,
) {
    try {
        val bytes = input.toCommonByteBuffer()
        val provider = createBinaryProvider(bytes, fileName)
        var outputLines = 0
        var outputChars = 0
        var truncated = false
        val emitLine: (dynamic) -> Unit = { value ->
            val line = runCatching { value.toString() }.getOrElse { "<unprintable>" }
            if (
                outputLines >= MAX_SCRIPT_OUTPUT_LINES || line.length > MAX_SCRIPT_OUTPUT_LINE_CHARS ||
                outputChars > MAX_SCRIPT_OUTPUT_LINES * MAX_SCRIPT_OUTPUT_LINE_CHARS - line.length
            ) {
                truncated = true
            } else {
                outputLines++
                outputChars += line.length
                postScriptLine(workerScope, requestId, line)
            }
        }
        // Bound from the shared scripting definition so a script behaves the
        // same here as it does in the IDE, in VS Code, and in the CLI.
        //
        // The open policy stays at its default, Unsupported: the worker only ever
        // receives the bytes of the one binary the page handed it, and has no
        // channel to ask for another. `open(path)` reports that rather than
        // resolving to some global named `open`.
        val binder = ScriptValueJsBinder(::scriptValue)
        val globals = binder.globalsToJs(
            scriptGlobals(
                provider = provider,
                output = ScriptOutput { line -> emitLine(line) },
                fileName = fileName,
            )
        )
        val error = executeWorkerScript(source = source, globals = globals)
        postScriptDone(workerScope, requestId, if (truncated && error == null) "Script output exceeded the browser limit." else error)
    } catch (_: Throwable) {
        postScriptDone(workerScope, requestId, "Script execution failed.")
    }
}

private fun executeWorkerScript(source: String, globals: Map<String, dynamic>): String? {
    @Suppress("UNUSED_VARIABLE")
    val sourceValue = source
    @Suppress("UNUSED_VARIABLE")
    val binaryValue = globals.getValue("binary")
    @Suppress("UNUSED_VARIABLE")
    val openValue = globals.getValue("open")
    @Suppress("UNUSED_VARIABLE")
    val printValue = globals.getValue("print")
    @Suppress("UNUSED_VARIABLE")
    val tableValue = globals.getValue("table")
    @Suppress("UNUSED_VARIABLE")
    val rowValue = globals.getValue("row")
    return js("""
        (function(source, binary, open, print, table, row) {
            try {
                new Function('binary', 'open', 'print', 'table', 'row', source)(binary, open, print, table, row);
                return null;
            } catch (error) {
                var message = error && error.message ? String(error.message) : 'Script execution failed.';
                return message.length > 1024 ? message.substring(0, 1024) : message;
            }
        })(sourceValue, binaryValue, openValue, printValue, tableValue, rowValue)
    """)
}

private fun postScriptLine(workerScope: dynamic, requestId: String, line: String) {
    val response = js("({})")
    response.protocolVersion = SCRIPT_WORKER_PROTOCOL_VERSION
    response.kind = "script_line"
    response.requestId = requestId
    response.line = line
    workerScope.postMessage(response)
}

private fun postScriptDone(workerScope: dynamic, requestId: String, error: String?) {
    val response = js("({})")
    response.protocolVersion = SCRIPT_WORKER_PROTOCOL_VERSION
    response.kind = "script_done"
    response.requestId = requestId
    response.error = error?.take(MAX_SCRIPT_ERROR_CHARS)
    workerScope.postMessage(response)
}

private fun scriptValue(value: Any?): dynamic {
    if (value == null || value is String || value is Number || value is Boolean) return value
    if (value is List<*>) {
        val result = js("[]")
        value.forEach { result.push(scriptValue(it)) }
        return result
    }
    val objectValue = js("({})")
    when (value) {
        is BinaryProvider -> {
            defineScriptValue(objectValue, "bytes") { value.bytes }
            defineScriptValue(objectValue, "sizeAnalysis") { value.sizeAnalysis }
            defineScriptValue(objectValue, "strings") { value.strings }
        }
    }
    if (value is WasmBinaryProvider) {
        defineScriptValue(objectValue, "parsedModule") { value.parsedModule }
        defineScriptValue(objectValue, "wasmModule") { value.wasmModule }
        defineScriptValue(objectValue, "imports") { value.imports }
        defineScriptValue(objectValue, "exports") { value.exports }
        defineScriptValue(objectValue, "data") { value.data }
        defineScriptValue(objectValue, "nameSection") { value.nameSection }
        defineScriptValue(objectValue, "codeRecords") { value.codeRecords }
        defineScriptValue(objectValue, "functions") { value.functions }
    }
    if (value is GenericBinaryProvider) defineScriptValue(objectValue, "nativeFormat") { value.nativeFormat }
    @Suppress("UNUSED_VARIABLE")
    val sourceValue = value.asDynamic()
    @Suppress("UNUSED_VARIABLE")
    val objectValueValue = objectValue
    @Suppress("UNUSED_VARIABLE")
    val wrapValue: (Any?) -> dynamic = ::scriptValue
    return js("""
        (function(source, objectValue, wrap) {
            if (Object.keys(objectValue).length !== 0) return objectValue;
            var result = {};
            var prototype = Object.getPrototypeOf(source);
            var getterPattern = /^get_(.+?)_[a-z0-9]+$/;
            while (prototype && prototype !== Object.prototype) {
                Object.getOwnPropertyNames(prototype).forEach(function(name) {
                    var match = name.match(getterPattern);
                    if (match && result[match[1]] === undefined && typeof source[name] === 'function') {
                        Object.defineProperty(result, match[1], {
                            get: function() { return wrap(source[name]()); }, enumerable: true
                        });
                    }
                });
                prototype = Object.getPrototypeOf(prototype);
            }
            return Object.keys(result).length === 0 ? source : result;
        })(sourceValue, objectValueValue, wrapValue)
    """)
}

private fun defineScriptValue(objectValue: dynamic, name: String, getter: () -> Any?) {
    @Suppress("UNUSED_VARIABLE")
    val propertyName = name
    @Suppress("UNUSED_VARIABLE")
    val propertyGetter: () -> dynamic = { scriptValue(getter()) }
    js("Object.defineProperty(objectValue, propertyName, { get: propertyGetter, enumerable: true })")
}

private suspend fun nonWasmPresentation(
    provider: BinaryProvider,
    inputBytes: Int,
    startedAt: Double,
    fileName: String?,
): WorkerPresentationResult {
    val materialized = workerTables(provider, inputBytes, fileName)
    val tables = materialized.tables.toMutableList()
    var producerRecordLimited = materialized.producerRecordLimited
    if (provider is ClassFileBinaryProvider) {
        val classFileTables = classFileTables(provider, inputBytes)
        classFileTables.tables.forEach { table ->
            if (tables.none { it.id == table.id }) {
                if (tables.size < MAX_PARSER_WORKER_TABLES) tables += table else producerRecordLimited = true
            }
        }
        producerRecordLimited = producerRecordLimited || classFileTables.producerRecordLimited
    }
    val nativeImage = (provider as? NativeBinaryProvider)?.nativeImageInfo?.takeIf { it.isNativeImage }
    nativeImage?.let { info ->
        val nativeImageTables = nativeImageTables(provider, info)
        nativeImageTables.tables.forEach { table ->
            if (tables.size < MAX_PARSER_WORKER_TABLES) tables += table else producerRecordLimited = true
        }
        producerRecordLimited = producerRecordLimited || nativeImageTables.producerRecordLimited
    }
    val sourceRanges = provider.structureRanges
    if (sourceRanges.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) producerRecordLimited = true
    return WorkerPresentationResult(ParserWorkerPresentation(
        format = nonWasmFormat(provider),
        capabilities = buildList {
            add("summary")
            if (provider.structureRanges.isNotEmpty()) add("structural_ranges")
            if (tables.isNotEmpty()) add("tables")
        },
        summary = ParserWorkerSummary(
            SCOPE_MARKER, true, inputBytes, sourceRanges.size, 0, 0, 0,
            now() - startedAt,
        ),
        ranges = sourceRanges.take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION)
            .mapNotNull { it.toWorkerRange(inputBytes) },
        tables = tables,
        toolbar = ParserWorkerToolbar(
            nativeImage = nativeImage?.let { info ->
                ParserWorkerNativeImage(
                    version = info.version,
                    edition = info.edition.name.lowercase(),
                    platform = info.platform,
                    hasSbom = info.sbom != null,
                )
            },
        ),
        displayLabel = fileTypeDescription(provider, fileName),
    ), producerRecordLimited)
}

private const val MAX_WORKER_SBOM_TEXT_ITEMS = 64
private const val MAX_WORKER_SBOM_CELL_CHARS = 16_384
private const val MAX_WORKER_SBOM_TABLE_TEXT_CHARS = 1_024
private val oversizedWorkerSbomCell = "x".repeat(MAX_WORKER_SBOM_CELL_CHARS + 1)
private val oversizedWorkerSbomTableText = "x".repeat(MAX_WORKER_SBOM_TABLE_TEXT_CHARS + 1)

private data class BoundedWorkerText(
    val value: String,
    val sourceTailLimited: Boolean = false,
)

private suspend fun nativeImageTables(
    provider: NativeBinaryProvider,
    info: NativeImageInfo,
): MaterializedWorkerTables {
    val nativeImage = ParserWorkerTable(
        id = "Native Image",
        records = listOf(
            row(listOf("Version", info.version ?: "unknown")),
            row(listOf("Edition", info.edition.name.lowercase())),
            row(listOf("Platform", info.platform ?: "unknown")),
            row(listOf("SBOM", if (info.sbom == null) "not embedded" else "embedded")),
        ),
        columns = listOf("Property", "Value"),
    )
    val locator = info.sbom ?: return MaterializedWorkerTables(listOf(nativeImage), false)
    val json = extractSbomJson(provider.bytes, locator)
    val sbom = json?.let { value ->
        CycloneDxParser.parse(
            value,
            maxComponents = MAX_PARSER_WORKER_RECORDS_PER_COLLECTION,
            maxDependencies = MAX_PARSER_WORKER_RECORDS_PER_COLLECTION,
        )
    }
    if (sbom == null) {
        return MaterializedWorkerTables(listOf(
            nativeImage,
            ParserWorkerTable(
                id = "SBOM",
                records = emptyList(),
                columns = listOf("Component", "Version", "Licenses", "PURL"),
                prologueText = boundedWorkerTableText(buildList {
                    add("CycloneDX SBOM embedded in ")
                    add(info.version ?: "this Native Image")
                    add(".")
                }),
                emptyMessage = "Embedded CycloneDX SBOM could not be decoded.",
                alwaysShowSearch = true,
                searchPlaceholder = "Search SBOM components…",
                showIdColumn = false,
            ),
        ), false)
    }
    var producerRecordLimited = sbom.componentDataTruncated || sbom.dependencyDataTruncated
    val format = boundedWorkerTableText(buildList {
        add(sbom.bomFormat ?: "CycloneDX")
        sbom.specVersion?.let { specVersion ->
            add(" ")
            add(specVersion)
        }
        add(" • ${sbom.components.size} component${if (sbom.components.size == 1) "" else "s"}")
        add(" • ${sbom.dependencies.size} dependenc${if (sbom.dependencies.size == 1) "y" else "ies"}")
        info.version?.let { version ->
            add(" • Producer: ")
            add(version)
        }
    })
    val componentRecords = buildList {
        val count = minOf(sbom.components.size, MAX_PARSER_WORKER_RECORDS_PER_COLLECTION)
        for (index in 0 until count) {
            val component = sbom.components[index]
            val name = boundedWorkerCell(listOfNotNull(component.group, component.name), ":", emptyValue = "?")
            val version = boundedWorkerCell(listOf(component.version.orEmpty()))
            val licenses = boundedWorkerCell(component.licenses, ", ")
            val purl = boundedWorkerCell(listOf(component.purl.orEmpty()))
            if (name.sourceTailLimited || version.sourceTailLimited || licenses.sourceTailLimited || purl.sourceTailLimited) {
                producerRecordLimited = true
            }
            add(row(listOf(name.value, version.value, licenses.value, purl.value)))
        }
        if (sbom.components.size > count) producerRecordLimited = true
    }
    val dependencyRecords = buildList {
        val count = minOf(sbom.dependencies.size, MAX_PARSER_WORKER_RECORDS_PER_COLLECTION)
        for (index in 0 until count) {
            val dependency = sbom.dependencies[index]
            val reference = boundedWorkerCell(listOf(dependency.ref))
            val dependsOn = boundedWorkerCell(dependency.dependsOn, ", ")
            if (reference.sourceTailLimited || dependsOn.sourceTailLimited) producerRecordLimited = true
            add(row(listOf(reference.value, dependsOn.value)))
        }
        if (sbom.dependencies.size > count) producerRecordLimited = true
    }
    return MaterializedWorkerTables(listOf(
        nativeImage,
        ParserWorkerTable(
            id = "SBOM",
            records = componentRecords,
            columns = listOf("Component", "Version", "Licenses", "PURL"),
            prologueText = format,
            emptyMessage = "No components in the embedded CycloneDX SBOM.",
            defaultSortColumn = 0,
            alwaysShowSearch = true,
            searchPlaceholder = "Search SBOM components…",
            showIdColumn = false,
        ),
        ParserWorkerTable(
            id = "SBOM Dependencies",
            records = dependencyRecords,
            columns = listOf("Component", "Depends on"),
            emptyMessage = "No dependency edges in the embedded CycloneDX SBOM.",
            defaultSortColumn = 0,
            alwaysShowSearch = true,
            searchPlaceholder = "Search SBOM dependencies…",
            showIdColumn = false,
        ),
    ), producerRecordLimited)
}

private fun boundedWorkerCell(
    values: List<String>,
    separator: String = "",
    emptyValue: String = "",
): BoundedWorkerText {
    if (values.isEmpty()) return BoundedWorkerText(emptyValue)
    val count = minOf(values.size, MAX_WORKER_SBOM_TEXT_ITEMS)
    val builder = StringBuilder()
    for (index in 0 until count) {
        val separatorLength = if (index == 0) 0 else separator.length
        val value = values[index]
        if (value.length > MAX_WORKER_SBOM_CELL_CHARS - builder.length - separatorLength) {
            return BoundedWorkerText(oversizedWorkerSbomCell)
        }
        if (index > 0) builder.append(separator)
        builder.append(value)
    }
    return if (values.size > count) BoundedWorkerText(oversizedWorkerSbomCell, sourceTailLimited = true)
    else BoundedWorkerText(builder.toString())
}

private fun boundedWorkerTableText(parts: List<String>): String {
    val builder = StringBuilder()
    for (part in parts) {
        if (part.length > MAX_WORKER_SBOM_TABLE_TEXT_CHARS - builder.length) return oversizedWorkerSbomTableText
        builder.append(part)
    }
    return builder.toString()
}

private fun nonWasmFormat(provider: BinaryProvider): String = when (provider) {
    is ZipBinaryProvider -> "zip"
    is ClassFileBinaryProvider -> "class"
    is DexBinaryProvider -> "dex"
    is ArscBinaryProvider -> "arsc"
    is PcapBinaryProvider -> "pcap"
    is ProtobufBinaryProvider -> "protobuf"
    is ElfBinaryProvider -> "elf"
    is MachOBinaryProvider -> "macho"
    is FatMachOBinaryProvider -> "fat_macho"
    is PeBinaryProvider -> "pe"
    is CoffBinaryProvider -> "coff"
    is ArchiveBinaryProvider -> "archive"
    is ParquetBinaryProvider -> "parquet"
    is ArrowBinaryProvider -> "arrow"
    else -> "generic"
}

/** Materialises UI-free descriptors in the Worker; callbacks and providers never cross the wire. */
private fun workerTables(provider: BinaryProvider, inputBytes: Int, fileName: String?): MaterializedWorkerTables {
    var producerRecordLimited = false
    val tables = mutableListOf<ParserWorkerTable>()

    fun addDescriptor(name: String, descriptor: TableTabDescriptor) {
        if (tables.any { it.id == name }) return
        if (descriptor.columns.isEmpty()) {
            if (descriptor.emptyMessage != null && tables.size < MAX_PARSER_WORKER_TABLES) {
                tables += ParserWorkerTable(
                    id = name,
                    records = emptyList(),
                    columns = listOf("Details"),
                    prologueText = descriptor.prologueText,
                    emptyMessage = descriptor.emptyMessage,
                )
            } else {
                producerRecordLimited = true
            }
            return
        }
        if (descriptor.columns.size > 16) {
            producerRecordLimited = true
            return
        }
        if (tables.size >= MAX_PARSER_WORKER_TABLES) {
            producerRecordLimited = true
            return
        }
        var visitedRows = 0

        fun appendRows(
            rows: TableTabChildRows?,
            depth: Int,
            retainedRootSourceIndices: MutableList<Int>? = null,
        ): List<ParserWorkerRecord> {
            if (depth > MAX_WORKER_TABLE_TREE_DEPTH) {
                producerRecordLimited = true
                return emptyList()
            }
            val columns = rows?.columns ?: descriptor.columns
            val count = rows?.rowCount ?: descriptor.rowCount
            val selection = rows?.rowSelectionProvider ?: descriptor.rowSelectionProvider
            val children = rows?.childrenProvider ?: descriptor.childrenProvider
            val materialized = mutableListOf<ParserWorkerRecord>()
            if (count < 0) {
                producerRecordLimited = true
                return materialized
            }
            for (index in 0 until count) {
                if (visitedRows >= MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) {
                    producerRecordLimited = true
                    break
                }
                visitedRows++
                val cells = runCatching { columns.map { it.cellProvider(index) } }.getOrElse {
                    producerRecordLimited = true
                    continue
                }
                val values = cells.map { it.representation }
                if (values.size != descriptor.columns.size) {
                    producerRecordLimited = true
                    continue
                }
                val range = runCatching { selection(index) }.getOrElse {
                    producerRecordLimited = true
                    null
                }
                val offset = range?.offset?.takeIf { it >= 0 && range.size > 0 && it <= inputBytes - range.size }
                val childRows = runCatching { children?.invoke(index) }.getOrElse {
                    producerRecordLimited = true
                    null
                }
                val childRecords = childRows?.let { appendRows(it, depth + 1) }.orEmpty()
                val actions = if (rows == null) {
                    runCatching { workerRowActions(provider, name, index, inputBytes) }.getOrElse {
                        producerRecordLimited = true
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                materialized += row(
                    values = values,
                    offset = offset,
                    size = offset?.let { range!!.size },
                    actions = actions,
                    children = childRecords.orEmpty(),
                    numericValues = cells.map { cell -> (cell.value as? Number)?.toDouble()?.takeIf(::isWireSafeNumber) },
                )
                retainedRootSourceIndices?.add(index)
            }
            return materialized
        }

        val retainedRootSourceIndices = mutableListOf<Int>()
        val records = appendRows(null, 0, retainedRootSourceIndices)
        if (descriptor.rowCount != retainedRootSourceIndices.size) producerRecordLimited = true
        val filters = descriptor.predefinedFilters.take(16).map { filter ->
            val matchingRootRows = mutableListOf<Int>()
            retainedRootSourceIndices.forEachIndexed { retainedRootIndex, sourceIndex ->
                val matches = runCatching { filter.predicate(sourceIndex) }.getOrElse {
                    producerRecordLimited = true
                    false
                }
                if (matches) matchingRootRows += retainedRootIndex
            }
            ParserWorkerTableFilter(
                label = filter.label,
                matchingRootRows = matchingRootRows,
            )
        }
        if (descriptor.predefinedFilters.size > filters.size) producerRecordLimited = true
        tables += ParserWorkerTable(
            id = name,
            records = records,
            columns = descriptor.columns.map { it.name },
            prologueText = descriptor.prologueText,
            emptyMessage = descriptor.emptyMessage,
            defaultSortColumn = descriptor.defaultSortColumn,
            defaultSortAscending = descriptor.defaultSortAscending,
            alwaysShowSearch = descriptor.alwaysShowSearch,
            searchPlaceholder = descriptor.searchPlaceholder,
            showIdColumn = descriptor.showIdColumn,
            filters = filters,
            defaultFilterShowAll = descriptor.defaultFilterShowAll,
        )
    }

    data class DescriptorEntry(val descriptor: TableTabDescriptor, val providerIndex: Int)
    val descriptors = buildList {
        TableTabsProviderRegistry.providers.forEachIndexed { providerIndex, tabProvider ->
            // Only Wasm currently emits grouped tabs and Wasm never reaches this route. Do not flatten a future
            // non-Wasm group into ordinary tables: that would silently change the navigation hierarchy.
            val groupedTabs = runCatching { tabProvider.groupedTabs(provider) }.getOrElse {
                producerRecordLimited = true
                emptyList()
            }
            check(groupedTabs.isEmpty()) {
                "Non-Wasm parser-worker table groups require an explicit wire representation."
            }
            runCatching { tabProvider.tableTabs(provider, TableTabsContext(fileName = fileName)) }
                .getOrElse {
                    producerRecordLimited = true
                    emptyList()
                }
                .forEach { descriptor -> add(DescriptorEntry(descriptor, providerIndex)) }
        }
    }.sortedWith(compareBy<DescriptorEntry> { it.descriptor.priority }.thenBy { it.providerIndex })
    for (entry in descriptors) {
        addDescriptor(entry.descriptor.name, entry.descriptor)
    }
    return MaterializedWorkerTables(tables, producerRecordLimited)
}

private fun workerRowActions(
    provider: BinaryProvider,
    tableId: String,
    rowIndex: Int,
    inputBytes: Int,
): List<ParserWorkerAction> = when {
    provider is ZipBinaryProvider && tableId == "Entries" -> {
        val entry = provider.displayEntries.getOrNull(rowIndex) ?: return emptyList()
        val range = boundedInputRange(entry.dataOffset, entry.compressedSize, inputBytes) ?: return emptyList()
        listOf(
            ParserWorkerAction(
                kind = "zip_entry",
                name = entry.name,
                dataOffset = range.first,
                compressedSize = range.second,
                uncompressedSize = entry.uncompressedSize,
                compressionMethod = entry.compressionMethod,
            ),
        )
    }
    provider is ArchiveBinaryProvider && tableId == "Members" -> {
        val member = provider.members.filterNot { it.isBookkeeping }.getOrNull(rowIndex) ?: return emptyList()
        val range = boundedInputRange(member.contentRange.offset.toLong(), member.contentRange.size.toLong(), inputBytes)
            ?: return emptyList()
        listOf(
            ParserWorkerAction(
                kind = "archive_member",
                index = member.index,
                name = member.name,
                offset = range.first,
                size = range.second,
                openable = classifyArchiveMember(provider.bytes, member.contentRange).openable,
            ),
        )
    }
    provider is PcapBinaryProvider && tableId == "Packets" -> {
        val pcap = provider.parsedPcapFile ?: return emptyList()
        val packet = pcap.packets.getOrNull(rowIndex) ?: return emptyList()
        val dissected = runCatching { PcapDissector.dissect(provider.bytes, packet, pcap.header.network) }.getOrNull()
            ?: return emptyList()
        val range = boundedInputRange(dissected.httpBodyFileOffset, dissected.httpBodySize.toLong(), inputBytes)
            ?: return emptyList()
        if (range.second == 0) {
            emptyList()
        } else {
            listOf(
                ParserWorkerAction(
                    kind = "pcap_http_body",
                    index = rowIndex,
                    name = "http-body-packet-${rowIndex + 1}${httpContentTypeExtension(dissected.httpContentType)}",
                    offset = range.first,
                    size = range.second,
                    contentType = dissected.httpContentType,
                    openable = true,
                ),
            )
        }
    }
    else -> emptyList()
}

/**
 * Wire form of a provider's structural region, or `null` when the region does not fit
 * the wire contract.
 *
 * Two things have to be bounded here. A label is whatever the format carries — an
 * archive entry path, a Wasm custom section name, a proto file name — and the protocol
 * rejects labels longer than it can carry. And a region has to lie inside the input:
 * most providers already drop out-of-bounds regions, but a Wasm section size is an
 * unvalidated LEB128, so a malformed module can declare one past the end of the file.
 * Truncating that to `Int` would smuggle a bogus region onto the wire, and passing it
 * through would fail validation for the whole presentation — dropping the one region
 * keeps the rest of the file readable.
 */
private fun StructureRange.toWorkerRange(inputBytes: Int): ParserWorkerRange? {
    val (boundedOffset, boundedSize) = boundedInputRange(offset, size, inputBytes) ?: return null
    if (boundedSize == 0) return null
    return ParserWorkerRange(boundedOffset, boundedSize, label.take(MAX_RANGE_LABEL_CHARS))
}

private fun boundedInputRange(offset: Long, size: Long, inputBytes: Int): Pair<Int, Int>? =
    if (offset < 0 || size < 0 || offset > inputBytes || size > inputBytes || offset > inputBytes - size) null
    else offset.toInt() to size.toInt()

private fun httpContentTypeExtension(contentType: String?): String {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return ""
    return when {
        mime == "application/json" -> ".json"
        mime == "application/xml" || mime == "text/xml" -> ".xml"
        mime == "text/html" -> ".html"
        mime == "text/css" -> ".css"
        mime == "text/javascript" || mime == "application/javascript" -> ".js"
        mime == "text/plain" -> ".txt"
        mime == "application/octet-stream" -> ".bin"
        mime == "application/wasm" -> ".wasm"
        mime == "application/zip" -> ".zip"
        mime == "image/png" -> ".png"
        mime == "image/jpeg" -> ".jpg"
        mime == "image/gif" -> ".gif"
        mime == "image/svg+xml" -> ".svg"
        mime.startsWith("text/") -> ".txt"
        else -> ""
    }
}

private fun classFileTables(provider: ClassFileBinaryProvider, inputBytes: Int): ClassFileWorkerTables {
    val classFile = provider.parsedClassFile ?: return ClassFileWorkerTables(emptyList(), false)
    val header = buildList {
        add(row(listOf("Class", classFile.thisClassName)))
        add(row(listOf("Superclass", classFile.superClassName ?: "<none>")))
        add(row(listOf("Version", "${classFile.majorVersion}.${classFile.minorVersion}")))
        add(row(listOf("Access flags", "${classFile.accessFlags} ${renderClassAccessFlags(classFile.accessFlags.raw)}".trim())))
        if (classFile.interfaceIndices.isEmpty()) {
            add(row(listOf("Implements", "(none)")))
        } else {
            classFile.interfaceIndices.forEach { index ->
                val interfaceName = (classFile.constantPool[index] as? JavaConstantPoolEntry.ClassRef)
                    ?.let { ref -> runCatching { classFile.constantPool.utf8(ref.nameIndex) }.getOrNull() }
                    ?: "<#$index ???>"
                add(row(listOf("Implements", interfaceName)))
            }
        }
        add(row(listOf("Fields", classFile.fields.size.toString())))
        add(row(listOf("Methods", classFile.methods.size.toString())))
        add(row(listOf("Class attributes", classFile.attributes.size.toString())))
    }
    val methods = mutableListOf<ParserWorkerRecord>()
    var recordCount = 0
    var producerRecordLimited = false
    fun safeOffset(offset: Int): Int? = offset.takeIf { it in 0 until inputBytes }
    fun addBodyRow(body: MutableList<ParserWorkerRecord>, text: String, offset: Int?) {
        if (recordCount >= MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) {
            producerRecordLimited = true
        } else {
            body += row(listOf("", text, ""), offset, offset?.let { 1 })
            recordCount++
        }
    }
    for (method in classFile.methods) {
        if (recordCount >= MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) {
            producerRecordLimited = true
            break
        }
        val name = runCatching { classFile.constantPool.utf8(method.nameIndex) }.getOrDefault("<#${method.nameIndex}>")
        val descriptor = runCatching { classFile.constantPool.utf8(method.descriptorIndex) }.getOrDefault("<#${method.descriptorIndex}>")
        val flags = renderMethodAccessFlags(method.accessFlags.raw)
        val signature = listOf(flags, name, descriptor).filter { it.isNotEmpty() }.joinToString(" ")
        val offset = safeOffset(method.byteOffset)
        recordCount++
        val body = mutableListOf<ParserWorkerRecord>()
        addBodyRow(body, "flags: 0x${method.accessFlags.raw.toString(16).padStart(4, '0')}", offset)
        val code = runCatching { JavaCodeAttributeParser.parse(method, classFile.constantPool) }.getOrNull()
        if (code == null) {
            addBodyRow(body, "(no Code attribute — abstract or native)", offset)
        } else {
            val codeOffset = safeOffset(code.codeBaseFileOffset)
            addBodyRow(body, "max_stack=${code.maxStack} max_locals=${code.maxLocals} code_length=${code.code.size} handlers=${code.exceptionTable.size}", codeOffset)
            for (instruction in code.instructions) {
                if (recordCount >= MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) {
                    producerRecordLimited = true
                    break
                }
                addBodyRow(
                    body,
                    "${instruction.bytecodeOffset.toString().padStart(4, ' ')}: ${formatClassInstruction(instruction, classFile.constantPool)}",
                    safeOffset(code.codeBaseFileOffset + instruction.bytecodeOffset),
                )
            }
            if (!producerRecordLimited && code.exceptionTable.isNotEmpty()) {
                addBodyRow(body, "exception_table:", codeOffset)
                for (handler in code.exceptionTable) {
                    if (recordCount >= MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) {
                        producerRecordLimited = true
                        break
                    }
                    val catchName = if (handler.catchTypeCpIndex == 0) "any" else {
                        (classFile.constantPool[handler.catchTypeCpIndex] as? JavaConstantPoolEntry.ClassRef)
                            ?.let { ref -> runCatching { classFile.constantPool.utf8(ref.nameIndex) }.getOrNull() }
                            ?: "<#${handler.catchTypeCpIndex} ???>"
                    }
                    addBodyRow(
                        body,
                        "[${handler.startPc.toString().padStart(4, ' ')}-${handler.endPc.toString().padStart(4, ' ')}) -> " +
                            "${handler.handlerPc.toString().padStart(4, ' ')}  catch $catchName",
                        safeOffset(code.codeBaseFileOffset + handler.handlerPc),
                    )
                }
            }
        }
        methods += row(listOf(signature, "method", "0x${method.byteOffset.toString(16)}"), offset, offset?.let { 1 }, children = body)
        if (producerRecordLimited) break
    }
    return ClassFileWorkerTables(buildList {
        add(ParserWorkerTable("Header", header, listOf("Property", "Value")))
        add(
            ParserWorkerTable(
                id = "Methods",
                records = methods,
                columns = listOf("Method", "Detail", "Offset"),
                emptyMessage = "(no methods)",
                alwaysShowSearch = true,
                searchPlaceholder = "Search methods…",
                showIdColumn = false,
            ),
        )
    }, producerRecordLimited)
}

private fun formatClassInstruction(instruction: JavaBytecodeInstruction, constantPool: JavaConstantPool): String {
    val mnemonic = instruction.opcode.mnemonic
    return when (val operands = instruction.operands) {
        JavaBytecodeOperands.None -> mnemonic
        is JavaBytecodeOperands.Byte1 -> "$mnemonic ${operands.value}"
        is JavaBytecodeOperands.Short1 -> constantPoolReference(operands.value, constantPool)
            ?.let { "$mnemonic #${operands.value}  // $it" }
            ?: "$mnemonic ${operands.value}"
        is JavaBytecodeOperands.Wide1 -> "$mnemonic ${operands.value}"
        is JavaBytecodeOperands.IInc -> "$mnemonic ${operands.varIndex}, ${operands.delta}"
        is JavaBytecodeOperands.InvokeInterface ->
            "$mnemonic #${operands.cpIndex}, ${operands.count}  // ${constantPoolReference(operands.cpIndex, constantPool) ?: "?"}"
        is JavaBytecodeOperands.InvokeDynamic ->
            "$mnemonic #${operands.cpIndex}  // ${constantPoolReference(operands.cpIndex, constantPool) ?: "?"}"
        is JavaBytecodeOperands.MultiANewArray ->
            "$mnemonic #${operands.cpIndex}, ${operands.dimensions}  // ${constantPoolReference(operands.cpIndex, constantPool) ?: "?"}"
        is JavaBytecodeOperands.TableSwitch ->
            "$mnemonic default=${operands.defaultOffset} ${operands.low}..${operands.high} -> ${operands.jumpOffsets}"
        is JavaBytecodeOperands.LookupSwitch -> "$mnemonic default=${operands.defaultOffset} pairs=${operands.pairs}"
        is JavaBytecodeOperands.Wide ->
            "$mnemonic 0x${operands.innerOpcode.toString(16).padStart(2, '0')} var=${operands.varIndex}" +
                (operands.constant?.let { ", const=$it" } ?: "")
        is JavaBytecodeOperands.Raw -> "$mnemonic <raw ${operands.bytes.size} bytes>"
    }
}

private fun constantPoolReference(index: Int, constantPool: JavaConstantPool): String? {
    if (index <= 0) return null
    val entry = runCatching { constantPool[index] }.getOrNull() ?: return null
    return when (entry) {
        is JavaConstantPoolEntry.ClassRef -> runCatching { constantPool.utf8(entry.nameIndex) }.getOrNull()
        is JavaConstantPoolEntry.StringRef -> "\"${runCatching { constantPool.utf8(entry.stringIndex) }.getOrDefault("?")}\""
        is JavaConstantPoolEntry.Methodref,
        is JavaConstantPoolEntry.InterfaceMethodref,
        is JavaConstantPoolEntry.Fieldref,
        -> {
            val (classIndex, nameAndTypeIndex) = when (entry) {
                is JavaConstantPoolEntry.Methodref -> entry.classIndex to entry.nameAndTypeIndex
                is JavaConstantPoolEntry.InterfaceMethodref -> entry.classIndex to entry.nameAndTypeIndex
                is JavaConstantPoolEntry.Fieldref -> entry.classIndex to entry.nameAndTypeIndex
                else -> return null
            }
            val owner = (constantPool[classIndex] as? JavaConstantPoolEntry.ClassRef)
                ?.let { runCatching { constantPool.utf8(it.nameIndex) }.getOrNull() } ?: "?"
            val nameAndType = constantPool[nameAndTypeIndex] as? JavaConstantPoolEntry.NameAndType ?: return owner
            val name = runCatching { constantPool.utf8(nameAndType.nameIndex) }.getOrDefault("?")
            val descriptor = runCatching { constantPool.utf8(nameAndType.descriptorIndex) }.getOrDefault("?")
            "$owner.$name:$descriptor"
        }
        is JavaConstantPoolEntry.Integer -> entry.value.toString()
        is JavaConstantPoolEntry.Float -> "${entry.value}f"
        is JavaConstantPoolEntry.Long -> "${entry.value}L"
        is JavaConstantPoolEntry.Double -> entry.value.toString()
        is JavaConstantPoolEntry.Utf8 -> "\"${entry.value}\""
        else -> null
    }
}

private fun renderClassAccessFlags(raw: Int): String = buildString {
    if (raw and JavaClassAccess.PUBLIC != 0) append("public ")
    if (raw and JavaClassAccess.FINAL != 0) append("final ")
    if (raw and JavaClassAccess.SUPER != 0) append("super ")
    if (raw and JavaClassAccess.INTERFACE != 0) append("interface ")
    if (raw and JavaClassAccess.ABSTRACT != 0) append("abstract ")
    if (raw and JavaClassAccess.SYNTHETIC != 0) append("synthetic ")
    if (raw and JavaClassAccess.ANNOTATION != 0) append("annotation ")
    if (raw and JavaClassAccess.ENUM != 0) append("enum ")
    if (raw and JavaClassAccess.MODULE != 0) append("module ")
}.trim()

private fun renderMethodAccessFlags(raw: Int): String = buildString {
    if (raw and JavaMethodAccess.PUBLIC != 0) append("public ")
    if (raw and JavaMethodAccess.PRIVATE != 0) append("private ")
    if (raw and JavaMethodAccess.PROTECTED != 0) append("protected ")
    if (raw and JavaMethodAccess.STATIC != 0) append("static ")
    if (raw and JavaMethodAccess.FINAL != 0) append("final ")
    if (raw and JavaMethodAccess.SYNCHRONIZED != 0) append("synchronized ")
    if (raw and JavaMethodAccess.BRIDGE != 0) append("bridge ")
    if (raw and JavaMethodAccess.VARARGS != 0) append("varargs ")
    if (raw and JavaMethodAccess.NATIVE != 0) append("native ")
    if (raw and JavaMethodAccess.ABSTRACT != 0) append("abstract ")
    if (raw and JavaMethodAccess.STRICT != 0) append("strict ")
    if (raw and JavaMethodAccess.SYNTHETIC != 0) append("synthetic ")
}.trim()

private fun corePresentation(provider: WasmBinaryProvider, inputBytes: Int, startedAt: Double): ParserWorkerPresentation? {
    val module = provider.wasmModule ?: return null
    val imports = provider.imports
    val exports = provider.exports
    val codeOffsets = provider.codeOffsets
    // The shared inspections, as every other host lists them. No runtime targets: the webapp
    // has no project directory to read them from.
    val problems = inspectBinary(provider)
    val proposals = provider.usedProposals.sortedBy { it.toString() }
    val graalWebImage = provider.graalWebImageInfo
    return ParserWorkerPresentation(
        format = "core_wasm",
        capabilities = listOf("summary", "structural_ranges", "tables", "diagnostics"),
        summary = ParserWorkerSummary(
            SCOPE_MARKER,
            true,
            inputBytes,
            module.sections.size,
            imports.size,
            provider.functions.size,
            codeOffsets.size,
            now() - startedAt,
            exports.size,
            0,
            proposals.size,
            graalWebImage.isWebImage,
            graalWebImage.matchedImportModules.sorted(),
        ),
        ranges = provider.structureRanges.mapNotNull { it.toWorkerRange(inputBytes) },
        tables = buildList {
            addTable("Imports", listOf("Data"), imports.map { item ->
                row(listOf(if (item.prefix.isEmpty()) item.name else "${item.prefix}.${item.name}"), item.offset, item.size)
            })
            addTable("Exports", listOf("Kind", "Name"), exports.map { item ->
                row(listOf(exportKindLabel(item), item.name), item.offset, item.size)
            })
            addTable("Functions", listOf("Function", "Size"), codeOffsets.mapIndexed { index, item ->
                row(listOf("func ${provider.importFuncCount + index}", "${item.size} bytes"), item.offset, item.size)
            })
            addTable("Problems", listOf("Severity", "Kind", "Location", "Message"), problems.map { item ->
                val range = item.byteRange
                val cells = listOf(item.severity.label, item.kind, item.location.orEmpty(), item.message)
                if (range == null) row(cells) else row(cells, range.offset.toInt(), range.size.toInt())
            })
            addTable("Proposals", listOf("Proposal"), proposals.map { item -> row(listOf(item.toString())) })
        },
        diagnostics = problems.map { item -> ParserWorkerDiagnostic(item.code, item.message) },
        legacyTabs = coreLegacyTabs(module),
        toolbar = ParserWorkerToolbar(
            runnableExportName = exports.filterIsInstance<WasmFunctionExportRecordEntry>()
                .firstOrNull { it.name == "_start" || it.name == "_initialize" }
                ?.name,
            proposalIds = proposals.map { it.name },
        ),
        displayLabel = fileTypeDescription(provider),
    )
}

private fun componentPresentation(
    provider: WasmComponentBinaryProvider,
    inputBytes: Int,
    startedAt: Double,
): ParserWorkerPresentation? {
    val component = provider.wasmModule ?: return null
    val imports = provider.imports
    val exports = provider.exports
    val modules = provider.modules
    val graph = componentGraph(provider)
    return ParserWorkerPresentation(
        format = "wasm_component",
        capabilities = listOf("summary", "structural_ranges", "tables", "component_dependency_graph"),
        summary = ParserWorkerSummary(
            SCOPE_MARKER,
            true,
            inputBytes,
            component.sections.size,
            imports.size,
            0,
            0,
            now() - startedAt,
            exports.size,
            modules.size,
            0,
        ),
        ranges = provider.structureRanges.mapNotNull { it.toWorkerRange(inputBytes) },
        tables = buildList {
            addTable("Imports", listOf("Name", "Extern Description"), imports.map { item ->
                row(listOf(componentName(item.importName, item.version), componentExternDescriptionLabel(item.externDescription)), item.offset, item.size)
            })
            addTable("Exports", listOf("Name", "Sort Index", "Extern Description"), exports.map { item ->
                row(
                    listOf(
                        componentName(item.exportName, item.version),
                        componentSortIndexLabel(item.sortIndex) ?: "",
                        item.externDescription?.let(::componentExternDescriptionLabel) ?: "",
                    ),
                    item.offset,
                    item.size,
                )
            })
            addTable("Modules", listOf("Data"), modules.mapIndexed { index, item ->
                row(listOf("module $index @0x${item.contentOffset.toString(16)}"), item.contentOffset, item.contentSize)
            })
        },
        componentGraph = graph,
        legacyTabs = buildList {
            if (graph.nodes.any { it.kind == "nested_component" }) add("Components")
            add("Wat")
            add("WAT (virtualized)")
        },
        toolbar = ParserWorkerToolbar(
            runnableExportName = if (hasWasiCliRunExport(provider)) "wasi:cli/run" else null,
        ),
        displayLabel = fileTypeDescription(provider),
    )
}

private fun coreLegacyTabs(module: org.jetbrains.hexana.wasm.WasmModule): List<String> = buildList {
    if (module.dataSection != null) add("Data")
    if (module.exceptionSection != null) {
        add("Tags")
        add("Exceptions")
    }
    if (module.customSections.isNotEmpty()) add("Custom")
    add("Top")
    if (module.codeSection != null && module.getCustomSectionByName("name") != null) add("Monos")
    if (module.codeSection != null) add("Garbage")
    add("Wat")
    add("WAT (virtualized)")
}

private fun MutableList<ParserWorkerTable>.addTable(
    id: String,
    columns: List<String>,
    records: List<ParserWorkerRecord>,
) {
    if (records.isNotEmpty()) add(ParserWorkerTable(id, records, columns))
}

private fun row(
    values: List<String>,
    offset: Int? = null,
    size: Int? = null,
    actions: List<ParserWorkerAction> = emptyList(),
    children: List<ParserWorkerRecord> = emptyList(),
    numericValues: List<Double?> = List(values.size) { null },
): ParserWorkerRecord =
    ParserWorkerRecord(
        values.firstOrNull().orEmpty(),
        values.getOrElse(1) { values.firstOrNull().orEmpty() },
        values, offset, size, actions = actions, children = children, numericValues = numericValues,
    )

private fun isWireSafeNumber(value: Double): Boolean =
    value.isFinite() && value >= -9_007_199_254_740_991.0 && value <= 9_007_199_254_740_991.0

private fun componentGraph(provider: WasmComponentBinaryProvider): ParserWorkerComponentGraph {
    val nodes = mutableListOf<ParserWorkerComponentGraphNode>()
    val edges = mutableListOf<ParserWorkerComponentGraphEdge>()
    provider.imports.forEachIndexed { index, item ->
        nodes += ParserWorkerComponentGraphNode(
            "import:$index",
            "import",
            componentName(item.importName, item.version),
            componentExternDescriptionLabel(item.externDescription),
            offset = item.offset,
            size = item.size,
        )
    }
    provider.modules.forEachIndexed { index, item ->
        nodes += ParserWorkerComponentGraphNode(
            "module:$index",
            "module",
            "core module $index",
            "${item.contentSize} bytes",
            offset = item.contentOffset,
            size = item.contentSize,
        )
    }
    val componentSection = provider.wasmModule?.componentSection
    provider.nestedComponents.forEachIndexed { index, _ ->
        nodes += ParserWorkerComponentGraphNode(
            "component:$index",
            "nested_component",
            "component $index",
            "${provider.nestedComponentExports(index).size} exports",
            offset = componentSection?.contentOffset,
            size = componentSection?.contentSize,
        )
    }
    provider.coreInstances.forEachIndexed { index, instance ->
        val id = "core-instance:$index"
        when (instance) {
            is ModuleInstanceCoreExpression -> {
                nodes += ParserWorkerComponentGraphNode(id, "core_instance", "core instance $index", "instantiates module ${instance.moduleIndex}")
                edges += ParserWorkerComponentGraphEdge("module:${instance.moduleIndex}", id)
                instance.instantiateArgs.forEach { arg ->
                    edges += ParserWorkerComponentGraphEdge("core-instance:${arg.instanceIndex}", id, arg.name)
                }
            }
            is InlineInstanceCoreExpression -> nodes += ParserWorkerComponentGraphNode(id, "core_instance", "core instance $index", "inline exports")
            is UnknownInstanceCoreExpression ->
                nodes += ParserWorkerComponentGraphNode(id, "core_instance", "core instance $index", "unknown encoding")
            is CorruptInstanceCoreExpression ->
                nodes += ParserWorkerComponentGraphNode(id, "core_instance", "core instance $index", "corrupt entry")
        }
    }
    provider.instances.forEachIndexed { index, instance ->
        val id = "instance:$index"
        when (instance) {
            is ComponentInstanceExpression -> {
                nodes += ParserWorkerComponentGraphNode(id, "instance", "instance $index", "instantiates component ${instance.index}")
                edges += ParserWorkerComponentGraphEdge("component:${instance.index}", id)
                instance.args.forEach { arg -> componentGraphSourceId(arg.sortIndex.sort, arg.sortIndex.index.toInt())?.let { source ->
                    edges += ParserWorkerComponentGraphEdge(source, id, arg.name)
                } }
            }
            is InlineInstanceExpression -> nodes += ParserWorkerComponentGraphNode(id, "instance", "instance $index", "inline exports")
        }
    }
    provider.exports.forEachIndexed { index, export ->
        val id = "export:$index"
        nodes += ParserWorkerComponentGraphNode(
            id,
            "export",
            componentName(export.exportName, export.version),
            componentSortIndexLabel(export.sortIndex) ?: "",
            offset = export.offset,
            size = export.size,
        )
        componentGraphSourceId(export.sortIndex.sort, export.sortIndex.index.toInt())?.let { source ->
            edges += ParserWorkerComponentGraphEdge(source, id)
        }
    }
    val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
    return ParserWorkerComponentGraph(nodes, edges.filter { it.fromId in nodeIds && it.toId in nodeIds })
}

private fun componentName(name: String, version: String?): String =
    if (version == null) name else "$name@$version"

private fun postError(
    workerScope: dynamic,
    requestId: String,
    code: String,
    message: String,
) {
    val safeRequestId = requestId.takeIf(::isValidParserWorkerRequestId).orEmpty()
    val response = js("({})")
    response.protocolVersion = PARSER_WORKER_PROTOCOL_VERSION
    response.kind = "error"
    response.requestId = safeRequestId
    response.code = code
    response.message = message
    workerScope.postMessage(response)
}

private fun now(): Double = js("performance.now()").unsafeCast<Double>()

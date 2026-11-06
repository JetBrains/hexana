package org.jetbrains.hexana.shared

import org.jetbrains.hexana.mlmodel.ModelByteRange
import org.jetbrains.hexana.mlmodel.ModelDiagnostic
import org.jetbrains.hexana.mlmodel.gguf.GgufFile
import org.jetbrains.hexana.mlmodel.gguf.GgufMetadataEntry
import org.jetbrains.hexana.mlmodel.gguf.GgufTensorInfo
import org.jetbrains.hexana.mlmodel.gguf.GgufValue
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsFile
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsTensor
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GGUF_HEADER_TAB
import org.jetbrains.hexana.providers.GGUF_METADATA_TAB
import org.jetbrains.hexana.providers.GgufBinaryProvider
import org.jetbrains.hexana.providers.MODEL_TENSORS_TAB
import org.jetbrains.hexana.providers.SAFETENSORS_HEADER_TAB
import org.jetbrains.hexana.providers.SafetensorsBinaryProvider

/**
 * Table tabs for GGUF models: Header (summary, per-type totals, warnings), Metadata (every
 * key/value pair; arrays expand into their elements) and Tensors.
 */
class GgufTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is GgufBinaryProvider) return emptyList()
        val file = provider.parsedGguf ?: return emptyList()
        val loaded = provider.bytes.size
        return listOf(ggufHeaderTab(file, loaded), ggufMetadataTab(file, loaded), ggufTensorsTab(file, loaded))
    }

    override fun availableTabNames(provider: BinaryProvider, context: TableTabsContext): Pair<List<String>, List<String>>? =
        if (provider is GgufBinaryProvider) listOf(GGUF_HEADER_TAB, GGUF_METADATA_TAB, MODEL_TENSORS_TAB) to emptyList() else null

    override fun singleTab(name: String, provider: BinaryProvider, context: TableTabsContext): TableTabDescriptor? {
        if (provider !is GgufBinaryProvider) return null
        val file = provider.parsedGguf ?: return null
        return when (name) {
            GGUF_HEADER_TAB -> ggufHeaderTab(file, provider.bytes.size)
            GGUF_METADATA_TAB -> ggufMetadataTab(file, provider.bytes.size)
            MODEL_TENSORS_TAB -> ggufTensorsTab(file, provider.bytes.size)
            else -> null
        }
    }
}

/** Table tabs for safetensors models: Header (summary, `__metadata__`, warnings) and Tensors. */
class SafetensorsTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is SafetensorsBinaryProvider) return emptyList()
        val file = provider.parsedSafetensors ?: return emptyList()
        val loaded = provider.bytes.size
        return listOf(safetensorsHeaderTab(file, loaded), safetensorsTensorsTab(file, loaded))
    }

    override fun availableTabNames(provider: BinaryProvider, context: TableTabsContext): Pair<List<String>, List<String>>? =
        if (provider is SafetensorsBinaryProvider) listOf(SAFETENSORS_HEADER_TAB, MODEL_TENSORS_TAB) to emptyList() else null

    override fun singleTab(name: String, provider: BinaryProvider, context: TableTabsContext): TableTabDescriptor? {
        if (provider !is SafetensorsBinaryProvider) return null
        val file = provider.parsedSafetensors ?: return null
        return when (name) {
            SAFETENSORS_HEADER_TAB -> safetensorsHeaderTab(file, provider.bytes.size)
            MODEL_TENSORS_TAB -> safetensorsTensorsTab(file, provider.bytes.size)
            else -> null
        }
    }
}

// --- GGUF ---

private fun ggufHeaderTab(file: GgufFile, loaded: Int): TableTabDescriptor {
    val header = file.headerRange.toNative(loaded)
    val rows = buildList {
        add(HeaderRow("Format", "GGUF v${file.version}", header))
        add(HeaderRow("Byte Order", if (file.bigEndian) "big-endian" else "little-endian", header))
        file.architecture?.let { add(HeaderRow("Architecture", it, file.entryRange("general.architecture", loaded))) }
        file.name?.let { add(HeaderRow("Name", it, file.entryRange("general.name", loaded))) }
        file.metadataInteger("general.file_type")?.let { type ->
            add(HeaderRow("File Type", file.fileTypeLabel?.let { "$it ($type)" } ?: type.toString(), file.entryRange("general.file_type", loaded)))
        }
        file.metadataInteger("general.quantization_version")?.let {
            add(HeaderRow("Quantization Version", it.toString(), file.entryRange("general.quantization_version", loaded)))
        }
        file.parameterCount?.let { add(HeaderRow("Parameters", formatParameterCount(it), file.tensorInfoRange?.toNative(loaded))) }
        add(HeaderRow("Tensors", countLabel(file.tensors.size, file.declaredTensorCount), file.tensorInfoRange?.toNative(loaded) ?: header))
        add(HeaderRow("Metadata Entries", countLabel(file.metadata.size, file.declaredMetadataCount), file.metadataRange?.toNative(loaded) ?: header))
        add(HeaderRow("Alignment", file.alignment.toString(), file.entryRange("general.alignment", loaded)))
        file.tensorDataOffset?.let { add(HeaderRow("Tensor Data Offset", hex(it), null)) }
        file.tensorDataSize?.let { add(HeaderRow("Tensor Data Size", sizeLabel(it), null)) }
        file.tensors.groupBy { it.typeLabel }.entries
            .sortedByDescending { (_, tensors) -> tensors.sumOf { it.dataSize ?: 0L } }
            .forEach { (type, tensors) -> add(HeaderRow("Type $type", typeTotalLabel(tensors.size, tensors.map { it.dataSize }), null)) }
        addDiagnostics(file.diagnostics, loaded)
    }
    return keyValueTab(GGUF_HEADER_TAB, rows, labelWidth = ColumnWidth.Fill(0.3f))
}

private fun GgufFile.entryRange(key: String, loaded: Int): NativeByteRange? =
    metadata.firstOrNull { it.key == key }?.range?.toNative(loaded)

private fun ggufMetadataTab(file: GgufFile, loaded: Int): TableTabDescriptor {
    val entries = file.metadata
    if (entries.isEmpty()) {
        return TableTabDescriptor(name = GGUF_METADATA_TAB, columns = emptyList(), rowCount = 0, emptyMessage = "(no metadata)")
    }
    return TableTabDescriptor(
        name = GGUF_METADATA_TAB,
        columns = listOf(
            col("Key", ColumnWidth.Fill(0.35f)) { CellValue(entries[it].key) },
            col("Type") { CellValue(entries[it].typeLabel()) },
            col("Value", ColumnWidth.Fill()) { index ->
                val value = entries[index].value
                CellValue(value.display(), tooltip = value.string?.takeIf { it.length > 200 })
            },
        ),
        rowCount = entries.size,
        rowSelectionProvider = { entries.getOrNull(it)?.range?.toSelection(loaded) },
        childrenProvider = { index -> entries.getOrNull(index)?.value?.takeIf { it.elements != null }?.let(::arrayChildRows) },
        alwaysShowSearch = true,
        searchPlaceholder = "Search metadata",
        showIdColumn = false,
    )
}

private fun GgufMetadataEntry.typeLabel(): String =
    value.elementType?.let { "${it.label}[]" } ?: type.label

/** Array elements as child rows: index, element type, value. Capped so a vocabulary stays browsable. */
private fun arrayChildRows(array: GgufValue): TableTabChildRows? {
    val elements = array.elements.orEmpty().take(MAX_ARRAY_CHILD_ROWS).takeIf { it.isNotEmpty() } ?: return null
    return TableTabChildRows(
        columns = listOf(
            ColumnDescriptor("Key", ColumnWidth.Fill(0.35f)) { CellValue(it.toString()) },
            ColumnDescriptor("Type") { CellValue(array.elementType?.label.orEmpty()) },
            ColumnDescriptor("Value", ColumnWidth.Fill()) { CellValue(elements[it].display()) },
        ),
        rowCount = elements.size,
    )
}

private const val MAX_ARRAY_CHILD_ROWS = 10_000

private fun ggufTensorsTab(file: GgufFile, loaded: Int): TableTabDescriptor {
    val tensors = file.tensors
    if (tensors.isEmpty()) {
        return TableTabDescriptor(name = MODEL_TENSORS_TAB, columns = emptyList(), rowCount = 0, emptyMessage = "(no tensors)")
    }
    return TableTabDescriptor(
        name = MODEL_TENSORS_TAB,
        columns = listOf(
            col("Name", ColumnWidth.Fill(0.4f)) { CellValue(tensors[it].name) },
            col("Type") { CellValue(tensors[it].typeLabel) },
            col("Shape", ColumnWidth.Fill(0.2f)) { CellValue(shapeLabel(tensors[it].dims), tooltip = "ggml order: innermost dimension first") },
            col("Elements") { index -> tensors[index].elementCount.let { CellValue(it ?: -1L, it?.toString() ?: "?") } },
            col("Size") { index -> tensors[index].dataSize.let { CellValue(it ?: -1L, it?.let(::sizeLabel) ?: "?") } },
            col("Offset") { index -> tensors[index].dataRange.let { CellValue(it?.offset ?: -1L, it?.offset?.let(::hex) ?: "?") } },
        ),
        rowCount = tensors.size,
        rowSelectionProvider = { tensors.getOrNull(it)?.selection(loaded) },
        alwaysShowSearch = true,
        searchPlaceholder = "Search tensors",
        showIdColumn = false,
    )
}

/**
 * The tensor's data when it is in the loaded bytes, else its info record. A partial view of a
 * large file loads only the head, so most tensors' data lies past what the hex view can show.
 */
private fun GgufTensorInfo.selection(loaded: Int): RowSelectionData? =
    dataRange?.toSelection(loaded) ?: infoRange.toSelection(loaded)

// --- safetensors ---

private fun safetensorsHeaderTab(file: SafetensorsFile, loaded: Int): TableTabDescriptor {
    val header = file.headerRange.toNative(loaded)
    val rows = buildList {
        add(HeaderRow("Header Size", "${file.headerSize} bytes", file.lengthRange.toNative(loaded)))
        file.formatTag?.let { add(HeaderRow("Format", it, header)) }
        file.parameterCount?.let { add(HeaderRow("Parameters", formatParameterCount(it), header)) }
        add(HeaderRow("Tensors", file.tensors.size.toString(), header))
        add(HeaderRow("Tensor Data Offset", hex(file.dataOffset), null))
        add(HeaderRow("Tensor Data Size", sizeLabel(file.tensorDataSize), null))
        file.tensors.groupBy { it.dtype }.entries
            .sortedByDescending { (_, tensors) -> tensors.sumOf { it.dataSize } }
            .forEach { (dtype, tensors) -> add(HeaderRow("Type $dtype", typeTotalLabel(tensors.size, tensors.map { it.dataSize }), null)) }
        file.metadata.forEach { add(HeaderRow("Metadata: ${it.key}", it.value, header)) }
        addDiagnostics(file.diagnostics, loaded)
    }
    return keyValueTab(SAFETENSORS_HEADER_TAB, rows, labelWidth = ColumnWidth.Fill(0.3f))
}

private fun safetensorsTensorsTab(file: SafetensorsFile, loaded: Int): TableTabDescriptor {
    val tensors = file.tensors
    if (tensors.isEmpty()) {
        return TableTabDescriptor(name = MODEL_TENSORS_TAB, columns = emptyList(), rowCount = 0, emptyMessage = "(no tensors)")
    }
    return TableTabDescriptor(
        name = MODEL_TENSORS_TAB,
        columns = listOf(
            col("Name", ColumnWidth.Fill(0.4f)) { CellValue(tensors[it].name) },
            col("DType") { CellValue(tensors[it].dtype) },
            col("Shape", ColumnWidth.Fill(0.2f)) { CellValue(shapeLabel(tensors[it].shape)) },
            col("Elements") { index -> tensors[index].elementCount.let { CellValue(it ?: -1L, it?.toString() ?: "?") } },
            col("Size") { CellValue(tensors[it].dataSize, sizeLabel(tensors[it].dataSize)) },
            col("Offset") { index -> tensors[index].dataRange.let { CellValue(it?.offset ?: -1L, it?.offset?.let(::hex) ?: "?") } },
        ),
        rowCount = tensors.size,
        rowSelectionProvider = { tensors.getOrNull(it)?.selection(file, loaded) },
        alwaysShowSearch = true,
        searchPlaceholder = "Search tensors",
        showIdColumn = false,
    )
}

/** The tensor's data when it is in the loaded bytes, else the JSON header that declares it. */
private fun SafetensorsTensor.selection(file: SafetensorsFile, loaded: Int): RowSelectionData? =
    dataRange?.toSelection(loaded) ?: file.headerRange.toSelection(loaded)

// --- shared formatting ---

private fun MutableList<HeaderRow>.addDiagnostics(diagnostics: List<ModelDiagnostic>, loaded: Int) {
    diagnostics.forEach { add(HeaderRow("Warning", it.message, it.range?.toNative(loaded))) }
}

/** The range as a hex-view range, or `null` when any of it lies past the [loaded] bytes. */
private fun ModelByteRange.toNative(loaded: Int): NativeByteRange? =
    if (offset <= loaded && size <= loaded - offset) NativeByteRange(offset.toInt(), size.toInt()) else null

private fun ModelByteRange.toSelection(loaded: Int): RowSelectionData? = toNative(loaded)?.let(::selection)

/** Dimensions joined with `×`; a scalar (no dimensions) is `scalar`. */
internal fun shapeLabel(dims: List<Long>): String = if (dims.isEmpty()) "scalar" else dims.joinToString(" × ")

private fun countLabel(read: Int, declared: Long): String =
    if (read.toLong() == declared) read.toString() else "$read of $declared declared"

private fun sizeLabel(bytes: Long): String = formatSizeLong(bytes)

private fun typeTotalLabel(count: Int, sizes: List<Long?>): String {
    val tensors = if (count == 1) "1 tensor" else "$count tensors"
    val total = sizes.takeIf { list -> list.all { it != null } }?.sumOf { it ?: 0L }
    return if (total != null) "$tensors, ${sizeLabel(total)}" else tensors
}

/** `7241732096` → `7.24 B (7,241,732,096)`; small counts are shown as is. */
internal fun formatParameterCount(count: Long): String {
    val grouped = count.toString().reversed().chunked(3).joinToString(",").reversed()
    val (scale, unit) = when {
        count >= 1_000_000_000_000L -> 1e12 to "T"
        count >= 1_000_000_000L -> 1e9 to "B"
        count >= 1_000_000L -> 1e6 to "M"
        count >= 1_000L -> 1e3 to "K"
        else -> return grouped
    }
    val scaled = (count / scale * 100).toLong()
    val text = "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    return "$text $unit ($grouped)"
}

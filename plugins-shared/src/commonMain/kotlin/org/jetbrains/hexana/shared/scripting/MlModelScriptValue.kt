package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.mlmodel.ModelByteRange
import org.jetbrains.hexana.mlmodel.ModelDiagnostic
import org.jetbrains.hexana.mlmodel.gguf.GgufFile
import org.jetbrains.hexana.mlmodel.gguf.GgufMetadataEntry
import org.jetbrains.hexana.mlmodel.gguf.GgufTensorInfo
import org.jetbrains.hexana.mlmodel.gguf.GgufValue
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsFile
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsTensor

/*
 * GGUF and safetensors models as plain maps, lists and scalars, with the member names the raw
 * `.d.ts` declares for `binary.raw.parsedGguf` / `binary.raw.parsedSafetensors`.
 *
 * Browser hosts cannot read Kotlin objects reflectively in production builds (member names are
 * minimized), so they expose these values instead of the parsed objects; the JVM host reads the
 * objects directly. Offsets, sizes and counts are 64-bit in the model and become JS numbers
 * here, which is exact up to 2^53 — far beyond any file a host can load.
 */

fun ggufScriptValue(file: GgufFile): Map<String, Any?> = mapOf(
    "version" to file.version,
    "bigEndian" to file.bigEndian,
    "declaredTensorCount" to file.declaredTensorCount.toDouble(),
    "declaredMetadataCount" to file.declaredMetadataCount.toDouble(),
    "metadata" to file.metadata.map(::metadataEntryValue),
    "tensors" to file.tensors.map(::ggufTensorValue),
    "alignment" to file.alignment,
    "headerRange" to rangeValue(file.headerRange),
    "metadataRange" to file.metadataRange?.let(::rangeValue),
    "tensorInfoRange" to file.tensorInfoRange?.let(::rangeValue),
    "tensorDataOffset" to file.tensorDataOffset?.toDouble(),
    "fileSize" to file.fileSize.toDouble(),
    "diagnostics" to file.diagnostics.map(::diagnosticValue),
    "architecture" to file.architecture,
    "name" to file.name,
    "fileTypeLabel" to file.fileTypeLabel,
    "parameterCount" to file.parameterCount?.toDouble(),
    "tensorDataSize" to file.tensorDataSize?.toDouble(),
)

fun safetensorsScriptValue(file: SafetensorsFile): Map<String, Any?> = mapOf(
    "headerSize" to file.headerSize.toDouble(),
    "lengthRange" to rangeValue(file.lengthRange),
    "headerRange" to rangeValue(file.headerRange),
    "metadata" to file.metadata.map { mapOf("key" to it.key, "value" to it.value) },
    "tensors" to file.tensors.map(::safetensorsTensorValue),
    "dataOffset" to file.dataOffset.toDouble(),
    "fileSize" to file.fileSize.toDouble(),
    "diagnostics" to file.diagnostics.map(::diagnosticValue),
    "parameterCount" to file.parameterCount?.toDouble(),
    "tensorDataSize" to file.tensorDataSize.toDouble(),
    "formatTag" to file.formatTag,
)

private fun metadataEntryValue(entry: GgufMetadataEntry): Map<String, Any?> = mapOf(
    "key" to entry.key,
    "type" to entry.type.name,
    "value" to ggufValue(entry.value),
    "range" to rangeValue(entry.range),
)

private fun ggufValue(value: GgufValue): Map<String, Any?> = mapOf(
    "type" to value.type.name,
    // uint64 values above Long.MAX_VALUE are stored as their bits; read them unsigned.
    "integer" to value.integer?.let { if (it < 0) it.toULong().toDouble() else it.toDouble() },
    "real" to value.real,
    "bool" to value.bool,
    "string" to value.string,
    "elementType" to value.elementType?.name,
    "count" to value.count?.toDouble(),
    "elements" to value.elements?.map(::ggufValue),
    "truncated" to value.truncated,
)

private fun ggufTensorValue(tensor: GgufTensorInfo): Map<String, Any?> = mapOf(
    "index" to tensor.index,
    "name" to tensor.name,
    "dims" to tensor.dims.map { it.toDouble() },
    "typeId" to tensor.typeId,
    "type" to tensor.type?.name,
    "relativeOffset" to tensor.relativeOffset.toDouble(),
    "elementCount" to tensor.elementCount?.toDouble(),
    "dataSize" to tensor.dataSize?.toDouble(),
    "dataRange" to tensor.dataRange?.let(::rangeValue),
    "infoRange" to rangeValue(tensor.infoRange),
    "typeLabel" to tensor.typeLabel,
)

private fun safetensorsTensorValue(tensor: SafetensorsTensor): Map<String, Any?> = mapOf(
    "index" to tensor.index,
    "name" to tensor.name,
    "dtype" to tensor.dtype,
    "shape" to tensor.shape.map { it.toDouble() },
    "begin" to tensor.begin.toDouble(),
    "end" to tensor.end.toDouble(),
    "elementCount" to tensor.elementCount?.toDouble(),
    "dataRange" to tensor.dataRange?.let(::rangeValue),
    "dataSize" to tensor.dataSize.toDouble(),
    "elementBits" to tensor.elementBits,
)

private fun rangeValue(range: ModelByteRange): Map<String, Any?> = mapOf(
    "offset" to range.offset.toDouble(),
    "size" to range.size.toDouble(),
    "endExclusive" to range.endExclusive.toDouble(),
)

private fun diagnosticValue(diagnostic: ModelDiagnostic): Map<String, Any?> = mapOf(
    "code" to diagnostic.code,
    "message" to diagnostic.message,
    "range" to diagnostic.range?.let(::rangeValue),
)

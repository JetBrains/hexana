package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.providers.pe.clr.ClrResource
import org.jetbrains.hexana.providers.pe.clr.ClrResourcesFile

/**
 * [ClrResourcesFile] as plain maps, lists and scalars, with the member names the raw `.d.ts`
 * declares for `binary.raw.parsedResources`.
 *
 * Browser hosts cannot read Kotlin objects reflectively in production builds — member names are
 * minimized there — so they expose this value instead of the parsed object itself. The JVM host
 * reads the object directly and does not need it.
 */
fun clrResourcesScriptValue(file: ClrResourcesFile): Map<String, Any?> = mapOf(
    "managerHeaderVersion" to file.managerHeaderVersion,
    "readerType" to file.readerType,
    "resourceSetType" to file.resourceSetType,
    "version" to file.version,
    "types" to file.types,
    "resources" to file.resources.map(::resourceValue),
    "headerRange" to rangeValue(file.headerRange),
    "nameSectionRange" to file.nameSectionRange?.let(::rangeValue),
    "dataSectionRange" to file.dataSectionRange?.let(::rangeValue),
    "diagnostics" to file.diagnostics.map(::diagnosticValue),
)

private fun resourceValue(resource: ClrResource): Map<String, Any?> = mapOf(
    "name" to resource.name,
    "type" to resource.type,
    "kind" to resource.kind.name,
    "value" to resource.value,
    "stringValue" to resource.stringValue,
    "nameRange" to rangeValue(resource.nameRange),
    "dataRange" to resource.dataRange?.let(::rangeValue),
    "payloadRange" to resource.payloadRange?.let(::rangeValue),
)

private fun rangeValue(range: NativeByteRange): Map<String, Any?> =
    mapOf("offset" to range.offset, "size" to range.size, "endExclusive" to range.endExclusive)

private fun diagnosticValue(diagnostic: NativeParserDiagnostic): Map<String, Any?> = mapOf(
    "code" to diagnostic.code,
    "message" to diagnostic.message,
    "range" to diagnostic.range?.let(::rangeValue),
    "severity" to diagnostic.severity.name,
)

package org.jetbrains.hexana.shared

import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.pe.clr.ClrPortablePdb
import org.jetbrains.hexana.providers.pe.clr.ClrCliHeader
import org.jetbrains.hexana.providers.pe.clr.ClrField
import org.jetbrains.hexana.providers.pe.clr.ClrHeaderFlags
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrMethodDef
import org.jetbrains.hexana.providers.pe.clr.ClrReadyToRunParser
import org.jetbrains.hexana.providers.pe.clr.ClrResourcesParser
import org.jetbrains.hexana.providers.pe.clr.ClrTypeDef
import org.jetbrains.hexana.providers.pe.clr.ClrTypeKind

internal const val CLR_OVERVIEW_TAB_NAME = ".NET"
internal const val CLR_METADATA_GROUP_NAME = ".NET Metadata"

/** Top-level tabs of a managed PE image, in display order: overview, type tree, methods, assembly references. */
internal val CLR_TAB_NAMES: List<String> = listOf(CLR_OVERVIEW_TAB_NAME, "Types", "Methods", "Assembly Refs")

/** The top-level CLR tab called [name], or null when the image has no metadata or nothing to show there. */
internal fun clrTab(name: String, provider: PeBinaryProvider): TableTabDescriptor? {
    val metadata = provider.clrMetadata ?: return null
    return when (name) {
        CLR_OVERVIEW_TAB_NAME -> clrOverviewTab(provider, metadata)
        "Types" -> clrTypesTab(metadata)
        "Methods" -> clrMethodsTab(metadata, provider.portablePdb)
        "Assembly Refs" -> clrAssemblyRefsTab(metadata)
        else -> null
    }
}

/** Lower-level metadata views, grouped so they do not crowd the main tab strip. */
internal fun clrMetadataGroup(provider: PeBinaryProvider): GroupedTableTabDescriptor? {
    val metadata = provider.clrMetadata ?: return null
    val innerTabs = listOfNotNull(
        clrStreamsTab(metadata),
        clrTablesTab(metadata),
        clrTypeRefsTab(metadata),
        clrMemberRefsTab(metadata),
        clrUserStringsTab(metadata),
        clrResourcesTab(provider, metadata),
    )
    if (innerTabs.isEmpty()) return null
    return GroupedTableTabDescriptor(name = CLR_METADATA_GROUP_NAME, innerTabs = innerTabs)
}

private fun clrOverviewTab(provider: PeBinaryProvider, metadata: ClrMetadata): TableTabDescriptor {
    val header = metadata.header
    val rows = buildList {
        add(HeaderRow("Runtime Header Version", "${header.majorRuntimeVersion}.${header.minorRuntimeVersion}", header.headerRange))
        add(HeaderRow("Flags", clrHeaderFlagsLabel(header.flags), header.headerRange))
        add(HeaderRow("Platform", clrPlatform(provider.coffHeader?.machine, header, metadata.readyToRun), header.headerRange))
        add(HeaderRow("Entry Point", metadata.entryPoint ?: "none (library)", header.headerRange))
        add(HeaderRow("Strong Name Signed", yesNo(header.isStrongNameSigned), header.headerRange))
        add(HeaderRow("ReadyToRun", yesNo(metadata.readyToRun), header.headerRange))
        metadata.targetFramework?.let { add(HeaderRow("Target Framework", it, null)) }
        metadata.root?.let { root ->
            add(HeaderRow("Metadata Version", root.version, root.range))
            add(HeaderRow("Metadata Size", hex(header.metadataSize), root.range))
        }
        metadata.tablesHeader?.let { tables ->
            add(HeaderRow("Tables Stream", "${tables.streamName} v${tables.majorVersion}.${tables.minorVersion}", tables.range))
        }
        metadata.assembly?.let { assembly ->
            add(HeaderRow("Assembly", assembly.name, assembly.recordRange))
            add(HeaderRow("Assembly Version", assembly.version.toString(), assembly.recordRange))
            add(HeaderRow("Culture", assembly.culture.ifEmpty { "neutral" }, assembly.recordRange))
            add(HeaderRow("Public Key", if (assembly.publicKey.isEmpty()) "none" else "${assembly.publicKey.size} bytes", assembly.recordRange))
            add(HeaderRow("Hash Algorithm", clrHashAlgorithm(assembly.hashAlgorithm), assembly.recordRange))
        } ?: add(HeaderRow("Assembly", "none (module without manifest)", null))
        metadata.module?.let { module ->
            add(HeaderRow("Module", module.name, module.recordRange))
            module.mvid?.let { add(HeaderRow("MVID", it, module.recordRange)) }
        }
        add(HeaderRow("Types", metadata.typeDefs.size.toString(), tableRange(metadata, "TypeDef")))
        add(HeaderRow("Methods", metadata.methods.size.toString(), tableRange(metadata, "MethodDef")))
        add(HeaderRow("Fields", metadata.fields.size.toString(), tableRange(metadata, "Field")))
        add(HeaderRow("Assembly References", metadata.assemblyRefs.size.toString(), tableRange(metadata, "AssemblyRef")))
        add(HeaderRow("Type References", metadata.typeRefs.size.toString(), tableRange(metadata, "TypeRef")))
        add(HeaderRow("Member References", metadata.memberRefs.size.toString(), tableRange(metadata, "MemberRef")))
        add(HeaderRow("Managed Resources", metadata.manifestResources.size.toString(), tableRange(metadata, "ManifestResource")))
        add(HeaderRow("User Strings", metadata.userStrings.size.toString(), metadata.streams.firstOrNull { it.name == "#US" }?.contentRange))
    }
    return keyValueTab(CLR_OVERVIEW_TAB_NAME, rows, labelWidth = ColumnWidth.Fill(0.32f))
}

private fun clrTypesTab(metadata: ClrMetadata): TableTabDescriptor? {
    val types = metadata.typeDefs.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Types",
        columns = listOf(
            col("Token") { tokenCell(types[it].token) },
            col("Type", ColumnWidth.Fill(0.42f)) { CellValue(clrTypeDisplayName(types[it])) },
            col("Kind") { CellValue(types[it].kind.label) },
            col("Modifiers", ColumnWidth.MeasuredMin("protected internal abstract")) { CellValue(clrTypeModifiers(types[it])) },
            col("Base Type", ColumnWidth.Fill()) { CellValue(types[it].extends.orEmpty()) },
        ),
        rowCount = types.size,
        rowSelectionProvider = { types.getOrNull(it)?.let { type -> selection(type.recordRange) } },
        childrenProvider = { index -> types.getOrNull(index)?.let { clrTypeMembers(metadata, it) } },
        alwaysShowSearch = true,
        searchPlaceholder = "Search types",
        showIdColumn = false,
    )
}

private fun clrTypeMembers(metadata: ClrMetadata, type: ClrTypeDef): TableTabChildRows? {
    val fields = metadata.fieldsByType[type.row].orEmpty()
    val methods = metadata.methodsByType[type.row].orEmpty()
    if (fields.isEmpty() && methods.isEmpty()) return null
    return TableTabChildRows(
        columns = listOf(
            ColumnDescriptor("Token") { index ->
                if (index < fields.size) tokenCell(fields[index].token) else tokenCell(methods[index - fields.size].token)
            },
            ColumnDescriptor("Type", ColumnWidth.Fill(0.42f)) { index ->
                if (index < fields.size) CellValue("${fields[index].type} ${fields[index].name}")
                else CellValue(methods[index - fields.size].signature)
            },
            ColumnDescriptor("Kind") { index -> CellValue(if (index < fields.size) "field" else "method") },
            ColumnDescriptor("Modifiers", ColumnWidth.MeasuredMin("protected internal abstract")) { index ->
                if (index < fields.size) CellValue(clrFieldModifiers(fields[index]))
                else CellValue(clrMethodModifiers(methods[index - fields.size]))
            },
            ColumnDescriptor("Base Type", ColumnWidth.Fill()) { CellValue("") },
        ),
        rowCount = fields.size + methods.size,
        rowSelectionProvider = { index ->
            if (index < fields.size) fields.getOrNull(index)?.let { selection(it.recordRange) }
            else methods.getOrNull(index - fields.size)?.let(::methodSelection)
        },
    )
}

private fun clrMethodsTab(metadata: ClrMetadata, pdb: ClrPortablePdb?): TableTabDescriptor? {
    val methods = metadata.methods.takeIf { it.isNotEmpty() } ?: return null
    val typeNames = metadata.typeDefs.map(::clrTypeDisplayName)
    // First visible sequence point of each method, decoded on first use per row.
    val sources = HashMap<Int, Pair<String, String>?>()
    fun source(index: Int): Pair<String, String>? = sources.getOrPut(index) {
        val point = pdb?.sequencePoints(methods[index].row)?.firstOrNull { !it.isHidden } ?: return@getOrPut null
        val path = pdb.document(point.document)?.name.orEmpty()
        "${path.substringAfterLast('/').substringAfterLast('\\')}:${point.startLine}" to "$path:${point.startLine}:${point.startColumn}"
    }
    val sourceColumn = pdb?.let {
        col("Source", ColumnWidth.Fill(0.16f)) { index -> source(index).let { CellValue(it?.first.orEmpty(), tooltip = it?.second) } }
    }
    return TableTabDescriptor(
        name = "Methods",
        columns = listOfNotNull(
            col("Token") { tokenCell(methods[it].token) },
            col("Type", ColumnWidth.Fill(0.28f)) { CellValue(typeNames.getOrNull(methods[it].declaringTypeRow - 1).orEmpty()) },
            col("Method", ColumnWidth.Fill(0.42f)) { CellValue(methods[it].signature) },
            col("Modifiers", ColumnWidth.MeasuredMin("protected internal abstract")) { CellValue(clrMethodModifiers(methods[it])) },
            col("RVA") { methods[it].rva.let { rva -> CellValue(rva.toLong(), if (rva == 0u) "" else hex(rva)) } },
            sourceColumn,
            col("IL Size", ColumnWidth.Fill()) { methods[it].body?.codeSize.let { size -> CellValue(size ?: -1, size?.toString() ?: "") } },
        ),
        rowCount = methods.size,
        rowSelectionProvider = { methods.getOrNull(it)?.let(::methodSelection) },
        alwaysShowSearch = true,
        searchPlaceholder = "Search methods",
        showIdColumn = false,
    )
}

private fun clrAssemblyRefsTab(metadata: ClrMetadata): TableTabDescriptor? {
    val refs = metadata.assemblyRefs.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Assembly Refs",
        columns = listOf(
            col("Token") { tokenCell(refs[it].token) },
            col("Name", ColumnWidth.Fill(0.36f)) { CellValue(refs[it].name) },
            col("Version") { CellValue(refs[it].version.toString()) },
            col("Culture") { CellValue(refs[it].culture.ifEmpty { "neutral" }) },
            col("Public Key Token", ColumnWidth.Fill()) {
                val key = refs[it].publicKeyOrToken
                CellValue(if (refs[it].flags and PUBLIC_KEY_FLAG != 0u) "full key, ${key.size} bytes" else key.toHexString())
            },
        ),
        rowCount = refs.size,
        rowSelectionProvider = { refs.getOrNull(it)?.let { ref -> selection(ref.recordRange) } },
        showIdColumn = false,
    )
}

internal fun clrStreamsTab(metadata: ClrMetadata): TableTabDescriptor? {
    val streams = metadata.streams.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Streams",
        columns = listOf(
            col("Index") { CellValue(streams[it].index) },
            col("Name", ColumnWidth.Fill(0.30f)) { CellValue(streams[it].name) },
            col("Offset") { CellValue(streams[it].offset.toLong(), hex(streams[it].offset)) },
            col("Size") { CellValue(streams[it].size.toLong(), hex(streams[it].size)) },
            col("File Offset", ColumnWidth.Fill()) { CellValue(streams[it].contentRange?.let { range -> hex(range.offset) } ?: "outside metadata") },
        ),
        rowCount = streams.size,
        rowSelectionProvider = { streams.getOrNull(it)?.let { stream -> selection(stream.contentRange ?: stream.headerRange) } },
    )
}

internal fun clrTablesTab(metadata: ClrMetadata): TableTabDescriptor? {
    val tables = metadata.tables.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Tables",
        columns = listOf(
            col("Id") { CellValue(tables[it].id, hex(tables[it].id)) },
            col("Name", ColumnWidth.Fill(0.36f)) { CellValue(tables[it].name) },
            col("Rows") { CellValue(tables[it].rowCount) },
            col("Row Size") { CellValue(tables[it].rowSize) },
            col("Sorted") { CellValue(yesNo(tables[it].sorted)) },
            col("File Offset", ColumnWidth.Fill()) { CellValue(tables[it].range?.let { range -> hex(range.offset) } ?: "unknown") },
        ),
        rowCount = tables.size,
        rowSelectionProvider = { tables.getOrNull(it)?.range?.let(::selection) },
        showIdColumn = false,
    )
}

private fun clrTypeRefsTab(metadata: ClrMetadata): TableTabDescriptor? {
    val refs = metadata.typeRefs.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Type Refs",
        columns = listOf(
            col("Token") { tokenCell(refs[it].token) },
            col("Type", ColumnWidth.Fill(0.55f)) { CellValue(refs[it].fullName) },
            col("Scope", ColumnWidth.Fill()) { CellValue(refs[it].scope) },
        ),
        rowCount = refs.size,
        rowSelectionProvider = { refs.getOrNull(it)?.let { ref -> selection(ref.recordRange) } },
        alwaysShowSearch = true,
        searchPlaceholder = "Search type references",
        showIdColumn = false,
    )
}

private fun clrMemberRefsTab(metadata: ClrMetadata): TableTabDescriptor? {
    val refs = metadata.memberRefs.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Member Refs",
        columns = listOf(
            col("Token") { tokenCell(refs[it].token) },
            col("Parent", ColumnWidth.Fill(0.36f)) { CellValue(refs[it].parent) },
            col("Member", ColumnWidth.Fill()) { CellValue(if (refs[it].isField) "${refs[it].signature} ${refs[it].name}" else refs[it].signature) },
            col("Kind") { CellValue(if (refs[it].isField) "field" else "method") },
        ),
        rowCount = refs.size,
        rowSelectionProvider = { refs.getOrNull(it)?.let { ref -> selection(ref.recordRange) } },
        alwaysShowSearch = true,
        searchPlaceholder = "Search member references",
        showIdColumn = false,
    )
}

private fun clrUserStringsTab(metadata: ClrMetadata): TableTabDescriptor? {
    val strings = metadata.userStrings.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "User Strings",
        columns = listOf(
            col("Token") { tokenCell(strings[it].token) },
            col("Length") { CellValue(strings[it].value.length) },
            col("Value", ColumnWidth.Fill()) { CellValue(strings[it].value.toDisplayLiteral()) },
        ),
        rowCount = strings.size,
        rowSelectionProvider = { strings.getOrNull(it)?.let { string -> selection(string.range) } },
        alwaysShowSearch = true,
        searchPlaceholder = "Search strings",
        showIdColumn = false,
    )
}

private fun clrResourcesTab(provider: PeBinaryProvider, metadata: ClrMetadata): TableTabDescriptor? {
    val resources = metadata.manifestResources.takeIf { it.isNotEmpty() } ?: return null
    // Embedded `.resources` sets expand to their entries. Decoded here, while the descriptor is
    // built (a background job in IntelliJ), rather than on demand: tree tables ask every root row
    // for its children just to draw the expand arrow, and they do that while composing the table.
    val entries: List<TableTabChildRows?> = resources.map { resource ->
        val data = resource.dataRange?.takeIf { resource.name.endsWith(".resources", ignoreCase = true) } ?: return@map null
        ClrResourcesParser.parse(provider.bytes, data)?.let(::clrResourceChildRows)
    }
    return TableTabDescriptor(
        name = "Resources",
        columns = listOf(
            col("Name", ColumnWidth.Fill(0.45f)) { CellValue(resources[it].name) },
            col("Visibility") { CellValue(if (resources[it].isPublic) "public" else "private") },
            col("Location", ColumnWidth.Fill(0.25f)) { CellValue(resources[it].implementation) },
            col("Size", ColumnWidth.Fill()) { resources[it].size.let { size -> CellValue(size?.toLong() ?: -1L, size?.toString() ?: "") } },
        ),
        rowCount = resources.size,
        rowSelectionProvider = { resources.getOrNull(it)?.let { resource -> selection(resource.dataRange ?: resource.recordRange) } },
        childrenProvider = { index -> entries.getOrNull(index) },
    )
}

private fun methodSelection(method: ClrMethodDef): RowSelectionData = selection(method.body?.range ?: method.recordRange)

private fun tableRange(metadata: ClrMetadata, name: String): NativeByteRange? =
    metadata.tables.firstOrNull { it.name == name }?.range

private fun tokenCell(token: UInt): CellValue<Long> =
    CellValue(token.toLong(), "0x" + token.toString(16).uppercase().padStart(8, '0'))

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

private fun ByteArray.toHexString(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }

/** Escapes control characters so multi-line user strings stay on one table row. */
internal fun String.toDisplayLiteral(): String = buildString {
    for (char in this@toDisplayLiteral) {
        when (char) {
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
        }
    }
}

/** Type name with generic parameters spelled out: `Box`1` becomes `Box<T>`. */
internal fun clrTypeDisplayName(type: ClrTypeDef): String {
    if (type.genericParameters.isEmpty()) return type.fullName
    return type.fullName.substringBeforeLast('`') + type.genericParameters.joinToString(", ", "<", ">")
}

internal fun clrHeaderFlagsLabel(flags: UInt): String {
    val names = buildList {
        if (flags and ClrHeaderFlags.IL_ONLY != 0u) add("IL only")
        if (flags and ClrHeaderFlags.REQUIRED_32BIT != 0u) add("32-bit required")
        if (flags and ClrHeaderFlags.IL_LIBRARY != 0u) add("IL library")
        if (flags and ClrHeaderFlags.STRONG_NAME_SIGNED != 0u) add("strong-name signed")
        if (flags and ClrHeaderFlags.NATIVE_ENTRYPOINT != 0u) add("native entry point")
        if (flags and ClrHeaderFlags.TRACK_DEBUG_DATA != 0u) add("track debug data")
        if (flags and ClrHeaderFlags.PREFERRED_32BIT != 0u) add("32-bit preferred")
    }
    return if (names.isEmpty()) hex(flags) else "${names.joinToString(", ")} (${hex(flags)})"
}

/**
 * The platform target as the C# compiler names it (`/platform:`), derived from the COFF machine and
 * CLI flags. ReadyToRun images for non-Windows targets XOR the machine with an OS constant
 * (`IMAGE_FILE_MACHINE_*` overrides in the runtime), and clear the IL-only flag like mixed-mode
 * images do, so both are decoded before the flags are read.
 */
internal fun clrPlatform(machine: Int?, header: ClrCliHeader, readyToRun: Boolean): String {
    if (machine == null) return "unknown"
    val (os, nativeMachine) = if (readyToRun) ClrReadyToRunParser.target(machine) else ("Windows" to machine)
    val architecture = when (nativeMachine) {
        0x014C -> when {
            readyToRun -> "x86"
            !header.isIlOnly -> "x86 (mixed mode)"
            header.requires32Bit && header.prefers32Bit -> "AnyCPU (32-bit preferred)"
            header.requires32Bit -> "x86"
            else -> "AnyCPU"
        }
        0x8664 -> "x64"
        0xAA64 -> "ARM64"
        0x01C4 -> "ARM"
        0x6264 -> "LoongArch64"
        0x5064 -> "RISC-V 64"
        else -> machineName(nativeMachine)
    }
    return if (readyToRun) "$architecture (ReadyToRun, $os)" else architecture
}

private fun clrHashAlgorithm(algorithm: UInt): String = when (algorithm) {
    0u -> "none"
    0x8003u -> "MD5 (${hex(algorithm)})"
    0x8004u -> "SHA1 (${hex(algorithm)})"
    0x800Cu -> "SHA256 (${hex(algorithm)})"
    0x800Du -> "SHA384 (${hex(algorithm)})"
    0x800Eu -> "SHA512 (${hex(algorithm)})"
    else -> hex(algorithm)
}

internal fun clrTypeModifiers(type: ClrTypeDef): String {
    val flags = type.flags.toInt()
    val visibility = when (flags and 0x7) {
        0 -> "internal"
        1, 2 -> "public"
        3 -> "private"
        4 -> "protected"
        5 -> "internal"
        6 -> "private protected"
        else -> "protected internal"
    }
    val isAbstract = flags and 0x80 != 0
    val isSealed = flags and 0x100 != 0
    val modifier = when {
        type.kind != ClrTypeKind.Class -> null
        isAbstract && isSealed -> "static"
        isAbstract -> "abstract"
        isSealed -> "sealed"
        else -> null
    }
    return listOfNotNull(visibility, modifier).joinToString(" ")
}

internal fun clrMethodModifiers(method: ClrMethodDef): String = buildList {
    add(memberAccess(method.flags))
    if (method.flags and 0x10 != 0) add("static")
    if (method.flags and 0x400 != 0) add("abstract")
    else if (method.flags and 0x40 != 0) add(if (method.flags and 0x20 != 0) "sealed virtual" else "virtual")
    if (method.flags and 0x2000 != 0 || method.implFlags and 0x1000 != 0) add("extern")
    if (method.implFlags and 0x3 == 0x3) add("runtime")
}.joinToString(" ")

internal fun clrFieldModifiers(field: ClrField): String = buildList {
    add(memberAccess(field.flags))
    when {
        field.flags and 0x40 != 0 -> add("const")
        field.flags and 0x10 != 0 -> add("static")
    }
    if (field.flags and 0x20 != 0) add("readonly")
}.joinToString(" ")

private fun memberAccess(flags: Int): String = when (flags and 0x7) {
    1 -> "private"
    2 -> "private protected"
    3 -> "internal"
    4 -> "protected"
    5 -> "protected internal"
    6 -> "public"
    else -> "compiler-controlled"
}

private const val PUBLIC_KEY_FLAG = 0x0001u

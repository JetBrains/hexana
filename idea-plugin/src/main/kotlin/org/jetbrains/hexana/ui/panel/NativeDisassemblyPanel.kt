package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ShowSettingsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexanaConfigurable
import org.jetbrains.hexana.HexanaSettings
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpDecodedFunction
import org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpCommands
import org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpDisassemblyParser
import org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpDiscovery
import org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpNativeDisassemblyBuilder
import org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpRunResult
import org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpRunner
import org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpSyntaxMode
import org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpValidInstruction
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.HexanaSourceLocation
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.NativeFunctionRecord
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.elf.ElfAddressMapper
import org.jetbrains.hexana.providers.macho.MachOSection
import org.jetbrains.hexana.providers.pe.PeSection
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.nio.file.Path

@Composable
internal fun NativeDisassemblyPanel(provider: NativeBinaryProvider, state: HexViewStateHolder) {
    var result by remember(provider) { mutableStateOf<NativeDisassemblyPanelState>(NativeDisassemblyPanelState.Loading) }
    var syntaxMode by remember(provider) { mutableStateOf(LlvmObjdumpSyntaxMode.ArchitectureDefault) }
    var selectedFunctionName by remember(provider) { mutableStateOf<String?>(null) }
    var reloadRequest by remember(provider) { mutableStateOf(0) }
    var functions by remember(provider) { mutableStateOf<List<NativeFunctionRecord>?>(null) }
    val fullDisassemblyEnabled = remember(provider) { provider.bytes.size <= MAX_DISASSEMBLY_FILE_BYTES }

    LaunchedEffect(provider) {
        val loadedFunctions = withContext(Dispatchers.IO) {
            provider.nativeFunctions
                .orEmpty()
                .filter { function -> !function.imported && function.name.isNotBlank() }
                .distinctBy { it.name }
        }
        functions = loadedFunctions
        if (!fullDisassemblyEnabled && selectedFunctionName == null) {
            selectedFunctionName = loadedFunctions.firstOrNull()?.name
        }
    }

    LaunchedEffect(provider, syntaxMode, selectedFunctionName, reloadRequest, functions) {
        if (!fullDisassemblyEnabled && functions == null) {
            result = NativeDisassemblyPanelState.Loading
            return@LaunchedEffect
        }
        if (!fullDisassemblyEnabled && selectedFunctionName == null) {
            result = NativeDisassemblyPanelState.FunctionSelectionRequired(provider.bytes.size)
            return@LaunchedEffect
        }
        result = NativeDisassemblyPanelState.Loading
        result = withContext(Dispatchers.IO) {
            loadNativeDisassembly(provider, syntaxMode, selectedFunctionName)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        NativeDisassemblySyntaxSelector(syntaxMode) { syntaxMode = it }
        functions?.let { loadedFunctions ->
            NativeDisassemblyFunctionSelector(
                functions = loadedFunctions,
                selectedFunctionName = selectedFunctionName,
                fullDisassemblyEnabled = fullDisassemblyEnabled,
            ) { selectedFunctionName = it }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (val current = result) {
                NativeDisassemblyPanelState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                is NativeDisassemblyPanelState.Error -> NativeDisassemblyError(current.message, state) {
                    reloadRequest++
                }
                is NativeDisassemblyPanelState.FunctionSelectionRequired -> NativeDisassemblyFunctionSelectionRequired(current.fileSize)
                is NativeDisassemblyPanelState.Loaded -> NativeDisassemblyListing(current.unit, state, provider.sourcePath)
            }
        }
    }
}

@Composable
private fun NativeDisassemblyError(
    message: String,
    state: HexViewStateHolder,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.padding(8.dp)) {
        Text(message.toUiMultiline(MAX_ERROR_TEXT_CHARS))
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Link("Open Hexana settings", onClick = {
                if (!state.project.isDisposed) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(state.project, HexanaConfigurable::class.java)
                }
            })
            Text("  ")
            Link("Retry", onClick = onRetry)
        }
    }
}

@Composable
private fun NativeDisassemblyFunctionSelectionRequired(fileSize: Int) {
    Text(
        "Disassembly is in function-level mode for this ${formatBytes(fileSize)} file. Select a function above.",
        modifier = Modifier.padding(8.dp),
    )
}

@Composable
private fun NativeDisassemblyFunctionSelector(
    functions: List<NativeFunctionRecord>,
    selectedFunctionName: String?,
    fullDisassemblyEnabled: Boolean,
    onFunctionSelected: (String?) -> Unit,
) {
    if (functions.isEmpty()) return

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Text("Function:")
        }
        if (fullDisassemblyEnabled) {
            val allLabel = if (selectedFunctionName == null) "[All]" else "All"
            item {
                Text(
                    allLabel,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable { onFunctionSelected(null) },
                )
            }
        }
        items(functions, key = { it.name }) { function ->
            val displayName = function.name.toUiSingleLine(MAX_FUNCTION_SELECTOR_LABEL_CHARS)
            val label = if (function.name == selectedFunctionName) "[$displayName]" else displayName
            Text(
                label,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable { onFunctionSelected(function.name) },
            )
        }
    }
}

@Composable
private fun NativeDisassemblySyntaxSelector(
    selectedMode: LlvmObjdumpSyntaxMode,
    onModeSelected: (LlvmObjdumpSyntaxMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text("Syntax:")
        LlvmObjdumpSyntaxMode.entries.forEach { mode ->
            val label = if (mode == selectedMode) "[${mode.label}]" else mode.label
            Text(
                label,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable { onModeSelected(mode) },
            )
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun NativeDisassemblyListing(
    unit: NativeDisassemblyUnit,
    state: HexViewStateHolder,
    binaryPath: String?,
) {
    if (unit.instructions.isEmpty()) {
        Text("Disassembly: absent. No instructions were returned by llvm-objdump.")
        return
    }
    var searchQuery by remember(unit) { mutableStateOf(TextFieldValue("")) }
    val listingRows = remember(unit, searchQuery.text) {
        unit.toListingRows(MAX_RENDERED_DISASSEMBLY_INSTRUCTIONS, searchQuery.text)
    }
    val renderedInstructions = remember(listingRows) {
        listingRows.mapNotNull { row -> (row as? DisassemblyListingRow.Instruction)?.instruction }
    }
    val rowIndexByAddress = remember(listingRows) {
        listingRows.mapIndexedNotNull { rowIndex, row ->
            val instruction = (row as? DisassemblyListingRow.Instruction)?.instruction
            instruction?.address?.virtualAddress?.let { address -> address to rowIndex }
        }.toMap()
    }
    val selectedInstructionAddress = selectedInstruction(renderedInstructions, state)?.address?.virtualAddress
    var navigatedInstructionAddress by remember(unit, searchQuery.text) { mutableStateOf<ULong?>(null) }
    val highlightedInstructionAddress = navigatedInstructionAddress ?: selectedInstructionAddress
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        if (unit.instructions.size > MAX_RENDERED_DISASSEMBLY_INSTRUCTIONS) {
            Text(
                "Disassembly view is truncated to the first $MAX_RENDERED_DISASSEMBLY_INSTRUCTIONS instructions.",
                modifier = Modifier.padding(8.dp),
            )
        }

        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text("Search instruction, symbol, address, bytes") },
            leadingIcon = {
                Icon(
                    AllIconsKeys.Actions.Search,
                    contentDescription = null,
                    iconClass = AllIcons::class.java,
                    modifier = Modifier.size(16.dp),
                )
            },
            trailingIcon = if (searchQuery.text.isNotEmpty()) {
                {
                    IconButton(onClick = { searchQuery = TextFieldValue("") }) {
                        Icon(
                            AllIconsKeys.Actions.Close,
                            contentDescription = "Clear search",
                            iconClass = AllIcons::class.java,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
                null
            },
        )

        if (listingRows.isEmpty()) {
            Text("No disassembly entries match the search.", modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                itemsIndexed(listingRows) { _, row ->
                    when (row) {
                        is DisassemblyListingRow.FunctionHeader -> {
                            Text(
                                "${row.function.name.toUiSingleLine(MAX_DISASSEMBLY_TEXT_CHARS)}:",
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                style = TextStyle(
                                    color = HexanaTheme.textColor,
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                        is DisassemblyListingRow.Instruction -> {
                            NativeInstructionListingRow(
                                instruction = row.instruction,
                                selected = row.instruction.address.virtualAddress == highlightedInstructionAddress,
                                targetRowIndex = row.instruction.primaryXref()
                                    ?.target
                                    ?.virtualAddress
                                    ?.let { rowIndexByAddress[it] },
                                onNavigateToTarget = { targetAddress, targetRowIndex ->
                                    navigatedInstructionAddress = targetAddress
                                    scope.launch {
                                        listState.animateScrollToItem(targetRowIndex)
                                    }
                                },
                                state = state,
                                binaryPath = binaryPath,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NativeInstructionListingRow(
    instruction: NativeInstruction,
    selected: Boolean,
    targetRowIndex: Int?,
    onNavigateToTarget: (targetAddress: ULong, targetRowIndex: Int) -> Unit,
    state: HexViewStateHolder,
    binaryPath: String?,
) {
    val backgroundColor = if (selected) HexanaTheme.hexCellHoverActiveColor else HexanaTheme.backgroundColor
    val offset = instruction.address.fileOffset
    val size = instruction.bytes.size.takeIf { it > 0 } ?: 1
    val targetXref = instruction.primaryXref()
    val targetAddress = targetXref?.target?.virtualAddress
    val targetOffset = targetXref?.target?.fileOffset

    val row = @Composable {
        Column(
            modifier = Modifier
                .background(backgroundColor)
                .clickable(enabled = offset != null) {
                    if (offset != null) {
                        state.scrollHexViewTo(offset.toInt(), size)
                    }
                }
                .padding(vertical = 1.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    instruction.toCodeText(),
                    style = TextStyle(
                        color = HexanaTheme.textColor,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                if (targetXref != null && targetAddress != null && (targetRowIndex != null || targetOffset != null)) {
                    val targetSize = 1
                    Tooltip(tooltip = { Text("Go to target ${targetXref.targetText()}") }) {
                        Icon(
                            AllIconsKeys.Actions.NextOccurence,
                            contentDescription = "Go to target",
                            iconClass = AllIcons::class.java,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(14.dp)
                                .clickable {
                                    if (targetRowIndex != null) {
                                        onNavigateToTarget(targetAddress, targetRowIndex)
                                    }
                                    if (targetOffset != null) {
                                        state.scrollHexViewTo(targetOffset.toInt(), targetSize)
                                    }
                                },
                        )
                    }
                }
                instruction.sourceLocation?.let { sourceLocation ->
                    Link(
                        sourceLocation.toSourceLabel().toUiSingleLine(MAX_SOURCE_LABEL_CHARS),
                        onClick = { openNativeSourceLocation(sourceLocation.toHexanaSourceLocation(), state, binaryPath) },
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }

    val tooltip = instruction.toMetadataText().toUiMultiline(MAX_TOOLTIP_TEXT_CHARS)
    if (tooltip.isNotEmpty()) {
        Tooltip(tooltip = { Text(tooltip) }) {
            row()
        }
    } else {
        row()
    }
}

private sealed interface DisassemblyListingRow {
    data class FunctionHeader(val function: NativeFunction) : DisassemblyListingRow
    data class Instruction(val instruction: NativeInstruction) : DisassemblyListingRow
}

private data class NativeDisassemblyUnit(
    val functions: List<NativeFunction>,
) {
    val instructions: List<NativeInstruction> = functions.flatMap { it.instructions }
}

private data class NativeFunction(
    val name: String,
    val entry: NativeAddress,
    val instructions: List<NativeInstruction>,
)

private data class NativeInstruction(
    val address: NativeAddress,
    val bytes: List<Int>,
    val mnemonic: String,
    val operandsText: String,
    val assemblyText: String,
    val symbol: String?,
    val sourceLocation: DisassemblySourceLocation?,
    val sectionName: String?,
    val relocation: NativeRelocationAnnotation?,
    val xrefs: List<NativeXref>,
)

private data class NativeAddress(
    val virtualAddress: ULong,
    val fileOffset: Long?,
)

private data class NativeRelocationAnnotation(
    val type: String,
    val symbolName: String?,
)

private data class NativeXref(
    val kind: NativeXrefKind,
    val target: NativeAddress?,
    val symbolName: String?,
)

private enum class NativeXrefKind {
    Call,
    Jump,
}

private data class DisassemblySourceLocation(
    val filePath: String,
    val line: Int,
    val column: Int?,
)

private fun NativeDisassemblyUnit.toListingRows(
    instructionLimit: Int,
    searchQuery: String,
): List<DisassemblyListingRow> {
    val rows = mutableListOf<DisassemblyListingRow>()
    var renderedInstructions = 0
    val normalizedQuery = searchQuery.trim().lowercase()

    for (function in functions) {
        if (renderedInstructions >= instructionLimit) break
        val matchingInstructions = function.instructions.filter { instruction ->
            normalizedQuery.isEmpty() ||
                    function.name.contains(normalizedQuery, ignoreCase = true) ||
                    instruction.matchesSearchQuery(normalizedQuery)
        }
        if (matchingInstructions.isEmpty()) continue

        rows.add(DisassemblyListingRow.FunctionHeader(function))

        for (instruction in matchingInstructions) {
            if (renderedInstructions >= instructionLimit) break
            rows.add(DisassemblyListingRow.Instruction(instruction))
            renderedInstructions++
        }
    }

    return rows
}

private fun NativeInstruction.matchesSearchQuery(query: String): Boolean =
    listOfNotNull(
        hex(address.virtualAddress),
        address.fileOffset?.let { hex(it) },
        bytes.toByteText(),
        mnemonic,
        operandsText,
        assemblyText,
        symbol,
        sourceLocation?.toSourceLabel(),
        sectionName,
        relocation?.type,
        relocation?.symbolName,
        primaryXref()?.targetText(),
    ).any { it.contains(query, ignoreCase = true) }

private fun NativeInstruction.toCodeText(): String {
    val addressText = address.virtualAddress.toString(16).uppercase().padStart(8)
    return "  $addressText:  $assemblyText"
}

private fun NativeInstruction.toMetadataText(): String {
    val metadata = mutableListOf<String>()
    metadata.add("address=${hex(address.virtualAddress)}")
    address.fileOffset?.let { metadata.add("file offset=${hex(it)}") }
    if (bytes.isNotEmpty()) metadata.add("bytes=${bytes.toByteText()}")
    sourceLocation?.let { metadata.add("source=${it.filePath}:${it.line}:${it.column}") }
    sectionName?.takeIf { it.isNotBlank() }?.let { metadata.add("section=$it") }
    relocation?.let { relocation ->
        val symbol = relocation.symbolName?.takeIf { it.isNotBlank() }?.let { " symbol=$it" }.orEmpty()
        metadata.add("relocation=${relocation.type}$symbol")
    }
    primaryXref()?.let { xref ->
        metadata.add("${xref.kind.name.lowercase()}=${xref.targetText()}")
    }

    return metadata.joinToString("\n")
}

private suspend fun loadNativeDisassembly(
    provider: NativeBinaryProvider,
    syntaxMode: LlvmObjdumpSyntaxMode,
    functionName: String?,
): NativeDisassemblyPanelState {
    val sourcePath = provider.sourcePath
        ?: return NativeDisassemblyPanelState.Error("Disassembly: unavailable. Native file path is not available for this in-memory artifact.")

    val file = Path.of(sourcePath)
    val command = if (functionName != null) {
        LlvmObjdumpCommands.disassembleSymbol(file, functionName, syntaxMode)
    } else {
        LlvmObjdumpCommands.disassembleExecutableSections(file, syntaxMode)
    }
    return when (val result = LlvmObjdumpRunner.discoverProbeAndRun(
        command = command,
        discovery = { extraDirectories ->
            LlvmObjdumpDiscovery.executableCandidates(
                explicitPath = HexanaSettings.getInstance().llvmObjdumpPath,
                toolchainDirectories = emptyList(),
                extraDirectories = extraDirectories,
            )
        },
    )) {
        is LlvmObjdumpRunResult.Success -> {
            val disassembly = LlvmObjdumpDisassemblyParser.parse(result.stdout)
            NativeDisassemblyPanelState.Loaded(
                LlvmObjdumpNativeDisassemblyBuilder
                    .fromLlvmObjdump(disassembly, arch = provider.disassemblyArch())
                    .toNativeDisassemblyUnit(provider)
            )
        }
        is LlvmObjdumpRunResult.Failure -> {
            NativeDisassemblyPanelState.Error(
                buildString {
                    append("Disassembly: unavailable. ")
                    append(result.message)
                    val detail = result.stderr.ifBlank { result.stdout }.trim()
                    if (detail.isNotEmpty()) {
                        append("\n")
                        append(detail)
                    }
                }
            )
        }
    }
}

private sealed interface NativeDisassemblyPanelState {
    data object Loading : NativeDisassemblyPanelState
    data class FunctionSelectionRequired(val fileSize: Int) : NativeDisassemblyPanelState
    data class Loaded(val unit: NativeDisassemblyUnit) : NativeDisassemblyPanelState
    data class Error(val message: String) : NativeDisassemblyPanelState
}

private fun org.jetbrains.hexana.disassembly.llvmobjdump.LlvmObjdumpDisassemblyResult.toNativeDisassemblyUnit(
    provider: NativeBinaryProvider,
): NativeDisassemblyUnit =
    NativeDisassemblyUnit(functions = functions.map { it.toNativeFunction(provider) })

private fun LlvmObjdumpDecodedFunction.toNativeFunction(provider: NativeBinaryProvider): NativeFunction =
    NativeFunction(
        name = name,
        entry = NativeAddress(entry, provider.disassemblyAddressToFileOffset(entry, null)),
        instructions = instructions.map { instruction -> instruction.toNativeInstruction(provider, name) },
    )

private fun LlvmObjdumpValidInstruction.toNativeInstruction(
    provider: NativeBinaryProvider,
    functionName: String,
): NativeInstruction {
    val sectionName = provider.sectionNameForVirtualAddress(address)
    val fileOffset = provider.disassemblyAddressToFileOffset(address, sectionName)
    val sourceLocation = runCatching {
        provider.nativeSourceLocationProvider
            ?.getSourceLocationForVirtualAddress(address)
            ?.toDisassemblySourceLocation()
    }.getOrNull()

    return NativeInstruction(
        address = NativeAddress(address, fileOffset),
        bytes = bytes,
        mnemonic = mnemonic,
        operandsText = operandsText,
        assemblyText = assemblyText,
        symbol = functionName,
        sourceLocation = sourceLocation,
        sectionName = sectionName,
        relocation = provider.relocationForVirtualAddress(address),
        xrefs = nativeXrefs(provider),
    )
}

private fun LlvmObjdumpValidInstruction.nativeXrefs(provider: NativeBinaryProvider): List<NativeXref> {
    val calls = callTargets.map { target ->
        NativeXref(
            kind = NativeXrefKind.Call,
            target = NativeAddress(target, provider.virtualAddressToFileOffset(target)),
            symbolName = null,
        )
    }
    val jumps = branchTargets.map { target ->
        NativeXref(
            kind = NativeXrefKind.Jump,
            target = NativeAddress(target, provider.virtualAddressToFileOffset(target)),
            symbolName = null,
        )
    }
    return calls + jumps
}

private fun NativeBinaryProvider.virtualAddressToFileOffset(virtualAddress: ULong): Long? {
    when (this) {
        is ElfBinaryProvider -> {
            return ElfAddressMapper.virtualAddressToFileOffset(
                virtualAddress = virtualAddress,
                programHeaders = programHeaders.orEmpty(),
                sections = sections.orEmpty(),
            )?.toLongOrNull()
        }
        is MachOBinaryProvider -> {
            return sections.orEmpty()
                .firstNotNullOfOrNull { it.mapVirtualAddressToFileOffset(virtualAddress) }
                ?.toLongOrNull()
        }
        is PeBinaryProvider -> {
            return sections.orEmpty()
                .firstNotNullOfOrNull { it.mapVirtualAddressToFileOffset(virtualAddress, optionalHeader?.imageBase) }
                ?.toLongOrNull()
        }
        else -> return null
    }
}

private fun NativeBinaryProvider.disassemblyArch(): Arch =
    when (val format = nativeFormat) {
        is NativeBinaryFormat.Elf -> archFromElfMachine(format.machine, format.wordSize, (this as? ElfBinaryProvider)?.armProfile)
        is NativeBinaryFormat.Pe -> archFromPeMachine(format.machine)
        is NativeBinaryFormat.Coff -> archFromPeMachine(format.machine)
        is NativeBinaryFormat.MachO -> archFromMachOCpuType(format.cpuType)
        is NativeBinaryFormat.Archive,
        is NativeBinaryFormat.FatMachO,
        -> Arch.X86_64
    }

private fun archFromElfMachine(machine: Int?, wordSize: NativeBinaryWordSize, armProfile: Char? = null): Arch =
    when (machine) {
        0x03 -> Arch.X86
        0x28 -> when (armProfile) {
            'M' -> Arch.THUMB
            else -> Arch.ARM
        }
        0x3E -> Arch.X86_64
        0xB7 -> Arch.ARM64
        0xF3 -> if (wordSize == NativeBinaryWordSize.Bits32) Arch.RISC_V_32 else Arch.RISC_V_64
        else -> Arch.X86_64
    }

private fun archFromPeMachine(machine: Int?): Arch =
    when (machine) {
        0x014C -> Arch.X86
        0x01C0, 0x01C4 -> Arch.ARM
        0x8664 -> Arch.X86_64
        0xAA64 -> Arch.ARM64
        0x5032 -> Arch.RISC_V_32
        0x5064 -> Arch.RISC_V_64
        else -> Arch.X86_64
    }

private fun archFromMachOCpuType(cpuType: Int?): Arch =
    when (cpuType) {
        0x00000007 -> Arch.X86
        0x01000007 -> Arch.X86_64
        0x0000000C -> Arch.ARM
        0x0100000C -> Arch.ARM64
        0x01000018 -> Arch.RISC_V_64
        else -> Arch.X86_64
    }

private fun NativeBinaryProvider.disassemblyAddressToFileOffset(address: ULong, sectionName: String?): Long? {
    virtualAddressToFileOffset(address)?.let { return it }

    when (this) {
        is ElfBinaryProvider -> {
            val elfSection = sectionName
                ?.takeIf { it.isNotBlank() }
                ?.let { name -> sections.orEmpty().firstOrNull { section -> section.name == name } }
            if (elfSection != null) {
                if (address >= elfSection.size) return null
                return (elfSection.fileOffset + address).toLongOrNull()
            }
        }
        is MachOBinaryProvider -> {
            val machOSection = sectionName
                ?.takeIf { it.isNotBlank() }
                ?.let { name ->
                    sections.orEmpty().firstOrNull { it.matchesObjdumpSectionName(name) }
                }
            if (machOSection != null) {
                if (address >= machOSection.size) return null
                return (machOSection.fileOffset + address).toLongOrNull()
            }
        }
        is PeBinaryProvider -> {
            val peSection = sectionName
                ?.takeIf { it.isNotBlank() }
                ?.let { name -> sections.orEmpty().firstOrNull { it.name == name } }
            if (peSection != null) {
                if (address > UInt.MAX_VALUE.toULong()) return null
                val sectionOffset = address.toUInt()
                if (sectionOffset >= peSection.rawDataSize) return null
                return (peSection.rawDataPointer.toULong() + sectionOffset.toULong()).toLongOrNull()
            }
        }
        else -> Unit
    }
    return null
}

private fun NativeBinaryProvider.sectionNameForVirtualAddress(virtualAddress: ULong): String? {
    when (this) {
        is ElfBinaryProvider -> return sections.orEmpty().firstOrNull { section ->
            section.address != 0uL && section.size != 0uL &&
                    virtualAddress >= section.address && virtualAddress < section.address + section.size
        }?.name?.takeIf { it.isNotBlank() }
        is MachOBinaryProvider -> return sections.orEmpty().firstOrNull { section ->
            section.size != 0uL &&
                    virtualAddress >= section.address && virtualAddress < section.address + section.size
        }?.objdumpSectionName()
        is PeBinaryProvider -> return sections.orEmpty().firstOrNull { section ->
            section.mapVirtualAddressToFileOffset(virtualAddress, optionalHeader?.imageBase) != null
        }?.name?.takeIf { it.isNotBlank() }
        else -> return null
    }
}

private fun NativeBinaryProvider.relocationForVirtualAddress(virtualAddress: ULong): NativeRelocationAnnotation? {
    val elf = this as? ElfBinaryProvider ?: return null
    return elf.relocations.orEmpty().firstOrNull { it.offset == virtualAddress }?.let { relocation ->
        NativeRelocationAnnotation(
            type = hex(relocation.type),
            symbolName = relocation.symbol?.name?.takeIf { it.isNotBlank() },
        )
    }
}

private fun MachOSection.mapVirtualAddressToFileOffset(virtualAddress: ULong): ULong? {
    if (size == 0uL || virtualAddress < address) return null
    val delta = virtualAddress - address
    if (delta >= size) return null
    return fileOffset + delta
}

private fun MachOSection.matchesObjdumpSectionName(name: String): Boolean =
    name == this.name || name == objdumpSectionName()

private fun MachOSection.objdumpSectionName(): String =
    listOf(segmentName, name).filter { it.isNotBlank() }.joinToString(",")

private fun PeSection.mapVirtualAddressToFileOffset(virtualAddress: ULong, imageBase: ULong?): ULong? {
    val start = virtualAddress.toPeRva(imageBase) ?: return null
    val sectionVirtualAddress = this.virtualAddress.toULong()
    if (start < sectionVirtualAddress) return null
    val delta = start - sectionVirtualAddress
    val sectionSize = rawDataSize.takeIf { it != 0u } ?: virtualSize
    if (delta >= sectionSize.toULong()) return null
    return rawDataPointer.toULong() + delta
}

private fun ULong.toPeRva(imageBase: ULong?): ULong? =
    when {
        imageBase != null && this >= imageBase -> this - imageBase
        this <= UInt.MAX_VALUE.toULong() -> this
        else -> null
    }

private fun List<Int>.toByteText(): String =
    joinToString(" ") { it.toString(16).padStart(2, '0') }

private fun NativeInstruction.primaryXref(): NativeXref? =
    xrefs.firstOrNull { it.target != null }

private fun NativeXref.targetText(): String =
    listOfNotNull(
        target?.virtualAddress?.let { hex(it) },
        symbolName?.takeIf { it.isNotBlank() }?.let { "<$it>" },
    ).joinToString(" ")

private fun DisassemblySourceLocation.toSourceLabel(): String {
    val fileName = filePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { filePath }
    val lineText = line.takeIf { it > 0 } ?: 1
    return "$fileName:$lineText"
}

private fun DisassemblySourceLocation.toHexanaSourceLocation(): HexanaSourceLocation =
    HexanaSourceLocation(
        filePath = filePath,
        line = line,
        column = column ?: 1,
    )

private fun HexanaSourceLocation.toDisassemblySourceLocation(): DisassemblySourceLocation =
    DisassemblySourceLocation(
        filePath = filePath,
        line = line,
        column = column,
    )

private fun selectedInstruction(
    instructions: List<NativeInstruction>,
    state: HexViewStateHolder,
): NativeInstruction? {
    val selection = state.selection
    if (selection.start.line < 0 || selection.start.lineOffset < 0) return null

    val selectedFileOffset = selection.start.getByteOffset(state.bytesPerRow.value).toLong() + state.displayBaseOffset
    return instructions.firstOrNull { instruction ->
        val instructionOffset = instruction.address.fileOffset ?: return@firstOrNull false
        val instructionSize = instruction.bytes.size.takeIf { it > 0 } ?: 1
        selectedFileOffset >= instructionOffset && selectedFileOffset < instructionOffset + instructionSize
    }
}

private const val MAX_DISASSEMBLY_FILE_BYTES: Int = 1024 * 1024
private const val MAX_RENDERED_DISASSEMBLY_INSTRUCTIONS: Int = 100_000
private const val MAX_FUNCTION_SELECTOR_LABEL_CHARS: Int = 160
private const val MAX_DISASSEMBLY_TEXT_CHARS: Int = 8_192
private const val MAX_SOURCE_LABEL_CHARS: Int = 512
private const val MAX_TOOLTIP_TEXT_CHARS: Int = 16_384
private const val MAX_ERROR_TEXT_CHARS: Int = 16_384

private fun formatBytes(bytes: Int): String =
    if (bytes >= 1024 * 1024) {
        "${bytes / (1024 * 1024)} MiB"
    } else {
        "${bytes / 1024} KiB"
    }

private fun hex(value: Long): String = "0x${value.toString(16).uppercase()}"
private fun hex(value: UInt): String = "0x${value.toString(16).uppercase()}"
private fun hex(value: ULong): String = "0x${value.toString(16).uppercase()}"

private fun ULong.toLongOrNull(): Long? =
    if (this <= Long.MAX_VALUE.toULong()) toLong() else null

private fun String.toUiSingleLine(maxChars: Int): String =
    replace('\n', ' ')
        .replace('\r', ' ')
        .truncateForUi(maxChars)

private fun String.toUiMultiline(maxChars: Int): String =
    truncateForUi(maxChars)

private fun String.truncateForUi(maxChars: Int): String {
    require(maxChars >= ELLIPSIS.length)
    if (length <= maxChars) return this
    return take(maxChars - ELLIPSIS.length) + ELLIPSIS
}

private const val ELLIPSIS = "..."

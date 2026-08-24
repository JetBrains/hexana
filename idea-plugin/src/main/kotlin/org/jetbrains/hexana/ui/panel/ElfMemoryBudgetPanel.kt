package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.memory.BudgetSection
import org.jetbrains.hexana.providers.memory.CapacitySource
import org.jetbrains.hexana.providers.memory.MemoryBudget
import org.jetbrains.hexana.providers.memory.MemoryRegion
import org.jetbrains.hexana.providers.memory.MemoryRegionKind
import org.jetbrains.hexana.providers.memory.RegionUsage
import org.jetbrains.hexana.providers.memory.SectionTag
import org.jetbrains.hexana.providers.memory.computeMemoryBudget
import org.jetbrains.hexana.providers.memory.linkerMapMatchesElf
import org.jetbrains.hexana.providers.memory.siblingMapCandidates
import org.jetbrains.hexana.ui.MemoryBudgetEntry
import org.jetbrains.hexana.ui.MemoryBudgetSettings
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.hexana.shared.formatSizeLong

private sealed interface BudgetUiState {
    data object Loading : BudgetUiState
    data object NotEligible : BudgetUiState
    data class Loaded(
        val budget: MemoryBudget,
        val staleMapWarning: String?,
        val linkedSourceLabel: String?,
        val isManuallyLinked: Boolean,
    ) : BudgetUiState
}

/**
 * Compose panel that renders the [MemoryBudget] for an ELF binary.
 *
 * Data loading runs on [Dispatchers.IO]. Rendering is purely structural: no
 * analysis runs on the composition thread.
 */
@Composable
fun ElfMemoryBudgetPanel(
    provider: ElfBinaryProvider,
    state: HexViewStateHolder,
    file: VirtualFile?,
    project: Project,
) {
    var uiState by remember { mutableStateOf<BudgetUiState>(BudgetUiState.Loading) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var showManualDialog by remember { mutableStateOf(false) }

    val elfPath = file?.path ?: ""

    LaunchedEffect(provider, refreshTrigger) {
        uiState = BudgetUiState.Loading
        withContext(Dispatchers.IO) {
            val secs = provider.sections ?: run {
                uiState = BudgetUiState.NotEligible
                return@withContext
            }
            val hdr = provider.header ?: run {
                uiState = BudgetUiState.NotEligible
                return@withContext
            }

            // Resolve capacity source: persisted entry wins over auto-discovery.
            val persistedEntry = MemoryBudgetSettings.getInstance(project).getEntry(elfPath)
            var mapText: String? = null
            var dtsText: String? = null
            var manualRegions: List<MemoryRegion> = emptyList()
            var sourceLabel: String? = null
            var staleMapWarning: String? = null
            var isManuallyLinked = false

            when {
                persistedEntry != null && (persistedEntry.linkedFilePath ?: "").isNotEmpty() -> {
                    val linkedPath = persistedEntry.linkedFilePath!!
                    val linkedVf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByPath(linkedPath)
                    if (linkedVf != null && linkedVf.exists()) {
                        val text = linkedVf.contentsToByteArray().decodeToString()
                        if (linkedPath.endsWith(".dts")) {
                            dtsText = text
                        } else {
                            if (linkerMapMatchesElf(text, secs)) {
                                mapText = text
                            } else {
                                staleMapWarning =
                                    "${linkedVf.name} does not match this ELF (stale build?) - capacities from it were ignored"
                                dtsText = null
                            }
                        }
                        sourceLabel = linkedVf.name
                        isManuallyLinked = true
                    }
                }
                persistedEntry != null && (persistedEntry.manualFlashOrigin ?: "").isNotEmpty() -> {
                    manualRegions = buildManualRegions(persistedEntry)
                    isManuallyLinked = true
                }
                else -> {
                    // Auto-discovery: only for real filesystem VirtualFiles.
                    // file == null means an in-memory provider; skip discovery.
                    if (file != null) {
                        val parent = file.parent
                        if (parent != null) {
                            // Sibling *.map files first (expected names, then any other map,
                            // each validated against the ELF), then dts and build-tree layouts.
                            val expectedMapNames = setOf("${file.nameWithoutExtension}.map", "zephyr.map")
                            val candidates =
                                siblingMapCandidates(file.name, parent.children.map { it.name })
                                    .mapNotNull { parent.findChild(it) } +
                                    listOfNotNull(
                                        parent.findChild("zephyr.dts"),
                                        parent.findChild("zephyr")?.findChild("zephyr.map"),
                                        parent.findChild("zephyr")?.findChild("zephyr.dts"),
                                        parent.parent?.findChild("zephyr")?.findChild("zephyr.map"),
                                        parent.parent?.findChild("zephyr")?.findChild("zephyr.dts"),
                                    )
                            for (candidate in candidates) {
                                if (!candidate.exists()) continue
                                val text = candidate.contentsToByteArray().decodeToString()
                                if (candidate.name.endsWith(".dts")) {
                                    dtsText = text
                                    sourceLabel = candidate.name
                                    break
                                }
                                if (linkerMapMatchesElf(text, secs)) {
                                    mapText = text
                                    sourceLabel = candidate.name
                                    break
                                }
                                // A mismatched map only warrants a stale-build warning when its
                                // name ties it to this ELF; unrelated maps are skipped silently.
                                if (candidate.name in expectedMapNames && staleMapWarning == null) {
                                    staleMapWarning =
                                        "${candidate.name} does not match this ELF (stale build?) - capacities from it were ignored"
                                }
                            }
                        }
                    }
                }
            }

            val budget = provider.computeMemoryBudget(
                mapText = mapText,
                dtsText = dtsText,
                manualRegions = manualRegions,
                sourceLabel = sourceLabel,
            )
            if (budget == null) {
                uiState = BudgetUiState.NotEligible
            } else {
                uiState = BudgetUiState.Loaded(
                    budget = budget,
                    staleMapWarning = staleMapWarning,
                    linkedSourceLabel = sourceLabel,
                    isManuallyLinked = isManuallyLinked,
                )
            }
        }
    }

    when (val s = uiState) {
        BudgetUiState.Loading -> LoadingMessage()
        BudgetUiState.NotEligible -> InfoMessageGray("Not eligible for memory budget analysis.")
        is BudgetUiState.Loaded -> BudgetContent(
            budget = s.budget,
            staleMapWarning = s.staleMapWarning,
            linkedSourceLabel = s.linkedSourceLabel,
            isManuallyLinked = s.isManuallyLinked,
            provider = provider,
            state = state,
            file = file,
            project = project,
            onRefresh = { refreshTrigger++ },
            onLinkSource = { showManualDialog = false; showFileChooser(file, project, elfPath) { refreshTrigger++ } },
            onEnterManual = { showManualDialog = true },
        )
    }

    if (showManualDialog) {
        ManualRegionsDialog(
            initialEntry = MemoryBudgetSettings.getInstance(project).getEntry(elfPath),
            onConfirm = { fo, fs, ro, rs ->
                showManualDialog = false
                MemoryBudgetSettings.getInstance(project).setManualRegions(elfPath, fo, fs, ro, rs)
                refreshTrigger++
            },
            onDismiss = { showManualDialog = false },
        )
    }
}

@Composable
private fun LoadingMessage() {
    Box(modifier = Modifier.padding(8.dp)) {
        Text("Loading...", style = TextStyle(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun InfoMessageGray(text: String) {
    Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(text, style = TextStyle(color = Color.Gray, fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun BudgetContent(
    budget: MemoryBudget,
    staleMapWarning: String?,
    linkedSourceLabel: String?,
    isManuallyLinked: Boolean,
    provider: ElfBinaryProvider,
    state: HexViewStateHolder,
    file: VirtualFile?,
    project: Project,
    onRefresh: () -> Unit,
    onLinkSource: () -> Unit,
    onEnterManual: () -> Unit,
) {
    // Hoist collapsible state outside LazyColumn so remember{} calls are in composable scope.
    var notLoadedExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Warnings block at top.
            val allWarnings = buildList {
                staleMapWarning?.let { add(it) }
                addAll(budget.warnings)
            }
            if (allWarnings.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .background(Color(0xFF4A3000)),
                    ) {
                        for (w in allWarnings) {
                            Text(
                                "⚠ $w",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = TextStyle(
                                    color = Color(0xFFFFC107),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                ),
                            )
                        }
                    }
                }
            }

            // ROM regions.
            if (budget.romUsages.isNotEmpty()) {
                item { SectionGroupHeader("ROM / Flash") }
                for (usage in budget.romUsages) {
                    item { RegionUsageRow(usage, isRom = true, provider = provider, state = state) }
                }
            }

            // RAM regions.
            if (budget.ramUsages.isNotEmpty()) {
                item { SectionGroupHeader("RAM") }
                for (usage in budget.ramUsages) {
                    item { RegionUsageRow(usage, isRom = false, provider = provider, state = state) }
                }
            }

            // Unmapped sections.
            if (budget.unmapped.isNotEmpty()) {
                item { SectionGroupHeader("Unmapped (outside all regions)") }
                for (sec in budget.unmapped) {
                    item {
                        BudgetSectionRow(
                            section = sec,
                            isRomContext = false,
                            provider = provider,
                            state = state,
                        )
                    }
                }
            }

            // Not-loaded sections (debug/comment), collapsed by default.
            if (budget.notLoaded.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { notLoadedExpanded = !notLoadedExpanded }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (notLoadedExpanded) "▼" else "▶",
                            style = TextStyle(fontSize = 10.sp, color = Color.Gray),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Not loaded / debug / comment (${budget.notLoaded.size})",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color.Gray,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
                if (notLoadedExpanded) {
                    items(budget.notLoaded) { name ->
                        Text(
                            "  $name",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color.Gray,
                            ),
                        )
                    }
                }
            }
        }

        // Footer.
        BudgetFooter(
            capacitySourceDescription = budget.capacitySourceDescription,
            isManuallyLinked = isManuallyLinked,
            onLinkSource = onLinkSource,
            onEnterManual = onEnterManual,
        )
    }
}

@Composable
private fun SectionGroupHeader(label: String) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RegionUsageRow(
    usage: RegionUsage,
    isRom: Boolean,
    provider: ElfBinaryProvider,
    state: HexViewStateHolder,
) {
    var expanded by remember(usage.region.name) { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
        // Region header with progress bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "▼" else "▶",
                style = TextStyle(fontSize = 9.sp, color = Color.Gray),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                usage.region.name,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
            )
            Spacer(Modifier.width(8.dp))
            val regionLength = usage.region.length
            val usedLabel = formatSizeLong(usage.usedBytes.toLong())
            val capacityLabel = if (regionLength != null) {
                "of ${formatSizeLong(regionLength.toLong())}"
            } else {
                "(capacity unknown)"
            }
            val pctLabel = usage.percent?.let { "  (${String.format("%.1f", it)}%)" } ?: ""
            Tooltip(tooltip = {
                Text(
                    "${usage.usedBytes} B used" +
                        (if (regionLength != null) " of $regionLength B" else ""),
                    modifier = Modifier.padding(4.dp),
                )
            }) {
                Text(
                    "$usedLabel $capacityLabel$pctLabel",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
            }
        }

        // Progress bar (only when capacity is known).
        val regionLength = usage.region.length
        if (regionLength != null && regionLength > 0uL) {
            val pct = (usage.percent ?: 0.0).coerceIn(0.0, 100.0)
            val fraction = (pct / 100.0).toFloat()
            val barColor = when {
                pct >= 95.0 -> Color(0xFFE53935)
                pct >= 80.0 -> Color(0xFFFFA000)
                else -> Color(0xFF1976D2)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Color.Gray.copy(alpha = 0.2f))
                    .padding(bottom = 2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(8.dp)
                        .background(barColor),
                )
            }
        }

        // Section rows, shown when expanded.
        if (expanded) {
            for (sec in usage.sections) {
                BudgetSectionRow(
                    section = sec,
                    isRomContext = isRom,
                    provider = provider,
                    state = state,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BudgetSectionRow(
    section: BudgetSection,
    isRomContext: Boolean,
    provider: ElfBinaryProvider,
    state: HexViewStateHolder,
) {
    val secs = provider.sections
    val fileOffset = secs?.firstOrNull { it.name == section.name }?.fileOffset?.toLong()
    val annotation = buildSectionAnnotation(section, isRomContext)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (fileOffset != null) {
                    Modifier
                        .clickable { state.scrollHexViewTo(fileOffset, highlightLine = true) }
                        .pointerHoverIcon(PointerIcon.Hand)
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Name.
        Text(
            section.name,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            modifier = Modifier.width(160.dp),
        )

        // Size with exact-bytes tooltip.
        Tooltip(tooltip = {
            Text("${section.size} bytes", modifier = Modifier.padding(4.dp))
        }) {
            Text(
                formatSizeLong(section.size.toLong()),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.width(70.dp),
            )
        }

        // VMA.
        Text(
            "0x${section.vma.toString(16)}",
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray),
            modifier = Modifier.width(100.dp),
        )

        // LMA (only when different from VMA).
        if (section.lma != section.vma) {
            Text(
                "lma 0x${section.lma.toString(16)}",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray),
                modifier = Modifier.width(120.dp),
            )
        }

        // Annotation tags.
        if (annotation.isNotEmpty()) {
            Text(
                annotation,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF90CAF9)),
            )
        }
    }
}

private fun buildSectionAnnotation(section: BudgetSection, isRomContext: Boolean): String {
    val parts = mutableListOf<String>()
    if (SectionTag.BOTH_ROM_AND_RAM in section.tags) {
        parts += if (isRomContext) "load image" else "runtime copy"
    }
    if (SectionTag.NOINIT in section.tags) {
        parts += "noinit (not zeroed)"
    }
    return parts.joinToString(", ")
}

@Composable
private fun BudgetFooter(
    capacitySourceDescription: String,
    isManuallyLinked: Boolean,
    onLinkSource: () -> Unit,
    onEnterManual: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(JewelTheme.globalColors.panelBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            capacitySourceDescription,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray),
            modifier = Modifier.weight(1f),
        )
        if (isManuallyLinked) {
            Text(
                "(linked manually)",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray),
            )
        }
        Text(
            "Link .map...",
            modifier = Modifier
                .clickable(onClick = onLinkSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 4.dp),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF64B5F6),
            ),
        )
        Text(
            "Enter manually...",
            modifier = Modifier
                .clickable(onClick = onEnterManual)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 4.dp),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF64B5F6),
            ),
        )
    }
}

private fun showFileChooser(
    file: VirtualFile?,
    project: Project,
    elfPath: String,
    onDone: () -> Unit,
) {
    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        .withTitle("Link Capacity Source")
        .withDescription("Select a linker .map or .dts file for this ELF")
        .withFileFilter { it.extension == "map" || it.extension == "dts" }
    FileChooser.chooseFile(descriptor, project, file) { chosen ->
        MemoryBudgetSettings.getInstance(project).setLinkedFile(elfPath, chosen.path)
        onDone()
    }
}

/**
 * Simple Compose dialog for entering flash/ram origins and sizes manually.
 * Uses a modal-like overlay rendered within the panel composition.
 */
@Composable
private fun ManualRegionsDialog(
    initialEntry: MemoryBudgetEntry?,
    onConfirm: (flashOrigin: String, flashSize: String, ramOrigin: String, ramSize: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var flashOrigin by remember { mutableStateOf(initialEntry?.manualFlashOrigin ?: "") }
    var flashSize by remember { mutableStateOf(initialEntry?.manualFlashSize ?: "") }
    var ramOrigin by remember { mutableStateOf(initialEntry?.manualRamOrigin ?: "") }
    var ramSize by remember { mutableStateOf(initialEntry?.manualRamSize ?: "") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(JewelTheme.globalColors.panelBackground)
                .padding(16.dp)
                .clickable(enabled = false, onClick = {}),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Manual memory regions",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp),
            )
            Text(
                "Enter values as hex (0x...) or decimal.",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray),
            )
            ManualField("Flash origin:", flashOrigin) { flashOrigin = it }
            ManualField("Flash size:  ", flashSize) { flashSize = it }
            ManualField("RAM origin:  ", ramOrigin) { ramOrigin = it }
            ManualField("RAM size:    ", ramSize) { ramSize = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "OK",
                    modifier = Modifier
                        .clickable { onConfirm(flashOrigin, flashSize, ramOrigin, ramSize) }
                        .background(Color(0xFF1976D2))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace, color = Color.White),
                )
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace, color = Color.Gray),
                )
            }
        }
    }
}

@Composable
private fun ManualField(label: String, value: String, onValueChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = JewelTheme.globalColors.text.normal),
            modifier = Modifier
                .width(160.dp)
                .background(JewelTheme.globalColors.panelBackground)
                .padding(4.dp),
        )
    }
}

/** Parses a persisted [MemoryBudgetEntry] that has manual regions into a [List] of [MemoryRegion]. */
private fun buildManualRegions(entry: MemoryBudgetEntry): List<MemoryRegion> {
    val result = mutableListOf<MemoryRegion>()
    val fo = parseHexOrDec(entry.manualFlashOrigin)
    val fs = parseHexOrDec(entry.manualFlashSize)
    if (fo != null && fs != null) {
        result += MemoryRegion(name = "FLASH", origin = fo, length = fs, kind = MemoryRegionKind.FLASH)
    }
    val ro = parseHexOrDec(entry.manualRamOrigin)
    val rs = parseHexOrDec(entry.manualRamSize)
    if (ro != null && rs != null) {
        result += MemoryRegion(name = "RAM", origin = ro, length = rs, kind = MemoryRegionKind.RAM)
    }
    return result
}

private fun parseHexOrDec(s: String?): ULong? {
    if (s.isNullOrBlank()) return null
    return try {
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s.substring(2).toULong(16)
        } else {
            s.toULong()
        }
    } catch (_: NumberFormatException) {
        null
    }
}

package org.jetbrains.hexana.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaBinaryVirtualFile
import org.jetbrains.hexana.document.EditableBinaryDocument
import org.jetbrains.hexana.firmware.FirmwareTarget
import org.jetbrains.hexana.firmware.FirmwareTargetService
import org.jetbrains.hexana.firmware.HexanaFirmwareSettings
import org.jetbrains.hexana.firmware.ui.FirmwareAcquisitionPopup
import org.jetbrains.hexana.firmware.ui.runFirmwareElfLater
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.NativeImageInfo
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.firmware.Backend
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.QemuBinary
import org.jetbrains.hexana.run.PopupMode
import org.jetbrains.hexana.run.WasmEngine
import org.jetbrains.hexana.run.WasmRunMode
import org.jetbrains.hexana.run.runWasmConfiguration
import org.jetbrains.hexana.run.runWasmFile
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.shared.hasWasiCliRunExport
import org.jetbrains.hexana.shared.fileTypeDescription
import org.jetbrains.hexana.ui.panel.ProgramArgsDialog
import org.jetbrains.hexana.ui.panel.WasmRunDialog
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.WasmProposal
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Resolved firmware state for the description bar. null = still resolving (buttons hidden). */
private data class FirmwareBarState(
    val target: FirmwareTarget?,
    val backendAvailable: Boolean,
    val missingBackend: Backend?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileDescriptionBar(
    fileName: String,
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    onSizeClick: (() -> Unit)? = null,
    parentFileName: String? = null,
    onNavigateToParent: (() -> Unit)? = null,
    onSaveAs: (() -> Unit)? = null,
    editableDoc: EditableBinaryDocument? = null,
    originalBinaryProvider: BinaryProvider? = null,
    overwriteShadowRanges: OverwriteShadowRanges? = null,
    onOverwriteShadowRangesChanged: ((OverwriteShadowRanges?) -> Unit)? = null,
    onReparseAfterOverwrite: () -> Unit = {},
    onSslKeyLogLoaded: () -> Unit = {},
) {
    val size = binaryProvider.bytes.limit()

    val editModeContent = if (editableDoc != null && originalBinaryProvider != null && onOverwriteShadowRangesChanged != null) {
        editModeToolbarContent(
            doc = editableDoc,
            stampKey = editableDoc.modificationStamp,
            binaryProvider = originalBinaryProvider,
            overwriteShadowRanges = overwriteShadowRanges,
            onOverwriteShadowRangesChanged = onOverwriteShadowRangesChanged,
            onReparseAfterOverwrite = onReparseAfterOverwrite,
        )
    } else {
        null
    }
    val showNormalContentWithEditMode = overwriteShadowRanges != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(HexanaTheme.backgroundColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileName,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = HexanaTheme.textColor,
            ),
        )

        SizeLabel(size = size, binaryProvider = binaryProvider, onSizeClick = onSizeClick)

        if (editModeContent != null) {
            val editBg = if (JewelTheme.isDark) Color(0xFF3D3223) else Color(0xFFFFF3E0)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(editBg, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    editModeContent()
                }
            }
            if (showNormalContentWithEditMode) {
                NormalModeContent(
                    binaryProvider = binaryProvider,
                    state = state,
                    parentFileName = parentFileName,
                    fileName = fileName,
                    onNavigateToParent = onNavigateToParent,
                    onSaveAs = onSaveAs,
                    onSslKeyLogLoaded = onSslKeyLogLoaded,
                )
            }
        } else {
            NormalModeContent(
                binaryProvider = binaryProvider,
                state = state,
                parentFileName = parentFileName,
                fileName = fileName,
                onNavigateToParent = onNavigateToParent,
                onSaveAs = onSaveAs,
                onSslKeyLogLoaded = onSslKeyLogLoaded,
            )
        }
    }

    Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SizeLabel(
    size: Int,
    binaryProvider: BinaryProvider,
    onSizeClick: (() -> Unit)?,
) {
    val sizeTooltip = remember(binaryProvider) {
        try {
            buildSizeTooltip(binaryProvider)
        } catch (_: Exception) {
            null
        }
    }

    val sizeText = formatSize(size)
    val sizeModifier = if (onSizeClick != null) {
        Modifier
            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
            .clickable { onSizeClick() }
    } else {
        Modifier
    }
    val sizeStyle = TextStyle(fontSize = 12.sp, color = HexanaTheme.hexTextColor)

    if (sizeTooltip != null) {
        Tooltip(tooltip = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Click to see the full analysis in the \"Top\" tab below",
                    style = TextStyle(fontSize = 12.sp),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Column(
                    modifier = Modifier.padding(top = 4.dp).width(IntrinsicSize.Max),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    sizeTooltip.forEach { (label, sz) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = label, style = TextStyle(fontSize = 12.sp))
                            Text(
                                text = sz,
                                style = TextStyle(fontSize = 12.sp),
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            }
        }) {
            Text(text = sizeText, modifier = sizeModifier, style = sizeStyle)
        }
    } else {
        Text(text = sizeText, modifier = sizeModifier, style = sizeStyle)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NormalModeContent(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    parentFileName: String?,
    fileName: String?,
    onNavigateToParent: (() -> Unit)?,
    onSaveAs: (() -> Unit)? = null,
    onSslKeyLogLoaded: () -> Unit = {},
) {
    val moduleType = fileTypeDescription(binaryProvider, fileName)

    val badgeBackground = if (JewelTheme.isDark) Color(0xFF4D4D4D) else Color(0xFFE0E0E0)
    val badgeForeground = if (JewelTheme.isDark) Color.White else Color.Black

    Text(
        text = moduleType,
        modifier = Modifier
            .background(badgeBackground, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = TextStyle(fontSize = 11.sp, color = badgeForeground),
    )

    val nativeImage = remember(binaryProvider) {
        (binaryProvider as? NativeBinaryProvider)?.nativeImageInfo
    }
    if (nativeImage != null) {
        NativeImageBadges(
            info = nativeImage,
            onOpenSbom = {
                state.requestSelectTabByDescription("SBOM")
                if (state.topFraction > 0.9f) state.setTopFraction(0.5f)
            },
        )
    }

    // SSL key log button for PCAP files — shown only when encrypted packets are present
    if (binaryProvider is org.jetbrains.hexana.providers.PcapBinaryProvider &&
        (binaryProvider.hasEncryptedPackets || binaryProvider.sslKeyLog.isNotEmpty)) {
        PcapSslKeyLogButton(binaryProvider, state, onSslKeyLogLoaded)
    }

    if (parentFileName != null) {
        val linkColor = if (JewelTheme.isDark) Color(0xFF589DF6) else Color(0xFF2470B3)
        val parentExt = parentFileName.substringAfterLast('.', "").lowercase()
        val parentLabel = if (parentExt in setOf("jar", "zip", "war", "ear", "apk", "aar")) {
            "Entry in $parentFileName"
        } else {
            "Submodule of $parentFileName"
        }
        Text(
            text = parentLabel,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
                .clickable { onNavigateToParent?.invoke() },
            style = TextStyle(fontSize = 11.sp, color = linkColor),
        )
        if (onSaveAs != null) {
            OutlinedButton(
                onClick = onSaveAs,
                modifier = Modifier.height(24.dp),
            ) {
                Text(
                    text = "Save As\u2026",
                    style = TextStyle(fontSize = 11.sp),
                )
            }
        }
    }

    val isSubmodule = parentFileName != null

    val isRunnable = remember(binaryProvider, isSubmodule) {
        if (isSubmodule) return@remember false
        try {
            if (binaryProvider is WasmBinaryProvider) {
                binaryProvider.exports.any { export ->
                    export is WasmFunctionExportRecordEntry &&
                        (export.name == "_start" || export.name == "_initialize")
                }
            } else if (binaryProvider is WasmComponentBinaryProvider) {
                hasWasiCliRunExport(binaryProvider)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    val isComponentCliRun = remember(binaryProvider) {
        try {
            binaryProvider is WasmComponentBinaryProvider && hasWasiCliRunExport(binaryProvider)
        } catch (_: Exception) {
            false
        }
    }

    val isDebuggable = remember(binaryProvider) {
        if (!isRunnable) return@remember false
        val engines = WasmEngine.entries.filter { !isComponentCliRun || it.supportsComponentsModel }
        engines.any { it.supportsDebug }
    }

    if (isRunnable) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolbarActionButton(
                state = state,
                binaryProvider = binaryProvider,
                isComponentCliRun = isComponentCliRun,
                popupMode = if (isDebuggable) PopupMode.RUN_ONLY else PopupMode.RUN_AND_DEBUG,
                icon = AllIconsKeys.Actions.Execute,
                label = "Run",
                tooltip = "Run Wasm module",
                buttonBackground = if (JewelTheme.isDark) Color(0xFF0E639C) else Color(0xFF007ACC),
            )
            if (isDebuggable) {
                ToolbarActionButton(
                    state = state,
                    binaryProvider = binaryProvider,
                    isComponentCliRun = isComponentCliRun,
                    popupMode = PopupMode.DEBUG_ONLY,
                    icon = AllIconsKeys.Actions.StartDebugger,
                    label = "Debug",
                    tooltip = "Debug Wasm module",
                    buttonBackground = if (JewelTheme.isDark) Color(0xFF6C3B2A) else Color(0xFFCC6633),
                )
            }
        }
    }

    // ELF firmware Run/Debug buttons (QEMU / Renode).
    // Only shown for on-disk ElfBinaryProvider files that are not submodules.
    val elfFile = if (!isSubmodule && binaryProvider is ElfBinaryProvider) {
        (binaryProvider as? BinaryVirtualFileProvider)?.file
            ?.takeUnless { it.getUserData(HexanaBinaryVirtualFile.BUFFER_KEY) != null }
    } else {
        null
    }

    val firmwareState by produceState<FirmwareBarState?>(initialValue = null, elfFile) {
        if (elfFile == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val target = try {
                FirmwareTargetService.getInstance(state.project).resolve(elfFile)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                org.jetbrains.hexana.HexanaLog.LOG.warn("FirmwareTargetService.resolve failed", t)
                null
            }
            if (target == null) return@withContext FirmwareBarState(null, false, null)

            val selection = target.selection as? BackendSelection.Ok
                ?: return@withContext FirmwareBarState(null, false, null)
            val settings = HexanaFirmwareSettings.getInstance()
            val available: Boolean
            val missingBackend: Backend?
            when (selection.backend) {
                Backend.Qemu -> {
                    val qemuBin = selection.qemuBinary ?: QemuBinary.SystemArm
                    val path = settings.effectiveQemuPath(qemuBin)
                    val exists = java.io.File(path).isFile ||
                        (java.io.File(path).name == path) // bare name = PATH lookup treated as available
                    available = exists
                    missingBackend = if (exists) null else Backend.Qemu
                }
                Backend.Renode -> {
                    val renode = settings.effectiveRenodesPath()
                    available = renode != null
                    missingBackend = if (renode != null) null else Backend.Renode
                }
            }
            FirmwareBarState(target, available, missingBackend)
        }
    }

    val fs = firmwareState
    if (fs != null && fs.target != null) {
        val fwRunBg = if (JewelTheme.isDark) Color(0xFF0E639C) else Color(0xFF007ACC)
        val fwDbgBg = if (JewelTheme.isDark) Color(0xFF6C3B2A) else Color(0xFFCC6633)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FirmwareActionButton(
                icon = AllIconsKeys.Actions.Execute,
                label = "Run",
                tooltip = "Run firmware in emulator",
                buttonBackground = fwRunBg,
                onClick = {
                    if (fs.backendAvailable) {
                        runFirmwareElfLater(state.project, elfFile!!, fs.target, WasmRunMode.RUN)
                    } else {
                        val sel = fs.target.selection as BackendSelection.Ok
                        FirmwareAcquisitionPopup.show(
                            state.project, fs.missingBackend ?: sel.backend, sel.qemuBinary, fs.target?.profile, elfFile
                        )
                    }
                },
            )
            FirmwareActionButton(
                icon = AllIconsKeys.Actions.StartDebugger,
                label = "Debug",
                tooltip = "Debug firmware in emulator",
                buttonBackground = fwDbgBg,
                onClick = {
                    if (fs.backendAvailable) {
                        runFirmwareElfLater(state.project, elfFile!!, fs.target, WasmRunMode.DEBUG)
                    } else {
                        val sel = fs.target.selection as BackendSelection.Ok
                        FirmwareAcquisitionPopup.show(
                            state.project, fs.missingBackend ?: sel.backend, sel.qemuBinary, fs.target?.profile, elfFile
                        )
                    }
                },
            )
        }
    }

    val proposals = remember(binaryProvider) {
        try {
            (binaryProvider as? WasmBinaryProvider)?.usedProposals?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    if (proposals.isNotEmpty()) {
        ProposalBadges(proposals)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarActionButton(
    state: HexViewStateHolder,
    binaryProvider: BinaryProvider,
    isComponentCliRun: Boolean,
    popupMode: PopupMode,
    icon: org.jetbrains.jewel.ui.icon.IconKey,
    label: String,
    tooltip: String,
    buttonBackground: Color,
) {
    val buttonForeground = Color.White

    Tooltip(tooltip = { Text(tooltip) }) {
        Row(
            modifier = Modifier
                .background(buttonBackground, RoundedCornerShape(4.dp))
                .onClick {
                    if (isComponentCliRun) {
                        val dialog = ProgramArgsDialog(state.project, "wasi:cli/run", isComponentModel = true, popupMode = popupMode)
                        if (!dialog.showAndGet()) return@onClick
                        val result = dialog.result ?: return@onClick
                        val file = (binaryProvider as? BinaryVirtualFileProvider)?.file ?: return@onClick
                        runWasmConfiguration(state.project, file, "", dialog.programArgs, "", result.engine, result.mode)
                    } else {
                        val dialog = WasmRunDialog.createSimple(state.project, "Run Wasm Module", popupMode = popupMode)
                        if (!dialog.showAndGet()) return@onClick
                        val result = dialog.result ?: return@onClick
                        runWasmFile(binaryProvider, state, result.engine, result.mode)
                    }
                }
                .height(20.dp)
                .padding(horizontal = 8.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = label,
                iconClass = AllIcons::class.java,
                tint = buttonForeground,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = buttonForeground,
                ),
            )
        }
    }
}

/**
 * Compact labelled icon button for the firmware Run/Debug actions.
 *
 * Mirrors [ToolbarActionButton] visually but takes a plain [onClick] lambda instead
 * of the WASM-specific popup-mode dispatch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FirmwareActionButton(
    icon: org.jetbrains.jewel.ui.icon.IconKey,
    label: String,
    tooltip: String,
    buttonBackground: Color,
    onClick: () -> Unit,
) {
    val buttonForeground = Color.White
    Tooltip(tooltip = { Text(tooltip) }) {
        Row(
            modifier = Modifier
                .background(buttonBackground, RoundedCornerShape(4.dp))
                .onClick { onClick() }
                .height(20.dp)
                .padding(horizontal = 8.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = label,
                iconClass = AllIcons::class.java,
                tint = buttonForeground,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = TextStyle(fontSize = 11.sp, color = buttonForeground),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NativeImageBadges(
    info: NativeImageInfo,
    onOpenSbom: () -> Unit,
) {
    val niBackground = if (JewelTheme.isDark) Color(0xFF3A3357) else Color(0xFFE6E0F5)
    val niForeground = if (JewelTheme.isDark) Color(0xFFC4B5F0) else Color(0xFF5B3FA6)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (info.isNativeImage) {
            val tooltipText = buildString {
                append("GraalVM Native Image")
                info.version?.let { append('\n').append(it) }
                val edition = when (info.edition) {
                    NativeImageInfo.Edition.COMMUNITY -> "Community"
                    NativeImageInfo.Edition.ENTERPRISE -> "Enterprise"
                    NativeImageInfo.Edition.UNKNOWN -> null
                }
                edition?.let { append('\n').append(it) }
                info.platform?.let { append('\n').append(it) }
            }
            Tooltip(tooltip = { Text(tooltipText) }) {
                Text(
                    text = "Native Image",
                    modifier = Modifier
                        .background(niBackground, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = TextStyle(fontSize = 11.sp, color = niForeground),
                )
            }
        }

        // SBOM badge: only when the embedded SBOM symbols resolved to a locator.
        if (info.sbom != null && info.isNativeImage) {
            Tooltip(tooltip = { Text("Open the CycloneDX SBOM viewer") }) {
                Text(
                    text = "SBOM",
                    modifier = Modifier
                        .background(niBackground, RoundedCornerShape(4.dp))
                        .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
                        .clickable { onOpenSbom() }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = TextStyle(fontSize = 11.sp, color = niForeground, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

private const val MAX_INDIVIDUAL_PROPOSAL_BADGES = 3

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProposalBadges(proposals: List<WasmProposal>) {
    val proposalBackground = if (JewelTheme.isDark) {
        Color(0xFF3D5040)
    } else {
        Color(0xFFD4EDDA)
    }
    val proposalForeground = if (JewelTheme.isDark) {
        Color(0xFFA8D8B0)
    } else {
        Color(0xFF2D6A3F)
    }

    if (proposals.size <= MAX_INDIVIDUAL_PROPOSAL_BADGES) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (proposal in proposals) {
                Tooltip(tooltip = { Text("Uses ${proposal.displayName} proposal") }) {
                    Text(
                        text = proposal.displayName,
                        modifier = Modifier
                            .background(proposalBackground, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = proposalForeground,
                        ),
                    )
                }
            }
        }
    } else {
        val tooltipText = proposals.joinToString("\n") { it.displayName }
        Tooltip(tooltip = { Text(tooltipText) }) {
            Text(
                text = "${proposals.size} proposals",
                modifier = Modifier
                    .background(proposalBackground, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = TextStyle(
                    fontSize = 11.sp,
                    color = proposalForeground,
                ),
            )
        }
    }
}

private fun buildSizeTooltip(binaryProvider: BinaryProvider): List<Pair<String, String>>? {
    val sizeAnalysis = binaryProvider.sizeAnalysis ?: return null
    val children = sizeAnalysis.children
    if (children.isEmpty()) return null

    val grouped = linkedMapOf<String, MutableList<BinarySizeNode>>()
    for (child in children) {
        grouped.getOrPut(child.name) { mutableListOf() }.add(child)
    }

    return grouped.map { (name, nodes) ->
        val totalSize = nodes.sumOf { it.size }
        val label = if (nodes.size > 1) "$name (\u00d7${nodes.size})" else name
        label to totalSize
    }.sortedByDescending { it.second }
     .map { (label, size) -> label to formatSize(size) }
}


/**
 * Returns a composable lambda for the edit-mode controls to embed in the
 * [FileDescriptionBar], or `null` when no editing UI should be shown
 * (i.e. no edits and no post-overwrite state).
 */
@Composable
private fun editModeToolbarContent(
    doc: EditableBinaryDocument,
    stampKey: Long,
    binaryProvider: BinaryProvider,
    overwriteShadowRanges: OverwriteShadowRanges?,
    onOverwriteShadowRangesChanged: (OverwriteShadowRanges?) -> Unit,
    onReparseAfterOverwrite: () -> Unit = {},
): (@Composable () -> Unit)? {
    @Suppress("UNUSED_PARAMETER") val unused = stampKey
    // In-memory nested views have no disk file to overwrite or finalize next
    // to — treat them as file-less so those actions stay disabled.
    val sourceFile = (binaryProvider as? BinaryVirtualFileProvider)?.file
        ?.takeUnless { it is HexanaBinaryVirtualFile }
    val isGzipSource = sourceFile?.name?.endsWith(".gz", ignoreCase = true) == true
    val finalizedName = sourceFile?.name?.let { computeFinalizedName(it, stripGz = isGzipSource) }
    val hasEdits = doc.canUndo()
    val canOverwrite = hasEdits && sourceFile != null && !isGzipSource
    val canSaveCopy = hasEdits && sourceFile != null

    val isPostOverwrite = overwriteShadowRanges != null && sourceFile != null

    if (!hasEdits && !isPostOverwrite) return null

    return {
        val compactTextStyle = TextStyle(fontSize = 11.sp)
        if (isPostOverwrite && sourceFile != null) {
            OutlinedButton(
                modifier = Modifier.height(20.dp),
                onClick = {
                    val restoredOriginal = overwriteShadowRanges!!.applyTo(
                        sourceFile.contentsToByteArray(),
                    )
                    overwriteSourceFile(sourceFile, restoredOriginal)
                    while (doc.canUndo()) doc.undo()
                    val currentBytes = doc.read(0L, doc.length.toInt())
                    if (!currentBytes.contentEquals(restoredOriginal)) {
                        doc.edit().use { tx ->
                            tx.replace(0L, doc.length, restoredOriginal)
                            tx.commit()
                        }
                        while (doc.canUndo()) doc.undo()
                    }
                    doc.clearPersistedJournal()
                    onOverwriteShadowRangesChanged(null)
                    onReparseAfterOverwrite()
                },
            ) { Text("Undo overwrite", style = compactTextStyle) }
        } else {
            OutlinedButton(
                modifier = Modifier.height(20.dp),
                onClick = {
                    while (doc.canUndo()) doc.undo()
                    doc.clearPersistedJournal()
                    onReparseAfterOverwrite()
                },
                enabled = hasEdits,
            ) { Text("Discard", style = compactTextStyle) }
            DefaultButton(
                modifier = Modifier.height(20.dp),
                onClick = {
                    if (sourceFile != null) {
                        val editedBytes = doc.read(0L, doc.length.toInt())
                        val canReparse = try {
                            val rebuilt = rebuildProviderFromBytes(binaryProvider, editedBytes)
                            when (rebuilt) {
                                is GenericBinaryProvider -> false
                                is WasmBinaryProvider -> rebuilt.canProvideWasmModule()
                                is WasmComponentBinaryProvider -> rebuilt.canProvideWasmModule()
                                else -> rebuilt != null
                            }
                        } catch (_: Throwable) { false }
                        val isGenericOriginal = binaryProvider is GenericBinaryProvider
                        if (!canReparse && !isGenericOriginal) {
                            val answer = Messages.showOkCancelDialog(
                                "The edited bytes can no longer be parsed as the original format. " +
                                    "Overwriting will damage the file's structure.",
                                "Overwrite Damaged Binary?",
                                "Overwrite Anyway",
                                Messages.getCancelButton(),
                                Messages.getWarningIcon(),
                            )
                            if (answer != Messages.OK) return@DefaultButton
                        }
                        val shadow = OverwriteShadowRanges.capture(
                            sourceFile.contentsToByteArray(),
                            doc.modifiedRanges(),
                        )
                        onOverwriteShadowRangesChanged(shadow)
                        overwriteSourceFile(sourceFile, editedBytes)
                        doc.clearPersistedJournal()
                        onReparseAfterOverwrite()
                    }
                },
                enabled = canOverwrite,
            ) { Text("Overwrite", style = compactTextStyle) }
            OutlinedButton(
                modifier = Modifier.height(20.dp),
                onClick = {
                    if (sourceFile != null && finalizedName != null) {
                        val bytes = doc.read(0L, doc.length.toInt())
                        finalizeWriteToSibling(sourceFile, finalizedName, bytes)
                    }
                },
                enabled = canSaveCopy,
            ) { Text(if (finalizedName != null) "Save a copy \u2192 $finalizedName" else "Save a copy", style = compactTextStyle) }
        }
    }
}

/**
 * Overwrites the [target] file with [bytes] via a VFS write action.
 * Runs synchronously when already on the EDT (button click handlers in
 * Compose/Jewel), otherwise schedules via `invokeLater`.  Synchronous
 * execution is critical so that callers can safely manipulate the
 * document state immediately after the file write without racing a
 * deferred VFS reload.
 */
private fun overwriteSourceFile(
    target: com.intellij.openapi.vfs.VirtualFile,
    bytes: ByteArray,
) {
    val app = com.intellij.openapi.application.ApplicationManager.getApplication()
    if (app.isDispatchThread) {
        com.intellij.openapi.application.WriteAction.run<Throwable> {
            target.setBinaryContent(bytes)
            org.jetbrains.hexana.HexanaLog.LOG.info(
                "Overwrite ${bytes.size} bytes \u2192 ${target.path}",
            )
        }
    } else {
        app.invokeAndWait {
            com.intellij.openapi.application.WriteAction.run<Throwable> {
                target.setBinaryContent(bytes)
                org.jetbrains.hexana.HexanaLog.LOG.info(
                    "Overwrite ${bytes.size} bytes \u2192 ${target.path}",
                )
            }
        }
    }
}

/**
 * `<base>.edited.<ext>` next to the original. Plain `<name>.edited` if there
 * is no extension. Subsequent finalizes overwrite the same target \u2014 the user
 * can clone it before re-finalizing if they want history.
 */
private fun computeFinalizedName(originalName: String, stripGz: Boolean = false): String {
    // For gzip files, strip the .gz extension so the copy is the decompressed
    // inner file (e.g. "foo.wasm.gz" → "foo.edited.wasm").
    val baseName = if (stripGz && originalName.endsWith(".gz", ignoreCase = true)) {
        originalName.substring(0, originalName.length - 3)
    } else {
        originalName
    }
    val dot = baseName.lastIndexOf('.')
    return if (dot < 0) "$baseName.edited"
    else baseName.substring(0, dot) + ".edited" + baseName.substring(dot)
}

/**
 * Writes [bytes] to a sibling of [source] named [outputName]. Creates the
 * file on first finalize; overwrites on subsequent ones. Runs on the EDT
 * inside a write action \u2014 required by the IntelliJ VFS contract.
 */
private fun finalizeWriteToSibling(
    source: com.intellij.openapi.vfs.VirtualFile,
    outputName: String,
    bytes: ByteArray,
) {
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        com.intellij.openapi.application.WriteAction.run<Throwable> {
            val parent = source.parent
                ?: error("source file has no parent directory: ${source.path}")
            val existing = parent.findChild(outputName)
            val target = existing ?: parent.createChildData(parent, outputName)
            target.setBinaryContent(bytes)
            org.jetbrains.hexana.HexanaLog.LOG.info(
                "Finalized ${bytes.size} bytes \u2192 ${target.path}",
            )
        }
    }
}

// ---- PCAP SSL Key Log UI ----

/** Well-known sibling file names for auto-detecting SSL key log files. */
private val SSL_KEY_LOG_SIBLING_NAMES = listOf(
    "sslkeylog.txt",
    "sslkeylog.log",
    "ssl-key.log",
    "keylog.txt",
    "premaster.txt",
)

/**
 * Attempts to find an SSL key log file next to the PCAP file.
 * Checks for well-known names and files matching `*.sslkeylog` or
 * `<pcapname>.keylog`.
 */
internal fun autoDetectSslKeyLog(
    pcapFile: com.intellij.openapi.vfs.VirtualFile,
): com.intellij.openapi.vfs.VirtualFile? {
    val parent = pcapFile.parent ?: return null
    val baseName = pcapFile.nameWithoutExtension.lowercase()

    // Check well-known names
    for (name in SSL_KEY_LOG_SIBLING_NAMES) {
        val sibling = parent.findChild(name)
        if (sibling != null && sibling.isValid && !sibling.isDirectory) return sibling
    }

    // Check <pcapname>.keylog or <pcapname>.sslkeylog
    val keylogName = "$baseName.keylog"
    val sslKeylogName = "$baseName.sslkeylog"
    for (child in parent.children) {
        val childName = child.name.lowercase()
        if (childName == keylogName || childName == sslKeylogName) return child
    }

    return null
}

/**
 * Button in the file description bar for PCAP files that allows loading
 * an SSL key log file for TLS decryption. Auto-detects sibling key files
 * on first render.
 */
@Composable
private fun PcapSslKeyLogButton(
    provider: org.jetbrains.hexana.providers.PcapBinaryProvider,
    state: HexViewStateHolder,
    onSslKeyLogLoaded: () -> Unit = {},
) {
    val hasKeyLog = provider.sslKeyLog.isNotEmpty
    val keyBadgeBg = if (hasKeyLog) {
        if (JewelTheme.isDark) Color(0xFF2E5339) else Color(0xFFE8F5E9)
    } else {
        if (JewelTheme.isDark) Color(0xFF4D4D4D) else Color(0xFFE0E0E0)
    }
    val keyBadgeFg = if (JewelTheme.isDark) Color.White else Color.Black

    // Auto-detect sibling key log on first composition
    val sourceFile = (provider as? org.jetbrains.hexana.providers.BinaryVirtualFileProvider)?.file
    androidx.compose.runtime.LaunchedEffect(provider) {
        if (provider.sslKeyLog.isNotEmpty) return@LaunchedEffect
        if (sourceFile == null) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val keyFile = autoDetectSslKeyLog(sourceFile) ?: return@withContext
            try {
                val content = String(keyFile.contentsToByteArray(), Charsets.UTF_8)
                val keyLog = org.jetbrains.hexana.pcap.SslKeyLog.parse(content)
                if (keyLog.isNotEmpty) {
                    provider.sslKeyLog = keyLog
                    org.jetbrains.hexana.HexanaLog.LOG.info(
                        "Auto-detected SSL key log: ${keyFile.name} (${keyLog.sessionCount} sessions)"
                    )
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onSslKeyLogLoaded()
                    }
                }
            } catch (t: Throwable) {
                org.jetbrains.hexana.HexanaLog.LOG.warn("Failed to load auto-detected SSL key log: ${keyFile.name}", t)
            }
        }
    }

    if (hasKeyLog) {
        Text(
            text = "\uD83D\uDD11 SSL keys loaded (${provider.sslKeyLog.sessionCount} sessions)",
            modifier = Modifier
                .background(keyBadgeBg, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = TextStyle(fontSize = 11.sp, color = keyBadgeFg),
        )
    }

    OutlinedButton(
        onClick = {
            @Suppress("DEPRECATION")
            val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
                .createSingleFileDescriptor()
                .withTitle("Select SSL Key Log File")
                .withDescription("Choose an SSLKEYLOGFILE (NSS Key Log Format) for TLS decryption")
            val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                descriptor, state.project, null,
            )
            if (chosen != null) {
                try {
                    val content = String(chosen.contentsToByteArray(), Charsets.UTF_8)
                    if (looksLikePrivateKey(content)) {
                        showPrivateKeyHintDialog(state.project)
                        return@OutlinedButton
                    }
                    val keyLog = org.jetbrains.hexana.pcap.SslKeyLog.parse(content)
                    if (keyLog.isNotEmpty) {
                        provider.sslKeyLog = keyLog
                        org.jetbrains.hexana.HexanaLog.LOG.info(
                            "Loaded SSL key log: ${chosen.name} (${keyLog.sessionCount} sessions)"
                        )
                        onSslKeyLogLoaded()
                    } else {
                        Messages.showWarningDialog(
                            state.project,
                            "The selected file does not contain any recognised SSL key log entries.\n\n" +
                                "Expected format: CLIENT_RANDOM <hex> <hex>",
                            "No Keys Found",
                        )
                    }
                } catch (t: Throwable) {
                    org.jetbrains.hexana.HexanaLog.LOG.warn("Failed to load SSL key log: ${chosen.name}", t)
                    Messages.showErrorDialog(
                        state.project,
                        "Failed to read the key log file: ${t.message}",
                        "SSL Key Log Error",
                    )
                }
            }
        },
        modifier = Modifier.height(24.dp),
    ) {
        Text(
            text = if (hasKeyLog) "Change Key Log\u2026" else "Load SSL Key Log\u2026",
            style = TextStyle(fontSize = 11.sp),
        )
    }
}

/**
 * Returns `true` when [content] looks like a PEM-encoded private key
 * (RSA, EC, PKCS#8, etc.) rather than an NSS Key Log file.
 */
private fun looksLikePrivateKey(content: String): Boolean {
    val trimmed = content.trimStart()
    return trimmed.startsWith("-----BEGIN") && trimmed.contains("PRIVATE KEY-----")
}

/**
 * Shows an informational dialog when the user selects a private key file
 * instead of an SSLKEYLOG file, explaining how to obtain the correct
 * key material for TLS decryption.
 */
private fun showPrivateKeyHintDialog(project: com.intellij.openapi.project.Project) {
    Messages.showInfoMessage(
        project,
        "You selected a private key file. Hexana uses the NSS Key Log Format " +
            "(SSLKEYLOGFILE) for TLS decryption, not raw private keys.\n\n" +
            "To capture an SSL key log:\n" +
            "  \u2022 Set the SSLKEYLOGFILE environment variable before starting your " +
            "application, e.g.:\n" +
            "      export SSLKEYLOGFILE=/tmp/sslkeys.log\n" +
            "  \u2022 Run the TLS client/server \u2014 the key log file will be created " +
            "automatically.\n" +
            "  \u2022 Then load that file here.\n\n" +
            "Most TLS libraries (OpenSSL, NSS, BoringSSL, Go, Java 17+) honour " +
            "this variable.",
        "Private Key Not Supported",
    )
}

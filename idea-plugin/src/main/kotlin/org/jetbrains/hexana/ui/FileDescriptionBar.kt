package org.jetbrains.hexana.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.run.PopupMode
import org.jetbrains.hexana.run.WasmEngine
import org.jetbrains.hexana.run.runWasmConfiguration
import org.jetbrains.hexana.run.runWasmFile
import org.jetbrains.hexana.run.showEnginePopup
import org.jetbrains.hexana.shared.hasWasiCliRunExport
import org.jetbrains.hexana.ui.panel.ProgramArgsDialog
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.analysis.WasmProposal
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileDescriptionBar(
    fileName: String,
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    onSizeClick: (() -> Unit)? = null,
    parentFileName: String? = null,
    onNavigateToParent: (() -> Unit)? = null,
) {
    val size = binaryProvider.bytes.limit()
    val moduleType = when (binaryProvider) {
        is WasmBinaryProvider -> "Core Wasm"
        is WasmComponentBinaryProvider -> "Component Model"
        is NativeBinaryProvider -> binaryProvider.nativeFormat.displayName
        is GenericBinaryProvider -> binaryProvider.nativeFormat?.displayName ?: "Binary"
    }

    val sizeTooltip = remember(binaryProvider) {
        buildSizeTooltip(binaryProvider)
    }

    val isSubmodule = parentFileName != null

    val isRunnable = remember(binaryProvider, isSubmodule) {
        if (isSubmodule) return@remember false
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
    }

    val isComponentCliRun = remember(binaryProvider) {
        binaryProvider is WasmComponentBinaryProvider && hasWasiCliRunExport(binaryProvider)
    }

    val isDebuggable = remember(binaryProvider) {
        if (!isRunnable) return@remember false
        val engines = WasmEngine.entries.filter { !isComponentCliRun || it.supportsComponentsModel }
        engines.any { it.supportsDebug }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                        sizeTooltip.forEach { (label, size) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = label,
                                    style = TextStyle(fontSize = 12.sp),
                                )
                                Text(
                                    text = size,
                                    style = TextStyle(fontSize = 12.sp),
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                }
            }) {
                Text(
                    text = formatSize(size),
                    modifier = if (onSizeClick != null) {
                        Modifier
                            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
                            .clickable { onSizeClick() }
                    } else {
                        Modifier
                    },
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = HexanaTheme.hexTextColor,
                    ),
                )
            }
        } else {
            Text(
                text = formatSize(size),
                modifier = if (onSizeClick != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
                        .clickable { onSizeClick() }
                } else {
                    Modifier
                },
                style = TextStyle(
                    fontSize = 12.sp,
                    color = HexanaTheme.hexTextColor,
                ),
            )
        }

        val badgeBackground = if (JewelTheme.isDark) {
            Color(0xFF4D4D4D)
        } else {
            Color(0xFFE0E0E0)
        }
        val badgeForeground = if (JewelTheme.isDark) {
            Color.White
        } else {
            Color.Black
        }

        Text(
            text = moduleType,
            modifier = Modifier
                .background(badgeBackground, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = TextStyle(
                fontSize = 11.sp,
                color = badgeForeground,
            ),
        )

        if (parentFileName != null) {
            val linkColor = if (JewelTheme.isDark) Color(0xFF589DF6) else Color(0xFF2470B3)
            Text(
                text = "Submodule of $parentFileName",
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
                    .clickable { onNavigateToParent?.invoke() },
                style = TextStyle(
                    fontSize = 11.sp,
                    color = linkColor,
                ),
            )
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

        val proposals = remember(binaryProvider) {
            (binaryProvider as? WasmBinaryProvider)?.usedProposals?.sorted() ?: emptyList()
        }

        if (proposals.isNotEmpty()) {
            ProposalBadges(proposals)
        }
    }

    Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
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
                        showEnginePopup(isComponentModel = true, popupMode = popupMode) { engine, mode ->
                            val file = (binaryProvider as? BinaryVirtualFileProvider)?.file ?: return@showEnginePopup
                            val dialog = ProgramArgsDialog(state.project, "wasi:cli/run")
                            if (!dialog.showAndGet()) return@showEnginePopup
                            runWasmConfiguration(state.project, file, "", dialog.programArgs, "", engine, mode)
                        }
                    } else {
                        showEnginePopup(popupMode = popupMode) { engine, mode ->
                            runWasmFile(binaryProvider, state, engine, mode)
                        }
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
    val sizeAnalysis: SizeNode.Module? = when (binaryProvider) {
        is WasmBinaryProvider -> binaryProvider.sizeAnalysis
        is WasmComponentBinaryProvider -> binaryProvider.sizeAnalysis
        is NativeBinaryProvider -> null
        is GenericBinaryProvider -> null
    }
    val children = sizeAnalysis?.children ?: return null
    if (children.isEmpty()) return null

    val grouped = linkedMapOf<String, MutableList<SizeNode>>()
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

private fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> {
        val mb = bytes.toDouble() / (1024 * 1024)
        val rounded = (mb * 10).toLong() / 10.0
        "$rounded MB"
    }
}

package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.nativebin.CycloneDxParser
import org.jetbrains.hexana.nativebin.CycloneDxSbom
import org.jetbrains.hexana.nativebin.SbomExtractor
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

private sealed interface SbomUiState {
    data object Loading : SbomUiState
    data object Unavailable : SbomUiState
    data class Loaded(val sbom: CycloneDxSbom) : SbomUiState
}

/**
 * Renders the GraalVM Native Image embedded SBOM (CycloneDX) as a metadata
 * header plus a searchable/sortable table of components. Extraction + parse run
 * off the EDT (a large app SBOM can list thousands of components).
 */
@Composable
internal fun CycloneDxSbomPanel(provider: NativeBinaryProvider, state: HexViewStateHolder) {
    val uiState by produceState<SbomUiState>(SbomUiState.Loading, provider) {
        value = withContext(Dispatchers.IO) {
            val locator = provider.nativeImageInfo?.sbom ?: return@withContext SbomUiState.Unavailable
            val json = SbomExtractor.extractJson(provider.bytes, locator) ?: return@withContext SbomUiState.Unavailable
            CycloneDxParser.parse(json)?.let { SbomUiState.Loaded(it) } ?: SbomUiState.Unavailable
        }
    }

    when (val s = uiState) {
        SbomUiState.Loading -> Box(Modifier.fillMaxSize().padding(12.dp)) { Text("Reading SBOM…") }
        SbomUiState.Unavailable -> Box(Modifier.fillMaxSize().padding(12.dp)) {
            Text("No readable CycloneDX SBOM was found in this binary.")
        }
        is SbomUiState.Loaded -> SbomContent(s.sbom, provider, state)
    }
}

@Composable
private fun SbomContent(sbom: CycloneDxSbom, provider: NativeBinaryProvider, state: HexViewStateHolder) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        val title = buildString {
            append(sbom.bomFormat ?: "CycloneDX")
            sbom.specVersion?.let { append(' ').append(it) }
        }
        Text(title, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp))

        sbom.mainComponent?.let { mc ->
            val coordinates = listOfNotNull(mc.group, mc.name).joinToString(":")
            val subject = listOfNotNull(coordinates.ifEmpty { null }, mc.version).joinToString(" ")
            if (subject.isNotBlank()) Text("Subject: $subject", modifier = Modifier.padding(top = 2.dp))
        }
        sbom.metadataTimestamp?.let { Text("Generated: $it", modifier = Modifier.padding(top = 2.dp)) }
        if (sbom.tools.isNotEmpty()) {
            Text("Tool: ${sbom.tools.joinToString(", ")}", modifier = Modifier.padding(top = 2.dp))
        }

        val summary = buildString {
            append(sbom.components.size).append(" components")
            if (sbom.dependencyCount > 0) append(" · ").append(sbom.dependencyCount).append(" dependency nodes")
            if (sbom.vulnerabilities.isNotEmpty()) {
                append(" · ").append(sbom.vulnerabilities.size).append(" vulnerabilities")
            }
        }
        Row(
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(summary)
            val linkColor = if (JewelTheme.isDark) Color(0xFF589DF6) else Color(0xFF2470B3)
            Text(
                text = "View raw JSON",
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
                    .clickable {
                        val locator = provider.nativeImageInfo?.sbom ?: return@clickable
                        val base = provider.sourcePath
                            ?.substringAfterLast('/')
                            ?.substringAfterLast('\\')
                            ?: "sbom"
                        SbomExtractor.openExtractedSbom(state.project, base, provider.bytes, locator)
                    },
                style = TextStyle(color = linkColor),
            )
        }

        if (sbom.components.isEmpty()) {
            Text("This SBOM lists no components.")
            return@Column
        }

        val comps = sbom.components
        Table(
            columns = listOf(
                TableColumn("Type", modifier = Modifier.fillMaxWidth(0.12f)) { TableCellValue(comps[it].type ?: "") },
                TableColumn("Group", modifier = Modifier.fillMaxWidth(0.22f)) { TableCellValue(comps[it].group ?: "") },
                TableColumn("Name", modifier = Modifier.fillMaxWidth(0.22f)) { TableCellValue(comps[it].name ?: "") },
                TableColumn("Version", modifier = Modifier.fillMaxWidth(0.14f)) { TableCellValue(comps[it].version ?: "") },
                TableColumn("Licenses", modifier = Modifier.fillMaxWidth(0.16f)) {
                    TableCellValue(comps[it].licenses.joinToString(", "))
                },
                TableColumn("PURL", modifier = Modifier.fillMaxWidth()) { TableCellValue(comps[it].purl ?: "") },
            ),
            dataCount = comps.size,
            defaultSortColumn = 2,
        )
    }
}
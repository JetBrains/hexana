package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaSettings
import org.jetbrains.hexana.nativebin.CdxComponent
import org.jetbrains.hexana.nativebin.CdxComponentKind
import org.jetbrains.hexana.nativebin.CycloneDxParser
import org.jetbrains.hexana.nativebin.CycloneDxSbom
import org.jetbrains.hexana.nativebin.SbomExtractor
import org.jetbrains.hexana.nativebin.vuln.ComponentVulnerabilities
import org.jetbrains.hexana.nativebin.vuln.CvssSeverity
import org.jetbrains.hexana.nativebin.vuln.OsvApiClient
import org.jetbrains.hexana.nativebin.vuln.OsvDatabase
import org.jetbrains.hexana.nativebin.vuln.OsvEvent
import org.jetbrains.hexana.nativebin.vuln.OsvRecord
import org.jetbrains.hexana.nativebin.vuln.ReachabilityAnalyzer
import org.jetbrains.hexana.nativebin.vuln.ReachabilityState
import org.jetbrains.hexana.nativebin.vuln.SbomVulnerabilityReport
import org.jetbrains.hexana.nativebin.vuln.VulnerabilityMatcher
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import java.time.Instant
import java.time.temporal.ChronoUnit

private sealed interface SbomUiState {
    data object Loading : SbomUiState
    data object Unavailable : SbomUiState
    data class Loaded(val sbom: CycloneDxSbom) : SbomUiState
}

private sealed interface VulnUiState {
    data object Disabled : VulnUiState
    data object DbMissing : VulnUiState
    data object Loading : VulnUiState
    data class Ready(val report: SbomVulnerabilityReport, val updated: Instant?) : VulnUiState
}

/**
 * Renders the GraalVM Native Image embedded SBOM (CycloneDX) and, when enabled,
 * overlays vulnerability reachability: severity badges + CVE details per component,
 * reachability state (component / class-retained / class-eliminated), and the
 * dependency path. Extraction, OSV indexing and matching all run off the EDT.
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
    var refreshTick by remember { mutableStateOf(0) }
    val settings = remember { HexanaSettings.getInstance() }

    val vulnState by produceState<VulnUiState>(VulnUiState.Loading, sbom, refreshTick) {
        value = computeVulnState(sbom, settings)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        SbomHeader(sbom, provider, state)
        VulnSummaryBar(
            vulnState = vulnState,
            onEnable = { settings.lookupVulnerabilities = true; refreshTick++ },
            onDownload = { downloadDatabase(state.project) { refreshTick++ } },
        )
        ComponentList(sbom, (vulnState as? VulnUiState.Ready)?.report, provider, state)
    }
}

@Composable
private fun SbomHeader(sbom: CycloneDxSbom, provider: NativeBinaryProvider, state: HexViewStateHolder) {
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

    Row(modifier = Modifier.padding(top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${sbom.components.size} components")
        Link("View raw JSON") {
            val locator = provider.nativeImageInfo?.sbom ?: return@Link
            val base = provider.sourcePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: "sbom"
            SbomExtractor.openExtractedSbom(state.project, base, provider.bytes, locator)
        }
    }
}

@Composable
private fun VulnSummaryBar(vulnState: VulnUiState, onEnable: () -> Unit, onDownload: () -> Unit) {
    when (vulnState) {
        VulnUiState.Disabled -> InfoBar {
            Text("Vulnerability checks are off.")
            Link("Enable", onEnable)
            Text("Matches components against a local OSV database; nothing is sent online.", muted = true)
        }
        VulnUiState.DbMissing -> InfoBar {
            Text("Vulnerability database not downloaded.")
            Link("Download database", onDownload)
        }
        VulnUiState.Loading -> InfoBar { Text("Checking components for known vulnerabilities…") }
        is VulnUiState.Ready -> {
            val r = vulnState.report
            InfoBar {
                if (r.vulnerableComponentCount == 0) {
                    Text("No known vulnerabilities in ${r.scannedComponentCount} components.")
                } else {
                    SeverityBadge(r.maxSeverity)
                    Text("${r.vulnerableComponentCount} of ${r.scannedComponentCount} components affected · ${r.totalFindings} advisories")
                }
                vulnState.updated?.let { Text("· DB ${agoLabel(it)}", muted = true) }
                Link("Refresh", onDownload)
                if (!r.classLevelAvailable) {
                    Text(
                        "Component-level only — rebuild with --enable-sbom=class-level for method-level reachability.",
                        muted = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComponentList(
    sbom: CycloneDxSbom,
    report: SbomVulnerabilityReport?,
    provider: NativeBinaryProvider,
    state: HexViewStateHolder,
) {
    val vulnByKey = remember(report) {
        report?.vulnerableComponents?.associateBy { componentKey(it.component) } ?: emptyMap()
    }
    val requiredBy = remember(sbom) { buildRequiredBy(sbom) }
    val ordered = remember(sbom, report) {
        sbom.components.sortedWith(
            compareByDescending<CdxComponent> { vulnByKey[componentKey(it)]?.maxSeverity?.rank ?: -2 }
                .thenBy { (it.name ?: "").lowercase() },
        )
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(Modifier.fillMaxSize()) {
        items(ordered.size) { index ->
            val component = ordered[index]
            val key = componentKey(component)
            val vuln = vulnByKey[key]
            val isOpen = expanded[key] == true
            ComponentRow(component, vuln, isOpen) { expanded[key] = !isOpen }
            if (isOpen) {
                ComponentDetails(component, vuln, requiredBy)
            }
        }
    }
}

@Composable
private fun ComponentRow(component: CdxComponent, vuln: ComponentVulnerabilities?, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (open) "▾" else "▸", muted = true)
        if (vuln != null) SeverityBadge(vuln.maxSeverity)
        val coordinates = listOfNotNull(component.group, component.name).joinToString(":").ifEmpty { component.name ?: "?" }
        Text(coordinates, style = TextStyle(fontWeight = if (vuln != null) FontWeight.SemiBold else FontWeight.Normal))
        component.version?.let { Text(it, muted = true) }
        if (vuln != null) {
            Text("${vuln.findings.size} CVE${if (vuln.findings.size == 1) "" else "s"}", muted = true)
            ReachabilityChip(componentReachability(vuln))
        }
    }
}

@Composable
private fun ComponentDetails(component: CdxComponent, vuln: ComponentVulnerabilities?, requiredBy: Map<String, List<String>>) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, bottom = 6.dp)) {
        vuln?.findings?.forEach { finding ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                SeverityBadge(finding.severity, finding.baseScore)
                Text(finding.record.cve ?: finding.record.id, style = TextStyle(fontWeight = FontWeight.SemiBold))
                ReachabilityChip(finding.state)
                fixedVersion(finding.record)?.let { Text("fixed in $it", muted = true) }
                finding.record.references.firstOrNull()?.let { url -> Link("advisory") { BrowserUtil.browse(url) } }
            }
            finding.record.summary?.let { Text(it, muted = true, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) }
        }

        val deps = component.bomRef?.let { requiredBy[it] }.orEmpty().distinct()
        if (deps.isNotEmpty()) {
            val shown = deps.take(6).joinToString(", ")
            val more = if (deps.size > 6) " …" else ""
            Text("Required by: $shown$more", muted = true)
        }

        // Class-level (post-DCE) detail when the binary carried it.
        val files = component.children
            .flatMap { it.flatten() }
            .filter { it.kind == CdxComponentKind.SourceFile }
        if (files.isNotEmpty()) {
            Text("Compiled-in sources (${files.size}):", muted = true, modifier = Modifier.padding(top = 2.dp))
            files.take(MAX_FILES).forEach { file ->
                val classes = file.classes()
                val shownClasses = classes.take(MAX_CLASSES).joinToString(", ")
                val moreClasses = if (classes.size > MAX_CLASSES) " …" else ""
                val classText = if (classes.isEmpty()) "" else " — $shownClasses$moreClasses"
                Text("${file.name ?: "?"}$classText", muted = true, mono = true, modifier = Modifier.padding(start = 8.dp))
            }
            if (files.size > MAX_FILES) {
                Text("… and ${files.size - MAX_FILES} more", muted = true, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

// --- small UI primitives -----------------------------------------------------

@Composable
private fun InfoBar(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun SeverityBadge(severity: CvssSeverity, score: Double? = null) {
    val (bg, fg) = severityColors(severity)
    val label = score?.let { "${severity.label} ${trim1(it)}" } ?: severity.label
    Text(
        text = label,
        modifier = Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        style = TextStyle(fontSize = 11.sp, color = fg, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
private fun ReachabilityChip(stateValue: ReachabilityState) {
    Text(stateValue.label, muted = true, style = TextStyle(fontSize = 11.sp))
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    val color = if (JewelTheme.isDark) Color(0xFF589DF6) else Color(0xFF2470B3)
    Text(
        text = text,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
            .clickable { onClick() },
        style = TextStyle(color = color, fontSize = 12.sp),
    )
}

@Composable
private fun Text(
    text: String,
    muted: Boolean = false,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(),
) {
    val color = if (muted) (if (JewelTheme.isDark) Color(0xFF9DA0A8) else Color(0xFF6E7177)) else Color.Unspecified
    val merged = style.copy(
        color = if (style.color == Color.Unspecified) color else style.color,
        fontSize = if (style.fontSize == androidx.compose.ui.unit.TextUnit.Unspecified) 12.sp else style.fontSize,
        fontFamily = if (mono) FontFamily.Monospace else style.fontFamily,
    )
    org.jetbrains.jewel.ui.component.Text(text = text, modifier = modifier, style = merged)
}

@Composable
private fun severityColors(severity: CvssSeverity): Pair<Color, Color> {
    val dark = JewelTheme.isDark
    return when (severity) {
        CvssSeverity.CRITICAL -> (if (dark) Color(0xFF7A2230) else Color(0xFFC0392B)) to Color.White
        CvssSeverity.HIGH -> (if (dark) Color(0xFF7A4A1E) else Color(0xFFD35400)) to Color.White
        CvssSeverity.MEDIUM -> (if (dark) Color(0xFF6B5D1E) else Color(0xFFB7950B)) to Color.White
        CvssSeverity.LOW -> (if (dark) Color(0xFF35506B) else Color(0xFF2E6DA4)) to Color.White
        CvssSeverity.NONE, CvssSeverity.UNKNOWN ->
            (if (dark) Color(0xFF4D4D4D) else Color(0xFFE0E0E0)) to (if (dark) Color.White else Color.Black)
    }
}

// --- pure helpers ------------------------------------------------------------

private const val MAX_FILES = 50
private const val MAX_CLASSES = 12

private fun componentKey(c: CdxComponent): String =
    c.purl ?: c.bomRef ?: listOfNotNull(c.group, c.name).joinToString(":").ifEmpty { c.name ?: "?" }

private fun componentReachability(vuln: ComponentVulnerabilities): ReachabilityState = when {
    vuln.findings.any { it.state == ReachabilityState.VulnerableClassRetained } -> ReachabilityState.VulnerableClassRetained
    vuln.findings.any { it.state == ReachabilityState.ComponentPresent } -> ReachabilityState.ComponentPresent
    else -> ReachabilityState.VulnerableClassEliminated
}

private fun fixedVersion(record: OsvRecord): String? =
    record.affected.asSequence().flatMap { it.ranges }.flatMap { it.events }
        .firstOrNull { it.kind == OsvEvent.Kind.Fixed }?.version

private fun buildRequiredBy(sbom: CycloneDxSbom): Map<String, List<String>> {
    val nameByRef = sbom.allComponents().associate { c ->
        (c.bomRef ?: "") to listOfNotNull(c.group, c.name).joinToString(":").ifEmpty { c.name ?: c.bomRef ?: "?" }
    }
    val map = HashMap<String, MutableList<String>>()
    for (dep in sbom.dependencies) {
        for (child in dep.dependsOn) {
            map.getOrPut(child) { mutableListOf() }.add(nameByRef[dep.ref] ?: dep.ref)
        }
    }
    return map
}

private fun trim1(d: Double): String {
    val rounded = (d * 10).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}.0" else rounded.toString()
}

private fun agoLabel(updated: Instant): String {
    val days = ChronoUnit.DAYS.between(updated, Instant.now())
    return when {
        days <= 0L -> "updated today"
        days == 1L -> "updated 1 day ago"
        else -> "updated $days days ago"
    }
}

private suspend fun computeVulnState(sbom: CycloneDxSbom, settings: HexanaSettings): VulnUiState =
    withContext(Dispatchers.IO) {
        if (!settings.lookupVulnerabilities) return@withContext VulnUiState.Disabled
        val db = OsvDatabase.getInstance()
        if (!db.isPresent()) return@withContext VulnUiState.DbMissing
        val offline = db.loadRecords() ?: return@withContext VulnUiState.DbMissing
        val online = if (settings.vulnerabilitiesOnline) {
            OsvApiClient.queryByPurls(sbom.components.mapNotNull { it.purl })
        } else {
            emptyList()
        }
        val matcher = VulnerabilityMatcher(VulnerabilityMatcher.indexByMavenPackage(offline + online))
        VulnUiState.Ready(ReachabilityAnalyzer.analyze(sbom, matcher), db.lastUpdated())
    }

private fun downloadDatabase(project: Project, onDone: () -> Unit) {
    ProgressManager.getInstance().run(
        object : Task.Backgroundable(project, "Downloading vulnerability database", true) {
            override fun run(indicator: ProgressIndicator) {
                val db = OsvDatabase.getInstance()
                db.download(indicator)
                db.loadMatcher(indicator)
            }

            override fun onSuccess() = onDone()
        },
    )
}

package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flex
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.minHeight
import org.jetbrains.compose.web.css.opacity
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.NativeImageInfo
import org.jetbrains.hexana.providers.SbomLocator
import org.jetbrains.hexana.providers.sbom.CdxComponent
import org.jetbrains.hexana.providers.sbom.CdxComponentKind
import org.jetbrains.hexana.providers.sbom.CycloneDxParser
import org.jetbrains.hexana.providers.sbom.CycloneDxSbom
import org.jetbrains.hexana.providers.sbom.extractSbomJson
import org.jetbrains.hexana.providers.sbom.vuln.SbomVulnerabilityReport

private sealed interface SbomLoadState {
    object Loading : SbomLoadState
    data class ExtractionFailed(val reason: String) : SbomLoadState
    data class ParseFailed(val reason: String) : SbomLoadState
    data class Loaded(val sbom: CycloneDxSbom, val json: String) : SbomLoadState
}

/**
 * Tab content for the embedded CycloneDX SBOM in a GraalVM Native Image binary.
 *
 * Extraction and parsing run in a [LaunchedEffect]; the composable renders a
 * loading placeholder until they complete, then shows a header and the component list.
 *
 * @param provider the native binary whose bytes will be read.
 * @param locator  file coordinates of the embedded SBOM blob.
 * @param graalVersion the version string from [NativeImageInfo.version], shown in the header.
 */
@Composable
fun SbomTabContent(
    provider: NativeBinaryProvider,
    locator: SbomLocator,
    graalVersion: String?,
    fileName: String = "",
    vulnerabilityCheck: SbomVulnerabilityContribution? = null,
) {
    var state by remember(provider, locator) { mutableStateOf<SbomLoadState>(SbomLoadState.Loading) }

    LaunchedEffect(provider, locator) {
        val json = extractSbomJson(provider.bytes, locator)
        if (json == null) {
            state = SbomLoadState.ExtractionFailed("extraction failed")
            return@LaunchedEffect
        }
        val sbom = CycloneDxParser.parse(json)
        state = if (sbom == null) SbomLoadState.ParseFailed("not a valid CycloneDX document")
        else SbomLoadState.Loaded(sbom, json)
    }

    when (val s = state) {
        is SbomLoadState.Loading -> StatusMessage("Loading SBOM…", muted = true)
        is SbomLoadState.ExtractionFailed -> StatusMessage("Error: ${s.reason}")
        is SbomLoadState.ParseFailed -> StatusMessage("Error: ${s.reason}")
        is SbomLoadState.Loaded -> SbomLoaded(s.sbom, s.json, graalVersion, fileName, vulnerabilityCheck)
    }
}

@Composable
private fun SbomLoaded(
    sbom: CycloneDxSbom,
    json: String,
    graalVersion: String?,
    fileName: String,
    vulnerabilityCheck: SbomVulnerabilityContribution?,
) {
    var vulnReport by remember(sbom) {
        mutableStateOf<SbomVulnerabilityReport?>(null)
    }
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            flex("1")
            minHeight(0.px)
        }
    }) {
        SbomHeaderRow(sbom, json, graalVersion, fileName)
        vulnerabilityCheck?.render?.invoke(sbom) { report -> vulnReport = report }
        SbomComponentList(sbom, vulnReport)
    }
}

@Composable
private fun SbomHeaderRow(sbom: CycloneDxSbom, json: String, graalVersion: String?, fileName: String) {
    Div({
        style {
            padding(0.px, 0.px, 8.px, 0.px)
        }
    }) {
        // Format + spec version: "CycloneDX 1.5"
        val formatLabel = buildString {
            append(sbom.bomFormat ?: "CycloneDX")
            sbom.specVersion?.let { append(' ').append(it) }
        }
        Div({
            style {
                fontWeight("600")
                fontSize(13.px)
            }
        }) {
            Text(formatLabel)
        }

        // Component count
        val count = sbom.components.size
        Div({
            style {
                fontSize(12.px)
                opacity(0.75)
                padding(2.px, 0.px)
            }
        }) {
            Text("$count component${if (count == 1) "" else "s"}")
        }

        // GraalVM version if available
        if (!graalVersion.isNullOrBlank()) {
            Div({
                style {
                    fontSize(12.px)
                    opacity(0.75)
                }
            }) {
                Text("Producer: $graalVersion")
            }
        }

        // "View raw JSON" link
        val host = LocalHost.current
        val baseName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val jsonFileName = baseName.let {
            val dot = it.lastIndexOf('.')
            if (dot < 0) "$it.cdx.json" else it.substring(0, dot) + ".cdx.json"
        }
        Span({
            style {
                fontSize(12.px)
                property("color", "var(--vscode-textLink-foreground, #3794FF)")
                property("cursor", "pointer")
                property("display", "inline-block")
                property("margin-top", "4px")
            }
            onClick { host.openTextFile(jsonFileName, json) }
        }) {
            Text("View raw JSON")
        }
    }
}

/** Component list. VirtualizedList requires a fixed item height; with expandable details
 * that is not practical here, so a plain scrollable Div is used. Hundreds of entries
 * are fine for the DOM at this size. When a [vulnReport] is present, affected components
 * sort first by worst severity descending, then the rest alphabetically. */
@Composable
private fun SbomComponentList(
    sbom: CycloneDxSbom,
    vulnReport: SbomVulnerabilityReport?,
) {
    val components = sbom.components
    val vulnByKey = remember(vulnReport) {
        vulnReport?.vulnerableComponents?.associateBy { componentKey(it.component) } ?: emptyMap()
    }
    val requiredBy = remember(sbom) { buildRequiredBy(sbom) }
    val sorted = remember(components, vulnReport) {
        if (vulnReport == null) {
            components.sortedBy { (it.name ?: "").lowercase() }
        } else {
            components.sortedWith(
                compareByDescending<CdxComponent> {
                    vulnByKey[componentKey(it)]?.maxSeverity?.rank ?: -2
                }.thenBy { (it.name ?: "").lowercase() }
            )
        }
    }
    val expanded = remember(components) { mutableStateMapOf<String, Boolean>() }

    Div({
        style {
            flex("1")
            minHeight(0.px)
            property("overflow-y", "auto")
        }
    }) {
        for (component in sorted) {
            val key = componentKey(component)
            val vuln = vulnByKey[key]
            val isOpen = expanded[key] == true
            // Row
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("align-items", "baseline")
                    property("gap", "6px")
                    padding(3.px, 0.px)
                    property("cursor", "pointer")
                    property("user-select", "none")
                }
                onClick {
                    expanded[key] = !isOpen
                }
            }) {
                // Expand toggle
                Span({
                    style {
                        opacity(0.5)
                        fontSize(11.px)
                        width(12.px)
                        property("flex-shrink", "0")
                    }
                }) { Text(if (isOpen) "▾" else "▸") }

                // Severity badge when a vuln report exists for this component
                if (vuln != null) {
                    SeverityChip(vuln.maxSeverity)
                }

                // Name (group:name or just name)
                val coordinates = listOfNotNull(component.group, component.name)
                    .joinToString(":").ifEmpty { component.name ?: "?" }
                Span({
                    style {
                        fontWeight(if (vuln != null) "600" else "500")
                    }
                }) { Text(coordinates) }

                // Version
                if (!component.version.isNullOrBlank()) {
                    Span({
                        style {
                            opacity(0.65)
                            fontSize(12.px)
                        }
                    }) { Text(component.version!!) }
                }

                // CVE count hint
                if (vuln != null) {
                    Span({ style { opacity(0.6); fontSize(11.px) } }) {
                        val n = vuln.findings.size
                        Text("$n CVE${if (n == 1) "" else "s"}")
                    }
                    Span({ style { opacity(0.55); fontSize(11.px) } }) {
                        Text(worstReachability(vuln).label)
                    }
                }

                // Kind badge (only for SourceFile; Library is the common case, skip noise)
                if (component.kind == CdxComponentKind.SourceFile) {
                    Span({
                        style {
                            fontSize(10.px)
                            opacity(0.55)
                            property("font-style", "italic")
                        }
                    }) { Text("file") }
                }
            }

            // Expanded details
            if (isOpen) {
                Div({
                    style {
                        padding(2.px, 0.px, 6.px, 18.px)
                        fontSize(12.px)
                        opacity(0.8)
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "2px")
                    }
                }) {
                    if (vuln != null) {
                        VulnCveRows(vuln, requiredBy)
                    }
                    if (!component.purl.isNullOrBlank()) {
                        DetailLine("purl", component.purl!!)
                    }
                    if (component.licenses.isNotEmpty()) {
                        DetailLine("license", component.licenses.joinToString(", "))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("gap", "6px")
        }
    }) {
        Span({ style { opacity(0.55) } }) { Text("$label:") }
        Span({
            style {
                property("font-family", "var(--vscode-editor-font-family, monospace)")
                property("word-break", "break-all")
            }
        }) { Text(value) }
    }
}

@Composable
private fun StatusMessage(text: String, muted: Boolean = false) {
    Div({
        style {
            padding(8.px)
            if (muted) opacity(0.65)
        }
    }) {
        Text(text)
    }
}

package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flex
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.opacity
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.paddingTop
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.sbom.CdxComponent
import org.jetbrains.hexana.providers.sbom.CdxComponentKind
import org.jetbrains.hexana.providers.sbom.CycloneDxSbom
import org.jetbrains.hexana.providers.sbom.vuln.ComponentVulnerabilities
import org.jetbrains.hexana.providers.sbom.vuln.CvssSeverity
import org.jetbrains.hexana.providers.sbom.vuln.OsvConstants
import org.jetbrains.hexana.providers.sbom.vuln.OsvEvent
import org.jetbrains.hexana.providers.sbom.vuln.OsvParser
import org.jetbrains.hexana.providers.sbom.vuln.OsvRecord
import org.jetbrains.hexana.providers.sbom.vuln.ReachabilityAnalyzer
import org.jetbrains.hexana.providers.sbom.vuln.ReachabilityState
import org.jetbrains.hexana.providers.sbom.vuln.SbomVulnerabilityReport
import org.jetbrains.hexana.providers.sbom.vuln.VulnerabilityMatcher
import kotlinx.coroutines.await
import kotlin.js.Promise

// ---------------------------------------------------------------------------
// State machine
// ---------------------------------------------------------------------------

internal sealed interface VulnCheckState {
    /** Not yet triggered — show the opt-in bar. */
    object Idle : VulnCheckState

    /** Pipeline is running; [hint] is a short progress description. */
    data class Checking(val hint: String) : VulnCheckState

    /** Pipeline completed successfully. */
    data class Ready(val report: SbomVulnerabilityReport) : VulnCheckState

    /** Network or parse failure; [message] is user-visible. */
    data class Failed(val message: String) : VulnCheckState
}

// ---------------------------------------------------------------------------
// Network helpers
// ---------------------------------------------------------------------------

/**
 * External declarations instead of js() string literals: js() literals that
 * reference enclosing Kotlin parameters break under the production
 * (optimizing) Kotlin/JS compile, which renames the parameters while the
 * literal keeps the original names ("ReferenceError: url is not defined").
 * See binaryProvider/src/jsMain/.../gunzip.js.kt for the same pattern.
 */
@Suppress("UNUSED_PARAMETER")
private external fun fetch(url: String): Promise<dynamic>

@Suppress("UNUSED_PARAMETER")
private external fun fetch(url: String, init: dynamic): Promise<dynamic>

/** Returns a Promise that resolves to the response body as a plain String. */
private fun fetchTextGet(url: String): Promise<String> =
    fetch(url).then<dynamic> { r ->
        if (r.ok != true) throw Error("HTTP ${r.status}")
        r.text()
    }.unsafeCast<Promise<String>>()

/** Returns a Promise that resolves to the response body as a plain String, or rejects on non-2xx. */
private fun fetchTextPost(url: String, body: String): Promise<String> {
    val init: dynamic = js("{}")
    init.method = "POST"
    val headers: dynamic = js("{}")
    headers["Content-Type"] = "application/json"
    init.headers = headers
    init.body = body
    return fetch(url, init).then<dynamic> { r ->
        if (r.ok != true) throw Error("HTTP ${r.status}")
        r.text()
    }.unsafeCast<Promise<String>>()
}

// ---------------------------------------------------------------------------
// Vuln pipeline
// ---------------------------------------------------------------------------

/**
 * Runs the full OSV online vulnerability pipeline for [sbom]:
 *   1. Batch-POST purls to api.osv.dev/v1/querybatch (in chunks of [OsvConstants.MAX_BATCH_SIZE]).
 *   2. GET each unique advisory id from api.osv.dev/v1/vulns/<id>.
 *   3. Parse records with [OsvParser], build a [VulnerabilityMatcher], call [ReachabilityAnalyzer.analyze].
 *
 * [onProgress] is called with a short human-readable hint at each stage.
 * Throws on non-2xx HTTP or unrecoverable parse failure.
 */
internal suspend fun fetchVulnReport(
    sbom: CycloneDxSbom,
    onProgress: (String) -> Unit,
): SbomVulnerabilityReport {
    // GraalVM 21's --enable-sbom=cyclonedx omits purl fields; synthesize them
    // from Maven coordinates when present so the OSV querybatch still works.
    val purls = sbom.components.mapNotNull { c ->
        c.purl?.takeIf { it.isNotBlank() }
            ?: run {
                val g = c.group?.takeIf { it.isNotBlank() } ?: return@run null
                val n = c.name?.takeIf { it.isNotBlank() } ?: return@run null
                val v = c.version?.takeIf { it.isNotBlank() }
                if (v != null) "pkg:maven/$g/$n@$v" else "pkg:maven/$g/$n"
            }
    }

    onProgress("Querying ${purls.size} components…")

    // 1. Batch purl query — chunked to MAX_BATCH_SIZE
    val allAdvisoryIds = mutableSetOf<String>()
    val chunks = purls.chunked(OsvConstants.MAX_BATCH_SIZE)
    for ((chunkIdx, chunk) in chunks.withIndex()) {
        if (chunks.size > 1) onProgress("Batch ${chunkIdx + 1}/${chunks.size}…")
        val requestBody = buildString {
            append("{\"queries\":[")
            chunk.forEachIndexed { i, purl ->
                if (i > 0) append(",")
                append("{\"package\":{\"purl\":")
                append(jsonString(purl))
                append("}}")
            }
            append("]}")
        }
        val json = fetchTextPost(OsvConstants.QUERY_BATCH_URL, requestBody).await()
        val ids = OsvParser.parseQueryBatchResponse(json, chunk).values.flatten()
        allAdvisoryIds.addAll(ids)
    }

    // 2. Fetch each unique advisory
    onProgress("Fetching ${allAdvisoryIds.size} advisories…")
    val records = mutableListOf<OsvRecord>()
    for (id in allAdvisoryIds) {
        val url = OsvConstants.VULN_BY_ID_URL + id
        // Best-effort: a missing advisory shouldn't abort the whole run.
        runCatching { fetchTextGet(url).await() }.getOrNull()
            ?.let { OsvParser.parseRecord(it) }
            ?.let { records.add(it) }
    }

    // 3. Match + analyze
    onProgress("Analyzing…")
    val matcher = VulnerabilityMatcher(VulnerabilityMatcher.indexByMavenPackage(records))
    return ReachabilityAnalyzer.analyze(sbom, matcher)
}

/** Minimal JSON string escaping for use in hand-built JSON. */
private fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

/**
 * Opt-in vulnerability overlay bar, placed between the SBOM header and the component list.
 *
 * Manages its own [VulnCheckState]; the report is surfaced via [onReportReady] so the
 * component list can reorder itself without coupling both composables to a shared parent state.
 */
@Composable
internal fun VulnBar(sbom: CycloneDxSbom, onReportReady: (SbomVulnerabilityReport?) -> Unit) {
    var state by remember(sbom) { mutableStateOf<VulnCheckState>(VulnCheckState.Idle) }
    val scope = rememberCoroutineScope()

    Div({
        style {
            padding(6.px, 0.px)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "4px")
            property("border-bottom", "1px solid var(--vscode-panel-border, rgba(255,255,255,0.1))")
            marginTop(4.px)
        }
    }) {
        when (val s = state) {
            is VulnCheckState.Idle -> {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("align-items", "center")
                        property("gap", "8px")
                        property("flex-wrap", "wrap")
                    }
                }) {
                    Button({
                        style {
                            property("background", "var(--vscode-button-background, #0e639c)")
                            property("color", "var(--vscode-button-foreground, #fff)")
                            property("border", "none")
                            property("border-radius", "2px")
                            padding(3.px, 8.px)
                            fontSize(12.px)
                            property("cursor", "pointer")
                        }
                        onClick {
                            state = VulnCheckState.Checking("Starting…")
                            scope.launch {
                                runCatching {
                                    fetchVulnReport(sbom) { hint -> state = VulnCheckState.Checking(hint) }
                                }.fold(
                                    onSuccess = { report ->
                                        state = VulnCheckState.Ready(report)
                                        onReportReady(report)
                                    },
                                    onFailure = { err ->
                                        state = VulnCheckState.Failed(err.message ?: "Network error")
                                        onReportReady(null)
                                    },
                                )
                            }
                        }
                    }) { Text("Check vulnerabilities online") }

                    Span({
                        style {
                            fontSize(11.px)
                            opacity(0.55)
                        }
                    }) { Text("Sends component identifiers (purls) to api.osv.dev") }
                }
            }

            is VulnCheckState.Checking -> {
                Span({ style { fontSize(12.px); opacity(0.7) } }) {
                    Text("Checking… ${s.hint}")
                }
            }

            is VulnCheckState.Ready -> {
                val r = s.report
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("align-items", "center")
                        property("gap", "6px")
                        property("flex-wrap", "wrap")
                        fontSize(12.px)
                    }
                }) {
                    if (r.vulnerableComponentCount == 0) {
                        Span({ style { opacity(0.75) } }) {
                            Text("No known vulnerabilities in ${r.scannedComponentCount} components.")
                        }
                    } else {
                        SeverityChip(r.maxSeverity)
                        Span {
                            Text("${r.vulnerableComponentCount} of ${r.scannedComponentCount} components affected · ${r.totalFindings} advisories")
                        }
                    }
                }
            }

            is VulnCheckState.Failed -> {
                Span({
                    style {
                        fontSize(12.px)
                        property("color", "var(--vscode-errorForeground, #f48771)")
                    }
                }) {
                    Text("Vulnerability check failed: ${s.message}")
                }
            }
        }
    }
}

/** Small coloured pill showing the severity label (and optionally the score). */
@Composable
internal fun SeverityChip(severity: CvssSeverity, score: Double? = null) {
    val (bg, fg) = severityBadgeColors(severity)
    val label = if (score != null) "${severity.label} ${trim1dp(score)}" else severity.label
    Span({
        style {
            property("background", bg)
            property("color", fg)
            property("border-radius", "3px")
            padding(1.px, 5.px)
            fontSize(11.px)
            fontWeight("600")
        }
    }) { Text(label) }
}

private fun severityBadgeColors(severity: CvssSeverity): Pair<String, String> = when (severity) {
    CvssSeverity.CRITICAL -> "#c0392b" to "#fff"
    CvssSeverity.HIGH     -> "#d35400" to "#fff"
    CvssSeverity.MEDIUM   -> "#b7950b" to "#fff"
    CvssSeverity.LOW      -> "#2e6da4" to "#fff"
    else                  -> "var(--vscode-badge-background, #4d4d4d)" to "var(--vscode-badge-foreground, #fff)"
}

private fun trim1dp(d: Double): String {
    val rounded = (d * 10).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}.0" else rounded.toString()
}

/**
 * Per-CVE detail rows rendered inside an expanded component row, mirroring [ComponentDetails]
 * in the IntelliJ plugin.
 */
@Composable
internal fun VulnCveRows(vuln: ComponentVulnerabilities, requiredBy: Map<String, List<String>>) {
    // CVE rows
    for (finding in vuln.findings) {
        Div({
            style {
                display(DisplayStyle.Flex)
                property("align-items", "baseline")
                property("gap", "6px")
                property("flex-wrap", "wrap")
                padding(2.px, 0.px)
            }
        }) {
            SeverityChip(finding.severity, finding.baseScore)
            Span({ style { fontWeight("600"); fontSize(12.px) } }) {
                Text(finding.record.cve ?: finding.record.id)
            }
            // Reachability text
            Span({ style { opacity(0.6); fontSize(11.px) } }) {
                Text(finding.state.label)
            }
            // Fixed-in version
            fixedVersionOf(finding.record)?.let { fv ->
                Span({ style { opacity(0.6); fontSize(11.px) } }) { Text("fixed in $fv") }
            }
            // Advisory link — first reference URL
            finding.record.references.firstOrNull()?.let { url ->
                A(href = url, {
                    attr("target", "_blank")
                    attr("rel", "noopener")
                    style {
                        fontSize(11.px)
                        property("color", "var(--vscode-textLink-foreground, #3794FF)")
                    }
                }) { Text("advisory") }
            }
        }
        // Summary line
        finding.record.summary?.let { summary ->
            Div({
                style {
                    fontSize(11.px)
                    opacity(0.6)
                    padding(0.px, 0.px, 2.px, 4.px)
                }
            }) { Text(summary) }
        }
    }

    // "Required by:" line
    val component = vuln.component
    val deps = component.bomRef?.let { requiredBy[it] }.orEmpty().distinct()
    if (deps.isNotEmpty()) {
        val shown = deps.take(6).joinToString(", ")
        val more = if (deps.size > 6) " …" else ""
        Div({ style { fontSize(11.px); opacity(0.6); paddingTop(2.px) } }) {
            Text("Required by: $shown$more")
        }
    }

    // "Compiled-in sources (N):" list
    val files = component.children
        .flatMap { it.flatten() }
        .filter { it.kind == CdxComponentKind.SourceFile }
    if (files.isNotEmpty()) {
        Div({ style { fontSize(11.px); opacity(0.6); paddingTop(2.px) } }) {
            Text("Compiled-in sources (${files.size}):")
        }
        files.take(MAX_SHOWN_FILES).forEach { file ->
            val classes = file.classes()
            val shown = classes.take(MAX_SHOWN_CLASSES).joinToString(", ")
            val more = if (classes.size > MAX_SHOWN_CLASSES) " …" else ""
            val classText = if (classes.isEmpty()) "" else " — $shown$more"
            Div({ style { fontSize(11.px); opacity(0.55); padding(0.px, 0.px, 0.px, 8.px) } }) {
                Text("${file.name ?: "?"}$classText")
            }
        }
        if (files.size > MAX_SHOWN_FILES) {
            Div({ style { fontSize(11.px); opacity(0.55); padding(0.px, 0.px, 0.px, 8.px) } }) {
                Text("… and ${files.size - MAX_SHOWN_FILES} more")
            }
        }
    }
}

private const val MAX_SHOWN_FILES = 10
private const val MAX_SHOWN_CLASSES = 5

/** Extracts the "Fixed" version from an OSV record's range events. */
internal fun fixedVersionOf(record: OsvRecord): String? =
    record.affected.asSequence()
        .flatMap { it.ranges }
        .flatMap { it.events }
        .firstOrNull { it.kind == OsvEvent.Kind.Fixed }
        ?.version

/** Dominant reachability across a component's findings (worst = most visible). */
internal fun worstReachability(vuln: ComponentVulnerabilities): ReachabilityState = when {
    vuln.findings.any { it.state == ReachabilityState.VulnerableClassRetained } ->
        ReachabilityState.VulnerableClassRetained
    vuln.findings.any { it.state == ReachabilityState.ComponentPresent } ->
        ReachabilityState.ComponentPresent
    else -> ReachabilityState.VulnerableClassEliminated
}

/** Stable key matching the IntelliJ plugin's `componentKey`. */
internal fun componentKey(c: CdxComponent): String =
    c.purl ?: c.bomRef ?: listOfNotNull(c.group, c.name).joinToString(":").ifEmpty { c.name ?: "?" }

/** Builds the "required by" reverse-dependency map from [CycloneDxSbom.dependencies]. */
internal fun buildRequiredBy(sbom: CycloneDxSbom): Map<String, List<String>> {
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

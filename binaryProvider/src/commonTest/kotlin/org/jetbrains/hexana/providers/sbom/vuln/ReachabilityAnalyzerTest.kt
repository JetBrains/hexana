package org.jetbrains.hexana.providers.sbom.vuln

import org.jetbrains.hexana.providers.sbom.CdxComponent
import org.jetbrains.hexana.providers.sbom.CdxProperty
import org.jetbrains.hexana.providers.sbom.CycloneDxSbom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReachabilityAnalyzerTest {

    @Test
    fun `component-level sbom reports ComponentPresent with severity`() {
        val sbom = sbom(listOf(netty("4.1.76.Final")))
        val report = ReachabilityAnalyzer.analyze(sbom, matcherForNetty())

        assertEquals(1, report.scannedComponentCount)
        assertEquals(1, report.vulnerableComponentCount)
        assertEquals(false, report.classLevelAvailable)

        val finding = report.vulnerableComponents.single().findings.single()
        assertEquals(ReachabilityState.ComponentPresent, finding.state)
        assertEquals(CvssSeverity.HIGH, finding.severity)
        assertEquals(CvssSeverity.HIGH, report.maxSeverity)
    }

    @Test
    fun `patched version produces no findings`() {
        val report = ReachabilityAnalyzer.analyze(sbom(listOf(netty("4.1.77.Final"))), matcherForNetty())
        assertEquals(0, report.vulnerableComponentCount)
        assertEquals(CvssSeverity.NONE, report.maxSeverity)
    }

    @Test
    fun `class-level data refines reachability via affected classes`() {
        val sbom = sbom(listOf(nettyClassLevel("4.1.76.Final", survivingClass = "Http2Headers")))
        val matcher = matcherForNetty()

        val retained = ReachabilityAnalyzer.analyze(sbom, matcher) { listOf("Http2Headers") }
        assertTrue(retained.classLevelAvailable)
        assertEquals(
            ReachabilityState.VulnerableClassRetained,
            retained.vulnerableComponents.single().findings.single().state,
        )

        val eliminated = ReachabilityAnalyzer.analyze(sbom, matcher) { listOf("SomeOtherClass") }
        assertEquals(
            ReachabilityState.VulnerableClassEliminated,
            eliminated.vulnerableComponents.single().findings.single().state,
        )
    }

    // --- fixtures ------------------------------------------------------------

    private fun matcherForNetty(): VulnerabilityMatcher {
        val record = OsvRecord(
            id = "GHSA-netty",
            aliases = listOf("CVE-2024-9999"),
            summary = "netty http2 flaw",
            severities = listOf(OsvSeverity("CVSS_V3", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N")),
            affected = listOf(
                OsvAffected(
                    ecosystem = "Maven",
                    packageName = "io.netty:netty-codec-http2",
                    ranges = listOf(
                        OsvRange("ECOSYSTEM", listOf(
                            OsvEvent(OsvEvent.Kind.Introduced, "0"),
                            OsvEvent(OsvEvent.Kind.Fixed, "4.1.77.Final"),
                        )),
                    ),
                    versions = emptyList(),
                ),
            ),
            references = emptyList(),
        )
        return VulnerabilityMatcher(VulnerabilityMatcher.indexByMavenPackage(listOf(record)))
    }

    private fun netty(version: String, children: List<CdxComponent> = emptyList()) = CdxComponent(
        type = "library",
        group = "io.netty",
        name = "netty-codec-http2",
        version = version,
        purl = "pkg:maven/io.netty/netty-codec-http2@$version",
        bomRef = "pkg:maven/io.netty/netty-codec-http2@$version",
        licenses = emptyList(),
        properties = emptyList(),
        children = children,
    )

    private fun nettyClassLevel(version: String, survivingClass: String): CdxComponent {
        val file = CdxComponent(
            type = "file",
            group = null,
            name = "io/netty/handler/codec/http2/Http2Headers.java",
            version = null,
            purl = null,
            bomRef = null,
            licenses = emptyList(),
            properties = listOf(CdxProperty("Classes", survivingClass)),
            children = emptyList(),
        )
        val module = CdxComponent("library", null, "netty.codec.http2", null, null, null, emptyList(), emptyList(), listOf(file))
        return netty(version, children = listOf(module))
    }

    private fun sbom(components: List<CdxComponent>) = CycloneDxSbom(
        bomFormat = "CycloneDX",
        specVersion = "1.4",
        serialNumber = null,
        version = 1,
        metadataTimestamp = null,
        mainComponent = null,
        tools = emptyList(),
        components = components,
        dependencies = emptyList(),
        vulnerabilities = emptyList(),
    )
}
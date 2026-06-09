package org.jetbrains.hexana.nativebin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneDxParserTest {

    @Test
    fun `parses cyclonedx 1_5 with object-form tools, licenses and vulnerabilities`() {
        val json = """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.5",
              "serialNumber": "urn:uuid:abc",
              "version": 1,
              "metadata": {
                "timestamp": "2026-01-02T03:04:05Z",
                "tools": { "components": [ { "type": "application", "name": "GraalVM Native Image", "version": "23" } ] },
                "component": { "type": "application", "name": "demo-app", "version": "1.0.0" }
              },
              "components": [
                { "type": "library", "group": "com.google.guava", "name": "guava", "version": "32.1.3-jre",
                  "purl": "pkg:maven/com.google.guava/guava@32.1.3-jre",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ] },
                { "type": "library", "name": "slf4j-api", "version": "2.0.9",
                  "licenses": [ { "expression": "MIT" } ] }
              ],
              "dependencies": [ { "ref": "a", "dependsOn": ["b"] }, { "ref": "b" } ],
              "vulnerabilities": [ { "id": "CVE-2024-1234", "ratings": [ { "severity": "high" } ] } ]
            }
        """.trimIndent()

        val sbom = requireNotNull(CycloneDxParser.parse(json))

        assertEquals("CycloneDX", sbom.bomFormat)
        assertEquals("1.5", sbom.specVersion)
        assertEquals(1, sbom.version)
        assertEquals("2026-01-02T03:04:05Z", sbom.metadataTimestamp)
        assertEquals("demo-app", sbom.mainComponent?.name)
        assertEquals("1.0.0", sbom.mainComponent?.version)
        assertEquals(listOf("GraalVM Native Image 23"), sbom.tools)

        assertEquals(2, sbom.components.size)
        val guava = sbom.components[0]
        assertEquals("com.google.guava", guava.group)
        assertEquals("guava", guava.name)
        assertEquals("pkg:maven/com.google.guava/guava@32.1.3-jre", guava.purl)
        assertEquals(listOf("Apache-2.0"), guava.licenses)

        val slf4j = sbom.components[1]
        assertNull(slf4j.group)
        assertEquals(listOf("MIT"), slf4j.licenses)

        assertEquals(2, sbom.dependencyCount)
        assertEquals(1, sbom.vulnerabilities.size)
        assertEquals("CVE-2024-1234", sbom.vulnerabilities[0].id)
        assertEquals("high", sbom.vulnerabilities[0].severity)
    }

    @Test
    fun `parses cyclonedx 1_4 array-form tools`() {
        val json = """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.4",
              "components": [],
              "metadata": { "tools": [ { "vendor": "GraalVM", "name": "native-image", "version": "21" } ] }
            }
        """.trimIndent()

        val sbom = requireNotNull(CycloneDxParser.parse(json))
        assertEquals(listOf("native-image 21"), sbom.tools)
        assertTrue(sbom.components.isEmpty())
    }

    @Test
    fun `accepts a components array even without an explicit bomFormat`() {
        val json = """{ "components": [ { "type": "library", "name": "x", "version": "1" } ] }"""
        val sbom = requireNotNull(CycloneDxParser.parse(json))
        assertEquals(1, sbom.components.size)
    }

    @Test
    fun `rejects non-cyclonedx json`() {
        assertNull(CycloneDxParser.parse("""{ "hello": "world" }"""))
    }

    @Test
    fun `rejects malformed json`() {
        assertNull(CycloneDxParser.parse("this is not json {"))
        assertNull(CycloneDxParser.parse("[1, 2, 3]"))
    }
}
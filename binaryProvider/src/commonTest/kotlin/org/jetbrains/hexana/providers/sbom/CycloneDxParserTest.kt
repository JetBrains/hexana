package org.jetbrains.hexana.providers.sbom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CycloneDxParserTest {

    @Test
    fun parseCycloneDx15WithObjectFormToolsLicensesAndVulnerabilities() {
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

        val sbom = assertNotNull(CycloneDxParser.parse(json))

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
        assertEquals(2, sbom.dependencies.size)
        assertEquals("a", sbom.dependencies[0].ref)
        assertEquals(listOf("b"), sbom.dependencies[0].dependsOn)
        assertEquals(1, sbom.vulnerabilities.size)
        assertEquals("CVE-2024-1234", sbom.vulnerabilities[0].id)
        assertEquals("high", sbom.vulnerabilities[0].severity)
    }

    @Test
    fun parseCycloneDx14ArrayFormTools() {
        val json = """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.4",
              "components": [],
              "metadata": { "tools": [ { "vendor": "GraalVM", "name": "native-image", "version": "21" } ] }
            }
        """.trimIndent()

        val sbom = assertNotNull(CycloneDxParser.parse(json))
        assertEquals(listOf("native-image 21"), sbom.tools)
        assertTrue(sbom.components.isEmpty())
    }

    @Test
    fun acceptsComponentsArrayWithoutExplicitBomFormat() {
        val json = """{ "components": [ { "type": "library", "name": "x", "version": "1" } ] }"""
        val sbom = assertNotNull(CycloneDxParser.parse(json))
        assertEquals(1, sbom.components.size)
    }

    @Test
    fun rejectsNonCycloneDxJson() {
        assertNull(CycloneDxParser.parse("""{ "hello": "world" }"""))
    }

    @Test
    fun rejectsMalformedJson() {
        assertNull(CycloneDxParser.parse("this is not json {"))
        assertNull(CycloneDxParser.parse("[1, 2, 3]"))
    }

    @Test
    fun parsesClassLevelNestedComponentsPropertiesAndDependencyGraph() {
        val json = """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.4",
              "version": 1,
              "components": [
                {
                  "type": "library",
                  "group": "io.netty",
                  "name": "netty-codec-http2",
                  "version": "4.1.76.Final",
                  "bom-ref": "pkg:maven/io.netty/netty-codec-http2@4.1.76.Final",
                  "purl": "pkg:maven/io.netty/netty-codec-http2@4.1.76.Final",
                  "properties": [ { "name": "syft:cpe23", "value": "cpe:2.3:a:netty:netty:4.1.76:*:*:*:*:*:*:*" } ],
                  "components": [
                    {
                      "type": "library",
                      "name": "netty.codec.http2",
                      "components": [
                        {
                          "type": "file",
                          "name": "io/netty/handler/codec/http2/Http2Headers.java",
                          "properties": [
                            { "name": "Classes", "value": "Http2Headers\nHttp2HeadersFrame" },
                            { "name": "Methods", "value": "add\nget" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ],
              "dependencies": [
                { "ref": "pkg:maven/io.netty/netty-codec-http2@4.1.76.Final",
                  "dependsOn": [ "pkg:maven/io.netty/netty-common@4.1.76.Final" ] },
                { "ref": "pkg:maven/io.netty/netty-common@4.1.76.Final", "dependsOn": [] }
              ]
            }
        """.trimIndent()

        val sbom = assertNotNull(CycloneDxParser.parse(json))

        assertEquals(1, sbom.components.size)
        val lib = sbom.components[0]
        assertEquals("pkg:maven/io.netty/netty-codec-http2@4.1.76.Final", lib.bomRef)
        assertEquals(CdxComponentKind.Library, lib.kind)
        assertEquals("cpe:2.3:a:netty:netty:4.1.76:*:*:*:*:*:*:*", lib.propertyValue("syft:cpe23"))

        // library -> module -> source file
        assertEquals(1, lib.children.size)
        val module = lib.children[0]
        assertEquals(1, module.children.size)
        val file = module.children[0]
        assertEquals(CdxComponentKind.SourceFile, file.kind)
        assertEquals(listOf("Http2Headers", "Http2HeadersFrame"), file.classes())
        assertEquals(listOf("add", "get"), file.methods())

        // post-DCE class set bubbles up through allClasses()
        assertTrue(lib.allClasses().contains("Http2Headers"))
        // flatten yields library + module + file
        assertEquals(3, sbom.allComponents().size)

        // dependency graph
        assertEquals(2, sbom.dependencies.size)
        assertEquals("pkg:maven/io.netty/netty-codec-http2@4.1.76.Final", sbom.dependencies[0].ref)
        assertEquals(
            listOf("pkg:maven/io.netty/netty-common@4.1.76.Final"),
            sbom.dependencies[0].dependsOn,
        )
    }
}
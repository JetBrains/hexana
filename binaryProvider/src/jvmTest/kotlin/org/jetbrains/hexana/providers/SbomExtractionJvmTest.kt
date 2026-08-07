package org.jetbrains.hexana.providers.sbom

import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.SbomLocator
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SbomExtractionJvmTest {

    private val minimalCdx = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.5",
          "version": 1,
          "components": [
            { "type": "library", "group": "com.example", "name": "guava", "version": "32.1.3",
              "purl": "pkg:maven/com.example/guava@32.1.3",
              "licenses": [{ "license": { "id": "Apache-2.0" } }] },
            { "type": "library", "name": "slf4j-api", "version": "2.0.9" }
          ]
        }
    """.trimIndent()

    @Test
    fun extractAndParseGzippedSbom() = runBlocking {
        // Gzip the JSON
        val gzipped = gzip(minimalCdx.toByteArray(Charsets.UTF_8))

        // Build a buffer: 16 bytes padding, then the gzip blob
        val padding = 16
        val full = ByteArray(padding + gzipped.size)
        gzipped.copyInto(full, padding)

        val buf = full.toCommonByteBuffer()
        val locator = SbomLocator(fileOffset = padding.toLong(), length = gzipped.size.toLong(), isGzip = true)

        val json = extractSbomJson(buf, locator)
        assertNotNull(json)

        val sbom = CycloneDxParser.parse(json)
        assertNotNull(sbom)
        assertEquals(2, sbom.components.size)
        assertEquals("guava", sbom.components[0].name)
        println("Parsed component count: ${sbom.components.size}")
        println("Components: ${sbom.components.map { "${it.group}:${it.name}@${it.version}" }}")
    }

    @Test
    fun extractPlainJson() = runBlocking {
        val bytes = minimalCdx.toByteArray(Charsets.UTF_8)
        val full = ByteArray(8 + bytes.size)
        bytes.copyInto(full, 8)
        val buf = full.toCommonByteBuffer()
        val locator = SbomLocator(fileOffset = 8L, length = bytes.size.toLong(), isGzip = false)
        val json = extractSbomJson(buf, locator)
        assertNotNull(json)
        val sbom = CycloneDxParser.parse(json)
        assertNotNull(sbom)
        assertEquals(2, sbom.components.size)
    }

    @Test
    fun outOfBoundsLocatorReturnsNull() = runBlocking {
        val buf = ByteArray(10).toCommonByteBuffer()
        val locator = SbomLocator(fileOffset = 5L, length = 100L, isGzip = false)
        assertNull(extractSbomJson(buf, locator))
    }

    @Test
    fun corruptGzipReturnsNull() = runBlocking {
        val bytes = ByteArray(20) { 0xFF.toByte() }
        val buf = bytes.toCommonByteBuffer()
        val locator = SbomLocator(fileOffset = 0L, length = bytes.size.toLong(), isGzip = true)
        assertNull(extractSbomJson(buf, locator))
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }
}

package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import java.io.File
import java.nio.file.Files

class WasmDwarfInjectorTest : UsefulTestCase() {

    private lateinit var tempDir: File

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("dwarf-injector-test").toFile()
    }

    override fun tearDown() {
        try {
            tempDir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testInjectDwarfSectionsFromCoreModule() {
        // Source: a core module with a .debug_info custom section
        val sourceWasm = buildWasmWithCustomSections(
            ".debug_info" to byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0x01),
            ".debug_line" to byteArrayOf(0xBE.toByte(), 0xEF.toByte()),
        )
        val sourceFile = File(tempDir, "source.wasm").also { it.writeBytes(sourceWasm) }

        // Composed: a plain module without any debug sections
        val composedWasm = wat2wasm("(module)")
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedWasm) }

        val injected = WasmDwarfInjector.injectDwarfSections(composedFile, listOf(sourceFile))

        assertTrue("Should report that DWARF sections were injected", injected)

        // Verify the result is still a valid Wasm module and has the DWARF sections
        val resultBytes = composedFile.readBytes()
        assertTrue("Result should be larger than the original", resultBytes.size > composedWasm.size)

        val sectionNames = customSectionNames(resultBytes)
        assertTrue(".debug_info should be present", ".debug_info" in sectionNames)
        assertTrue(".debug_line should be present", ".debug_line" in sectionNames)
    }

    fun testInjectPreservesSectionContent() {
        val debugPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val sourceWasm = buildWasmWithCustomSections(
            ".debug_info" to debugPayload,
        )
        val sourceFile = File(tempDir, "source.wasm").also { it.writeBytes(sourceWasm) }

        val composedWasm = wat2wasm("(module)")
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedWasm) }

        WasmDwarfInjector.injectDwarfSections(composedFile, listOf(sourceFile))

        // Parse the result and verify the .debug_info content matches
        val resultBytes = composedFile.readBytes()
        val provider = WasmBinaryProvider(resultBytes.toCommonByteBuffer())
        val module = provider.wasmModule
        assertNotNull("Should parse as a valid module", module)

        val debugSection = module!!.customSections.find { it.name == ".debug_info" }
        assertNotNull("Should have a .debug_info section", debugSection)

        val buffer = resultBytes.toCommonByteBuffer()
        val content = ByteArray(debugSection!!.contentSize)
        buffer.position(debugSection.contentOffset)
        buffer.get(content, 0, debugSection.contentSize)
        assertEquals(
            "Section content should match the original",
            debugPayload.toList(),
            content.toList(),
        )
    }

    fun testNoInjectionWhenSourceHasNoDwarf() {
        val sourceWasm = wat2wasm("(module)")
        val sourceFile = File(tempDir, "source.wasm").also { it.writeBytes(sourceWasm) }

        val composedWasm = wat2wasm("(module)")
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedWasm) }

        val injected = WasmDwarfInjector.injectDwarfSections(composedFile, listOf(sourceFile))

        assertFalse("Should report no DWARF sections injected", injected)
        assertEquals(
            "Composed file should be unchanged",
            composedWasm.toList(),
            composedFile.readBytes().toList(),
        )
    }

    fun testNoInjectionWhenSourceFilesMissing() {
        val composedWasm = wat2wasm("(module)")
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedWasm) }

        val injected = WasmDwarfInjector.injectDwarfSections(
            composedFile,
            listOf(File(tempDir, "nonexistent.wasm")),
        )

        assertFalse("Should report no DWARF sections injected", injected)
    }

    fun testNoInjectionWithEmptySourceList() {
        val composedWasm = wat2wasm("(module)")
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedWasm) }

        val injected = WasmDwarfInjector.injectDwarfSections(composedFile, emptyList())

        assertFalse("Should report no DWARF sections injected", injected)
    }

    fun testMultipleSourceFiles() {
        val source1 = buildWasmWithCustomSections(
            ".debug_info" to byteArrayOf(0x01, 0x02),
        )
        val source2 = buildWasmWithCustomSections(
            ".debug_line" to byteArrayOf(0x03, 0x04),
        )
        val file1 = File(tempDir, "source1.wasm").also { it.writeBytes(source1) }
        val file2 = File(tempDir, "source2.wasm").also { it.writeBytes(source2) }

        val composedWasm = wat2wasm("(module)")
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedWasm) }

        val injected = WasmDwarfInjector.injectDwarfSections(composedFile, listOf(file1, file2))

        assertTrue("Should inject DWARF sections", injected)

        val sectionNames = customSectionNames(composedFile.readBytes())
        assertTrue(".debug_info should be present", ".debug_info" in sectionNames)
        assertTrue(".debug_line should be present", ".debug_line" in sectionNames)
    }

    fun testNonDwarfCustomSectionsNotInjected() {
        val sourceWasm = buildWasmWithCustomSections(
            "name" to byteArrayOf(0x00, 0x01),
            "producers" to byteArrayOf(0x02, 0x03),
        )
        val sourceFile = File(tempDir, "source.wasm").also { it.writeBytes(sourceWasm) }

        val composedWasm = wat2wasm("(module)")
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedWasm) }

        val injected = WasmDwarfInjector.injectDwarfSections(composedFile, listOf(sourceFile))

        assertFalse("Should not inject non-DWARF custom sections", injected)
    }

    fun testLargePayload() {
        // Test with a payload larger than 127 bytes to exercise multi-byte LEB128 encoding
        val largePayload = ByteArray(300) { (it % 256).toByte() }
        val sourceWasm = buildWasmWithCustomSections(
            ".debug_info" to largePayload,
        )
        val sourceFile = File(tempDir, "source.wasm").also { it.writeBytes(sourceWasm) }

        val composedWasm = wat2wasm("(module)")
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedWasm) }

        val injected = WasmDwarfInjector.injectDwarfSections(composedFile, listOf(sourceFile))

        assertTrue("Should inject large DWARF section", injected)

        // Verify content is preserved
        val provider = WasmBinaryProvider(composedFile.readBytes().toCommonByteBuffer())
        val debugSection = provider.wasmModule!!.customSections.find { it.name == ".debug_info" }
        assertNotNull("Should have .debug_info", debugSection)
        assertEquals(
            "Large payload size should be preserved",
            largePayload.size,
            debugSection!!.contentSize,
        )
    }

    fun testInjectIntoComponentBinary() {
        // Build a source core module with DWARF sections
        val debugPayload = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val sourceWasm = buildWasmWithCustomSections(
            ".debug_info" to debugPayload,
        )
        val sourceFile = File(tempDir, "source.wasm").also { it.writeBytes(sourceWasm) }

        // Build a minimal component binary that embeds a core module
        val coreModule = wat2wasm("(module)")
        val composedComponent = buildComponentWithEmbeddedModule(coreModule)
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedComponent) }

        val injected = WasmDwarfInjector.injectDwarfSections(composedFile, listOf(sourceFile))

        assertTrue("Should inject DWARF into component binary", injected)

        // The result should be larger than the original
        val resultBytes = composedFile.readBytes()
        assertTrue("Result should be larger", resultBytes.size > composedComponent.size)

        // Verify the result is still a valid component binary (starts with component magic)
        assertEquals("Should start with \\0asm", 0x00, resultBytes[0].toInt() and 0xFF)
        assertEquals("Should start with \\0asm", 0x61, resultBytes[1].toInt() and 0xFF)
        assertEquals("Should start with \\0asm", 0x73, resultBytes[2].toInt() and 0xFF)
        assertEquals("Should start with \\0asm", 0x6D, resultBytes[3].toInt() and 0xFF)
        // Component version: 0x0d, 0x00, layer 0x01, 0x00
        assertEquals("Component version byte", 0x0d, resultBytes[4].toInt() and 0xFF)

        // Verify the DWARF info is inside the embedded core module:
        // extract the core module from the result and check it has DWARF
        val embeddedModule = extractFirstCoreModuleFromComponent(resultBytes)
        assertNotNull("Should have an embedded core module", embeddedModule)
        assertTrue(
            "Embedded core module should have DWARF info",
            ".debug_info" in customSectionNames(embeddedModule!!),
        )
    }

    fun testInjectIntoComponentPreservesSectionsAfterModule() {
        // Build a source core module with DWARF sections
        val sourceWasm = buildWasmWithCustomSections(
            ".debug_info" to byteArrayOf(0x01, 0x02, 0x03),
        )
        val sourceFile = File(tempDir, "source.wasm").also { it.writeBytes(sourceWasm) }

        // Build a component with a core module and additional sections after it
        val coreModule = wat2wasm("(module)")
        val composedComponent = buildComponentWithEmbeddedModuleAndTrailingSections(coreModule)
        val composedFile = File(tempDir, "composed.wasm").also { it.writeBytes(composedComponent) }
        val originalSize = composedComponent.size

        val injected = WasmDwarfInjector.injectDwarfSections(composedFile, listOf(sourceFile))

        assertTrue("Should inject DWARF sections", injected)

        val resultBytes = composedFile.readBytes()
        assertTrue("Result should be larger than original", resultBytes.size > originalSize)

        // The component should still be structurally valid — all section data after
        // the module section should be preserved
        assertTrue("Result should contain trailing section marker",
            resultBytes.any { it == 0x04.toByte() }) // type section marker from trailing section
    }

    /**
     * The names of every custom section in the given core module, empty when it does not parse.
     */
    private fun customSectionNames(wasmBytes: ByteArray): Set<String> {
        val module = WasmBinaryProvider(wasmBytes.toCommonByteBuffer()).wasmModule ?: return emptySet()
        return module.customSections.map { it.name }.toSet()
    }

    /**
     * Builds a minimal Wasm module with multiple custom sections appended.
     */
    private fun buildWasmWithCustomSections(vararg sections: Pair<String, ByteArray>): ByteArray {
        val base = wat2wasm("(module)")
        val out = mutableListOf<Byte>()
        out.addAll(base.toList())

        for ((name, payload) in sections) {
            val nameBytes = name.encodeToByteArray()
            val sectionContent = mutableListOf<Byte>()
            writeULEB128(sectionContent, nameBytes.size)
            sectionContent.addAll(nameBytes.toList())
            sectionContent.addAll(payload.toList())

            out.add(0x00.toByte())
            writeULEB128(out, sectionContent.size)
            out.addAll(sectionContent)
        }

        return out.toByteArray()
    }

    /**
     * Builds a minimal component binary that wraps the given core module.
     *
     * Component binary format:
     * - magic: \0asm
     * - version: 0x0d 0x00 (version 13 = component model)
     * - layer: 0x01 0x00
     * - module section: id=1, content_size, <core module bytes>
     */
    private fun buildComponentWithEmbeddedModule(coreModuleBytes: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()

        // Magic
        out.addAll(byteArrayOf(0x00, 0x61, 0x73, 0x6D).toList())
        // Version: 0x0d, layer: 0x01 → encoded as (0x0d, 0x00, 0x01, 0x00)
        out.addAll(byteArrayOf(0x0d, 0x00, 0x01, 0x00).toList())

        // Module section (id = 1)
        out.add(0x01) // section id
        writeULEB128(out, coreModuleBytes.size) // content size
        out.addAll(coreModuleBytes.toList()) // core module content

        return out.toByteArray()
    }

    /**
     * Builds a component binary with a core module followed by additional sections.
     * The trailing section is a dummy type section (id=4) with minimal valid content.
     */
    private fun buildComponentWithEmbeddedModuleAndTrailingSections(coreModuleBytes: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()

        // Magic + version
        out.addAll(byteArrayOf(0x00, 0x61, 0x73, 0x6D).toList())
        out.addAll(byteArrayOf(0x0d, 0x00, 0x01, 0x00).toList())

        // Module section (id = 1)
        out.add(0x01) // section id
        writeULEB128(out, coreModuleBytes.size)
        out.addAll(coreModuleBytes.toList())

        // Trailing dummy section (component type section, id = 4)
        out.add(0x04) // section id
        val dummyContent = byteArrayOf(0x00) // empty vector
        writeULEB128(out, dummyContent.size)
        out.addAll(dummyContent.toList())

        return out.toByteArray()
    }

    /**
     * Extracts the first embedded core module from a component binary.
     */
    private fun extractFirstCoreModuleFromComponent(componentBytes: ByteArray): ByteArray? {
        if (componentBytes.size < 8) return null
        var pos = 8 // skip magic + version

        while (pos < componentBytes.size) {
            val sectionId = componentBytes[pos].toInt() and 0xFF
            pos++

            // Read LEB128 content size
            var contentSize = 0
            var shift = 0
            while (pos < componentBytes.size) {
                val byte = componentBytes[pos].toInt() and 0xFF
                contentSize = contentSize or ((byte and 0x7F) shl shift)
                pos++
                if (byte and 0x80 == 0) break
                shift += 7
            }

            if (sectionId == 1) {
                return componentBytes.copyOfRange(pos, pos + contentSize)
            }
            pos += contentSize
        }
        return null
    }

    private fun writeULEB128(out: MutableList<Byte>, value: Int) {
        var v = value
        do {
            var byte = v and 0x7F
            v = v ushr 7
            if (v != 0) byte = byte or 0x80
            out.add(byte.toByte())
        } while (v != 0)
    }
}

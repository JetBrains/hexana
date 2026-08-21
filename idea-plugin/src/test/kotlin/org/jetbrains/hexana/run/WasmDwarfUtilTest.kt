package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.toCommonByteBuffer

class WasmDwarfUtilTest : UsefulTestCase() {

    fun testNoDwarfInfo() {
        val wasm = wat2wasm("""
            (module
              (func (export "main") (result i32)
                i32.const 42
              )
            )
        """.trimIndent())

        assertFalse("Simple module without DWARF should return false", WasmDwarfUtil.hasDwarfInfo(wasm))
        assertTrue("Simple module should have no DWARF section names", WasmDwarfUtil.dwarfSectionNames(wasm).isEmpty())
    }

    fun testHasDwarfInfoWithDebugInfoSection() {
        val wasm = buildWasmWithCustomSection(".debug_info", byteArrayOf(0x00, 0x01, 0x02))

        assertTrue("Module with .debug_info section should return true", WasmDwarfUtil.hasDwarfInfo(wasm))
        assertTrue(
            "dwarfSectionNames should contain .debug_info",
            WasmDwarfUtil.dwarfSectionNames(wasm).contains(".debug_info"),
        )
    }

    fun testDwarfSectionNamesMultiple() {
        val wasm = buildWasmWithCustomSections(
            ".debug_info" to byteArrayOf(0x00),
            ".debug_line" to byteArrayOf(0x01),
            ".debug_abbrev" to byteArrayOf(0x02),
        )

        val names = WasmDwarfUtil.dwarfSectionNames(wasm)
        assertEquals(3, names.size)
        assertTrue(".debug_info" in names)
        assertTrue(".debug_line" in names)
        assertTrue(".debug_abbrev" in names)
    }

    fun testNonDwarfCustomSectionsIgnored() {
        val wasm = buildWasmWithCustomSection("name", byteArrayOf(0x00, 0x01))

        assertFalse("Module with only 'name' custom section should return false", WasmDwarfUtil.hasDwarfInfo(wasm))
        assertTrue(WasmDwarfUtil.dwarfSectionNames(wasm).isEmpty())
    }

    fun testInvalidBinaryReturnsFalse() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertFalse("Invalid binary should return false", WasmDwarfUtil.hasDwarfInfo(garbage))
        assertTrue(WasmDwarfUtil.dwarfSectionNames(garbage).isEmpty())
    }

    fun testEmptyByteArrayReturnsFalse() {
        assertFalse("Empty byte array should return false", WasmDwarfUtil.hasDwarfInfo(byteArrayOf()))
        assertTrue(WasmDwarfUtil.dwarfSectionNames(byteArrayOf()).isEmpty())
    }

    // ── Component-model binaries ────────────────────────────────────

    fun testEmbeddedCoreModulesAreStandaloneModules() {
        val first = buildWasmWithCustomSection("name", byteArrayOf(0x00))
        val second = buildWasmWithCustomSection(".debug_line", byteArrayOf(0x01))
        val component = WasmComponentTestBuilder.component(first, second)

        val embedded = WasmDwarfUtil.embeddedCoreModules(component.toCommonByteBuffer())
        assertEquals("both module sections must be enumerated, in order", 2, embedded.size)
        assertTrue("the first embedded module must be returned verbatim", first.contentEquals(embedded[0]))
        assertTrue("the second embedded module must be returned verbatim", second.contentEquals(embedded[1]))
        // Each element starts at its own magic, so a core-module parser can take it as is.
        assertEquals(
            listOf(0x00, 0x61, 0x73, 0x6d),
            embedded[0].take(4).map { it.toInt() and 0xFF },
        )
    }

    fun testEmbeddedCoreModulesOfACoreModuleIsEmpty() {
        val wasm = wat2wasm("(module)")
        assertTrue(
            "a core module embeds nothing — it IS the module",
            WasmDwarfUtil.embeddedCoreModules(wasm.toCommonByteBuffer()).isEmpty(),
        )
    }

    fun testHasDwarfInfoFindsDwarfInsideAnEmbeddedCoreModule() {
        val plain = buildWasmWithCustomSection("name", byteArrayOf(0x00))
        val withDwarf = buildWasmWithCustomSection(".debug_info", byteArrayOf(0x00, 0x01))

        assertTrue(
            "DWARF in any embedded module must count as DWARF in the component",
            WasmDwarfUtil.hasDwarfInfo(WasmComponentTestBuilder.component(plain, withDwarf)),
        )
        assertFalse(
            "a component whose modules carry no DWARF must not report DWARF",
            WasmDwarfUtil.hasDwarfInfo(WasmComponentTestBuilder.component(plain)),
        )
    }

    /**
     * Builds a minimal Wasm module with one custom section appended.
     */
    private fun buildWasmWithCustomSection(sectionName: String, payload: ByteArray): ByteArray {
        return buildWasmWithCustomSections(sectionName to payload)
    }

    /**
     * Builds a minimal Wasm module with multiple custom sections appended.
     */
    private fun buildWasmWithCustomSections(vararg sections: Pair<String, ByteArray>): ByteArray {
        // Minimal valid wasm module: magic + version
        val base = wat2wasm("(module)")
        val out = mutableListOf<Byte>()
        out.addAll(base.toList())

        for ((name, payload) in sections) {
            val nameBytes = name.encodeToByteArray()
            val sectionContent = mutableListOf<Byte>()
            // name length (unsigned LEB128)
            writeULEB128(sectionContent, nameBytes.size)
            sectionContent.addAll(nameBytes.toList())
            sectionContent.addAll(payload.toList())

            // Custom section id = 0
            out.add(0x00.toByte())
            // Section size (unsigned LEB128)
            writeULEB128(out, sectionContent.size)
            out.addAll(sectionContent)
        }

        return out.toByteArray()
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

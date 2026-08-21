package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.toCommonByteBuffer

class WasmDwarfUtilTest : UsefulTestCase() {

    fun testEmbeddedCoreModulesAreStandaloneModules() {
        val first = buildWasmWithCustomSection("name", byteArrayOf(0x00))
        val second = buildWasmWithCustomSection(".debug_line", byteArrayOf(0x01))
        val component = WasmComponentTestBuilder.component(first, second)

        val embedded = WasmDwarfUtil.embeddedCoreModules(component.toCommonByteBuffer())
        assertEquals("both module sections must be enumerated, in order", 2, embedded.size)
        assertTrue("the first embedded module must be returned verbatim", first.contentEquals(embedded[0].toByteArray()))
        assertTrue("the second embedded module must be returned verbatim", second.contentEquals(embedded[1].toByteArray()))
        // Each element starts at its own magic, so a core-module parser can take it as is.
        assertEquals(
            listOf(0x00, 0x61, 0x73, 0x6d),
            embedded[0].toByteArray().take(4).map { it.toInt() and 0xFF },
        )
    }

    fun testEmbeddedCoreModulesOfACoreModuleIsEmpty() {
        val wasm = wat2wasm("(module)")
        assertTrue(
            "a core module embeds nothing — it IS the module",
            WasmDwarfUtil.embeddedCoreModules(wasm.toCommonByteBuffer()).isEmpty(),
        )
    }

    /**
     * Builds a minimal Wasm module with one custom section appended.
     */
    private fun buildWasmWithCustomSection(sectionName: String, payload: ByteArray): ByteArray {
        // Minimal valid wasm module: magic + version
        val base = wat2wasm("(module)")
        val out = mutableListOf<Byte>()
        out.addAll(base.toList())

        val nameBytes = sectionName.encodeToByteArray()
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

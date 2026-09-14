package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.hexana.io.containsRange
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Transfers DWARF debug information from source Wasm binaries into a
 * composed component binary.
 *
 * Composition tools (`wasm-tools compose`, `wac plug`) strip custom
 * sections — including DWARF `.debug_*` sections — from their output.
 * This utility works around that limitation by:
 *
 * 1. Extracting the raw bytes of every DWARF custom section from the
 *    source binaries (looking both in core modules and inside embedded
 *    core modules of component binaries).
 * 2. Injecting those custom sections into the embedded core module(s)
 *    inside the composed component binary, and updating the enclosing
 *    component module-section size so the file remains valid.
 *
 * Wasmtime's JIT loader only registers DWARF info it finds inside core
 * modules it compiles, so DWARF must live inside the core modules —
 * appending to the top-level component is not sufficient.
 */
internal object WasmDwarfInjector {

    private val LOG = Logger.getInstance(WasmDwarfInjector::class.java)

    /**
     * The DWARF custom section name prefixes we transfer.
     * Any custom section whose name starts with `.debug_` is considered
     * a DWARF section.
     */
    private const val DWARF_SECTION_PREFIX = ".debug_"

    /**
     * Injects DWARF debug sections from the [sourceFiles] into the
     * [composedFile]. The composed file is rewritten in-place.
     *
     * For component binaries, DWARF sections are injected into the
     * first embedded core module (which is the one wasmtime JIT-compiles).
     * For core module binaries, sections are appended to the end.
     *
     * @param composedFile the composed Wasm binary (output of composition)
     * @param sourceFiles  the original Wasm files that were fed into
     *                     composition and may contain DWARF debug info
     * @return `true` if at least one DWARF section was injected
     */
    fun injectDwarfSections(composedFile: File, sourceFiles: List<File>): Boolean {
        val dwarfSections = mutableListOf<DwarfCustomSection>()

        for (file in sourceFiles) {
            if (!file.exists()) {
                LOG.info("Source file does not exist, skipping: ${file.absolutePath}")
                continue
            }
            try {
                val bytes = file.readBytes()
                val extracted = extractDwarfSections(bytes)
                LOG.info("Extracted ${extracted.size} DWARF section(s) from ${file.name}")
                dwarfSections.addAll(extracted)
            } catch (e: Exception) {
                LOG.warn("Failed to extract DWARF sections from ${file.name}: ${e.message}", e)
            }
        }

        if (dwarfSections.isEmpty()) {
            LOG.info("No DWARF sections found in source files; nothing to inject")
            return false
        }

        LOG.info(
            "Injecting ${dwarfSections.size} DWARF section(s) into ${composedFile.name}: " +
                dwarfSections.joinToString { "${it.name} (${it.content.size} bytes)" }
        )

        try {
            val composedBytes = composedFile.readBytes()
            val result = injectInto(composedBytes, dwarfSections)
            if (result != null) {
                composedFile.writeBytes(result)
                return true
            } else {
                LOG.warn("Could not inject DWARF sections — no suitable injection target in ${composedFile.name}")
                return false
            }
        } catch (e: Exception) {
            LOG.warn("Failed to inject DWARF sections into ${composedFile.name}: ${e.message}", e)
            return false
        }
    }

    /**
     * Injects DWARF sections into a Wasm binary.
     *
     * For core modules, sections are simply appended at the end.
     * For component binaries, sections are injected into the first
     * embedded core module, and the component module-section size is
     * updated accordingly.
     *
     * @return the modified binary, or `null` if injection is not possible
     */
    private fun injectInto(composedBytes: ByteArray, sections: List<DwarfCustomSection>): ByteArray? {
        val buffer = composedBytes.toCommonByteBuffer()
        return when (WasmBinaryProviderSelector.select(buffer)) {
            is BinaryProviderType.Wasm -> appendCustomSectionsToCore(composedBytes, sections)
            is BinaryProviderType.WasmComponent -> injectIntoComponent(composedBytes, sections)
            else -> null
        }
    }

    /**
     * Extracts all DWARF custom sections from a Wasm binary.
     *
     * For core modules, the custom sections are read directly.
     * For component binaries, we look inside each embedded core module.
     */
    private fun extractDwarfSections(wasmBytes: ByteArray): List<DwarfCustomSection> {
        val buffer = wasmBytes.toCommonByteBuffer()
        return when (WasmBinaryProviderSelector.select(buffer)) {
            is BinaryProviderType.Wasm -> extractFromCoreModule(wasmBytes)
            is BinaryProviderType.WasmComponent -> extractFromComponent(wasmBytes)
            else -> emptyList()
        }
    }

    /**
     * Extracts DWARF custom sections from a core Wasm module.
     */
    private fun extractFromCoreModule(wasmBytes: ByteArray): List<DwarfCustomSection> {
        return try {
            val provider = WasmBinaryProvider(wasmBytes.toCommonByteBuffer())
            val module = provider.wasmModule ?: return emptyList()
            val buffer = wasmBytes.toCommonByteBuffer()

            module.customSections
                .filter { it.name.startsWith(DWARF_SECTION_PREFIX) }
                .map { section ->
                    val content = ByteArray(section.contentSize)
                    buffer.position(section.contentOffset)
                    buffer.get(content, 0, section.contentSize)
                    DwarfCustomSection(section.name, content)
                }
        } catch (e: Exception) {
            LOG.info("Failed to extract from core module: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extracts DWARF custom sections from a component binary by
     * examining each embedded core module.
     */
    private fun extractFromComponent(wasmBytes: ByteArray): List<DwarfCustomSection> {
        val result = mutableListOf<DwarfCustomSection>()
        try {
            val buffer = wasmBytes.toCommonByteBuffer()
            val provider = WasmComponentBinaryProvider(buffer)
            if (!provider.canProvideWasmModule()) return emptyList()

            // Check top-level component custom sections
            val component = provider.wasmModule
            if (component != null) {
                for (section in component.customSections) {
                    if (section.name.startsWith(DWARF_SECTION_PREFIX)) {
                        val content = ByteArray(section.contentSize)
                        buffer.position(section.contentOffset)
                        buffer.get(content, 0, section.contentSize)
                        result.add(DwarfCustomSection(section.name, content))
                    }
                }
            }

            // Check embedded core modules
            for (coreModule in provider.modules) {
                if (!buffer.containsRange(coreModule.contentOffset, coreModule.contentSize)) continue
                val moduleBytes = ByteArray(coreModule.contentSize)
                buffer.position(coreModule.contentOffset)
                buffer.get(moduleBytes, 0, coreModule.contentSize)
                result.addAll(extractFromCoreModule(moduleBytes))
            }
        } catch (e: Exception) {
            LOG.info("Failed to extract from component: ${e.message}")
        }
        return result
    }

    /**
     * Injects DWARF sections into the first embedded core module of a
     * component binary.
     *
     * The approach:
     * 1. Locate the first module section (section id 1) in the component.
     * 2. Read the core module bytes from that section.
     * 3. Append the DWARF custom sections to the core module bytes.
     * 4. Rebuild the component binary with the enlarged module section,
     *    updating the module section's LEB128 size field.
     *
     * This works because the component binary format wraps each core
     * module in a module section:
     * ```
     * section_id=1 | content_size (LEB128) | <core module bytes>
     * ```
     * We just need to replace `<core module bytes>` with the enlarged
     * version and update `content_size`.
     */
    private fun injectIntoComponent(composedBytes: ByteArray, sections: List<DwarfCustomSection>): ByteArray? {
        // Find the first module section in the component binary.
        // Component binary: magic(4) + version(4) + sections...
        // Each section: section_id(1) + content_size(LEB128) + content(content_size)
        // Module section id = 1 in the component layer.
        val moduleSectionLocation = findFirstModuleSection(composedBytes)
        if (moduleSectionLocation == null) {
            LOG.warn("No embedded core module section found in component binary")
            return null
        }

        // Read the core module bytes from the module section
        val coreModuleBytes = composedBytes.copyOfRange(
            moduleSectionLocation.contentOffset,
            moduleSectionLocation.contentOffset + moduleSectionLocation.contentSize
        )

        // Append DWARF custom sections to the core module
        val enlargedModule = appendCustomSectionsToCore(coreModuleBytes, sections)

        // Rebuild the component binary with the enlarged module section
        val out = ByteArrayOutputStream(composedBytes.size + enlargedModule.size - coreModuleBytes.size + 10)

        // 1. Everything before the module section
        out.write(composedBytes, 0, moduleSectionLocation.sectionOffset)

        // 2. Module section id
        out.write(MODULE_SECTION_ID.toInt())

        // 3. New content size (LEB128)
        out.write(encodeULEB128(enlargedModule.size))

        // 4. The enlarged core module
        out.write(enlargedModule)

        // 5. Everything after the original module section
        val afterSection = moduleSectionLocation.sectionOffset + moduleSectionLocation.totalSectionSize
        if (afterSection < composedBytes.size) {
            out.write(composedBytes, afterSection, composedBytes.size - afterSection)
        }

        return out.toByteArray()
    }

    /**
     * Appends custom sections to a core Wasm module binary.
     */
    private fun appendCustomSectionsToCore(
        moduleBytes: ByteArray,
        sections: List<DwarfCustomSection>,
    ): ByteArray {
        val out = ByteArrayOutputStream(moduleBytes.size + sections.sumOf { it.estimateEncodedSize() })
        out.write(moduleBytes)

        for (section in sections) {
            val nameBytes = section.name.encodeToByteArray()
            val nameLenEncoded = encodeULEB128(nameBytes.size)

            // Total payload = encoded name length + name + content
            val payloadSize = nameLenEncoded.size + nameBytes.size + section.content.size
            val payloadSizeEncoded = encodeULEB128(payloadSize)

            // section_id = 0 (custom section)
            out.write(0x00)
            out.write(payloadSizeEncoded)
            out.write(nameLenEncoded)
            out.write(nameBytes)
            out.write(section.content)
        }

        return out.toByteArray()
    }

    /** Section id for module sections in the component model binary format. */
    private const val MODULE_SECTION_ID: Byte = 1

    /**
     * Scans a component binary to find the first module section (section id 1).
     *
     * Returns the location descriptor, or `null` if not found.
     */
    private fun findFirstModuleSection(composedBytes: ByteArray): ModuleSectionLocation? {
        if (composedBytes.size < 8) return null

        // Skip magic (4 bytes) + version (4 bytes)
        var pos = 8

        while (pos < composedBytes.size) {
            val sectionOffset = pos

            val sectionId = composedBytes[pos].toInt() and 0xFF
            pos++

            // Read content size as LEB128
            val (contentSize, bytesRead) = readULEB128(composedBytes, pos)
            pos += bytesRead

            val contentOffset = pos

            if (sectionId == MODULE_SECTION_ID.toInt()) {
                return ModuleSectionLocation(
                    sectionOffset = sectionOffset,
                    contentOffset = contentOffset,
                    contentSize = contentSize,
                    totalSectionSize = contentOffset - sectionOffset + contentSize,
                )
            }

            // Skip to next section
            pos += contentSize
        }

        return null
    }

    /**
     * Reads an unsigned LEB128 value from a byte array.
     *
     * @return a pair of (value, number of bytes consumed)
     */
    private fun readULEB128(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = offset
        while (pos < bytes.size) {
            val byte = bytes[pos].toInt() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            pos++
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result to (pos - offset)
    }

    /**
     * Encodes an unsigned integer as a LEB128 byte sequence.
     */
    private fun encodeULEB128(value: Int): ByteArray {
        val bytes = ByteArrayOutputStream(5)
        var remaining = value
        do {
            var byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) {
                byte = byte or 0x80
            }
            bytes.write(byte)
        } while (remaining != 0)
        return bytes.toByteArray()
    }

    /**
     * Describes the location of a module section within a component binary.
     */
    private data class ModuleSectionLocation(
        /** Offset of the section id byte in the component binary. */
        val sectionOffset: Int,
        /** Offset where the core module content begins. */
        val contentOffset: Int,
        /** Size of the core module content. */
        val contentSize: Int,
        /** Total size of the section (id + LEB128 size + content). */
        val totalSectionSize: Int,
    )

    /**
     * Holds a single DWARF custom section: its name and raw content bytes.
     */
    internal data class DwarfCustomSection(
        val name: String,
        val content: ByteArray,
    ) {
        /** Rough estimate of encoded size for pre-allocating output buffers. */
        fun estimateEncodedSize(): Int =
            1 /* section id */ + 5 /* max LEB128 */ + 5 /* max LEB128 */ + name.length + content.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DwarfCustomSection) return false
            return name == other.name && content.contentEquals(other.content)
        }

        override fun hashCode(): Int {
            return 31 * name.hashCode() + content.contentHashCode()
        }
    }
}

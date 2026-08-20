package org.jetbrains.hexana.run

/**
 * Wraps core modules into a component-model binary, so the component code paths can
 * be tested against any core-module fixture in the repo (none of the checked-in
 * component fixtures carry DWARF).
 *
 * The layout is the one the parser recognises: the component preamble — magic plus
 * `version=0x0d, layer=1` — followed by one `module` section (id 1) per module,
 * whose payload is the core module verbatim, starting at its own magic.
 */
internal object WasmComponentTestBuilder {

    fun component(vararg coreModules: ByteArray): ByteArray {
        val out = mutableListOf<Byte>(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00)
        for (module in coreModules) {
            out.add(0x01) // core:module section
            writeULEB128(out, module.size)
            out.addAll(module.toList())
        }
        return out.toByteArray()
    }

    /** The offset the [index]-th module's magic ends up at, i.e. its base in stub coordinates. */
    fun magicOffsetOf(component: ByteArray, index: Int): Int {
        var offset = 8
        repeat(index + 1) { i ->
            offset++ // section id
            var size = 0
            var shift = 0
            while (true) {
                val byte = component[offset++].toInt()
                size = size or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            if (i == index) return offset
            offset += size
        }
        error("component has no module at index $index")
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

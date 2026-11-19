package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `WasmComponentBinaryProvider.nestedComponents` lists every nested component definition (not only
 * the parser's singular, last `componentSection`) with its component index space index.
 */
class WasmNestedComponentsTest {

    private val componentHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)
    private val coreModule = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    private fun wrap(vararg sections: Pair<Int, ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        componentHeader.forEach { out.add(it) }
        for ((id, content) in sections) {
            require(content.size < 0x80)
            out.add(id.toByte())
            out.add(content.size.toByte())
            content.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    /** One alias of sort component: `(alias outer 1 0 (component))`. */
    private val componentAlias = byteArrayOf(0x01, 0x04, 0x02, 0x01, 0x00)

    private val component = wrap(
        6 to componentAlias,               // component index 0: the alias
        4 to wrap(1 to coreModule),        // component index 1
        4 to byteArrayOf(1, 2, 3, 4),      // component index 2: does not parse, still takes an index
        4 to wrap(),                       // component index 3
    )

    private val provider = WasmComponentBinaryProvider(component.toCommonByteBuffer())

    @Test
    fun `every parsable nested component is listed with its component index`() {
        val nested = provider.nestedComponents
        assertEquals(listOf(1, 3), nested.map { it.componentIndex })
        assertEquals(2, nested.map { it.section.contentOffset }.toSet().size, "each entry keeps its own section")
    }

    @Test
    fun `each buffer access gets its own cursor over the nested bytes`() {
        val data = provider.nestedComponents.first()
        val first = data.buffer
        first.position(first.limit())
        val second = data.buffer
        assertEquals(0, second.position(), "a consumed cursor does not leak into the next caller")
        assertEquals(data.section.contentSize, second.limit())
        assertEquals(componentHeader.toList(), List(componentHeader.size) { second.get(it) })
    }
}

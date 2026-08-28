package org.jetbrains.hexana.ui.panel

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

/**
 * Section sizes come from the binary, so a truncated component can declare a
 * core module larger than the file that holds it. Slicing that range throws,
 * which would surface as an IDE error on a table-row click or a graph
 * double-click; [submoduleSlice] must refuse it instead.
 */
class NestedComponentBinaryNavigationTest : UsefulTestCase() {

    /**
     * ```
     * \0asm 0x0d 0x00 0x01 0x00     ; magic + component version/layer
     * 01 04 00 61 73 6D             ; module section, 4 content bytes
     * ```
     */
    private val componentWithCoreModule = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
        0x01, 0x04, 0x00, 0x61, 0x73, 0x6D,
    )

    /** The same component with the module section claiming 0x40 bytes it does not have. */
    private val truncatedComponent = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
        0x01, 0x40, 0x00, 0x61, 0x73, 0x6D,
    )

    fun testDeclaredRangeIsSliced() {
        val provider = WasmComponentBinaryProvider(componentWithCoreModule.toCommonByteBuffer())
        val module = provider.modules.single()
        val slice = provider.bytes.submoduleSlice(module.contentOffset, module.contentSize)
        assertNotNull(slice)
        assertEquals(4, slice!!.limit())
    }

    fun testRangeTheBinaryCannotHoldIsRefused() {
        val provider = WasmComponentBinaryProvider(truncatedComponent.toCommonByteBuffer())
        val module = provider.modules.single()
        assertEquals(0x40, module.contentSize)
        assertTrue(module.contentOffset + module.contentSize > truncatedComponent.size)
        assertNull(provider.bytes.submoduleSlice(module.contentOffset, module.contentSize))
    }

    fun testEmptyAndNegativeRangesAreRefused() {
        val bytes = componentWithCoreModule.toCommonByteBuffer()
        assertNull(bytes.submoduleSlice(contentOffset = 10, contentSize = 0))
        assertNull(bytes.submoduleSlice(contentOffset = -1, contentSize = 4))
        assertNull(bytes.submoduleSlice(contentOffset = 10, contentSize = Int.MAX_VALUE))
    }
}

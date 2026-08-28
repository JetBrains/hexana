package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleFileUtilTest {
    @Test
    fun coreModuleNamesAreRecognizedAndPointBackAtTheirParent() {
        val moduleName = submoduleFileName("/tmp/app.wasm", index = 3, contentOffset = 0x1f4, isComponent = false)
        assertEquals("app_3@1f4.wasm", moduleName)
        assertTrue(isSubmoduleFileName(moduleName))
        assertEquals("app.wasm", parentFileNameOf(moduleName))
    }

    @Test
    fun nestedComponentNamesAreRecognizedAsSubmodules() {
        val componentName = submoduleFileName("app.wasm", index = 0, contentOffset = 0x10, isComponent = true)
        assertEquals("app_component_0@10.wasm", componentName)
        assertTrue(isSubmoduleFileName(componentName))
        // The `_component_` infix is part of the base name as far as
        // parentFileNameOf is concerned; it only feeds a display label.
        assertEquals("app_component.wasm", parentFileNameOf(componentName))
    }

    @Test
    fun extensionlessBinariesFallBackToWasm() {
        assertEquals("blob_1@2.wasm", submoduleFileName("blob", index = 1, contentOffset = 2, isComponent = false))
    }

    /**
     * ```
     * \0asm 0x0d 0x00 0x01 0x00     ; magic + component version/layer
     * 01 04 00 61 73 6D              ; module section, 4 content bytes
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

    @Test
    fun submoduleBytesCopiesTheDeclaredRange() {
        val provider = WasmComponentBinaryProvider(componentWithCoreModule.toCommonByteBuffer())
        val module = provider.modules.single()
        val moduleBytes = provider.submoduleBytes(module.contentOffset, module.contentSize)
        assertEquals(listOf<Byte>(0x00, 0x61, 0x73, 0x6D), moduleBytes?.toList())
    }

    @Test
    fun submoduleBytesRejectsARangeTheBinaryCannotHold() {
        val provider = WasmComponentBinaryProvider(truncatedComponent.toCommonByteBuffer())
        val module = provider.modules.single()
        // The parser keeps the declared size; reading it would run past the buffer.
        assertEquals(0x40, module.contentSize)
        assertTrue(module.contentOffset + module.contentSize > truncatedComponent.size)
        assertNull(provider.submoduleBytes(module.contentOffset, module.contentSize))
    }

    @Test
    fun submoduleBytesRejectsEmptyAndNegativeRanges() {
        val provider = WasmComponentBinaryProvider(componentWithCoreModule.toCommonByteBuffer())
        assertNull(provider.submoduleBytes(contentOffset = 10, contentSize = 0))
        assertNull(provider.submoduleBytes(contentOffset = -1, contentSize = 4))
        assertNull(provider.submoduleBytes(contentOffset = 10, contentSize = Int.MAX_VALUE))
    }
}

package org.jetbrains.hexana.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
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
}

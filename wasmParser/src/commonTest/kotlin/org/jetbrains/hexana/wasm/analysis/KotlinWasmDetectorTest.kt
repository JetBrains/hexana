package org.jetbrains.hexana.wasm.analysis

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinWasmDetectorTest {

    private suspend fun detect(resourcePath: String): KotlinWasmInfo {
        val buffer = resourceAsByteBuffer(resourcePath)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        return KotlinWasmDetector.detect(module, buffer.duplicate())
    }

    @Test
    fun `detects a real Kotlin Wasm Compose Multiplatform module`() = runTest {
        // 23735.mpp-demo.wasm is a reduced Compose Multiplatform (Kotlin/Wasm) binary whose imports
        // sit under the legacy `env` host module — so detection must key on the kotlin.wasm.internal.*
        // field-name prefix, not the module name.
        val info = detect("23735.mpp-demo.wasm")
        assertTrue(info.isKotlinWasm, "kotlin.wasm.internal.* import field should identify Kotlin/Wasm")
    }

    @Test
    fun `does not flag a GraalVM Web Image module`() = runTest {
        // Web Image imports under interop/jsbody/compat with non-Kotlin field names.
        val info = detect("webImage/javac.js.wasm")
        assertFalse(info.isKotlinWasm)
    }
}

package org.jetbrains.hexana.run

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.ByteSequence
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.shared.WasmImportStubGenerator as SharedStubGenerator

/**
 * Thin wrapper around [SharedStubGenerator] that adds GraalWasm-specific
 * instantiation logic. All stub generation is delegated to the shared
 * multiplatform implementation in `sharedUi`.
 */
internal object WasmImportStubGenerator {

    /**
     * Evaluates and instantiates all stub modules in the given context,
     * then builds and returns a ProxyObject that maps each import module
     * name to its exports, suitable for passing to `mainModule.newInstance(importObject)`.
     */
    fun instantiateStubs(context: Context, stubs: List<SharedStubGenerator.ImportModuleStub>): ProxyObject {
        val moduleMap = mutableMapOf<String, Any>()
        for (stub in stubs) {
            val stubSource = Source.newBuilder("wasm", ByteSequence.create(stub.wasmBytes), stub.moduleName)
                .build()
            val stubModule = context.eval(stubSource)
            val stubInstance = stubModule.newInstance()
            val stubExports = stubInstance.getMember("exports")
            moduleMap[stub.moduleName] = stubExports
        }
        return ProxyObject.fromMap(moduleMap)
    }

    fun hasImports(wasmBytes: ByteArray): Boolean =
        SharedStubGenerator.hasImports(wasmBytes)

    fun hasNonFunctionImports(wasmBytes: ByteArray): Boolean =
        SharedStubGenerator.hasNonFunctionImports(wasmBytes)

    fun generateNonFunctionStubs(wasmBytes: ByteArray): List<SharedStubGenerator.ImportModuleStub> =
        SharedStubGenerator.generateNonFunctionStubs(wasmBytes)

    fun generateStubs(wasmBytes: ByteArray): List<SharedStubGenerator.ImportModuleStub> =
        SharedStubGenerator.generateStubs(wasmBytes)
}

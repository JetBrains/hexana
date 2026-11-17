package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildNodeJsRunArgs
import java.io.File

/**
 * A command-line state that launches [Node.js](https://nodejs.org/)
 * to run a Wasm module via the built-in WebAssembly API.
 *
 * Node.js does not natively execute `.wasm` files from the CLI, so
 * the state generates an inline script that reads the binary, compiles
 * and instantiates it, and invokes the requested export.
 */
class NodeJsCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val nodePath = configuration.nodePath.ifBlank {
            WasmRuntimeSettings.getInstance().effectiveNodePath()
        }

        val input = WasmRunInput(
            wasmFilePath = configuration.wasmFilePath,
            programArguments = configuration.programArguments,
            workingDirectory = configuration.workingDirectory,
            runtimeOptions = configuration.nodeOptions,
            exportName = configuration.exportName,
        )

        val cmdLine = buildNodeJsRunArgs(input)
        return runCommandLine(nodePath, cmdLine)
    }

    companion object {
        private val LOG = Logger.getInstance(NodeJsCommandLineState::class.java)

        val DEFAULT_NODE_PATH: String = if (SystemInfo.isWindows) "node.exe" else "node"

        fun resolveDefaultNodePath(): String {
            val exeName = if (SystemInfo.isWindows) "node.exe" else "node"
            val candidates = buildList {
                if (!SystemInfo.isWindows) {
                    add("/usr/local/bin/node")
                    add("/usr/bin/node")
                }
            }
            for (candidate in candidates) {
                if (File(candidate).exists()) return candidate
            }
            return DEFAULT_NODE_PATH
        }
    }
}

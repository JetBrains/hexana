package org.jetbrains.hexana.run

import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/**
 * Breakpoint handler for Wasm line breakpoints.
 *
 * When the debug session starts, the platform calls [registerBreakpoint]
 * for each saved breakpoint, and [unregisterBreakpoint] when one is
 * removed.
 *
 * Breakpoints are forwarded to LLDB through the [WasmDebugProcess] so
 * that they are actually set in the native debugger wrapping wasmtime.
 */
class WasmBreakpointHandler(
    private val debugProcess: WasmDebugProcess,
) : XBreakpointHandler<XLineBreakpoint<WasmBreakpointProperties>>(WasmLineBreakpointType::class.java) {

    override fun registerBreakpoint(breakpoint: XLineBreakpoint<WasmBreakpointProperties>) {
        debugProcess.registerBreakpointInLldb(breakpoint)
    }

    override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<WasmBreakpointProperties>, temporary: Boolean) {
        debugProcess.unregisterBreakpointInLldb(breakpoint)
    }
}

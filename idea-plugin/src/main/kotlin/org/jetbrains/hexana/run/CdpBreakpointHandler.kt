package org.jetbrains.hexana.run

import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/**
 * Breakpoint handler for CDP-based Wasm debug sessions (Node.js / Chrome).
 *
 * Forwards breakpoint registration and removal to the [CdpDebugProcess]
 * which communicates with the debug target via the Chrome DevTools Protocol.
 */
internal class CdpBreakpointHandler(
    private val debugProcess: CdpDebugProcess,
) : XBreakpointHandler<XLineBreakpoint<WasmBreakpointProperties>>(WasmLineBreakpointType::class.java) {

    override fun registerBreakpoint(breakpoint: XLineBreakpoint<WasmBreakpointProperties>) {
        debugProcess.registerBreakpointInCdp(breakpoint)
    }

    override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<WasmBreakpointProperties>, temporary: Boolean) {
        debugProcess.unregisterBreakpointInCdp(breakpoint)
    }
}

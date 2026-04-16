package org.jetbrains.hexana.run

import com.intellij.xdebugger.breakpoints.XBreakpointProperties

/**
 * Properties attached to a Wasm line breakpoint.
 *
 * Currently no extra state is needed beyond the file/line pair that the
 * platform already stores, but a concrete properties class is required
 * by [com.intellij.xdebugger.breakpoints.XLineBreakpointType].
 */
class WasmBreakpointProperties : XBreakpointProperties<WasmBreakpointProperties>() {

    override fun getState(): WasmBreakpointProperties = this

    override fun loadState(state: WasmBreakpointProperties) {
        // No extra fields to restore.
    }
}

package org.jetbrains.hexana.breakpoints

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import org.jetbrains.hexana.shared.BinaryAnchor

/**
 * Persisted properties for a Hexana binary breakpoint.
 *
 * The only durable field is [registryId], which links this platform
 * breakpoint object to the corresponding [BinaryBreakpoint] in
 * [BinaryBreakpointRegistry]. The rich anchor data lives there; we
 * look it up on demand rather than duplicating it.
 */
class HexanaBinaryBreakpointProperties : XBreakpointProperties<HexanaBinaryBreakpointProperties>() {

    @com.intellij.util.xmlb.annotations.Attribute
    var registryId: String = ""

    override fun getState(): HexanaBinaryBreakpointProperties = this

    override fun loadState(state: HexanaBinaryBreakpointProperties) {
        registryId = state.registryId
    }
}

/**
 * Non-line breakpoint type for Hexana binary breakpoints (WAT/disassembly gutter).
 *
 * Breakpoints of this type are created programmatically through the gutter and
 * mirrored to the IDE Breakpoints dialog by [XBreakpointMirror]. Adding from
 * the dialog "+' button is not supported ([addBreakpoint] returns null).
 *
 * [getDisplayText] and [getGeneralDescription] look up the backing
 * [BinaryBreakpoint] for a human-readable label.
 */
class HexanaBinaryBreakpointType :
    XBreakpointType<XBreakpoint<HexanaBinaryBreakpointProperties>, HexanaBinaryBreakpointProperties>(
        ID,
        "Hexana Binary Breakpoints",
    ) {

    companion object {
        const val ID = "HexanaBinaryBreakpoint"

        /** Returns the display name suffix for the given anchor, e.g. "+0x10". */
        private fun anchorLabel(anchor: BinaryAnchor): String = when (anchor) {
            is BinaryAnchor.Wasm -> {
                val name = anchor.funcName ?: "\$func${anchor.funcIdx}"
                "$name+0x${(anchor.byteOffset).toString(16)}"
            }
            is BinaryAnchor.Native -> {
                val name = anchor.symbolName ?: "0x${anchor.virtualAddress.toString(16)}"
                "$name+0x${anchor.offsetInSymbol.toString(16)}"
            }
        }
    }

    override fun addBreakpoint(
        project: Project,
        parentComponent: javax.swing.JComponent?,
    ): com.intellij.xdebugger.breakpoints.XBreakpoint<HexanaBinaryBreakpointProperties>? = null

    override fun getDisplayText(breakpoint: XBreakpoint<HexanaBinaryBreakpointProperties>): String {
        val props = breakpoint.properties ?: return "Binary Breakpoint"
        val bp = findBp(props) ?: return "Binary Breakpoint (id=${props.registryId})"
        val fileName = bp.filePath?.substringAfterLast('/') ?: "?"
        val suffix = if (bp.stale) " (stale)" else if (bp.rehomed) " (rehomed)" else ""
        return "$fileName  ${anchorLabel(bp.anchor)}$suffix"
    }

    override fun getGeneralDescription(breakpoint: XBreakpoint<HexanaBinaryBreakpointProperties>): String {
        return getDisplayText(breakpoint)
    }

    private fun findBp(props: HexanaBinaryBreakpointProperties): org.jetbrains.hexana.shared.BinaryBreakpoint? {
        // Properties hold no project reference; scan open projects.
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
            .firstOrNull { !it.isDisposed } ?: return null
        return BinaryBreakpointRegistry.getInstance(project)
            .breakpoints.value.firstOrNull { it.id == props.registryId }
    }
}

package org.jetbrains.hexana.breakpoints

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.shared.BinaryAnchor

/**
 * Observes [BreakpointMaterializationCoordinator.executionPosition] and drives
 * [HexanaFileEditor.executionOffsetFlow] on every matching open editor so the
 * gutter can highlight the current instruction.
 *
 * "Matching" means the editor's [HexanaFileEditor.cachedIdentity] equals the
 * position's identity. All matching editors are updated; in practice there is at
 * most one (you can't open the same binary twice with different identities).
 */
@Service(Service.Level.PROJECT)
final class BinaryExecutionNavigator(private val project: Project) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        val coordinator = project.getService(BreakpointMaterializationCoordinator::class.java)
        coordinator.executionPosition
            .onEach { position -> applyPosition(position) }
            .launchIn(scope)
    }

    private fun applyPosition(position: BinaryExecutionPosition?) {
        val allEditors = FileEditorManager.getInstance(project).allEditors
        for (editor in allEditors) {
            val hexEditor = editor as? HexanaFileEditor ?: continue
            if (position == null) {
                hexEditor.executionOffsetFlow.value = null
                continue
            }
            val identity = hexEditor.cachedIdentity ?: continue
            if (!position.identity.matches(identity)) continue
            hexEditor.executionOffsetFlow.value = when (val anchor = position.anchor) {
                is BinaryAnchor.Wasm -> anchor.byteOffset
                is BinaryAnchor.Native -> anchor.virtualAddress.toLong()
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}

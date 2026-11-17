package org.jetbrains.hexana.breakpoints

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Forces eager instantiation of [XBreakpointMirror] and [BinaryExecutionNavigator]
 * at project startup. Both are @Service light services (no @State) whose init
 * blocks subscribe to flows; without explicit instantiation they would never
 * activate, so breakpoint dialog sync and on-stop navigation would silently do
 * nothing.
 *
 * [BreakpointMaterializationCoordinator] is omitted here because debug-process
 * classes already call project.getService() on session start.
 */
internal class BreakpointsEagerStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<XBreakpointMirror>()
        project.service<BinaryExecutionNavigator>()
    }
}

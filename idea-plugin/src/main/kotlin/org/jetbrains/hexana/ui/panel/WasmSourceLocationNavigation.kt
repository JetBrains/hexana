package org.jetbrains.hexana.ui.panel

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.providers.HexanaSourceLocation
import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * Short display form for a context-menu label: the last two path segments
 * plus the 1-based line, e.g. `markdown/render.rs:87`. Full path stays in
 * the navigation itself; menus keep a bounded width.
 */
internal fun sourceLocationLabel(location: HexanaSourceLocation): String {
    val normalized = location.filePath.replace('\\', '/')
    val segments = normalized.split('/').filter { it.isNotEmpty() }
    val short = segments.takeLast(2).joinToString("/")
    return "$short:${location.line}"
}

/**
 * Resolves the source location of a defined function's body start via the
 * provider's [WasmBinaryProvider.sourceLocationProvider] chain (DWARF first,
 * then a `<file>.map` source-map sidecar when the editor loaded one).
 * [codeIdx] is the defined-function index (row index of the Functions tab).
 */
internal fun wasmFunctionSourceLocation(provider: WasmBinaryProvider, codeIdx: Int): HexanaSourceLocation? {
    val entry = provider.codeOffsets.getOrNull(codeIdx) ?: return null
    // Range query, not a point query: the body's size field and locals precede
    // the first line-program row, so the first mapped row WITHIN the body is
    // the function's location.
    return provider.sourceLocationProvider.getFirstSourceLocationInRange(entry.offset, entry.offset + entry.size)
}

/**
 * Opens [location] in an editor at its 1-based line/column (see
 * [HexanaSourceLocation]); shows the file-not-found notification when the
 * path does not resolve in the VFS. Same flow as the hex view's
 * "Open in sources" action.
 */
internal fun openWasmSourceLocation(project: Project, location: HexanaSourceLocation) {
    HexanaService.getInstance().coroutineScope.launch(Dispatchers.IO) {
        val virtualFile = readAction {
            try {
                VfsUtil.findFile(Path.of(location.filePath), true)
            } catch (_: Exception) {
                null
            }
        }

        if (virtualFile != null) {
            withContext(Dispatchers.EDT) {
                OpenFileDescriptor(
                    project,
                    virtualFile,
                    (location.line - 1).coerceAtLeast(0),
                    (location.column - 1).coerceAtLeast(0),
                ).navigate(true)
            }
        } else {
            val message = HexanaBundle.message("notifications.fileNotFound", location.filePath)
            Notification(
                HexanaService.NOTIFICATION_GROUP,
                message,
                NotificationType.WARNING,
            ).notify(project)
        }
    }
}

package org.jetbrains.hexana.ui.panel

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.nativebin.JVM_FILE_SYSTEM
import org.jetbrains.hexana.providers.HexanaSourceLocation
import org.jetbrains.hexana.shared.resolveNativeSourcePath as sharedResolveNativeSourcePath
import java.nio.file.Path

internal fun openNativeSourceLocation(
    sourceLocation: HexanaSourceLocation,
    state: HexViewStateHolder,
    binaryPath: String?,
) {
    HexanaService.getInstance().coroutineScope.launch(Dispatchers.IO) {
        val resolvedPath = resolveNativeSourcePath(sourceLocation.filePath, state, binaryPath)
        val virtualFile = resolvedPath?.let { path ->
            readAction {
                try {
                    VfsUtil.findFile(path, true)
                } catch (_: Exception) {
                    null
                }
            }
        }

        if (virtualFile != null) {
            withContext(Dispatchers.EDT) {
                OpenFileDescriptor(
                    state.project,
                    virtualFile,
                    (sourceLocation.line - 1).coerceAtLeast(0),
                    (sourceLocation.column - 1).coerceAtLeast(0),
                ).navigate(true)
            }
        } else {
            val message = HexanaBundle.message("notifications.fileNotFound", sourceLocation.filePath)
            Notification(HexanaService.NOTIFICATION_GROUP, message, NotificationType.WARNING).notify(state.project)
        }
    }
}

internal fun resolveNativeSourcePath(
    sourceFilePath: String,
    state: HexViewStateHolder,
    binaryPath: String?,
): Path? {
    val resolved = sharedResolveNativeSourcePath(sourceFilePath, binaryPath, state.project.basePath, JVM_FILE_SYSTEM)
    return resolved?.let { runCatching { Path.of(it) }.getOrNull() }
}

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
import org.jetbrains.hexana.providers.HexanaSourceLocation
import java.nio.file.Path
import kotlin.io.path.exists

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
    return resolveNativeSourcePath(sourceFilePath, state.project.basePath, binaryPath)
}

internal fun resolveNativeSourcePath(
    sourceFilePath: String,
    projectBasePath: String?,
    binaryPath: String?,
): Path? {
    val raw = runCatching { Path.of(sourceFilePath) }.getOrNull() ?: return null
    val binaryDirectory = binaryPath?.let { runCatching { Path.of(it).parent }.getOrNull() }
    val projectDirectory = projectBasePath?.let { runCatching { Path.of(it) }.getOrNull() }

    return buildList {
        add(raw)
        if (!raw.isAbsolute) {
            binaryDirectory?.let { directory ->
                add(directory.resolve(raw).normalize())
                directory.parent?.let { add(it.resolve(raw).normalize()) }
            }
            projectDirectory?.let { add(it.resolve(raw).normalize()) }
        }
    }.distinct().firstOrNull { it.exists() } ?: raw.takeIf { it.isAbsolute }
}

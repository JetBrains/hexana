package org.jetbrains.hexana.ui.pcap

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.pcap.CapturedHttpRequest
import org.jetbrains.hexana.pcap.HttpReplay
import org.jetbrains.hexana.pcap.HttpReplayRequest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hands a captured request to the IntelliJ HTTP Client (bundled in IDEA
 * Ultimate and other paid IDEs) as a scratch `.http` file, where it can be
 * edited, run against environments and kept.
 *
 * There is no compile-time dependency on the HTTP Client: the `.http` text
 * format is its public interface, so all Hexana needs to know is whether the
 * plugin is loaded to offer the action.
 */
internal object HttpClientReplayExport {

    private val HTTP_CLIENT_PLUGIN_ID = PluginId.getId("com.jetbrains.restClient")

    val isAvailable: Boolean
        get() = PluginManagerCore.isLoaded(HTTP_CLIENT_PLUGIN_ID)

    /**
     * Writes [request] - [captured] as captured, or as edited in the replay
     * window - as a new scratch file and opens it. A body that cannot be
     * inlined is written next to it and read with `<`, so the scratch keeps
     * working after the capture is closed.
     */
    suspend fun open(project: Project, captured: CapturedHttpRequest, request: HttpReplayRequest) {
        val packetNumber = captured.packetNumber
        val bodyFileName = if (HttpReplay.needsBodyFile(request)) {
            withContext(Dispatchers.IO) {
                val root = Path.of(ScratchFileService.getInstance().getRootPath(ScratchRootType.getInstance()))
                Files.createDirectories(root)
                val file = Files.createTempFile(root, "hexana-replay-packet-$packetNumber-body-", ".bin")
                Files.write(file, request.body)
                file.fileName.toString()
            }
        } else {
            null
        }
        val text = HttpReplay.toHttpClientFile(
            request,
            title = captured.title,
            comments = listOf(captured.origin),
            bodyFilePath = bodyFileName?.let { "./$it" },
        )
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val file = ScratchRootType.getInstance()
                .createScratchFile(project, captured.httpFileName, null, text)
                ?: return@withContext
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
}

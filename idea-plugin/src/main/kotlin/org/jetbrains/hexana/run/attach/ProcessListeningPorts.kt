package org.jetbrains.hexana.run.attach

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.shared.ListeningEndpoint
import org.jetbrains.hexana.shared.ListeningPorts
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The TCP endpoints process [pid] listens on, for attach targets on an
 * OS-chosen port. Parsing is shared ([ListeningPorts]); this runs the
 * per-OS source: `/proc` on Linux (falling back to `lsof`), `lsof` on macOS,
 * `netstat` on Windows. Blocking; call it off the EDT.
 */
internal fun findListeningEndpoints(pid: Int): List<ListeningEndpoint> = when {
    SystemInfo.isLinux -> fromProc(pid).ifEmpty { fromLsof(pid, "lsof") }
    SystemInfo.isMac -> fromLsof(pid, MAC_LSOF.takeIf { File(it).canExecute() } ?: "lsof")
    SystemInfo.isWindows -> {
        val output = run(GeneralCommandLine(listOf("netstat") + ListeningPorts.NETSTAT_ARGUMENTS))
        ListeningPorts.parseNetstat(output, pid)
    }
    else -> emptyList()
}

private fun fromProc(pid: Int): List<ListeningEndpoint> {
    val inodes = try {
        Files.list(Path.of("/proc/$pid/fd")).use { fds ->
            fds.toList().mapNotNullTo(HashSet()) { fd ->
                try {
                    ListeningPorts.socketInode(Files.readSymbolicLink(fd).toString())
                } catch (_: Exception) {
                    null
                }
            }
        }
    } catch (_: Exception) {
        return emptyList()
    }
    if (inodes.isEmpty()) return emptyList()
    return listOf("/proc/net/tcp", "/proc/net/tcp6").flatMap { table ->
        try {
            ListeningPorts.parseProcNetTcp(File(table).readText(), inodes)
        } catch (_: Exception) {
            emptyList()
        }
    }.distinct()
}

private fun fromLsof(pid: Int, lsof: String): List<ListeningEndpoint> =
    ListeningPorts.parseLsof(run(GeneralCommandLine(listOf(lsof) + ListeningPorts.lsofArguments(pid))))

/** lsof exits 1 when the process has no matching socket; its output is still the answer. */
private fun run(commandLine: GeneralCommandLine): String =
    ExecUtil.execAndGetOutput(commandLine, COMMAND_TIMEOUT_MS).stdout

private const val MAC_LSOF = "/usr/sbin/lsof"
private const val COMMAND_TIMEOUT_MS = 5_000

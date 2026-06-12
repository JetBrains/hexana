package org.jetbrains.hexana.document

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.document.journal.Journal
import org.jetbrains.hexana.document.journal.JournalCodec
import org.jetbrains.hexana.document.journal.JournalLoadResult
import org.jetbrains.hexana.document.journal.JournalPersister
import org.jetbrains.hexana.document.journal.MismatchReason
import org.jetbrains.hexana.io.CommonByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * IDE-side [JournalPersister] that writes the edit journal to a sidecar file
 * named `<source>.hexana.json` next to the original binary.
 *
 * - `save(journal)` with non-null journal: serialize via [JournalCodec.encode]
 *   and write the bytes to the sidecar via VFS inside a write action.
 * - `save(null)`: delete the sidecar if it exists.
 *
 * **Cadence**: called on every successful `commit()`, `undo()`, and `redo()`.
 * The piece-table document drives the calls; this persister just translates
 * them to VFS operations.
 *
 * **Atomic-rename caveat**: §6 of the design spec asks for tmp-file +
 * atomic-rename. The current Phase 1 implementation just calls
 * `setBinaryContent` directly. Power-loss during write could leave a
 * truncated sidecar — at which point the `loadFromJson` path treats it as
 * `Mismatch(CORRUPT)` and the IDE prompts the user. Tightening this to a
 * real atomic write is a follow-up.
 */
internal class IdeaJournalPersister(
    private val sourceFile: VirtualFile,
) : JournalPersister {

    private val sidecarName: String = "${sourceFile.name}.hexana.json"

    override fun save(journal: Journal?) {
        // Persisting fires on every commit/undo/redo, which run on whatever
        // thread the editing happens on. We schedule the actual VFS write
        // on the EDT inside a write action — the only thread/contract VFS
        // accepts. The ordering of these scheduled writes is preserved
        // because invokeLater is FIFO on the EDT, so the final state on
        // disk matches the final journal observed in memory.
        ApplicationManager.getApplication().invokeLater {
            try {
                WriteAction.run<Throwable> { writeSidecar(journal) }
            } catch (t: Throwable) {
                HexanaLog.LOG.warn("Failed to persist journal sidecar at $sidecarName", t)
            }
        }
    }

    private fun writeSidecar(journal: Journal?) {
        val parent: VirtualFile? = sourceFile.parent
        if (parent == null) {
            HexanaLog.LOG.warn("source file has no parent; cannot persist sidecar: ${sourceFile.path}")
            return
        }
        if (journal == null) {
            // Try VFS first; fall back to NIO delete for the case where a
            // preceding save(journal) wrote the sidecar via atomicWrite
            // (NIO) and the async VFS refresh hasn't completed yet, so
            // findChild returns null even though the file exists on disk.
            val vfsChild = parent.findChild(sidecarName)
            if (vfsChild != null) {
                vfsChild.delete(this)
            } else {
                try {
                    val nioPath = Paths.get(parent.path).resolve(sidecarName)
                    Files.deleteIfExists(nioPath)
                } catch (_: InvalidPathException) { /* non-local fs, nothing to clean */ }
            }
            return
        }
        val json: String = JournalCodec.encode(journal)
        val bytes: ByteArray = json.encodeToByteArray()
        if (atomicWrite(parent, bytes)) {
            // nio path: file appeared on disk outside VFS; trigger a refresh
            // so the IDE's tree / inspections see the new sidecar.
            parent.refresh(/* async = */ true, /* recursive = */ false)
        } else {
            // Non-local fs (e.g. jar://, ssh) — fall back to direct VFS
            // overwrite. Not atomic, but the only path available there.
            val existing = parent.findChild(sidecarName)
            val target = existing ?: parent.createChildData(this, sidecarName)
            target.setBinaryContent(bytes)
        }
    }

    /**
     * §6 atomic-rename pattern. Writes [bytes] to `<sidecar>.tmp` and then
     * `Files.move` with `ATOMIC_MOVE + REPLACE_EXISTING` over the real
     * sidecar. Either the move completes (new state visible) or it doesn't
     * (old state intact). Crash / power-loss between the two states never
     * leaves a half-written sidecar, which is the failure mode that would
     * otherwise produce `Mismatch(CORRUPT)` on the next file open.
     *
     * Returns `true` if the nio write succeeded, `false` if the parent
     * isn't a real on-disk directory (jar://, sftp://, …) — caller falls
     * back to a direct VFS write.
     *
     * Falls back to non-atomic `REPLACE_EXISTING` when the filesystem
     * doesn't support `ATOMIC_MOVE` (e.g. moves crossing mount points).
     */
    private fun atomicWrite(parent: VirtualFile, bytes: ByteArray): Boolean {
        val parentPath: Path = try {
            Paths.get(parent.path)
        } catch (_: InvalidPathException) {
            return false
        }
        if (!Files.isDirectory(parentPath)) return false
        val tmp = parentPath.resolve("$sidecarName.tmp")
        val target = parentPath.resolve(sidecarName)
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // Some filesystems can't atomic-move (e.g. across mount points).
            // Fall back to a non-atomic move that still avoids the
            // truncate-then-write window, just not power-loss-safe.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    }
}

/**
 * Looks for `<source>.hexana.json` next to [sourceFile] and, if present,
 * runs it through [JournalCodec.loadFromJson] against [originalBytes]. Pure
 * IDE-side I/O wrapper around the codec — no replay, no UX. Returns:
 * - the parsed [JournalLoadResult] when a sidecar exists, or
 * - `null` when no sidecar exists (the caller treats this as a fresh open).
 *
 * Read happens off-EDT-safe (VFS reads are allowed from any thread for
 * existing files); the caller is expected to call this synchronously
 * during `remember { ... }` — the journal is small enough that this isn't
 * worth deferring to a background dispatcher in Phase 1.
 */
internal fun loadSidecarIfPresent(
    sourceFile: VirtualFile,
    originalBytes: CommonByteBuffer,
): JournalLoadResult? {
    val parent: VirtualFile = sourceFile.parent ?: return null
    val sidecar: VirtualFile = parent.findChild("${sourceFile.name}.hexana.json") ?: return null
    val text: String = try {
        sidecar.contentsToByteArray().decodeToString()
    } catch (t: Throwable) {
        HexanaLog.LOG.warn("Failed to read journal sidecar at ${sidecar.path}", t)
        return null
    }
    return JournalCodec.loadFromJson(text, originalBytes)
}

/**
 * Surfaces the §8 Mismatch UX: balloon notification with `Discard` and
 * `Backup, then discard` actions. Per spec, no "apply anyway" option —
 * replaying a stale log against the wrong file produces silently bad
 * offsets, which is data loss disguised as success.
 *
 * Both action handlers run on the EDT inside a write action and operate
 * on the VFS. Backup renames the sidecar to
 * `<source>.hexana.stale.<UTC-timestamp>.json` (timestamp formatted
 * `yyyyMMddTHHmmssZ` — portable to Windows file names) before deletion;
 * the file system actually moves the bytes via `rename`, then the
 * original sidecar VFS entry is gone, so the next file open sees no
 * sidecar and starts fresh.
 *
 * The pure layer (`JournalCodec`) never deletes or modifies the sidecar —
 * deletion is exclusively the IDE plugin's responsibility, gated behind
 * an explicit user click.
 */
internal fun notifyJournalMismatch(
    project: Project,
    sourceFile: VirtualFile,
    reason: MismatchReason,
) {
    val sidecarName = "${sourceFile.name}.hexana.json"
    val title = when (reason) {
        MismatchReason.HASH, MismatchReason.LENGTH ->
            "Edit log is for a different version of ${sourceFile.name}"
        MismatchReason.CORRUPT ->
            "Edit log $sidecarName is corrupted"
        MismatchReason.SCHEMA_UNKNOWN ->
            "Edit log $sidecarName uses an unknown schema version"
    }
    val content = "The on-disk edit log can't be applied to this file. " +
        "Discard it, or back it up first so you can recover manually."
    val notification = Notification(
        HexanaService.NOTIFICATION_GROUP,
        title,
        content,
        NotificationType.WARNING,
    )
    notification.addAction(NotificationAction.createSimple("Discard log") {
        deleteSidecar(sourceFile, sidecarName)
        notification.expire()
    })
    notification.addAction(NotificationAction.createSimple("Backup, then discard") {
        backupAndDeleteSidecar(sourceFile, sidecarName)
        notification.expire()
    })
    notification.notify(project)
}

private fun deleteSidecar(sourceFile: VirtualFile, sidecarName: String) {
    ApplicationManager.getApplication().invokeLater {
        try {
            WriteAction.run<Throwable> {
                val parent = sourceFile.parent ?: return@run
                val sidecar = parent.findChild(sidecarName) ?: return@run
                sidecar.delete(sourceFile)
                HexanaLog.LOG.info("Discarded stale journal sidecar ${sidecar.path}")
            }
        } catch (t: Throwable) {
            HexanaLog.LOG.warn("Failed to discard journal sidecar $sidecarName", t)
        }
    }
}

private val STALE_TIMESTAMP_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

private fun backupAndDeleteSidecar(sourceFile: VirtualFile, sidecarName: String) {
    ApplicationManager.getApplication().invokeLater {
        try {
            WriteAction.run<Throwable> {
                val parent = sourceFile.parent ?: return@run
                val sidecar = parent.findChild(sidecarName) ?: return@run
                val ts = STALE_TIMESTAMP_FMT.format(Instant.now())
                val backupName = "${sourceFile.name}.hexana.stale.$ts.json"
                // VFS `rename` would clash if a backup already exists under
                // this exact timestamp (extremely unlikely). Suffix-bump to
                // be safe: `.json` → `.1.json`, `.2.json`, etc.
                var resolvedName = backupName
                var bump = 1
                while (parent.findChild(resolvedName) != null) {
                    resolvedName = "${sourceFile.name}.hexana.stale.$ts.$bump.json"
                    bump++
                }
                sidecar.rename(sourceFile, resolvedName)
                HexanaLog.LOG.info("Backed up stale journal sidecar to $resolvedName")
            }
        } catch (t: Throwable) {
            HexanaLog.LOG.warn("Failed to back up + discard journal sidecar $sidecarName", t)
        }
    }
}

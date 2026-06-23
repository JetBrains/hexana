package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.ArchiveMemberTabTitleProvider
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.ArchiveMemberClassification
import org.jetbrains.hexana.providers.ArchiveMemberKind
import org.jetbrains.hexana.providers.classifyArchiveMember
import org.jetbrains.hexana.providers.pe.PeArchiveMember
import org.jetbrains.hexana.shared.formatSizeLong
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Member list for a static-library archive (`.a` / `.lib`). Lists the real object members
 * (bookkeeping members — the symbol table `/`, the long-names table `//`, BSD `__.SYMDEF` —
 * are hidden). Clicking a member extracts its bytes to a file in the IDE temp folder and opens
 * that file in a child editor; because the extracted file lands on disk with its native magic
 * intact, [org.jetbrains.hexana.NativeBinaryFileTypeDetector] routes it to the right ELF / PE /
 * Mach-O view, and its on-disk path also enables the llvm-objdump disassembly panel.
 *
 * Scale: enumeration is header-only (done in [ArchiveBinaryProvider.archiveFile]); only the
 * single clicked member is ever materialized into the heap. The list is virtualized and keyed
 * by [PeArchiveMember.index] — member names are not unique (duplicate `.o` names are common).
 */
@Composable
internal fun ArchiveMembersPanel(
    provider: ArchiveBinaryProvider,
    state: HexViewStateHolder,
    archiveName: String? = null,
) {
    val project = state.project
    // Service-level scope, NOT rememberCoroutineScope: opening the extracted member switches the
    // active editor, which removes THIS panel from composition. A composition-scoped scope would
    // be cancelled mid-extraction (ForgottenCoroutineScopeException) — the extraction must outlive
    // the panel. Cancellation is still respected via the catch below (rethrown, never logged).
    val scope = HexanaService.getInstance().coroutineScope

    // One temp dir per opened archive editor, created lazily on first click. Extracted files
    // outlive this panel (they open in independent editor tabs), so they are NOT deleted when
    // the panel leaves composition — only on JVM exit (deleteOnExit). The IDE temp folder is
    // cleared on restart regardless.
    val tempDir = remember(provider) { AtomicReference<Path?>(null) }

    val members = remember(provider) { provider.members.filterNot { it.isBookkeeping } }
    // Base names shared by more than one member — those tabs get the member index appended so the
    // two are distinguishable (`archive/foo.o (3)` vs `archive/foo.o (7)`).
    val ambiguousBaseNames = remember(members) {
        members.groupingBy { baseName(it.name) }.eachCount().filterValues { it > 1 }.keys
    }
    var status by remember(provider) { mutableStateOf<String?>(null) }

    var query by rememberSaveable { mutableStateOf("") }

    val displayed by remember(members, query) {
        derivedStateOf {
            if (query.isBlank()) members
            else members.filter { it.name.contains(query, ignoreCase = true) }
        }
    }

    fun open(member: PeArchiveMember, cls: ArchiveMemberClassification) {
        if (!cls.openable) {
            status = "“${member.name}” is a ${cls.displayName.lowercase()} — no object file to open."
            return
        }
        status = null
        // Tab title: `archive-name/member-base-name`, with the member index appended when the
        // base name collides with another member's. See ArchiveMemberTabTitleProvider.
        val base = baseName(member.name)
        val tabTitle = buildString {
            archiveName?.let { append(it).append('/') }
            append(base)
            if (base in ambiguousBaseNames) append(" (").append(member.index).append(')')
        }
        scope.launch(Dispatchers.IO) {
            try {
                val dir = tempDir.updateAndGet { existing ->
                    existing ?: Files.createTempDirectory(
                        Path.of(PathManager.getTempPath()),
                        "hexana-archive-",
                    ).also { it.toFile().deleteOnExit() }
                }!!
                // Copy only this one member's bytes (slice is a zero-copy view; toByteArray
                // materializes just the member range, never the whole archive).
                val bytes = provider.bytes
                    .slice(member.contentRange.offset, member.contentRange.size)
                    .toByteArray()
                // index in the file name guarantees uniqueness across duplicate member names.
                val fileName = "${member.index}-${sanitizeName(member.name)}${extensionFor(cls.kind)}"
                val path = dir.resolve(fileName)
                Files.write(path, bytes)
                path.toFile().deleteOnExit()
                val vf = VfsUtil.findFile(path, true)
                if (vf == null) {
                    status = "Could not materialize “${member.name}” in the temp folder."
                    return@launch
                }
                withContext(Dispatchers.EDT) {
                    if (project.isDisposed) return@withContext
                    vf.putUserData(ArchiveMemberTabTitleProvider.TITLE_KEY, tabTitle)
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            } catch (c: CancellationException) {
                throw c // control-flow exception — must be rethrown, never logged
            } catch (t: Throwable) {
                HexanaLog.LOG.warn("Failed to extract archive member ${member.name}", t)
                status = "Failed to open “${member.name}”: ${t.message ?: t::class.simpleName}"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(query = query, onQueryChange = { query = it }, placeholder = "Search members…")
        HeaderRow()
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (displayed.isEmpty()) {
                Text(
                    if (members.isEmpty()) "(no object members)" else "(no matches for \"$query\")",
                    style = RowStyle,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(state = listState) {
                    items(
                        count = displayed.size,
                        key = { idx -> displayed[idx].index },
                    ) { idx ->
                        val member = displayed[idx]
                        // Classify on demand for the visible row only — reads the member's
                        // leading header fields via a zero-copy slice, no body read.
                        val cls = remember(provider, member.index) {
                            classifyArchiveMember(provider.bytes, member.contentRange)
                        }
                        MemberRow(member = member, cls = cls, onOpen = { open(member, cls) })
                    }
                }
                VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }

        status?.let { message ->
            Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
            Text(
                message,
                style = RowStyle.copy(color = HexanaTheme.hexTextColor),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun HeaderRow() {
    val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = HexanaTheme.textColor,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Member", style = style, modifier = Modifier.weight(5f))
        Text("Format", style = style, modifier = Modifier.weight(1.6f))
        Text("Size", style = style, modifier = Modifier.weight(1.2f))
    }
}

@Composable
private fun MemberRow(
    member: PeArchiveMember,
    cls: ArchiveMemberClassification,
    onOpen: () -> Unit,
) {
    val nameColor = if (cls.openable) HexanaTheme.textColor else HexanaTheme.hexTextColor
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(member.name, style = RowStyle.copy(color = nameColor), modifier = Modifier.weight(5f))
        Text(cls.displayName, style = RowStyle, modifier = Modifier.weight(1.6f))
        Text(formatSizeLong(member.size.toLong()), style = RowStyle, modifier = Modifier.weight(1.2f))
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    val outline = if (JewelTheme.isDark) Color(0xFF393B3D) else Color(0xFFC9CCD6)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = HexanaTheme.textColor,
            ),
            cursorBrush = SolidColor(HexanaTheme.textColor),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, outline, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(placeholder, style = RowStyle.copy(color = HexanaTheme.hexTextColor))
                }
                inner()
            },
        )
    }
}

/** The member's file name without any archive-internal directory path. */
private fun baseName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').ifEmpty { name }

/** Keeps only filesystem-safe characters so a member name can become a temp file name. */
private fun sanitizeName(name: String): String {
    val base = baseName(name)
    val cleaned = base.map { ch ->
        if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
    }.joinToString("")
    return cleaned.take(96).ifEmpty { "member" }
}

/** A file-name hint for the extracted member; the content detector re-sniffs the magic regardless. */
private fun extensionFor(kind: ArchiveMemberKind): String = when (kind) {
    ArchiveMemberKind.ELF,
    ArchiveMemberKind.MACH_O,
    ArchiveMemberKind.FAT_MACH_O,
    ArchiveMemberKind.COFF -> ".o"
    ArchiveMemberKind.PE -> ".exe"
    ArchiveMemberKind.ARCHIVE -> ".a"
    ArchiveMemberKind.SHORT_IMPORT,
    ArchiveMemberKind.UNKNOWN -> ""
}

private val RowStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

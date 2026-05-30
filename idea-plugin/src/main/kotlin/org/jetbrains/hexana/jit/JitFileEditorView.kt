package org.jetbrains.hexana.jit

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed as keyIsCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed as keyIsMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexHighlightRange
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.HexanaFileEditorProvider
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.hexana.javaLang.ChicoryAotMatch
import org.jetbrains.hexana.javaLang.ChicoryAotRegistry
import org.jetbrains.hexana.ui.CLASS_FILE_NAV_TARGET_KEY
import org.jetbrains.hexana.ui.ClassFileNavTarget
import org.jetbrains.hexana.ui.WASM_FUNCTION_NAV_TARGET_KEY
import org.jetbrains.hexana.ui.WasmFunctionNavTarget
import org.jetbrains.hexana.disassembly.capstone.CapstoneWasmDisassembler
import org.jetbrains.hexana.disassembly.capstone.loadFromClasspath
import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.AssemblyFormatter
import org.jetbrains.hexana.disassembly.core.AssemblySyntax
import org.jetbrains.hexana.disassembly.core.DecodedInstruction
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.javabytecode.JavaBytecodeDecoder
import org.jetbrains.hexana.javabytecode.JavaBytecodeInstruction
import org.jetbrains.hexana.javabytecode.JavaBytecodeOperands
import org.jetbrains.hexana.jvm.jit.Inlined
import org.jetbrains.hexana.jvm.jit.JitFile
import org.jetbrains.hexana.jvm.jit.JitSym
import org.jetbrains.hexana.jvm.jit.Sym
import org.jetbrains.hexana.providers.BinaryProvider
import java.awt.datatransfer.StringSelection
import java.util.Locale
import org.jetbrains.hexana.ui.HexViewer
import org.jetbrains.hexana.ui.VerticalSplitPanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

// Debug logger for the chicory-AOT bytecode-view-badge feature. Tagged
// [chicory-debug] in messages so a single grep on idea.log surfaces the
// full pipeline (Maven afterImport → registry setConfigs → findMatch
// → badge render decision).
private val CHICORY_LOG: Logger = Logger.getInstance("hexana.jit.chicory")

/**
 * Editor view for a `.jit` dump.
 *
 *  - Left:  tree of compiled methods grouped by Java FQN — each method
 *           collapses its recompile versions; clicking the parent expands
 *           and selects the latest version. A special "Dump info" entry at
 *           the top of the tree switches the right pane to [JitInfoView].
 *  - Top right:  global hex view over the WHOLE `.jit` file. On sym
 *                selection the view scrolls to that sym's `fileOffset`
 *                and the sym's native byte range is highlighted; selecting
 *                a bytecode row adds per-BCI overlay highlights on top.
 *                Hidden when Info is selected.
 *  - Bottom right: a tab strip with three tabs over the selected method:
 *                  • Combined — one row per JVM bytecode index pairing the
 *                    decoded bytecode with the JIT-emitted native instructions
 *                    for that BCI (`bci opcode | addr hex asm`), with inline
 *                    records shown as a clickable inlay.
 *                  • Bytecode — the method's decoded JVM bytecode, flat
 *                    (`bci opcode operands`), independent of any native code.
 *                  • Machine code — the whole native code block disassembled
 *                    start-to-end (`addr hex asm`), including prologue /
 *                    epilogue / stub code that maps to no BCI.
 *                  Replaced by [JitInfoView] when Info is selected.
 *
 * This is the v0 "compiled-method browser" — when the disassembler lands
 * the per-BCI machine-code hex on the right of each row will be replaced
 * with rendered ISA mnemonics, but the rest of the structure stays.
 */

/**
 * Selection state for the right pane of [JitFileEditorView].
 *
 * [Info] renders [JitInfoView] (dump metadata, system properties, loaded
 * libraries). [Sym] renders the normal bytecode/hex split for the chosen
 * compiled method at a particular compilation version.
 */
private sealed interface JitSelection {
    data object Info : JitSelection
    data class Sym(val key: SymKey, val version: Int) : JitSelection
}

/**
 * A pending "go to machine address" navigation, produced when the user types a
 * `0x…` address into the tree search box. [address] is the absolute runtime address;
 * [nonce] bumps on every search so downstream one-shot effects (tab switch, list
 * scroll, hex scroll) re-fire even when the same address is searched twice.
 */
private data class AddressNav(val address: ULong, val nonce: Int)

/**
 * Parse a tree-search query as a machine address. Requires an explicit `0x` / `0X`
 * prefix (so a bare-hex-looking method name like `feed` stays a name search) and at
 * most 16 hex digits. Returns null when the query is not an address.
 */
private fun parseMachineAddress(query: String): ULong? {
    val t = query.trim()
    if (t.length < 3 || !(t.startsWith("0x") || t.startsWith("0X"))) return null
    val hex = t.substring(2)
    if (hex.length > 16) return null
    return hex.toULongOrNull(16)
}

/**
 * Index of the [NativeRow] whose instruction covers [addr] — the row with the
 * greatest `address <= addr`. Rows are address-ascending, so this is a binary
 * search. Returns null when [addr] precedes the first row or the list is empty.
 */
private fun indexOfAddress(rows: List<NativeRow>, addr: ULong): Int? {
    var lo = 0
    var hi = rows.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (rows[mid].address <= addr) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return if (ans >= 0) ans else null
}

@Composable
internal fun JitFileEditorView(
    project: Project,
    jit: JitFile,
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    hexFocusRequester: FocusRequester,
) {
    val groups = remember(jit) { groupSyms(jit.symbols) }

    // Selection is a sealed type: either the Info entry or a specific sym
    // version. remember(jit) re-initialises whenever the open file changes,
    // which is the right semantic (a different .jit file should default to
    // its own first method, not carry over the previous file's selection).
    // rememberSaveable would require a custom Saver for the sealed type and
    // only buys survival across process death in desktop Compose — not worth
    // the boilerplate for an analysis panel.
    var selection by remember(jit) {
        val firstKey = groups.firstOrNull()?.key
        val firstVersion = groups.firstOrNull()?.versions?.maxOf { it.version.toInt() } ?: -1
        mutableStateOf<JitSelection>(
            if (firstKey != null) JitSelection.Sym(firstKey, firstVersion) else JitSelection.Info,
        )
    }

    val selectedSym = remember(groups, selection) {
        val sel = selection as? JitSelection.Sym ?: return@remember null
        val g = groups.firstOrNull { it.key == sel.key } ?: return@remember null
        g.versions.firstOrNull { it.version.toInt() == sel.version }
            ?: g.versions.maxByOrNull { it.version.toInt() }
    }

    // Drive the hex view's selected-range highlight + scroll from the
    // currently-selected sym. Per-BCI highlights, when present, layer on
    // top via a separate state path below.
    var selectedBci by remember(selectedSym) { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedSym, selectedBci) {
        val sym = selectedSym ?: run {
            state.setHighlightRanges(emptyList())
            return@LaunchedEffect
        }
        val ranges = ArrayList<HexHighlightRange>()
        // Persistent sym-range highlight — the user always knows where
        // the current method's native bytes live in the file.
        ranges += HexHighlightRange(
            offset = sym.fileOffset.toLong(),
            size = sym.codeSize.toInt(),
            color = SymRangeColor,
        )
        val bci = selectedBci
        if (bci != null) {
            ranges += bciHighlightRanges(sym, bci)
        }
        state.setHighlightRanges(ranges)
        state.scrollHexViewTo(sym.fileOffset.toLong())
    }

    // --- Search by machine address (typed as 0x… in the tree search box) ---
    // Resolve the dump-wide method whose [address, address+codeSize) contains the
    // target, select it, and stash an AddressNav so the detail pane switches to the
    // Machine-code tab and scrolls to the instruction. Returns false (no change)
    // when no compiled method covers the address.
    var addressNav by remember(jit) { mutableStateOf<AddressNav?>(null) }
    var navCounter by remember(jit) { mutableStateOf(0) }
    val onGoToAddress: (ULong) -> Boolean = { addr ->
        val target = jit.symbols.firstOrNull { addr >= it.address && addr < it.address + it.codeSize }
        val grp = target?.let { t -> groups.firstOrNull { g -> g.versions.any { it === t } } }
        if (target != null && grp != null) {
            selection = JitSelection.Sym(grp.key, target.version.toInt())
            navCounter += 1
            addressNav = AddressNav(addr, navCounter)
            true
        } else {
            false
        }
    }

    // Address-nav hex sync: when a fresh address search lands inside the selected
    // sym, scroll the hex view to that instruction's byte offset. Keyed on the nonce
    // only (declared after the selection effect above), so a later BCI click isn't
    // fought by this effect, and the last scroll emission wins at search time.
    LaunchedEffect(addressNav?.nonce) {
        val nav = addressNav ?: return@LaunchedEffect
        val sym = selectedSym ?: return@LaunchedEffect
        if (nav.address >= sym.address && nav.address < sym.address + sym.codeSize) {
            state.scrollHexViewTo(sym.fileOffset.toLong() + (nav.address - sym.address).toLong())
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(if (JewelTheme.isDark) Color(0xFF1F2123) else Color(0xFFF7F8FA)),
        ) {
            JitSymTree(
                groups = groups,
                selection = selection,
                onSelectInfo = { selection = JitSelection.Info },
                onSelect = { key, version -> selection = JitSelection.Sym(key, version) },
                onGoToAddress = onGoToAddress,
            )
        }
        Divider(orientation = Orientation.Vertical, modifier = Modifier.fillMaxHeight())
        Box(modifier = Modifier.fillMaxSize()) {
            when (val sel = selection) {
                is JitSelection.Info -> {
                    JitInfoView(jit = jit)
                }
                is JitSelection.Sym -> {
                    if (selectedSym == null) {
                        Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                            Text("This JIT dump contains no compiled-method records.", style = MonoStyle)
                        }
                    } else {
                        JitSymDetail(
                            project = project,
                            sym = selectedSym,
                            binaryProvider = binaryProvider,
                            state = state,
                            hexFocusRequester = hexFocusRequester,
                            selectedBci = selectedBci,
                            onSelectBci = { selectedBci = if (selectedBci == it) null else it },
                            addressNav = addressNav,
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Sym grouping — versions of the same method collapsed under one parent
// -----------------------------------------------------------------------------

private data class SymKey(
    val classFqn: String,
    val methodName: String,
    val methodSignature: String,
)

private data class SymGroup(
    val key: SymKey,
    val displayLabel: String,
    val versions: List<JitSym>,
)

private fun groupSyms(syms: List<JitSym>): List<SymGroup> {
    val byKey = LinkedHashMap<SymKey, MutableList<JitSym>>()
    for (sym in syms) {
        val s = sym.sym() ?: continue
        val key = SymKey(
            classFqn = javaFqn(s.classSignature),
            methodName = s.methodName,
            methodSignature = s.methodSignature,
        )
        byKey.getOrPut(key) { ArrayList() }.add(sym)
    }
    return byKey.map { (key, versions) ->
        val sorted = versions.sortedBy { it.version.toInt() }
        SymGroup(
            key = key,
            displayLabel = "${key.classFqn}::${key.methodName}${key.methodSignature}",
            versions = sorted,
        )
    }
}

/** Strips `L`/`;`, converts `/` to `.`, returns the bare Java-style FQN. */
private fun javaFqn(jvmSignature: String): String =
    jvmSignature.removePrefix("L").removeSuffix(";").replace('/', '.')

// -----------------------------------------------------------------------------
// Left column — sym tree
// -----------------------------------------------------------------------------

@Composable
private fun JitSymTree(
    groups: List<SymGroup>,
    selection: JitSelection,
    onSelectInfo: () -> Unit,
    onSelect: (SymKey, Int) -> Unit,
    onGoToAddress: (ULong) -> Boolean,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable {
        mutableStateOf(setOf<SymKey>())
    }

    val filtered by remember(groups, query) {
        derivedStateOf {
            when {
                // Address mode (0x…) is a jump, not a name filter — keep the whole
                // tree visible so the jumped-to method shows its selection highlight.
                parseMachineAddress(query) != null -> groups
                query.isBlank() -> groups
                else -> groups.filter { it.displayLabel.contains(query, ignoreCase = true) }
            }
        }
    }

    val infoSelected = selection is JitSelection.Info
    val selectedKey = (selection as? JitSelection.Sym)?.key
    val selectedVersion = (selection as? JitSelection.Sym)?.version ?: -1

    Column(modifier = Modifier.fillMaxSize()) {
        SymSearchBar(query = query, onQueryChange = { query = it }, onAddressSubmit = onGoToAddress)
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState) {
                // "Dump info" pinned entry — always at the top of the tree,
                // above all sym groups. Styled distinctly: italic label,
                // info glyph, no version count badge.
                item(key = "__info__") {
                    InfoEntryRow(selected = infoSelected, onClick = onSelectInfo)
                }
                if (filtered.isEmpty()) {
                    item(key = "__empty__") {
                        Text(
                            if (groups.isEmpty()) "(no compiled methods)" else "(no matches)",
                            style = RowStyle.copy(color = HexanaMuted),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    for (group in filtered) {
                        val open = group.key in expanded
                        item(key = "g-${group.key}") {
                            GroupHeaderRow(
                                group = group,
                                open = open,
                                selected = group.key == selectedKey,
                                onClick = {
                                    expanded = if (open) expanded - group.key else expanded + group.key
                                    // Clicking the parent also drives the
                                    // right-side panels: pick the most
                                    // recently-compiled version by default.
                                    val latest = group.versions.maxByOrNull { it.version.toInt() }
                                    if (latest != null) onSelect(group.key, latest.version.toInt())
                                },
                            )
                        }
                        if (open) {
                            for (sym in group.versions) {
                                // Key on fileOffset, not version: a buggy
                                // pre-fix agent dump can carry two syms
                                // with the same version for one method,
                                // and version-keyed rows would collide.
                                item(key = "v-${group.key}-${sym.fileOffset}") {
                                    GroupVersionRow(
                                        sym = sym,
                                        selected = group.key == selectedKey &&
                                            sym.version.toInt() == selectedVersion,
                                        onClick = { onSelect(group.key, sym.version.toInt()) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Special pinned row at the top of the sym tree that opens [JitInfoView].
 * Visually distinct from method-group rows: uses an "ⓘ" glyph and a
 * slightly different foreground so it reads as a meta-entry rather than a
 * compiled method.
 */
@Composable
private fun InfoEntryRow(selected: Boolean, onClick: () -> Unit) {
    val bg = when {
        selected && JewelTheme.isDark -> Color(0xFF2E436E)
        selected -> Color(0xFFD4E2FC)
        else -> Color.Transparent
    }
    val labelColor = if (JewelTheme.isDark) Color(0xFF9DA0A8) else Color(0xFF6C6F75)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ⓘ", style = RowStyle.copy(color = labelColor))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "Dump info",
            style = RowStyle.copy(color = labelColor, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun GroupHeaderRow(
    group: SymGroup,
    open: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        selected && JewelTheme.isDark -> Color(0xFF2E436E)
        selected -> Color(0xFFD4E2FC)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "▼" else "▶", style = RowStyle.copy(color = HexanaMuted))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            group.displayLabel,
            style = RowStyle.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "${group.versions.size}×",
            style = RowStyle.copy(color = HexanaMuted),
        )
    }
}

@Composable
private fun GroupVersionRow(
    sym: JitSym,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        selected && JewelTheme.isDark -> Color(0xFF243C5F)
        selected -> Color(0xFFE3ECFB)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = 26.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("v${sym.version.toInt()}", style = RowStyle.copy(fontWeight = FontWeight.SemiBold))
        Spacer(modifier = Modifier.width(10.dp))
        Text("${sym.codeSize} bytes", style = RowStyle.copy(color = HexanaMuted))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            "@0x${sym.address.toString(16)}",
            style = RowStyle.copy(fontSize = 10.sp, color = HexanaMuted),
        )
    }
}

/**
 * Tree search box. Doubles as a "go to machine address" jump: when the query is a
 * `0x…` address ([parseMachineAddress]), pressing Enter calls [onAddressSubmit] to
 * locate + select the compiled method containing that address (and drive the
 * Machine-code tab / hex view to it). A hint line below the field shows the parsed
 * address, or a not-found message when no method covers it.
 */
@Composable
private fun SymSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onAddressSubmit: (ULong) -> Boolean,
) {
    val outline = if (JewelTheme.isDark) Color(0xFF393B3D) else Color(0xFFC9CCD6)
    val textColor = if (JewelTheme.isDark) Color(0xFFBCBEC4) else Color(0xFF1F1F1F)
    val addr = parseMachineAddress(query)
    // Reset the not-found flag whenever the query changes.
    var notFound by remember(query) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = textColor),
            cursorBrush = SolidColor(textColor),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, outline, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && addr != null) {
                        notFound = !onAddressSubmit(addr)
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("Search class/method, or 0x<addr>…", style = RowStyle.copy(color = HexanaMuted))
                }
                inner()
            },
        )
        if (addr != null) {
            Text(
                if (notFound) "no compiled method contains 0x${addr.toString(16)}"
                else "press ↵ to go to 0x${addr.toString(16)}",
                style = RowStyle.copy(
                    fontSize = 10.sp,
                    color = if (notFound) HexanaSearchErrorColor else HexanaMuted,
                ),
                modifier = Modifier.padding(top = 3.dp, start = 2.dp),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Right side — header + global hex + bytecode/lines tabs
// -----------------------------------------------------------------------------

@Composable
private fun JitSymDetail(
    project: Project,
    sym: JitSym,
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    hexFocusRequester: FocusRequester,
    selectedBci: Int?,
    onSelectBci: (Int) -> Unit,
    addressNav: AddressNav?,
) {
    val instructions = remember(sym) {
        runCatching { JavaBytecodeDecoder.decode(sym.bytecode) }.getOrDefault(emptyList())
    }

    // Load the Capstone-WASM disassembler once (process-wide singleton),
    // then decode this sym's native bytes and group decoded instructions
    // by BCI via the address-location map.
    val disasmState = rememberDisassembler(state)
    val arch = remember { hostArch() }
    // Decode the whole method's native bytes once. The Machine-code tab shows
    // this flat list as-is; the Combined tab buckets it by BCI through the
    // address-location map — so the disassembler runs exactly once per sym.
    val nativeRowsFlat = remember(sym, disasmState, arch) {
        val ready = disasmState as? DisasmState.Ready ?: return@remember emptyList<NativeRow>()
        runCatching {
            disassembleFlat(sym, ready.bundle.disassembler, arch, ready.bundle.formatterFor(arch))
        }.getOrElse {
            emptyList()
        }
    }
    val nativeRowsByBci = remember(sym, nativeRowsFlat) {
        if (nativeRowsFlat.isEmpty()) emptyMap() else bucketByBci(sym, nativeRowsFlat)
    }

    VerticalSplitPanel(
        state = state,
        northPanel = {
            Box(modifier = Modifier.fillMaxSize()) {
                HexViewer(
                    binaryProvider = binaryProvider,
                    state = state,
                    focusRequester = hexFocusRequester,
                )
            }
        },
        southPanel = {
            JitDetailTabs(
                project = project,
                sym = sym,
                instructions = instructions,
                nativeRowsByBci = nativeRowsByBci,
                nativeRowsFlat = nativeRowsFlat,
                disasmState = disasmState,
                selectedBci = selectedBci,
                onSelectBci = onSelectBci,
                addressNav = addressNav,
            )
        },
    )
}

// -----------------------------------------------------------------------------
// Info view — dump metadata: system properties + loaded-library table
// -----------------------------------------------------------------------------

/**
 * Right-pane view shown when the user selects the "Dump info" tree entry.
 *
 * Renders two sections in a single scrollable [LazyColumn]:
 *
 *  1. **System info** — key/value pairs from [JitFile.systemInfo]. Absent
 *     when the `system_info` agent flag was off (list is empty).
 *  2. **Loaded libraries** — address-range table from [JitFile.loadedLibraries].
 *     Absent when the `dump_libraries` agent flag was off or dump is pre-v4.
 *
 * A compact summary line at the top shows the file-format version and the
 * total number of compiled symbols so users can orient themselves at a glance
 * without needing to count tree rows.
 */
@Composable
private fun JitInfoView(jit: JitFile) {
    val systemInfo = remember(jit) { jit.systemInfo }
    val libraries = remember(jit) { jit.loadedLibraries }
    val symCount = remember(jit) { jit.symbols.size }
    val totalNativeBytes = remember(jit) { jit.symbols.sumOf { it.codeSize.toLong() } }

    val sectionHeaderColor = if (JewelTheme.isDark) Color(0xFF9DA0A8) else Color(0xFF6C6F75)
    // Alternate-stripe tints — same pattern as JitBytecodeTab's bciIndex striping.
    val stripeDark = Color(0x10FFFFFF)
    val stripeLight = Color(0x08000000)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // ── Summary ──────────────────────────────────────────────────────────
        item(key = "__summary__") {
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    "Format version: v${jit.header.version.toInt()}",
                    style = RowStyle.copy(color = HexanaMuted),
                )
                Text(
                    "Compiled methods: $symCount   " +
                        "Native code total: ${formatByteSize(totalNativeBytes)}",
                    style = RowStyle.copy(color = HexanaMuted),
                )
            }
        }

        // ── System info ──────────────────────────────────────────────────────
        item(key = "__sysinfo_header__") {
            InfoSectionHeader(label = "System info", color = sectionHeaderColor)
        }
        if (systemInfo.isEmpty()) {
            item(key = "__sysinfo_empty__") {
                Text(
                    "(no system info captured — enable the system_info agent flag to record)",
                    style = RowStyle.copy(color = HexanaMuted),
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        } else {
            for ((idx, pair) in systemInfo.withIndex()) {
                val (key, value) = pair
                item(key = "si-$idx") {
                    val stripe = (idx and 1) == 1
                    val bg = if (stripe) {
                        if (JewelTheme.isDark) stripeDark else stripeLight
                    } else Color.Transparent
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            key,
                            style = RowStyle,
                            modifier = Modifier.weight(0.4f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            value,
                            style = RowStyle.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                }
            }
        }

        // ── Loaded libraries ─────────────────────────────────────────────────
        item(key = "__libs_header__") {
            Spacer(modifier = Modifier.padding(top = 8.dp))
            InfoSectionHeader(label = "Loaded libraries", color = sectionHeaderColor)
        }
        if (libraries.isEmpty()) {
            item(key = "__libs_empty__") {
                Text(
                    "(no library map captured — enable the dump_libraries agent flag to record)",
                    style = RowStyle.copy(color = HexanaMuted),
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        } else {
            // Column-header row for the library table.
            item(key = "__libs_col_header__") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        "Path",
                        style = RowStyle.copy(color = HexanaMuted, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Base addr",
                        style = RowStyle.copy(color = HexanaMuted, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.width(LibAddrColumnWidth),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Size",
                        style = RowStyle.copy(color = HexanaMuted, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.width(LibSizeColumnWidth),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "End addr",
                        style = RowStyle.copy(color = HexanaMuted, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.width(LibAddrColumnWidth),
                    )
                }
            }
            for ((idx, lib) in libraries.withIndex()) {
                item(key = "lib-$idx") {
                    val stripe = (idx and 1) == 1
                    val bg = if (stripe) {
                        if (JewelTheme.isDark) stripeDark else stripeLight
                    } else Color.Transparent
                    val endAddr = lib.baseAddr + lib.size
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Path column — weight(1f) so it takes all remaining
                        // width after the fixed address/size columns. Overflow
                        // is ellipsized at the start (most-specific part of the
                        // path is the filename, which sits at the end).
                        Text(
                            lib.path.ifEmpty { "(anonymous)" },
                            style = RowStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            formatNativeAddress(lib.baseAddr),
                            style = RowStyle.copy(color = HexanaMcHexColor),
                            modifier = Modifier.width(LibAddrColumnWidth),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            formatByteSize(lib.size.toLong()),
                            style = RowStyle.copy(color = HexanaMuted),
                            modifier = Modifier.width(LibSizeColumnWidth),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            formatNativeAddress(endAddr),
                            style = RowStyle.copy(color = HexanaMcHexColor),
                            modifier = Modifier.width(LibAddrColumnWidth),
                        )
                    }
                }
            }
        }
    }
}

/** Section-header row inside [JitInfoView] — a short divider-like label. */
@Composable
private fun InfoSectionHeader(label: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = RowStyle.copy(color = color, fontWeight = FontWeight.SemiBold))
        Spacer(modifier = Modifier.width(6.dp))
        Divider(
            orientation = Orientation.Horizontal,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Human-readable size: always emits a `0x`-prefixed hex value matching the
 * `formatNativeAddress` aesthetic, plus a parenthesised decimal shorthand
 * for quick mental parsing.
 *
 *   1_048_576  →  "0x0000000000100000 (1.0 MB)"
 *   65_536     →  "0x0000000000010000 (64 KB)"
 *   1_234      →  "0x00000000000004D2 (1234 B)"
 */
private fun formatByteSize(bytes: Long): String {
    val hex = "0x" + bytes.toULong().toString(16).padStart(16, '0').uppercase()
    val human = when {
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
    return "$hex ($human)"
}

// -----------------------------------------------------------------------------
// Detail tabs — Combined / Bytecode / Machine code over the selected method
// -----------------------------------------------------------------------------

/** The three tabs shown in the bottom-right pane for a selected compiled method. */
private enum class JitDetailTab(val label: String) {
    Combined("Combined"),
    Bytecode("Bytecode"),
    MachineCode("Machine code"),
}

/**
 * Tabbed container for the south (bottom-right) pane. Tab #1 is the existing
 * per-BCI [JitBytecodeTab] (bytecode paired with its native instructions); the
 * two added tabs render each side on its own — [JitFlatBytecodeTab] for the
 * decoded JVM bytecode and [JitMachineCodeTab] for the whole native code block
 * disassembled flat.
 *
 * Tab selection is held in a plain [remember] (not keyed on [sym]) so it
 * persists as the user clicks through methods in the tree — switching method
 * keeps you on the tab you were reading.
 */
/**
 * One-line header above the tab strip identifying the compiled method the tabs show:
 * `class::method(sig)   vN   @0x…   N B`. A muted "no bytecode" tag flags native /
 * VM-synthetic methods (machine code present, Bytecode tab empty) so it's clear at a
 * glance why the bytecode side is blank.
 */
@Composable
private fun JitSymHeader(sym: JitSym) {
    val s = sym.sym()
    val label = if (s != null) {
        "${javaFqn(s.classSignature)}::${s.methodName}${s.methodSignature}"
    } else {
        "(unresolved symbol)"
    }
    val noBytecode = sym.bytecode.isEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = RowStyle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        if (noBytecode) {
            Text("no bytecode", style = RowStyle.copy(fontSize = 10.sp, color = HexanaMuted))
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text("v${sym.version.toInt()}", style = RowStyle.copy(color = HexanaMuted))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            "@0x${sym.address.toString(16)}",
            style = RowStyle.copy(fontSize = 11.sp, color = HexanaMcHexColor),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text("${sym.codeSize} B", style = RowStyle.copy(color = HexanaMuted))
    }
}

@Composable
private fun JitDetailTabs(
    project: Project,
    sym: JitSym,
    instructions: List<JavaBytecodeInstruction>,
    nativeRowsByBci: Map<Int, List<NativeRow>>,
    nativeRowsFlat: List<NativeRow>,
    disasmState: DisasmState,
    selectedBci: Int?,
    onSelectBci: (Int) -> Unit,
    addressNav: AddressNav?,
) {
    var tab by remember { mutableStateOf(JitDetailTab.Combined) }

    // Row index in this sym's flat native rows for an address search — null when the
    // searched address isn't inside this method, or the rows haven't decoded yet.
    val mcTargetIndex = remember(addressNav, nativeRowsFlat, sym) {
        val nav = addressNav ?: return@remember null
        if (nav.address < sym.address || nav.address >= sym.address + sym.codeSize) return@remember null
        indexOfAddress(nativeRowsFlat, nav.address)
    }
    // A fresh address search that lands in this sym jumps to the Machine-code tab.
    LaunchedEffect(addressNav?.nonce) {
        val nav = addressNav ?: return@LaunchedEffect
        if (nav.address >= sym.address && nav.address < sym.address + sym.codeSize) {
            tab = JitDetailTab.MachineCode
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        JitSymHeader(sym)
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        JitTabStrip(selected = tab, onSelect = { tab = it })
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                JitDetailTab.Combined -> JitBytecodeTab(
                    project = project,
                    sym = sym,
                    instructions = instructions,
                    nativeRowsByBci = nativeRowsByBci,
                    nativeRowsFlat = nativeRowsFlat,
                    disasmState = disasmState,
                    selectedBci = selectedBci,
                    onSelectBci = onSelectBci,
                )
                JitDetailTab.Bytecode -> JitFlatBytecodeTab(sym = sym, instructions = instructions)
                JitDetailTab.MachineCode -> JitMachineCodeTab(
                    sym = sym,
                    nativeRows = nativeRowsFlat,
                    disasmState = disasmState,
                    scrollToIndex = mcTargetIndex,
                    scrollNonce = addressNav?.nonce,
                )
            }
        }
    }
}

@Composable
private fun JitTabStrip(selected: JitDetailTab, onSelect: (JitDetailTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in JitDetailTab.values()) {
            JitTab(label = t.label, selected = t == selected, onClick = { onSelect(t) })
            Spacer(modifier = Modifier.width(2.dp))
        }
    }
}

@Composable
private fun JitTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = when {
        selected && JewelTheme.isDark -> Color(0xFF2E436E)
        selected -> Color(0xFFD4E2FC)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            style = RowStyle.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/**
 * Flat JVM-bytecode listing for the selected method: one row per decoded
 * instruction, `bci  opcode operands`. No native code — this is the pure
 * bytecode side of the Combined tab. Rows are multi-selectable; the copy menu
 * offers the full line, the bytecode text, or the raw bytecode hex.
 */
@Composable
private fun JitFlatBytecodeTab(sym: JitSym, instructions: List<JavaBytecodeInstruction>) {
    if (sym.bytecode.isEmpty()) {
        EmptyTabMessage("(no JVM bytecode captured — pre-v2 dump or native method)")
        return
    }
    if (instructions.isEmpty()) {
        EmptyTabMessage("(bytecode decoder rejected ${sym.bytecode.size} bytes — see idea.log)")
        return
    }
    val rows = remember(sym, instructions) {
        val code = sym.bytecode
        instructions.map { insn ->
            val start = insn.bytecodeOffset.coerceIn(0, code.size)
            val end = (insn.bytecodeOffset + insn.size).coerceIn(start, code.size)
            BytecodeRowData(
                bci = insn.bytecodeOffset,
                opcodeText = formatInstruction(insn),
                hex = formatHexBytes(code.copyOfRange(start, end), Int.MAX_VALUE),
            )
        }
    }
    var gotoIndex by remember(rows) { mutableStateOf<Int?>(null) }
    var gotoNonce by remember(rows) { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        GotoBar(label = "goto bci", placeholder = "e.g. 42") { input ->
            val bci = input.trim().toIntOrNull() ?: return@GotoBar false
            val idx = rows.indexOfFirst { it.bci == bci }
            if (idx < 0) {
                false
            } else {
                gotoIndex = idx
                gotoNonce += 1
                true
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SelectableCopyableList(
                items = rows,
                rowKey = { _, r -> "fb-${r.bci}" },
                copyVariants = { sel ->
                    listOf(
                        "Copy" to sel.joinToString("\n") {
                            "${it.bci.toString().padStart(5, ' ')}  ${it.hex}  ${it.opcodeText}"
                        },
                        "Copy bytecode" to sel.joinToString("\n") { it.opcodeText },
                        "Copy hex" to sel.joinToString(" ") { it.hex },
                    )
                },
                scrollToIndex = gotoIndex,
                scrollNonce = gotoNonce,
            ) { r ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        r.bci.toString().padStart(5, ' '),
                        style = RowStyle.copy(color = HexanaMuted),
                        modifier = Modifier.width(BciColumnWidth),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(r.opcodeText, style = RowStyle)
                }
            }
        }
    }
}

/**
 * Flat native-code listing for the selected method: every decoded instruction
 * in the method's code block, start to end — `addr  hex  asm`. Unlike the
 * Combined tab this includes prologue / epilogue / stub code that maps to no
 * JVM bytecode index. Rows are multi-selectable; the copy menu offers the full
 * line, the machine-code hex, or the disassembled instructions.
 */
@Composable
private fun JitMachineCodeTab(
    sym: JitSym,
    nativeRows: List<NativeRow>,
    disasmState: DisasmState,
    scrollToIndex: Int? = null,
    scrollNonce: Int? = null,
) {
    when (disasmState) {
        DisasmState.Loading -> {
            EmptyTabMessage("loading disassembler…")
            return
        }
        is DisasmState.Failed -> {
            EmptyTabMessage("disassembler failed: ${disasmState.reason}")
            return
        }
        is DisasmState.Ready -> Unit
    }
    if (sym.codeSize == 0uL) {
        EmptyTabMessage("(no native code captured for this record)")
        return
    }
    if (nativeRows.isEmpty()) {
        EmptyTabMessage("(disassembler produced no instructions for ${sym.codeSize} bytes)")
        return
    }
    // Merge two scroll sources into one (index, token): the external address search
    // (scrollToIndex/scrollNonce from the tree, via JitDetailTabs) and this tab's own
    // "goto addr" field. Both bump the same token, so the most recent one wins.
    var scrollIdx by remember(nativeRows) { mutableStateOf<Int?>(null) }
    var scrollTok by remember(nativeRows) { mutableStateOf(0) }
    LaunchedEffect(scrollNonce) {
        if (scrollNonce != null && scrollToIndex != null) {
            scrollIdx = scrollToIndex
            scrollTok += 1
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        GotoBar(label = "goto addr", placeholder = "0x…") { input ->
            val a = parseCodeAddress(input)
            if (a == null || a < sym.address || a >= sym.address + sym.codeSize) {
                false
            } else {
                val idx = indexOfAddress(nativeRows, a)
                if (idx == null) {
                    false
                } else {
                    scrollIdx = idx
                    scrollTok += 1
                    true
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SelectableCopyableList(
                items = nativeRows,
                rowKey = { idx, row -> "mc-${row.address}-$idx" },
                copyVariants = { sel ->
                    // Method-relative offset width, padded so the asm column lines up.
                    val offWidth = sel.maxOfOrNull { it.nativeOffset.toString(16).length } ?: 1
                    listOf(
                        "Copy" to sel.joinToString("\n") {
                            "${formatNativeAddress(it.address)}  ${it.hex}  ${it.asm}"
                        },
                        "Copy offset + instructions" to sel.joinToString("\n") {
                            "+0x${it.nativeOffset.toString(16).padStart(offWidth, '0')}  ${it.asm}"
                        },
                        "Copy hex" to sel.joinToString(" ") { it.hex },
                        "Copy instructions" to sel.joinToString("\n") { it.asm },
                    )
                },
                scrollToIndex = scrollIdx,
                scrollNonce = scrollTok,
            ) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        formatNativeAddress(row.address),
                        style = RowStyle.copy(color = HexanaMuted),
                        modifier = Modifier.width(NativeAddressColumnWidth),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        row.hex,
                        style = RowStyle.copy(color = HexanaMcHexColor),
                        modifier = Modifier.width(HexColumnWidth),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(row.asm, style = RowStyle, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Centered muted message used by the detail tabs for empty / loading / error states. */
@Composable
private fun EmptyTabMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(text, style = RowStyle.copy(color = HexanaMuted))
    }
}

/**
 * Compact "go to" field shown at the top of each detail tab. [onSubmit] parses the
 * entered text and navigates, returning false when nothing matched (rendered as a
 * muted "not found" note and a red outline). Enter submits.
 *
 * The coordinate is per tab: bytecode offset (BCI) for the Bytecode / Combined tabs,
 * machine address for the Machine-code tab — each tab supplies its own [onSubmit].
 */
@Composable
private fun GotoBar(label: String, placeholder: String, onSubmit: (String) -> Boolean) {
    val outline = if (JewelTheme.isDark) Color(0xFF393B3D) else Color(0xFFC9CCD6)
    val textColor = if (JewelTheme.isDark) Color(0xFFBCBEC4) else Color(0xFF1F1F1F)
    var text by remember { mutableStateOf("") }
    var notFound by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = RowStyle.copy(color = HexanaMuted))
        Spacer(modifier = Modifier.width(6.dp))
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                notFound = false
            },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = textColor),
            cursorBrush = SolidColor(textColor),
            modifier = Modifier
                .width(180.dp)
                .border(1.dp, if (notFound) HexanaSearchErrorColor else outline, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                        if (text.isNotBlank()) notFound = !onSubmit(text)
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(placeholder, style = RowStyle.copy(color = HexanaMuted))
                }
                inner()
            },
        )
        if (notFound) {
            Spacer(modifier = Modifier.width(6.dp))
            Text("not found", style = RowStyle.copy(fontSize = 10.sp, color = HexanaSearchErrorColor))
        }
    }
}

/** Parse a "goto addr" field value (with or without a `0x` prefix) into an address. */
private fun parseCodeAddress(s: String): ULong? {
    val t = s.trim()
    val hex = if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2) else t
    if (hex.isEmpty() || hex.length > 16) return null
    return hex.toULongOrNull(16)
}

/** Row model for the flat Bytecode tab — precomputed display text + raw-byte hex for copy. */
private data class BytecodeRowData(val bci: Int, val opcodeText: String, val hex: String)

/**
 * A scrollable list of monospace rows with multi-row selection and clipboard
 * copy — shared by the Bytecode and Machine-code tabs.
 *
 * Selection: a primary click selects a single row; Shift+click extends a range
 * from the anchor; Cmd/Ctrl+click toggles an individual row. A right-click on a
 * row outside the current selection targets just that row.
 *
 * Copy: the right-click menu lists the per-tab [copyVariants] (the first entry
 * is treated as "full" and is also what Cmd/Ctrl+C copies); Cmd/Ctrl+A selects
 * everything. Text is placed on the IDE clipboard via [CopyPasteManager], the
 * same path the table + hex views use.
 *
 * [copyVariants] receives the selected items in row order and returns
 * `label -> text` pairs; the text is built lazily when the menu opens or the
 * copy shortcut fires.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun <T> SelectableCopyableList(
    items: List<T>,
    rowKey: (index: Int, item: T) -> Any,
    copyVariants: (selected: List<T>) -> List<Pair<String, String>>,
    scrollToIndex: Int? = null,
    scrollNonce: Int? = null,
    rowContent: @Composable (item: T) -> Unit,
) {
    var selected by remember(items) { mutableStateOf<Set<Int>>(emptySet()) }
    var anchor by remember(items) { mutableStateOf<Int?>(null) }
    var pressed by remember(items) { mutableStateOf(false) }
    var pointerY by remember(items) { mutableStateOf(Float.NaN) }
    var areaHeight by remember(items) { mutableStateOf(0f) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    fun orderedSelection(indices: Set<Int>): List<T> =
        indices.sorted().mapNotNull { items.getOrNull(it) }

    fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    // Map a pointer Y (list coordinate space) to a row index, mirroring
    // HexViewer.resolveSelectionPosition: clamp above the first / below the last
    // visible item so a drag past either edge still extends to the end.
    fun rowAt(y: Float): Int? {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || items.isEmpty()) return null
        val first = visible.first()
        val last = visible.last()
        val row = when {
            y < first.offset -> first.index - 1
            y > last.offset + last.size -> last.index + 1
            else -> visible.indexOfLast { it.offset <= y } + listState.firstVisibleItemIndex
        }
        return row.coerceIn(0, items.size - 1)
    }

    fun selectRangeTo(target: Int) {
        val a = anchor ?: target
        selected = (minOf(a, target)..maxOf(a, target)).toSet()
    }

    // Edge auto-scroll while dragging a selection past the top/bottom — same
    // 40dp threshold + proportional speed as the hex view, so a drag can extend
    // the selection across the whole method, not just the visible rows.
    LaunchedEffect(pressed, pointerY, areaHeight) {
        if (!pressed || pointerY.isNaN() || areaHeight <= 0f) return@LaunchedEffect
        while (pressed && !pointerY.isNaN()) {
            val threshold = 40.dp.value
            val speed = 50f
            val delta = when {
                pointerY < threshold -> -speed * ((threshold - maxOf(0f, pointerY)) / threshold)
                pointerY > areaHeight - threshold ->
                    speed * (maxOf(0f, pointerY - (areaHeight - threshold)) / threshold)
                else -> 0f
            }
            if (delta == 0f) break
            val consumed = listState.scrollBy(delta)
            rowAt(pointerY)?.let { selectRangeTo(it) }
            if (consumed == 0f) break
            delay(16)
        }
    }

    // External scroll request (machine-address search): scroll to and select the
    // target row. Keyed on the nonce AND items, so it also fires once the
    // disassembler's rows arrive if the search happened before decode finished.
    LaunchedEffect(scrollNonce, items) {
        val idx = scrollToIndex ?: return@LaunchedEffect
        if (scrollNonce == null || idx !in items.indices) return@LaunchedEffect
        selected = setOf(idx)
        anchor = idx
        runCatching { listState.scrollToItem(idx) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { areaHeight = it.size.height.toFloat() }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val meta = event.keyIsCtrlPressed || event.keyIsMetaPressed
                when {
                    meta && event.key == Key.C -> {
                        val sel = orderedSelection(selected)
                        if (sel.isNotEmpty()) {
                            copyVariants(sel).firstOrNull()?.let { copyToClipboard(it.second) }
                        }
                        true
                    }
                    meta && event.key == Key.A -> {
                        selected = items.indices.toSet()
                        anchor = 0
                        true
                    }
                    else -> false
                }
            }
            // Press-and-drag selects a contiguous range; a plain press selects
            // one row. Shift+click extends from the anchor, Cmd/Ctrl+click
            // toggles. Right-click targets the row under the cursor (if it's
            // outside the current selection) and lets the menu open.
            .pointerInput(items) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    focusRequester.requestFocus()
                    val startRow = rowAt(down.position.y) ?: return@awaitEachGesture
                    val mods = currentEvent.keyboardModifiers
                    if (currentEvent.buttons.isSecondaryPressed) {
                        if (startRow !in selected) {
                            selected = setOf(startRow)
                            anchor = startRow
                        }
                        return@awaitEachGesture
                    }
                    when {
                        mods.isShiftPressed && anchor != null -> selectRangeTo(startRow)
                        mods.isCtrlPressed || mods.isMetaPressed -> {
                            selected = if (startRow in selected) selected - startRow else selected + startRow
                            anchor = startRow
                        }
                        else -> {
                            selected = setOf(startRow)
                            anchor = startRow
                        }
                    }
                    pressed = true
                    pointerY = down.position.y
                    drag(down.id) { change ->
                        pointerY = change.position.y
                        rowAt(change.position.y)?.let { selectRangeTo(it) }
                    }
                    pressed = false
                    pointerY = Float.NaN
                }
            },
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            for ((index, item) in items.withIndex()) {
                item(key = rowKey(index, item)) {
                    val isSelected = index in selected
                    val stripe = (index and 1) == 1
                    val bg = when {
                        isSelected && JewelTheme.isDark -> Color(0xFF2E436E)
                        isSelected -> Color(0xFFD4E2FC)
                        stripe && JewelTheme.isDark -> Color(0x10FFFFFF)
                        stripe -> Color(0x08000000)
                        else -> Color.Transparent
                    }
                    ContextMenuArea(items = {
                        val sel = orderedSelection(if (index in selected) selected else setOf(index))
                        copyVariants(sel).map { (label, text) ->
                            ContextMenuItem(label) { copyToClipboard(text) }
                        }
                    }) {
                        Box(modifier = Modifier.fillMaxWidth().background(bg)) {
                            rowContent(item)
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Bytecode tab — one row per native instruction, four columns:
//   bci  | opcode operands     | hex bytes      | disassembled asm
// The bci + opcode columns are populated only on the *first* native row of
// each JVM bytecode index; continuation rows leave them blank so the
// bytecode-to-native fan-out reads as one logical entry.
// -----------------------------------------------------------------------------

@Composable
private fun JitBytecodeTab(
    project: Project,
    sym: JitSym,
    instructions: List<JavaBytecodeInstruction>,
    nativeRowsByBci: Map<Int, List<NativeRow>>,
    nativeRowsFlat: List<NativeRow>,
    disasmState: DisasmState,
    selectedBci: Int?,
    onSelectBci: (Int) -> Unit,
) {
    if (sym.bytecode.isEmpty()) {
        Text(
            "(no JVM bytecode captured — pre-v2 dump or native method)",
            style = RowStyle.copy(color = HexanaMuted),
        )
        return
    }
    if (instructions.isEmpty()) {
        Text(
            "(bytecode decoder rejected ${sym.bytecode.size} bytes — see idea.log)",
            style = RowStyle.copy(color = HexanaMuted),
        )
        return
    }

    // Per-PC inline-stack lookup: map each `InlinedAt.address` (an absolute
    // runtime PC) to the matching list of `Inlined` frames. Used to decorate
    // a native row in-place — one clickable line per inline frame, right
    // side of the row, no fold toggle.
    val inlinesByPc = remember(sym) { buildInlinesByPc(sym) }

    // Native instructions the per-BCI loop below would otherwise drop, so the
    // Combined tab covers the SAME address range as the Machine-code tab:
    //  • prologue / setup — bytes before the first BCI-mapped instruction
    //    (frame build, sync entry); these have no address-location entry.
    //  • trailing — instructions whose address-location entry points at a BCI
    //    not present in the decoded bytecode (rare; handler/stub regions).
    // `prologueRows` render above the bytecode, `trailingRows` below it; both
    // as native-only rows (blank bci/opcode columns).
    val (prologueRows, trailingRows) = remember(instructions, nativeRowsByBci, nativeRowsFlat) {
        val shownBcis = instructions.mapTo(HashSet()) { it.bytecodeOffset }
        val shownAddrs = HashSet<ULong>()
        for ((bci, rows) in nativeRowsByBci) {
            if (bci in shownBcis) rows.forEach { shownAddrs += it.address }
        }
        val orphans = nativeRowsFlat.filter { it.address !in shownAddrs }
        val firstShown = shownAddrs.minOrNull()
        val leading = orphans.filter { firstShown == null || it.address < firstShown }
        val trailing = orphans.filter { firstShown != null && it.address >= firstShown }
        leading to trailing
    }

    // Recognise classes generated by the chicory-compiler-{maven,gradle}-plugin
    // — `<moduleInterfaceFqn>ModuleMachineFunGroup_<n>::func_<idx>`. When this
    // sym matches a registered Chicory AOT config, we show a WASM-source badge
    // in the header that opens the originating .wasm at function `funcIdx`.
    val chicoryMatch: ChicoryAotMatch? = remember(sym) {
        val s = sym.sym()
        if (s == null) {
            CHICORY_LOG.warn("[chicory-debug] JitBytecodeTab: sym.sym() is null, skipping match")
            null
        } else {
            val classFqn = javaFqn(s.classSignature)
            CHICORY_LOG.warn("[chicory-debug] JitBytecodeTab: querying ChicoryAotRegistry classFqn=$classFqn methodName=${s.methodName}")
            val result = ChicoryAotRegistry.getInstance(project).findMatch(classFqn, s.methodName)
            CHICORY_LOG.warn("[chicory-debug] JitBytecodeTab: result=$result")
            result
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // First LazyColumn item index for a BCI — mirrors the emission order below
    // (prologue header + rows, then per-instruction fan-out) so "goto bci" can scroll.
    fun firstItemIndexForBci(targetBci: Int): Int? {
        var index = 0
        if (prologueRows.isNotEmpty()) index += 1 + prologueRows.size
        for (insn in instructions) {
            if (insn.bytecodeOffset == targetBci) return index
            val rows = nativeRowsByBci[insn.bytecodeOffset].orEmpty()
            index += if (rows.isEmpty()) 1 else rows.size
        }
        return null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (chicoryMatch != null) {
            ChicoryWasmBadge(project, chicoryMatch)
        }

        GotoBar(label = "goto bci", placeholder = "e.g. 42") { input ->
            val bci = input.trim().toIntOrNull() ?: return@GotoBar false
            val idx = firstItemIndexForBci(bci) ?: return@GotoBar false
            // Highlight the BCI (drives the per-BCI hex highlight too); set, don't toggle.
            if (selectedBci != bci) onSelectBci(bci)
            scope.launch { runCatching { listState.scrollToItem(idx) } }
            true
        }

    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (prologueRows.isNotEmpty()) {
            item(key = "__prologue_header__") { OrphanSectionLabel("prologue / setup") }
            for ((i, row) in prologueRows.withIndex()) {
                item(key = "pro-${row.address}-$i") {
                    OrphanNativeRow(
                        project = project,
                        row = row,
                        inlineStack = inlinesByPc[row.address].orEmpty(),
                        stripe = (i and 1) == 1,
                    )
                }
            }
        }
        // bciIndex flips the row background between adjacent BCIs so a
        // multi-row native fan-out reads as one cohesive block — every row
        // of the same BCI shares the same tint, every transition between
        // BCIs is a visible color change.
        var bciIndex = 0
        for (insn in instructions) {
            val bci = insn.bytecodeOffset
            val nativeRows = nativeRowsByBci[bci].orEmpty()
            val opcodeText = formatInstruction(insn)
            val selected = selectedBci == bci
            val stripe = (bciIndex and 1) == 1
            bciIndex++

            if (nativeRows.isEmpty()) {
                // BCI with no JIT-emitted bytes (or disassembler not ready).
                // For the Ready case this is a real "folded by JIT" — call
                // it out so the empty asm column doesn't read as a renderer
                // bug. Loading / Failed keep their existing state text.
                val (hexCol, asmCol) = when (disasmState) {
                    DisasmState.Loading -> "…" to "loading disassembler…"
                    is DisasmState.Failed -> "" to "disassembler failed: ${disasmState.reason}"
                    is DisasmState.Ready -> "" to "(folded — no native code emitted)"
                }
                item(key = "bc-$bci-only") {
                    PairedRow(
                        project = project,
                        bci = bci,
                        opcodeText = opcodeText,
                        nativeAddress = null,
                        hexText = hexCol,
                        asmText = asmCol,
                        inlineStack = emptyList(),
                        showBcOpcode = true,
                        selected = selected,
                        stripe = stripe,
                        asmMuted = disasmState is DisasmState.Ready,
                        onRowClick = { onSelectBci(bci) },
                    )
                }
            } else {
                for ((nIdx, row) in nativeRows.withIndex()) {
                    val isFirst = nIdx == 0
                    val stack = inlinesByPc[row.address].orEmpty()
                    item(key = "bc-$bci-$nIdx") {
                        PairedRow(
                            project = project,
                            bci = bci,
                            opcodeText = opcodeText,
                            nativeAddress = row.address,
                            hexText = row.hex,
                            asmText = row.asm,
                            inlineStack = stack,
                            // bci + opcode column populated only on the
                            // first native row of a BCI run — keeps the
                            // bytecode-to-native fan-out visually paired.
                            showBcOpcode = isFirst,
                            selected = selected,
                            stripe = stripe,
                            asmMuted = false,
                            onRowClick = { onSelectBci(bci) },
                        )
                    }
                }
            }
        }
        if (trailingRows.isNotEmpty()) {
            item(key = "__trailing_header__") { OrphanSectionLabel("unmapped native code") }
            for ((i, row) in trailingRows.withIndex()) {
                item(key = "orph-${row.address}-$i") {
                    OrphanNativeRow(
                        project = project,
                        row = row,
                        inlineStack = inlinesByPc[row.address].orEmpty(),
                        stripe = (i and 1) == 1,
                    )
                }
            }
        }
    }
    }
}

/**
 * A native row in the Combined tab with no owning JVM bytecode index — method
 * prologue or an unmapped handler / stub region. Renders like a continuation
 * [PairedRow] (blank bci/opcode columns) and isn't selectable, since there's
 * no BCI to drive the per-BCI hex highlight.
 */
@Composable
private fun OrphanNativeRow(
    project: Project,
    row: NativeRow,
    inlineStack: List<Inlined>,
    stripe: Boolean,
) {
    PairedRow(
        project = project,
        bci = -1,
        opcodeText = "",
        nativeAddress = row.address,
        hexText = row.hex,
        asmText = row.asm,
        inlineStack = inlineStack,
        showBcOpcode = false,
        selected = false,
        stripe = stripe,
        asmMuted = false,
        onRowClick = {},
    )
}

/** Muted divider label introducing a prologue / unmapped native-code group. */
@Composable
private fun OrphanSectionLabel(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "· $text ·",
            style = RowStyle.copy(color = HexanaMuted, fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * Compact clickable "this method came from WASM" indicator. Rendered above
 * the LazyColumn body when [ChicoryAotRegistry.findMatch] recognises the
 * sym's class as one of the chicory-compiler-{maven,gradle}-plugin's
 * generated `ModuleMachineFunGroup_<n>::func_<idx>` shapes. Clicking opens
 * the originating `.wasm` in Hexana's WAT (virtualized) view scrolled to
 * function-section entry `funcIdx`.
 */
@Composable
private fun ChicoryWasmBadge(project: Project, match: ChicoryAotMatch) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clickable {
                    openWasmFunctionView(project, match.wasmFile, match.funcIdx)
                }
                .border(1.dp, HexanaLinkColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                "WASM → ${match.wasmFile.name} · func_${match.funcIdx}",
                style = RowStyle.copy(
                    color = HexanaLinkColor,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

/**
 * Open the originating `.wasm` in Hexana's editor and request that the WAT
 * (virtualized) panel scroll to function index [funcIdx]. Mirrors
 * [openBytecodeView] — if an editor is already mounted for the file we
 * call `requestWasmFunctionNav` directly so even no-op `openFile` triggers
 * the scroll; otherwise we stash the target on the VirtualFile's UserData
 * for the constructor to pick up.
 */
private fun openWasmFunctionView(project: Project, wasmFile: VirtualFile, funcIdx: Int) {
    val target = WasmFunctionNavTarget(funcIdx)
    val mgr = FileEditorManager.getInstance(project)
    val existing = mgr.getEditors(wasmFile).filterIsInstance<HexanaFileEditor>().firstOrNull()
    if (existing != null) {
        existing.requestWasmFunctionNav(target)
    } else {
        wasmFile.putUserData(WASM_FUNCTION_NAV_TARGET_KEY, target)
    }
    mgr.openFile(wasmFile, /* focusEditor = */ true)
    mgr.setSelectedEditor(wasmFile, HexanaFileEditorProvider.EDITOR_TYPE_ID)
}

@Composable
private fun PairedRow(
    project: Project,
    bci: Int,
    opcodeText: String,
    nativeAddress: ULong?,
    hexText: String,
    asmText: String,
    inlineStack: List<Inlined>,
    showBcOpcode: Boolean,
    selected: Boolean,
    stripe: Boolean,
    asmMuted: Boolean,
    onRowClick: () -> Unit,
) {
    val bg = when {
        selected && JewelTheme.isDark -> Color(0xFF2E436E)
        selected -> Color(0xFFD4E2FC)
        stripe && JewelTheme.isDark -> Color(0x10FFFFFF)
        stripe -> Color(0x08000000)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onRowClick)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        // Top-aligned so a tall inline-stack column doesn't drag the
        // bci/opcode/hex columns toward the row's vertical center.
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            if (showBcOpcode) bci.toString().padStart(5, ' ') else "",
            style = RowStyle.copy(color = HexanaMuted),
            modifier = Modifier.width(BciColumnWidth),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            if (showBcOpcode) opcodeText else "",
            style = RowStyle.copy(
                fontWeight = if (showBcOpcode) FontWeight.SemiBold else FontWeight.Normal,
            ),
            modifier = Modifier.width(OpcodeColumnWidth),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            // Absolute runtime memory address of the native instruction.
            // `null` on folded / loading / failed rows so the column stays
            // aligned with the rest of the table but doesn't print a
            // misleading 0x00…00.
            if (nativeAddress != null) formatNativeAddress(nativeAddress) else "",
            style = RowStyle.copy(color = HexanaMuted),
            modifier = Modifier.width(NativeAddressColumnWidth),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            hexText,
            style = RowStyle.copy(color = HexanaMcHexColor),
            modifier = Modifier.width(HexColumnWidth),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Inline-stack column. Empty placeholder for rows without an inline
        // stack so the asm column stays aligned across the table.
        Box(modifier = Modifier.width(InlineColumnWidth)) {
            if (inlineStack.isNotEmpty() && nativeAddress != null) {
                InlineStackColumn(project = project, stack = inlineStack)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            asmText,
            style = if (asmMuted) RowStyle.copy(color = HexanaMuted) else RowStyle,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Multi-line inlay on the right of a native row. Each line is a clickable
 * hyperlink to the corresponding bytecode location, rendered as
 * `<fqn>::<methodName><jvmDescriptor>:<bci>`. Frames are listed deepest-
 * inlinee first (index 0) so the column reads top-down like a stack trace
 * from the innermost call out.
 */
@Composable
private fun InlineStackColumn(project: Project, stack: List<Inlined>) {
    Column {
        for (frame in stack) {
            val frameSym = frame.sym
            if (frameSym == null) {
                Text(
                    "(unresolved frame)",
                    style = InlineLineStyle.copy(color = HexanaMuted),
                )
            } else {
                val label = "${javaFqn(frameSym.classSignature)}::${frameSym.methodName}" +
                    "${frameSym.methodSignature}:${frame.bci}"
                Text(
                    label,
                    style = InlineLineStyle.copy(color = HexanaLinkColor),
                    modifier = Modifier.clickable {
                        navigateToInline(project, frameSym, frame.bci)
                    },
                )
            }
        }
    }
}

/** Renders an absolute runtime address like `0x0000000114a36f40` (16 hex digits, zero-padded). */
private fun formatNativeAddress(address: ULong): String =
    "0x" + address.toString(16).padStart(16, '0')

private fun formatInstruction(insn: JavaBytecodeInstruction): String {
    val m = insn.opcode.mnemonic
    return when (val ops = insn.operands) {
        JavaBytecodeOperands.None -> m
        is JavaBytecodeOperands.Byte1 -> "$m ${ops.value}"
        // No constant pool in a .jit dump — leave the raw cp index. The
        // disassembly-view replacement will resolve through the runtime
        // classpath.
        is JavaBytecodeOperands.Short1 -> "$m #${ops.value}"
        is JavaBytecodeOperands.Wide1 -> "$m ${ops.value}"
        is JavaBytecodeOperands.IInc -> "$m ${ops.varIndex}, ${ops.delta}"
        is JavaBytecodeOperands.InvokeInterface -> "$m #${ops.cpIndex}, ${ops.count}"
        is JavaBytecodeOperands.InvokeDynamic -> "$m #${ops.cpIndex}"
        is JavaBytecodeOperands.MultiANewArray -> "$m #${ops.cpIndex}, ${ops.dimensions}"
        is JavaBytecodeOperands.TableSwitch ->
            "$m default=${ops.defaultOffset} ${ops.low}..${ops.high} -> ${ops.jumpOffsets}"
        is JavaBytecodeOperands.LookupSwitch ->
            "$m default=${ops.defaultOffset} pairs=${ops.pairs}"
        is JavaBytecodeOperands.Wide ->
            "$m 0x${ops.innerOpcode.toString(16).padStart(2, '0')} var=${ops.varIndex}" +
                (ops.constant?.let { ", const=$it" } ?: "")
        is JavaBytecodeOperands.Raw -> "$m <raw ${ops.bytes.size} bytes>"
    }
}

// -----------------------------------------------------------------------------
// BCI ↔ native-byte / inline-record projections
// -----------------------------------------------------------------------------

/**
 * Map each JVMTI-reported native PC ([InlinedAt.address]) to its list of
 * `Inlined` frames. Looked up by exact PC against each native row's
 * `address` field — every native instruction whose PC matches an inline
 * record gets a multi-line inlay on the row.
 */
private fun buildInlinesByPc(sym: JitSym): Map<ULong, List<Inlined>> {
    if (sym.inlineRecords.isEmpty()) return emptyMap()
    val out = HashMap<ULong, List<Inlined>>()
    for (record in sym.inlineRecords) {
        for (at in record.inlines) {
            out[at.address] = at.inlined
        }
    }
    return out
}

/**
 * Collect the JIT-native bytes that implement the given JVM bytecode
 * index, by scanning the address-location map for entries whose BCI
 * matches and taking each `[start_i, start_{i+1})` chunk.
 */
private fun nativeBytesForBci(sym: JitSym, bci: Int): ByteArray {
    val map = sym.addressLocationMap
    if (map.isEmpty()) return ByteArray(0)
    val limit = sym.codeSize.toLong()
    val code = sym.codeBlock
    val out = ArrayList<Byte>()
    for (i in map.indices) {
        val (start, location) = map[i]
        if (location.toInt() != bci) continue
        val end = (if (i + 1 < map.size) map[i + 1].first.toLong() else limit)
            .coerceAtMost(code.size.toLong())
        val s = start.toInt().coerceAtLeast(0)
        val e = end.toInt().coerceAtLeast(s)
        for (j in s until e) out.add(code[j])
    }
    return out.toByteArray()
}

/** Native byte ranges (file offsets) to highlight for the selected BCI. */
private fun bciHighlightRanges(sym: JitSym, bci: Int): List<HexHighlightRange> {
    val map = sym.addressLocationMap
    if (map.isEmpty()) return emptyList()
    val ranges = ArrayList<HexHighlightRange>()
    val limit = sym.codeSize.toLong()
    val base = sym.fileOffset.toLong()
    for (i in map.indices) {
        val (start, location) = map[i]
        if (location.toInt() != bci) continue
        val end = if (i + 1 < map.size) map[i + 1].first.toLong() else limit
        val size = (end - start.toLong()).toInt().coerceAtLeast(0)
        if (size > 0) {
            ranges += HexHighlightRange(
                offset = base + start.toLong(),
                size = size,
                color = BciHighlightColor,
            )
        }
    }
    return ranges
}

private fun formatHexBytes(bytes: ByteArray, maxBytes: Int): String {
    if (bytes.isEmpty()) return ""
    val show = minOf(bytes.size, maxBytes)
    val sb = StringBuilder(show * 3)
    for (i in 0 until show) {
        if (i > 0) sb.append(' ')
        sb.append((bytes[i].toInt() and 0xFF).toString(16).padStart(2, '0'))
    }
    if (bytes.size > maxBytes) sb.append(" …")
    return sb.toString()
}

// -----------------------------------------------------------------------------
// Disassembly — Capstone-WASM backend, decoded once per sym
// -----------------------------------------------------------------------------

/** One decoded native instruction in a JIT-compiled method, ready for display. */
private data class NativeRow(
    /** Absolute runtime memory address of the instruction (JIT code-cache address). */
    val address: ULong,
    /**
     * Byte offset of this instruction within the method's code block. Kept so
     * [bucketByBci] can attribute the flat-decoded instruction to a JVM
     * bytecode index via the address-location map.
     */
    val nativeOffset: Int,
    val hex: String,
    val asm: String,
)

/**
 * The loaded disassembler + a cache of per-arch formatters. Cached in a
 * process-wide singleton ([DisassemblerHolder]) so opening more than one
 * `.jit` editor doesn't re-pay the WASM-module load cost.
 */
private class JitDisasmBundle(
    val disassembler: CapstoneWasmDisassembler,
) {
    private val formatters = HashMap<Arch, AssemblyFormatter>()

    fun formatterFor(arch: Arch): AssemblyFormatter = formatters.getOrPut(arch) {
        disassembler.formatter(arch, defaultSyntaxFor(arch))
    }

    private fun defaultSyntaxFor(arch: Arch): AssemblySyntax = when (arch) {
        Arch.X86, Arch.X86_64 -> AssemblySyntax.INTEL
        Arch.ARM, Arch.ARM64 -> AssemblySyntax.ARM_GAS
        Arch.RISC_V_32, Arch.RISC_V_64 -> AssemblySyntax.RISC_V_GAS
    }
}

private object DisassemblerHolder {
    private val log = logger<DisassemblerHolder>()

    @Volatile private var bundle: JitDisasmBundle? = null
    @Volatile private var lastError: Throwable? = null
    private val lock = Any()

    val error: Throwable? get() = lastError

    suspend fun get(): JitDisasmBundle {
        bundle?.let { return it }
        // Loading the WASM blob is suspending — do it outside the lock,
        // then double-check under the lock to publish exactly once.
        val fresh = try {
            JitDisasmBundle(
                withContext(Dispatchers.IO) { CapstoneWasmDisassembler.loadFromClasspath() },
            )
        } catch (t: Throwable) {
            lastError = t
            log.warn("Hexana: failed to load Capstone-WASM disassembler from plugin classpath", t)
            throw t
        }
        synchronized(lock) {
            bundle?.let { return it }
            bundle = fresh
            return fresh
        }
    }
}

/** Either-or surface — UI can render the failure reason instead of spinning forever. */
private sealed interface DisasmState {
    data object Loading : DisasmState
    data class Ready(val bundle: JitDisasmBundle) : DisasmState
    data class Failed(val reason: String) : DisasmState
}

@Composable
private fun rememberDisassembler(state: HexViewStateHolder): DisasmState {
    @Suppress("UNUSED_PARAMETER") val unused = state
    var current by remember { mutableStateOf<DisasmState>(DisasmState.Loading) }
    LaunchedEffect(Unit) {
        current = try {
            DisasmState.Ready(DisassemblerHolder.get())
        } catch (t: Throwable) {
            DisasmState.Failed(
                buildString {
                    append(t::class.simpleName ?: "Throwable")
                    t.message?.let { append(": "); append(it) }
                },
            )
        }
    }
    return current
}

private fun hostArch(): Arch {
    val raw = System.getProperty("os.arch")?.lowercase(Locale.ROOT).orEmpty()
    return when (raw) {
        "aarch64", "arm64" -> Arch.ARM64
        "x86_64", "amd64" -> Arch.X86_64
        "arm" -> Arch.ARM
        "riscv64" -> Arch.RISC_V_64
        "riscv32" -> Arch.RISC_V_32
        // Desktop fallback — matches the dominant target for modern JITs.
        else -> Arch.X86_64
    }
}

/**
 * Disassemble [sym]'s native bytes once, start-to-end, into a flat list of
 * display rows in decode (address-ascending) order. This is the whole method:
 * prologue, body, epilogue, deopt pads and stubs all included. The Machine-code
 * tab renders this directly; the Combined tab funnels it through [bucketByBci].
 */
private fun disassembleFlat(
    sym: JitSym,
    disasm: CapstoneWasmDisassembler,
    arch: Arch,
    formatter: AssemblyFormatter,
): List<NativeRow> {
    if (sym.codeSize == 0uL) return emptyList()
    val buf = sym.codeBlock.toCommonByteBuffer()
    val decoded = with(disasm) { buf.disassemble(sym.address, arch) }.toList()
    if (decoded.isEmpty()) return emptyList()

    val code = sym.codeBlock
    val out = ArrayList<NativeRow>(decoded.size)
    for (insn in decoded) {
        val nativeOffset = insn.byteRange.first
        val instructionBytes = ByteArray(insn.size) { code[insn.byteRange.first + it] }
        val hex = formatHexBytes(instructionBytes, Int.MAX_VALUE)
        val asm = when (insn) {
            is DecodedInstruction.Valid -> formatter.format(insn)
            is DecodedInstruction.Unknown -> with(formatter) { buf.formatUnknown(insn) }
            else -> "" // sealed interface; future variants render empty
        }
        out.add(NativeRow(address = insn.address, nativeOffset = nativeOffset, hex = hex, asm = asm))
    }
    return out
}

/**
 * Bucket already-decoded [flat] rows by the JVM bytecode index whose native
 * byte range they fall into, using the address-location map.
 *
 * Instructions that don't land inside any mapped BCI range (function
 * prologue / epilogue, deoptimisation pads, vtable stubs) are dropped here —
 * those are visible in full on the Machine-code tab; the Combined tab only
 * shows what's directly attributable to a BCI.
 */
private fun bucketByBci(sym: JitSym, flat: List<NativeRow>): Map<Int, List<NativeRow>> {
    val map = sym.addressLocationMap
    if (map.isEmpty() || flat.isEmpty()) return emptyMap()
    val limit = sym.codeSize.toLong()
    // (start, end, bci) sorted by start; address-location entries are
    // already in start-ascending order per the JVMTI spec.
    val spans = ArrayList<IntArray>(map.size) // [start, end, bci]
    for (i in map.indices) {
        val (start, location) = map[i]
        val end = if (i + 1 < map.size) map[i + 1].first.toLong() else limit
        spans += intArrayOf(start.toInt(), end.toInt(), location.toInt())
    }

    fun bciAt(nativeOffset: Int): Int? {
        // Linear walk — `spans.size` is bounded by JVMTI line points, in
        // practice low hundreds even for fat methods. Promote to binary
        // search if a real-world dump shows this in a profile.
        for (span in spans) {
            if (nativeOffset >= span[0] && nativeOffset < span[1]) return span[2]
        }
        return null
    }

    val out = HashMap<Int, MutableList<NativeRow>>()
    for (row in flat) {
        val bci = bciAt(row.nativeOffset) ?: continue
        out.getOrPut(bci) { ArrayList() }.add(row)
    }
    return out
}

// -----------------------------------------------------------------------------
// Navigation for inline frame clicks — Hexana bytecode view first, PSI fallback
// -----------------------------------------------------------------------------

/**
 * Resolve the inlined frame's class and open the Hexana Bytecode view on
 * its `.class` file. Falls back to PSI source navigation when no
 * compiled class file is available on the project's classpath (e.g. a
 * pure-source class that hasn't been compiled yet).
 *
 * The PSI / stub-index lookup must NOT run on the EDT — `findClasses`
 * eventually calls `FileBasedIndexImpl.ensureUpToDate`, which the
 * platform's SlowOperations guard rejects from the UI thread. We run
 * the resolution in a non-blocking read action on a background pooled
 * thread and hop back to the EDT only for the editor-open call.
 */
private fun navigateToInline(project: Project, sym: Sym, bci: Int) {
    ReadAction
        .nonBlocking<InlineTarget?> { resolveInlineTarget(project, sym) }
        .inSmartMode(project)
        .expireWith(project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { target ->
            when (target) {
                is InlineTarget.Bytecode -> openBytecodeView(project, target.classFile, sym, bci)
                is InlineTarget.Source -> target.navigatable.navigate(true)
                null -> Unit
            }
        }
        .submit(AppExecutorUtil.getAppExecutorService())
}

/**
 * Open the Hexana bytecode view on [vf] and ask it to scroll to ([sym]'s
 * method, [bci]). If a `HexanaFileEditor` is already mounted for the file,
 * route the request through its `MutableStateFlow` so the editor reacts
 * even when [FileEditorManager.openFile] is a no-op (the file is already
 * the active tab). For not-yet-open files, stash the target on the
 * VirtualFile UserData where the editor constructor picks it up.
 */
private fun openBytecodeView(project: Project, vf: VirtualFile, sym: Sym, bci: Int) {
    val target = ClassFileNavTarget(
        methodName = sym.methodName,
        methodDescriptor = sym.methodSignature,
        bci = bci,
    )
    val mgr = FileEditorManager.getInstance(project)
    val existing = mgr.getEditors(vf).filterIsInstance<HexanaFileEditor>().firstOrNull()
    if (existing != null) {
        existing.requestClassFileNav(target)
    } else {
        vf.putUserData(CLASS_FILE_NAV_TARGET_KEY, target)
    }
    mgr.openFile(vf, /* focusEditor = */ true)
    // Force Hexana's tab to the front. Without this the platform restores
    // the last-selected editor for the file, which is often the decompiler.
    mgr.setSelectedEditor(vf, HexanaFileEditorProvider.EDITOR_TYPE_ID)
}

/**
 * Off-EDT half of [navigateToInline] — does all the index-touching work
 * under the read action and returns a target that can be opened on the
 * UI thread without blocking. Prefers a compiled-backed PsiClass (the
 * `.class` file backing it is what the bytecode view reads); if only a
 * source-backed PsiClass exists, returns a source [Navigatable] so the
 * caller can degrade gracefully to old-style PSI navigation.
 */
private fun resolveInlineTarget(project: Project, sym: Sym): InlineTarget? {
    val fqn = javaFqn(sym.classSignature)
    val scope = GlobalSearchScope.allScope(project)
    val classes = JavaPsiFacade.getInstance(project).findClasses(fqn, scope)
    if (classes.isEmpty()) return null

    val compiled = classes.firstOrNull { it.containingFile is PsiCompiledFile }
    if (compiled != null) {
        val vf = compiled.containingFile?.virtualFile
        if (vf != null) return InlineTarget.Bytecode(vf)
    }

    val cls = classes.first()
    val matched = cls.findMethodsByName(sym.methodName, true).firstOrNull { method ->
        runCatching {
            com.intellij.psi.util.ClassUtil.getAsmMethodSignature(method) == sym.methodSignature
        }.getOrDefault(false)
    } ?: cls.findMethodsByName(sym.methodName, true).firstOrNull()
    val nav = (matched as? Navigatable) ?: (cls as? Navigatable)
    return nav?.let { InlineTarget.Source(it) }
}

private sealed interface InlineTarget {
    data class Bytecode(val classFile: VirtualFile) : InlineTarget
    data class Source(val navigatable: Navigatable) : InlineTarget
}

// -----------------------------------------------------------------------------
// Styling
// -----------------------------------------------------------------------------

private val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
private val RowStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
// 11sp inline-stack lines — one notch smaller than the row text so the
// inlay reads as secondary information without overpowering the row.
private val InlineLineStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

// Fixed column widths for the paired bytecode/native rows so continuation
// lines visually align under the first row of a BCI run.
private val BciColumnWidth = 44.dp
private val OpcodeColumnWidth = 220.dp
// Info view — library table address columns (16 hex digits + "0x" = 18 chars)
// and a compact size column. Mirrored from NativeAddressColumnWidth.
private val LibAddrColumnWidth = 155.dp
private val LibSizeColumnWidth = 190.dp
// 16 hex digits + `0x` prefix = 18 chars in a 12sp monospace font ≈ 130dp;
// 140dp gives a touch of breathing room before the hex bytes column.
private val NativeAddressColumnWidth = 140.dp
// Wide enough for ~15 bytes of x86 hex; ARM64 ends well under this.
private val HexColumnWidth = 280.dp
// Inline-stack column — one line of `Class.method(Sig):bci` fits comfortably.
private val InlineColumnWidth = 360.dp
private val HexanaMuted = Color(0xFF7F8186)
private val HexanaLinkColor = Color(0xFF3574F0)
private val HexanaMcHexColor = Color(0xFF6F8294)
// Soft red for the "no method at this address" search hint.
private val HexanaSearchErrorColor = Color(0xFFE06C75)
// Per-sym range — translucent blue, drawn whenever a sym is selected.
private val SymRangeColor = Color(0x223B82F6)
// Per-BCI range — saturated blue, layered on top of SymRangeColor.
private val BciHighlightColor = Color(0x553B82F6)

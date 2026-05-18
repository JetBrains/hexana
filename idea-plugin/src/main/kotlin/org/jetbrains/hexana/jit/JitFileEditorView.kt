package org.jetbrains.hexana.jit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexHighlightRange
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.HexanaFileEditorProvider
import org.jetbrains.hexana.ui.CLASS_FILE_NAV_TARGET_KEY
import org.jetbrains.hexana.ui.ClassFileNavTarget
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
import org.jetbrains.hexana.jvm.jit.InlinedAt
import org.jetbrains.hexana.jvm.jit.Inlined
import org.jetbrains.hexana.jvm.jit.JitFile
import org.jetbrains.hexana.jvm.jit.JitSym
import org.jetbrains.hexana.jvm.jit.Sym
import org.jetbrains.hexana.providers.BinaryProvider
import java.util.Locale
import org.jetbrains.hexana.ui.HexViewer
import org.jetbrains.hexana.ui.VerticalSplitPanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/**
 * Editor view for a `.jit` dump.
 *
 *  - Left:  tree of compiled methods grouped by Java FQN — each method
 *           collapses its recompile versions; clicking the parent expands
 *           and selects the latest version.
 *  - Top right:  global hex view over the WHOLE `.jit` file. On sym
 *                selection the view scrolls to that sym's `fileOffset`
 *                and the sym's native byte range is highlighted; selecting
 *                a bytecode row adds per-BCI overlay highlights on top.
 *  - Bottom right: Bytecode / Lines tabs. The Bytecode tab renders
 *                  `bci opcode operands   <machine-code-hex>` per BCI, and
 *                  any inline records for that BCI hang underneath as a
 *                  foldable group labelled `fqn::method:bci` (click =
 *                  navigate to the inlined method in source via PSI).
 *
 * This is the v0 "compiled-method browser" — when the disassembler lands
 * the per-BCI machine-code hex on the right of each row will be replaced
 * with rendered ISA mnemonics, but the rest of the structure stays.
 */
@Composable
internal fun JitFileEditorView(
    project: Project,
    jit: JitFile,
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    hexFocusRequester: FocusRequester,
) {
    val groups = remember(jit) { groupSyms(jit.symbols) }
    var selectedKey by rememberSaveable(jit) {
        mutableStateOf(groups.firstOrNull()?.key)
    }
    var selectedVersion by rememberSaveable(jit) {
        // Most-recent version is the default when picking a method by group.
        mutableStateOf(groups.firstOrNull()?.versions?.maxOf { it.version.toInt() } ?: -1)
    }
    val selectedSym = remember(groups, selectedKey, selectedVersion) {
        val g = groups.firstOrNull { it.key == selectedKey } ?: return@remember null
        g.versions.firstOrNull { it.version.toInt() == selectedVersion }
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

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(if (JewelTheme.isDark) Color(0xFF1F2123) else Color(0xFFF7F8FA)),
        ) {
            JitSymTree(
                groups = groups,
                selectedKey = selectedKey,
                selectedVersion = selectedVersion,
                onSelect = { key, version ->
                    selectedKey = key
                    selectedVersion = version
                },
            )
        }
        Divider(orientation = Orientation.Vertical, modifier = Modifier.fillMaxHeight())
        Box(modifier = Modifier.fillMaxSize()) {
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
                )
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

/**
 * Converts a JVM method descriptor like `([BIB)V` into a Java-style argument
 * list like `(byte[], int, byte)`. Return type is dropped — inline frames
 * read more naturally without it, the way stack traces are usually printed.
 *
 * Unparseable / truncated descriptors degrade to `(…)`.
 */
private fun prettyJvmArgList(descriptor: String): String {
    val open = descriptor.indexOf('(')
    val close = descriptor.indexOf(')')
    if (open < 0 || close < 0 || close < open) return "(…)"
    if (close == open + 1) return "()"
    val args = ArrayList<String>(4)
    var i = open + 1
    while (i < close) {
        var dims = 0
        while (i < close && descriptor[i] == '[') { dims++; i++ }
        if (i >= close) return "(…)"
        val base = when (descriptor[i]) {
            'B' -> "byte"
            'C' -> "char"
            'D' -> "double"
            'F' -> "float"
            'I' -> "int"
            'J' -> "long"
            'S' -> "short"
            'Z' -> "boolean"
            'L' -> {
                val semi = descriptor.indexOf(';', i)
                if (semi < 0 || semi > close) return "(…)"
                val cls = descriptor.substring(i + 1, semi).substringAfterLast('/')
                i = semi
                cls
            }
            else -> return "(…)"
        }
        i++
        args += if (dims == 0) base else base + "[]".repeat(dims)
    }
    return args.joinToString(prefix = "(", postfix = ")")
}

/**
 * Build the human-readable label for an inlined frame. Renders constructors
 * as `new Class(args)` and otherwise as `Class.method(args)`, matching how
 * Java tooling (stack traces, IDEs) refer to call sites.
 */
private fun prettyInlineLabel(frame: Inlined): String {
    val sym = frame.sym ?: return "<unresolved>"
    val fqn = javaFqn(sym.classSignature)
    val args = prettyJvmArgList(sym.methodSignature)
    return when (sym.methodName) {
        "<init>" -> "new $fqn$args"
        "<clinit>" -> "$fqn.<clinit>()"
        else -> "$fqn.${sym.methodName}$args"
    }
}

// -----------------------------------------------------------------------------
// Left column — sym tree
// -----------------------------------------------------------------------------

@Composable
private fun JitSymTree(
    groups: List<SymGroup>,
    selectedKey: SymKey?,
    selectedVersion: Int,
    onSelect: (SymKey, Int) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable {
        mutableStateOf(setOf<SymKey>())
    }

    val filtered by remember(groups, query) {
        derivedStateOf {
            if (query.isBlank()) groups
            else groups.filter { it.displayLabel.contains(query, ignoreCase = true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SymSearchBar(query = query, onQueryChange = { query = it })
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) {
                Text(
                    if (groups.isEmpty()) "(no compiled methods)" else "(no matches)",
                    style = RowStyle.copy(color = HexanaMuted),
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(state = listState) {
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

@Composable
private fun SymSearchBar(query: String, onQueryChange: (String) -> Unit) {
    val outline = if (JewelTheme.isDark) Color(0xFF393B3D) else Color(0xFFC9CCD6)
    val textColor = if (JewelTheme.isDark) Color(0xFFBCBEC4) else Color(0xFF1F1F1F)
    Box(
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
                .padding(horizontal = 8.dp, vertical = 4.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("Search class or method…", style = RowStyle.copy(color = HexanaMuted))
                }
                inner()
            },
        )
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
) {
    val instructions = remember(sym) {
        runCatching { JavaBytecodeDecoder.decode(sym.bytecode) }.getOrDefault(emptyList())
    }
    // Pre-compute the per-BCI inline groups so the Bytecode row doesn't
    // re-scan inlineRecords on every recomposition.
    val inlinesByBci = remember(sym) { buildInlinesByBci(sym) }

    // Load the Capstone-WASM disassembler once (process-wide singleton),
    // then decode this sym's native bytes and group decoded instructions
    // by BCI via the address-location map.
    val disasmState = rememberDisassembler(state)
    val arch = remember { hostArch() }
    val nativeRowsByBci = remember(sym, disasmState, arch) {
        val ready = disasmState as? DisasmState.Ready ?: return@remember emptyMap<Int, List<NativeRow>>()
        runCatching {
            disassembleByBci(sym, ready.bundle.disassembler, arch, ready.bundle.formatterFor(arch))
        }.getOrElse {
            emptyMap()
        }
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
            JitBytecodeTab(
                project = project,
                sym = sym,
                instructions = instructions,
                inlinesByBci = inlinesByBci,
                nativeRowsByBci = nativeRowsByBci,
                disasmState = disasmState,
                selectedBci = selectedBci,
                onSelectBci = onSelectBci,
            )
        },
    )
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
    inlinesByBci: Map<Int, List<InlinedAt>>,
    nativeRowsByBci: Map<Int, List<NativeRow>>,
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

    var expandedInlines by rememberSaveable(sym) { mutableStateOf(setOf<Int>()) }
    val listState = rememberLazyListState()

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // bciIndex flips the row background between adjacent BCIs so a
        // multi-row native fan-out reads as one cohesive block — every row
        // of the same BCI shares the same tint, every transition between
        // BCIs is a visible color change.
        var bciIndex = 0
        for (insn in instructions) {
            val bci = insn.bytecodeOffset
            val inlines = inlinesByBci[bci]
            val nativeRows = nativeRowsByBci[bci].orEmpty()
            val open = bci in expandedInlines
            val opcodeText = formatInstruction(insn)
            val hasInlines = !inlines.isNullOrEmpty()
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
                        bci = bci,
                        opcodeText = opcodeText,
                        nativeAddress = null,
                        hexText = hexCol,
                        asmText = asmCol,
                        showBcOpcode = true,
                        hasInlines = hasInlines,
                        inlineOpen = open,
                        selected = selected,
                        stripe = stripe,
                        asmMuted = disasmState is DisasmState.Ready,
                        onRowClick = { onSelectBci(bci) },
                        onToggleInline = {
                            expandedInlines = if (open) expandedInlines - bci
                            else expandedInlines + bci
                        },
                    )
                }
            } else {
                for ((nIdx, row) in nativeRows.withIndex()) {
                    val isFirst = nIdx == 0
                    item(key = "bc-$bci-$nIdx") {
                        PairedRow(
                            bci = bci,
                            opcodeText = opcodeText,
                            nativeAddress = row.address,
                            hexText = row.hex,
                            asmText = row.asm,
                            // bci + opcode column populated only on the
                            // first native row of a BCI run — keeps the
                            // bytecode-to-native fan-out visually paired.
                            showBcOpcode = isFirst,
                            hasInlines = hasInlines && isFirst,
                            inlineOpen = open,
                            selected = selected,
                            stripe = stripe,
                            asmMuted = false,
                            onRowClick = { onSelectBci(bci) },
                            onToggleInline = {
                                expandedInlines = if (open) expandedInlines - bci
                                else expandedInlines + bci
                            },
                        )
                    }
                }
            }
            if (open && inlines != null) {
                for ((groupIdx, inlinedAt) in inlines.withIndex()) {
                    for ((frameIdx, frame) in inlinedAt.inlined.withIndex()) {
                        item(key = "in-$bci-$groupIdx-$frameIdx") {
                            InlineFrameRow(
                                project = project,
                                frame = frame,
                                depth = frameIdx,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairedRow(
    bci: Int,
    opcodeText: String,
    nativeAddress: ULong?,
    hexText: String,
    asmText: String,
    showBcOpcode: Boolean,
    hasInlines: Boolean,
    inlineOpen: Boolean,
    selected: Boolean,
    stripe: Boolean,
    asmMuted: Boolean,
    onRowClick: () -> Unit,
    onToggleInline: () -> Unit,
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fold toggle — only on first row of a BCI that has inline records.
        Box(modifier = Modifier.width(14.dp)) {
            if (hasInlines) {
                Text(
                    if (inlineOpen) "▼" else "▶",
                    style = RowStyle.copy(color = HexanaMuted),
                    modifier = Modifier.clickable(onClick = onToggleInline),
                )
            }
        }
        Spacer(modifier = Modifier.width(2.dp))
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
        Text(
            asmText,
            style = if (asmMuted) RowStyle.copy(color = HexanaMuted) else RowStyle,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Renders an absolute runtime address like `0x0000000114a36f40` (16 hex digits, zero-padded). */
private fun formatNativeAddress(address: ULong): String =
    "0x" + address.toString(16).padStart(16, '0')

@Composable
private fun InlineFrameRow(
    project: Project,
    frame: Inlined,
    depth: Int,
) {
    val symInfo: Sym? = frame.sym
    val label = prettyInlineLabel(frame)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = symInfo != null) {
                navigateToInline(project, symInfo!!, frame.bci)
            }
            .padding(start = (28 + depth * 14).dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "↳ ",
            style = RowStyle.copy(color = HexanaMuted),
        )
        Text(
            label,
            style = RowStyle.copy(
                color = if (symInfo == null) HexanaMuted else HexanaLinkColor,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "@bci ${frame.bci}",
            style = RowStyle.copy(color = HexanaMuted, fontSize = 11.sp),
        )
    }
}

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
 * Map each BCI seen in the address-location map to its list of `InlinedAt`
 * groups — i.e. the inline stacks JVMTI reported for native PCs that fall
 * inside the BCI's native byte range.
 */
private fun buildInlinesByBci(sym: JitSym): Map<Int, List<InlinedAt>> {
    val map = sym.addressLocationMap
    if (map.isEmpty() || sym.inlineRecords.isEmpty()) return emptyMap()

    // Build flat list of (bci, startNative, endNative) intervals.
    data class Span(val bci: Int, val start: Long, val end: Long)
    val limit = sym.codeSize.toLong()
    val spans = ArrayList<Span>(map.size)
    for (i in map.indices) {
        val (start, location) = map[i]
        val end = if (i + 1 < map.size) map[i + 1].first.toLong() else limit
        spans += Span(location.toInt(), start.toLong(), end)
    }

    val out = HashMap<Int, MutableList<InlinedAt>>()
    val allInlines = sym.inlineRecords.flatMap { it.inlines }
    for (inl in allInlines) {
        // inlineRecord.address is the JIT-runtime address; reduce to a
        // native-byte offset relative to the codeBlock.
        val relative = (inl.address - sym.address).toLong()
        val matching = spans.firstOrNull { relative in it.start until it.end } ?: continue
        out.getOrPut(matching.bci) { ArrayList() }.add(inl)
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
 * Disassemble [sym]'s native bytes once and bucket the decoded instructions
 * by the JVM bytecode index whose native byte range they fall into,
 * using the address-location map.
 *
 * Instructions that don't land inside any mapped BCI range (function
 * prologue / epilogue, deoptimisation pads, vtable stubs) are dropped here
 * — the disassembly view replacement will surface them; the v0 bytecode
 * tab only shows what's directly attributable to a BCI.
 */
private fun disassembleByBci(
    sym: JitSym,
    disasm: CapstoneWasmDisassembler,
    arch: Arch,
    formatter: AssemblyFormatter,
): Map<Int, List<NativeRow>> {
    if (sym.codeSize == 0uL) return emptyMap()
    val buf = sym.codeBlock.toCommonByteBuffer()
    val decoded = with(disasm) { buf.disassemble(sym.address, arch) }.toList()
    if (decoded.isEmpty()) return emptyMap()

    val map = sym.addressLocationMap
    if (map.isEmpty()) return emptyMap()
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

    val code = sym.codeBlock
    val out = HashMap<Int, MutableList<NativeRow>>()
    for (insn in decoded) {
        val nativeOffset = insn.byteRange.first
        val bci = bciAt(nativeOffset) ?: continue
        val instructionBytes = ByteArray(insn.size) { code[insn.byteRange.first + it] }
        val hex = formatHexBytes(instructionBytes, Int.MAX_VALUE)
        val asm = when (insn) {
            is DecodedInstruction.Valid -> formatter.format(insn)
            is DecodedInstruction.Unknown -> with(formatter) { buf.formatUnknown(insn) }
            else -> "" // sealed interface; future variants render empty
        }
        out.getOrPut(bci) { ArrayList() }.add(NativeRow(insn.address, hex, asm))
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

// Fixed column widths for the paired bytecode/native rows so continuation
// lines visually align under the first row of a BCI run.
private val BciColumnWidth = 44.dp
private val OpcodeColumnWidth = 220.dp
// 16 hex digits + `0x` prefix = 18 chars in a 12sp monospace font ≈ 130dp;
// 140dp gives a touch of breathing room before the hex bytes column.
private val NativeAddressColumnWidth = 140.dp
// Wide enough for ~15 bytes of x86 hex; ARM64 ends well under this.
private val HexColumnWidth = 280.dp
private val HexanaMuted = Color(0xFF7F8186)
private val HexanaLinkColor = Color(0xFF3574F0)
private val HexanaMcHexColor = Color(0xFF6F8294)
// Per-sym range — translucent blue, drawn whenever a sym is selected.
private val SymRangeColor = Color(0x223B82F6)
// Per-BCI range — saturated blue, layered on top of SymRangeColor.
private val BciHighlightColor = Color(0x553B82F6)

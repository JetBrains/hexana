package org.jetbrains.hexana.breakpoints

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity
import java.util.UUID

/**
 * Project-level persistent storage for binary breakpoints.
 *
 * Breakpoints are keyed by a stable [BinaryBreakpoint.id] (UUID) and survive
 * file moves and rehoming across builds. All mutations update both the persisted
 * [BinaryBreakpointRegistryState] and the hot [breakpoints] flow atomically
 * (synchronized on [lock]).
 */
@Service(Service.Level.PROJECT)
@State(name = "HexanaBinaryBreakpoints", storages = [Storage("hexanaBinaryBreakpoints.xml")])
final class BinaryBreakpointRegistry :
    SimplePersistentStateComponent<BinaryBreakpointRegistryState>(BinaryBreakpointRegistryState()) {

    private val lock = Any()
    private val _breakpoints = MutableStateFlow<List<BinaryBreakpoint>>(emptyList())

    /** Hot flow of all breakpoints; updated on every mutation. */
    val breakpoints: StateFlow<List<BinaryBreakpoint>> = _breakpoints.asStateFlow()

    companion object {
        fun getInstance(project: Project): BinaryBreakpointRegistry = project.service()
    }

    override fun loadState(state: BinaryBreakpointRegistryState) {
        super.loadState(state)
        synchronized(lock) {
            _breakpoints.value = state.entries.mapNotNull { it.toBinaryBreakpoint() }
        }
    }

    /** Adds a new breakpoint and returns it. */
    fun add(identity: BinaryIdentity, anchor: BinaryAnchor, filePath: String?): BinaryBreakpoint {
        val bp = BinaryBreakpoint(
            id = UUID.randomUUID().toString(),
            identity = identity,
            anchor = anchor,
            enabled = true,
            rehomed = false,
            stale = false,
            filePath = filePath,
        )
        synchronized(lock) {
            state.entries = (state.entries + BinaryBreakpointEntry.fromBinaryBreakpoint(bp)).toMutableList()
            _breakpoints.value = state.entries.mapNotNull { it.toBinaryBreakpoint() }
        }
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "hexana-bp: registry.add id=${bp.id.take(8)} anchor=${describeAnchor(anchor)} sha=${identity.contentSha256.take(12)}"
        )
        return bp
    }

    /** Removes the breakpoint with the given [id]. No-op if not found. */
    fun remove(id: String) {
        synchronized(lock) {
            state.entries = state.entries.filter { it.id != id }.toMutableList()
            _breakpoints.value = state.entries.mapNotNull { it.toBinaryBreakpoint() }
        }
    }

    /** Enables or disables the breakpoint with the given [id]. No-op if not found. */
    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            state.entries = state.entries.map { e ->
                if (e.id != id) e
                else {
                    val bp = e.toBinaryBreakpoint() ?: return@map e
                    BinaryBreakpointEntry.fromBinaryBreakpoint(bp.copy(enabled = enabled))
                }
            }.toMutableList()
            _breakpoints.value = state.entries.mapNotNull { it.toBinaryBreakpoint() }
        }
    }

    /**
     * Toggles a breakpoint: removes it if one already exists that matches
     * (same identity by exact sha256, same anchor position ignoring secondary
     * keys), or adds a new one otherwise.
     *
     * @return true if a breakpoint was added, false if one was removed.
     */
    fun toggle(identity: BinaryIdentity, anchor: BinaryAnchor, filePath: String?): Boolean {
        synchronized(lock) {
            val existing = state.entries.firstOrNull { e ->
                e.sha256 == identity.contentSha256 && anchorsPositionallyEqual(e, anchor)
            }
            return if (existing != null) {
                state.entries = state.entries.filter { it.id != existing.id }.toMutableList()
                _breakpoints.value = state.entries.mapNotNull { it.toBinaryBreakpoint() }
                org.jetbrains.hexana.HexanaLog.LOG.warn(
                    "hexana-bp: registry.toggle REMOVED anchor=${describeAnchor(anchor)} sha=${identity.contentSha256.take(12)}"
                )
                false
            } else {
                val bp = BinaryBreakpoint(
                    id = UUID.randomUUID().toString(),
                    identity = identity,
                    anchor = anchor,
                    enabled = true,
                    rehomed = false,
                    stale = false,
                    filePath = filePath,
                )
                state.entries = (state.entries + BinaryBreakpointEntry.fromBinaryBreakpoint(bp)).toMutableList()
                _breakpoints.value = state.entries.mapNotNull { it.toBinaryBreakpoint() }
                org.jetbrains.hexana.HexanaLog.LOG.warn(
                    "hexana-bp: registry.toggle ADDED id=${bp.id.take(8)} anchor=${describeAnchor(anchor)} " +
                        "sha=${identity.contentSha256.take(12)} path=$filePath"
                )
                true
            }
        }
    }

    private fun describeAnchor(anchor: BinaryAnchor): String = when (anchor) {
        is BinaryAnchor.Wasm -> "wasm(func=${anchor.funcIdx} off=0x${anchor.byteOffset.toString(16)} ord=${anchor.instrOrdinal})"
        is BinaryAnchor.Native -> "native(va=0x${anchor.virtualAddress.toString(16)} sym=${anchor.symbolName}+0x${anchor.offsetInSymbol.toString(16)})"
    }

    private fun anchorsPositionallyEqual(entry: BinaryBreakpointEntry, anchor: BinaryAnchor): Boolean {
        return when (anchor) {
            is BinaryAnchor.Wasm -> entry.kind == "wasm" &&
                entry.funcIdx == anchor.funcIdx &&
                entry.byteOffset == anchor.byteOffset
            is BinaryAnchor.Native -> entry.kind == "native" &&
                entry.virtualAddressHex == anchor.virtualAddress.toString(16)
        }
    }

    /** Returns all breakpoints whose identity matches [identity]. */
    fun forIdentity(identity: BinaryIdentity): List<BinaryBreakpoint> =
        _breakpoints.value.filter { identity.matches(it.identity) }

    /**
     * Returns breakpoints associated with [path] that belong to a DIFFERENT
     * identity than [identity]. Used during session start to find stale-path
     * breakpoints that may need rehoming.
     */
    fun forPathWithDifferentIdentity(path: String, identity: BinaryIdentity): List<BinaryBreakpoint> =
        _breakpoints.value.filter { bp ->
            bp.filePath == path && !identity.matches(bp.identity)
        }

    /**
     * Records a successful rehome: updates the identity and anchor of the
     * breakpoint with [id] and clears the stale flag.
     */
    fun applyRehome(id: String, newIdentity: BinaryIdentity, newAnchor: BinaryAnchor) {
        synchronized(lock) {
            state.entries = state.entries.map { e ->
                if (e.id != id) e
                else BinaryBreakpointEntry.fromBinaryBreakpoint(
                    BinaryBreakpoint(
                        id = id,
                        identity = newIdentity,
                        anchor = newAnchor,
                        enabled = e.enabled,
                        rehomed = true,
                        stale = false,
                        filePath = e.filePath,
                    )
                )
            }.toMutableList()
            _breakpoints.value = state.entries.mapNotNull { it.toBinaryBreakpoint() }
        }
    }

    /** Marks the breakpoint as stale (rehome failed) and disables it. */
    fun markStale(id: String) {
        synchronized(lock) {
            state.entries = state.entries.map { e ->
                if (e.id != id) e
                else {
                    val bp = e.toBinaryBreakpoint() ?: return@map e
                    BinaryBreakpointEntry.fromBinaryBreakpoint(bp.copy(stale = true, enabled = false))
                }
            }.toMutableList()
            _breakpoints.value = state.entries.mapNotNull { it.toBinaryBreakpoint() }
        }
    }
}

class BinaryBreakpointRegistryState : BaseState() {
    @get:XCollection
    var entries by list<BinaryBreakpointEntry>()
}

@Tag("BinaryBreakpointEntry")
class BinaryBreakpointEntry : BaseState() {
    var id by string()
    var sha256 by string()
    var buildId by string()
    /** "wasm" or "native". */
    var kind by string()
    var funcIdx by property(-1)
    var byteOffset by property(-1L)
    var funcName by string()
    var instrOrdinal by property(-1)
    /** Hex-encoded virtual address for native breakpoints. */
    var virtualAddressHex by string()
    var symbolName by string()
    /** Hex-encoded symbol offset for native breakpoints. */
    var offsetInSymbolHex by string()
    var enabled by property(true)
    var rehomed by property(false)
    var stale by property(false)
    var filePath by string()

    fun toBinaryBreakpoint(): BinaryBreakpoint? {
        val bpId = id?.takeIf { it.isNotEmpty() } ?: return null
        val sha = sha256?.takeIf { it.isNotEmpty() } ?: return null
        val bpKind = kind?.takeIf { it.isNotEmpty() } ?: return null
        val identity = BinaryIdentity(
            contentSha256 = sha,
            elfBuildId = buildId?.takeIf { it.isNotEmpty() },
        )
        val anchor = when (bpKind) {
            "wasm" -> BinaryAnchor.Wasm(
                funcIdx = funcIdx,
                byteOffset = byteOffset,
                funcName = funcName?.takeIf { it.isNotEmpty() },
                instrOrdinal = instrOrdinal,
            )
            "native" -> {
                val va = virtualAddressHex?.takeIf { it.isNotEmpty() }
                    ?.toULongOrNull(16) ?: return null
                val off = offsetInSymbolHex?.takeIf { it.isNotEmpty() }
                    ?.toULongOrNull(16) ?: 0uL
                BinaryAnchor.Native(
                    virtualAddress = va,
                    symbolName = symbolName?.takeIf { it.isNotEmpty() },
                    offsetInSymbol = off,
                )
            }
            else -> return null
        }
        return BinaryBreakpoint(
            id = bpId,
            identity = identity,
            anchor = anchor,
            enabled = enabled,
            rehomed = rehomed,
            stale = stale,
            filePath = filePath?.takeIf { it.isNotEmpty() },
        )
    }

    companion object {
        fun fromBinaryBreakpoint(bp: BinaryBreakpoint): BinaryBreakpointEntry = BinaryBreakpointEntry().also { e ->
            e.id = bp.id
            e.sha256 = bp.identity.contentSha256
            e.buildId = bp.identity.elfBuildId ?: ""
            e.filePath = bp.filePath ?: ""
            e.enabled = bp.enabled
            e.rehomed = bp.rehomed
            e.stale = bp.stale
            when (val anchor = bp.anchor) {
                is BinaryAnchor.Wasm -> {
                    e.kind = "wasm"
                    e.funcIdx = anchor.funcIdx
                    e.byteOffset = anchor.byteOffset
                    e.funcName = anchor.funcName ?: ""
                    e.instrOrdinal = anchor.instrOrdinal
                    e.virtualAddressHex = ""
                    e.symbolName = ""
                    e.offsetInSymbolHex = ""
                }
                is BinaryAnchor.Native -> {
                    e.kind = "native"
                    e.funcIdx = -1
                    e.byteOffset = -1L
                    e.funcName = ""
                    e.instrOrdinal = -1
                    e.virtualAddressHex = anchor.virtualAddress.toString(16)
                    e.symbolName = anchor.symbolName ?: ""
                    e.offsetInSymbolHex = anchor.offsetInSymbol.toString(16)
                }
            }
        }
    }
}

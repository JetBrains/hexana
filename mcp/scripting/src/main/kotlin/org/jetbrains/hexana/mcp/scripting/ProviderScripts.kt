package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.StructureRange
import org.jetbrains.hexana.providers.binaryParserOf
import org.jetbrains.hexana.shared.scripting.ProviderRegistrationInfo
import org.jetbrains.hexana.shared.scripting.SliceRegistrationInfo
import org.jetbrains.hexana.shared.scripting.appliesToFormats
import org.jetbrains.hexana.shared.scripting.scriptFormatIds
import org.jetbrains.hexana.shared.scripting.structureRanges

/**
 * One script from `.hexana/providers/` and what it registered.
 *
 * @param error why the script registered nothing usable — it failed to evaluate, or every
 *   provider it defined was already defined by an earlier script — or `null`
 */
data class ProviderScript(
    val path: String,
    val text: String,
    val providers: List<ProviderRegistrationInfo>,
    val tabs: List<SliceRegistrationInfo>,
    val error: String? = null,
)

/**
 * Everything the project's provider scripts registered.
 *
 * @param blockedByTrust the project is not trusted, so the scripts were not run at all
 */
data class ProviderScriptCatalog(
    val scripts: List<ProviderScript> = emptyList(),
    val blockedByTrust: Boolean = false,
) {
    /** Every provider, in script order; an id is owned by the first script that defined it. */
    val providers: List<ProviderRegistrationInfo> get() = scripts.flatMap { it.providers }

    /** The provider registered under [id], and the script that registered it. */
    fun provider(id: String): Pair<ProviderScript, ProviderRegistrationInfo>? {
        for (script in scripts) {
            script.providers.firstOrNull { it.id == id }?.let { return script to it }
        }
        return null
    }

    companion object {
        val EMPTY = ProviderScriptCatalog()
    }
}

/**
 * What a scripted provider added to the file it was applied to.
 *
 * @param ranges the regions its `parse()` reported, as hex-view ranges
 * @param error why the regions are missing (the script failed, `parse()` threw), or `null`
 */
data class ProviderOverlay(
    val provider: ProviderRegistrationInfo,
    val ranges: List<StructureRange>,
    val error: String? = null,
)

/** A `defineTab` tab attached to an editor, with the live session its rows come from. */
class ScriptTabBinding(val session: HexanaScriptSession, val tab: SliceRegistrationInfo)

/**
 * The provider-script state of one editor: the [overlay] of the scripted provider it was
 * opened with, if any, and the [tabs] that attach to its file. Owns the sessions behind them.
 */
class ProviderScriptBindings(
    val overlay: ProviderOverlay?,
    val tabs: List<ScriptTabBinding>,
    private val sessions: List<HexanaScriptSession>,
) : AutoCloseable {

    override fun close() {
        sessions.forEach { it.close() }
    }

    companion object {
        val EMPTY = ProviderScriptBindings(null, emptyList(), emptyList())
    }
}

/**
 * Evaluates the scripts in [catalog] that matter for [provider] — the one defining [scripted],
 * and those with tabs that apply to the file — and collects what they contribute.
 *
 * A script with nothing for this file is not run. One that is keeps its session only while it
 * has tabs to page; the session of a provider whose only job was `parse()` is closed right
 * after. Runs scripts, so never on the EDT.
 */
fun bindProviderScripts(
    openSession: (ProviderScript, BinaryProvider, String?, String?) -> HexanaScriptSession,
    catalog: ProviderScriptCatalog,
    provider: BinaryProvider,
    fileName: String?,
    filePath: String?,
    scripted: ProviderRegistrationInfo?,
): ProviderScriptBindings {
    val formatIds = scriptFormatIds(binaryParserOf(provider), scripted?.id)
    val owner = scripted?.let { catalog.provider(it.id)?.first }
    var overlay: ProviderOverlay? = null
    val tabs = mutableListOf<ScriptTabBinding>()
    val sessions = mutableListOf<HexanaScriptSession>()

    for (script in catalog.scripts) {
        val definesProvider = script === owner
        val hasTabs = script.tabs.any { it.isTab && appliesToFormats(it.formats, formatIds) }
        if (!definesProvider && !hasTabs) continue

        val session = openSession(script, provider, fileName, filePath)
        val result = session.initialResult
        if (!result.success) {
            session.close()
            if (definesProvider) {
                overlay = ProviderOverlay(
                    scripted!!, emptyList(),
                    error = "${script.path.substringAfterLast('/')}: " +
                        result.diagnostics.joinToString("; ") { it.message },
                )
            }
            continue
        }
        if (definesProvider) {
            overlay = when (val outcome = session.runParse(scripted!!.id)) {
                is ProviderParseOutcome.Ok -> {
                    val ranges = outcome.result.structureRanges(provider.bytes.limit())
                    // Malformed regions never made it into the result; ones outside the file
                    // are filtered just now. Both are the script's to fix, so both are counted.
                    val dropped = outcome.result.dropped + (outcome.result.regions.size - ranges.size)
                    ProviderOverlay(
                        scripted,
                        ranges,
                        error = dropped.takeIf { it > 0 }
                            ?.let { "parse() reported $it region(s) that are empty, malformed or outside the file." },
                    )
                }
                is ProviderParseOutcome.Err -> ProviderOverlay(scripted, emptyList(), outcome.message)
            }
        }
        // The tabs this evaluation registered, not the metadata pass's: top-level code may
        // register differently once it sees a real binary.
        val applicable = result.slices.filter { it.isTab && appliesToFormats(it.formats, formatIds) }
        if (applicable.isEmpty() || !session.isAlive) {
            session.close()
        } else {
            sessions += session
            applicable.mapTo(tabs) { ScriptTabBinding(session, it) }
        }
    }
    return ProviderScriptBindings(overlay, tabs, sessions)
}

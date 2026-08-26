package org.jetbrains.hexana.firmware.debug

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import org.jetbrains.hexana.breakpoints.GdbBreakpointOutcome
import org.jetbrains.hexana.breakpoints.GdbInstructionBreakpointTransport

/**
 * [GdbInstructionBreakpointTransport] backed by [GdbDapClient].
 *
 * Strategy selection is based on the DAP `initialize` response capabilities:
 *  - If `supportsInstructionBreakpoints == true`: uses `setInstructionBreakpoints`
 *    (DAP replace-all semantics — the full active set is re-sent on every mutation).
 *  - Otherwise: falls back to GDB's `evaluate` REPL with `break *0x<addr>`.
 *    LANDMINE: evaluating `continue` or any execution-control command through
 *    GDB -i=dap REPL wedges the interpreter. Only `break` and `delete` are safe here.
 *
 * Both paths are thread-safe: [GdbDapClient.request] is already thread-safe, and
 * mutations to the active-address set are serialized under [lock].
 *
 * @param client The live [GdbDapClient] for this debug session.
 * @param capabilities The `body` of the DAP `initialize` response. Pass null if
 *   the initialize response was not captured (defaults to CLI fallback).
 */
internal class GdbDapBreakpointTransport(
    private val client: GdbDapClient,
    capabilities: JsonObject?,
) : GdbInstructionBreakpointTransport {

    private val LOG = Logger.getInstance(GdbDapBreakpointTransport::class.java)

    override val loadBias: ULong = 0u

    private val supportsInstruction: Boolean =
        capabilities?.get("supportsInstructionBreakpoints")?.jsonPrimitive?.booleanOrNull == true

    // Serializes mutations of activeAddresses and CLI-side breakpoint-id map.
    private val lock = Any()

    // DAP path: ordered set of addresses currently armed (order is deterministic for tests).
    private val activeAddresses = LinkedHashSet<ULong>()

    // CLI path: address → GDB breakpoint number (from "Breakpoint N at ..." output).
    private val cliBreakIds = HashMap<ULong, Int>()

    override suspend fun setAddressBreakpoint(address: ULong): GdbBreakpointOutcome {
        return try {
            if (supportsInstruction) {
                setViaInstruction(address)
            } else {
                setViaCli(address)
            }
        } catch (e: Exception) {
            LOG.warn("GdbDapBreakpointTransport: setAddressBreakpoint 0x${address.toString(16)} failed", e)
            GdbBreakpointOutcome.Rejected("transport error: ${e.message}")
        }
    }

    override suspend fun removeAddressBreakpoint(address: ULong) {
        try {
            if (supportsInstruction) {
                removeViaInstruction(address)
            } else {
                removeViaCli(address)
            }
        } catch (e: Exception) {
            LOG.warn("GdbDapBreakpointTransport: removeAddressBreakpoint 0x${address.toString(16)} failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // DAP setInstructionBreakpoints path
    // -------------------------------------------------------------------------

    private fun setViaInstruction(address: ULong): GdbBreakpointOutcome {
        val fullSet: List<ULong>
        synchronized(lock) {
            activeAddresses += address
            fullSet = activeAddresses.toList()
        }
        val response = client.request("setInstructionBreakpoints", buildJsonObject {
            putJsonArray("breakpoints") {
                for (addr in fullSet) {
                    addJsonObject { put("instructionReference", "0x${addr.toString(16)}") }
                }
            }
        })
        return parseInstructionBreakpointOutcome(response, address)
    }

    private fun removeViaInstruction(address: ULong) {
        val fullSet: List<ULong>
        synchronized(lock) {
            activeAddresses -= address
            fullSet = activeAddresses.toList()
        }
        client.request("setInstructionBreakpoints", buildJsonObject {
            putJsonArray("breakpoints") {
                for (addr in fullSet) {
                    addJsonObject { put("instructionReference", "0x${addr.toString(16)}") }
                }
            }
        })
    }

    // -------------------------------------------------------------------------
    // CLI evaluate path (GDB REPL via DAP evaluate request)
    // -------------------------------------------------------------------------

    private fun setViaCli(address: ULong): GdbBreakpointOutcome {
        val expr = "break *0x${address.toString(16)}"
        val response = client.request("evaluate", buildJsonObject {
            put("expression", expr)
            put("context", "repl")
        })
        val resultText = response["body"]?.jsonObject?.get("result")?.jsonPrimitive?.contentOrNull ?: ""
        val bpNumber = parseCliBreakNumber(resultText)
        return if (bpNumber != null) {
            synchronized(lock) { cliBreakIds[address] = bpNumber }
            GdbBreakpointOutcome.Resolved
        } else {
            val errorMsg = resultText.ifBlank {
                response["message"]?.jsonPrimitive?.contentOrNull ?: "no breakpoint number in response"
            }
            GdbBreakpointOutcome.Rejected(errorMsg)
        }
    }

    private fun removeViaCli(address: ULong) {
        val id = synchronized(lock) { cliBreakIds.remove(address) } ?: return
        client.request("evaluate", buildJsonObject {
            put("expression", "delete $id")
            put("context", "repl")
        })
    }
}

// =============================================================================
// Pure parsing helpers — package-internal, extracted for unit testing.
// =============================================================================

/**
 * Parses the `body.breakpoints` array from a DAP `setInstructionBreakpoints`
 * response and returns the outcome for the entry whose `instructionReference`
 * matches [instructionReference] (hex with "0x" prefix).
 *
 * Returns [GdbBreakpointOutcome.Resolved] when the entry has `verified == true`,
 * [GdbBreakpointOutcome.Pending] when `verified == false` or the entry is absent,
 * and [GdbBreakpointOutcome.Rejected] when the response body is malformed.
 */
internal fun parseInstructionBreakpointOutcome(
    response: JsonObject,
    address: ULong,
): GdbBreakpointOutcome {
    val body = response["body"]?.jsonObject
        ?: return GdbBreakpointOutcome.Pending  // older GDB may omit body
    val breakpoints = body["breakpoints"]?.jsonArray
        ?: return GdbBreakpointOutcome.Pending
    val ref = "0x${address.toString(16)}"
    val entry = breakpoints.firstOrNull { el ->
        el.jsonObject["instructionReference"]?.jsonPrimitive?.contentOrNull == ref
    }?.jsonObject
    return when {
        entry == null -> GdbBreakpointOutcome.Pending
        entry["verified"]?.jsonPrimitive?.booleanOrNull == true -> GdbBreakpointOutcome.Resolved
        else -> GdbBreakpointOutcome.Pending
    }
}

/**
 * Parses the GDB breakpoint number from the output text of a `break` command,
 * e.g. `"Breakpoint 2 at 0x1a2: file main.c, line 12."` returns `2`.
 *
 * Returns `null` when no "Breakpoint N" pattern is found (error or empty output).
 */
internal fun parseCliBreakNumber(resultText: String): Int? {
    if (resultText.isBlank()) return null
    val match = Regex("""Breakpoint\s+(\d+)""").find(resultText) ?: return null
    return match.groupValues[1].toIntOrNull()
}

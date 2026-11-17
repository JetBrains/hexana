package org.jetbrains.hexana.shared

import org.jetbrains.hexana.wasm.analysis.garbage.GarbageReason

/**
 * Returns a human-readable label for a [GarbageReason].
 */
fun garbageReasonLabel(reason: GarbageReason): String = when (reason) {
    GarbageReason.NO_CALLERS -> "No callers"
    GarbageReason.CALLERS_UNREACHABLE -> "Callers unreachable"
}

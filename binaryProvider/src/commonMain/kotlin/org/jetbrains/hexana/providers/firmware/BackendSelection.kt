package org.jetbrains.hexana.providers.firmware

/**
 * Emulator backend choice.
 */
public enum class Backend { Qemu, Renode }

/**
 * The `qemu-system-*` binary variant required for a given target.
 */
public enum class QemuBinary {
    SystemArm,
    SystemAarch64,
    SystemRiscv32,
    SystemRiscv64,
}

/** How the Renode platform description was obtained. */
public enum class ReplSource {
    /** Generated on the fly from `zephyr.dts` via dts2repl. */
    Generated,

    /** Built into Renode's platform library (known board). */
    Library,
}

/**
 * Result of [selectBackend].
 */
public sealed interface BackendSelection {

    /**
     * A supported backend was selected.
     *
     * @param backend The selected backend.
     * @param recommended The recommended backend for this target (may differ from
     *   [backend] when the user forced a specific choice).
     * @param qemuMachine The QEMU `-machine` argument, or null when [backend] is Renode.
     * @param qemuBinary The `qemu-system-*` binary variant, or null when [backend] is Renode.
     * @param qemuExtraArgs Extra QEMU arguments from the board table (e.g. `-cpu` override).
     * @param replSource How the Renode platform description is obtained, or null for QEMU.
     * @param rationale Human-readable explanation of the selection, suitable for UI display.
     */
    public data class Ok(
        val backend: Backend,
        val recommended: Backend,
        val qemuMachine: String?,
        val qemuBinary: QemuBinary?,
        val qemuExtraArgs: List<String>,
        val replSource: ReplSource?,
        val rationale: String,
    ) : BackendSelection

    /**
     * No supported backend could be selected for this target.
     *
     * @param reason Human-readable failure description (from the failure copy template).
     * @param alternative Suggestion for the user, or null when none is applicable.
     */
    public data class Fail(
        val reason: String,
        val alternative: String?,
    ) : BackendSelection
}

/**
 * Board IDs known to exist in the Renode platform library.
 *
 * A board in this set triggers [ReplSource.Library] so dts2repl is not required.
 * The set is data-driven; the corpus / fact-sheet agent refines it.
 */
/**
 * Renode library platforms keyed by normalized board id. The value is the path of the
 * built-in `.repl` inside a Renode installation (referenced as `@<path>` in `.resc`).
 */
public val RENODE_LIBRARY_REPL: Map<String, String> = mapOf(
    "nrf52840dk_nrf52840" to "platforms/cpus/nrf52840.repl",
    "nrf52840dk" to "platforms/cpus/nrf52840.repl",
    "stm32f4_discovery" to "platforms/boards/stm32f4_discovery.repl",
    "stm32f4_disco" to "platforms/boards/stm32f4_discovery.repl",
)

internal val RENODE_LIBRARY_BOARDS: Set<String> = RENODE_LIBRARY_REPL.keys

/**
 * Resolves the Renode built-in `.repl` path for [boardId], applying the same
 * slash-qualifier prefix normalization as [lookupBoard] (e.g. `nrf52840dk/nrf52840`
 * matches via its underscore-joined form, `stm32f4_disco/stm32f407xg` via its
 * `stm32f4_disco` prefix). Returns null when the board is not in Renode's library.
 */
public fun renodeLibraryReplFor(boardId: String): String? {
    RENODE_LIBRARY_REPL[boardId]?.let { return it }
    val segments = boardId.split('/').filter { it.isNotEmpty() }
    for (take in segments.size downTo 1) {
        RENODE_LIBRARY_REPL[segments.take(take).joinToString("_")]?.let { return it }
    }
    return null
}

/** True when [boardId] resolves to a Renode library platform via [renodeLibraryReplFor]. */
internal fun isRenodeLibraryBoard(boardId: String): Boolean = renodeLibraryReplFor(boardId) != null

/**
 * Selects an emulator backend for the given [TargetProfile].
 *
 * Selection rules (in priority order):
 *
 * 1. **Known QEMU board**: [boardId] resolves via [lookupBoard] to a [QemuBoardEntry].
 *    Backend = QEMU with the table's machine/binary/extra args.
 *
 * 2. **CortexA**: architecture-class QEMU mapping — `-machine virt` with the
 *    appropriate `qemu-system-aarch64` (ELF64) or `qemu-system-arm` (ELF32).
 *
 * 3. **CortexM / RiscV / unknown MCU**: Backend = Renode, platform from dts2repl
 *    (Generated) or Renode's built-in library if the board is in [RENODE_LIBRARY_BOARDS].
 *
 * 4. **Other** with no board table hit → [BackendSelection.Fail].
 *
 * When [forced] is non-null the user's explicit choice overrides the recommendation.
 * If the forced backend cannot support the target, [BackendSelection.Fail] is returned
 * with the canonical failure copy (to be displayed at run-config validation time, never
 * at process start).
 */
public fun selectBackend(profile: TargetProfile, forced: Backend? = null): BackendSelection {
    val entry = profile.boardId?.let { lookupBoard(it) }

    val recommended: Backend
    val naturalResult: BackendSelection.Ok

    when {
        // Rule 1: known QEMU board.
        entry != null -> {
            recommended = Backend.Qemu
            naturalResult = BackendSelection.Ok(
                backend = Backend.Qemu,
                recommended = Backend.Qemu,
                qemuMachine = entry.machine,
                qemuBinary = entry.binary,
                qemuExtraArgs = entry.extraArgs,
                replSource = null,
                rationale = "Board '${profile.boardId}' maps to QEMU machine '${entry.machine}'.",
            )
        }

        // Rule 2: CortexA — QEMU virt machine.
        profile.archClass == ArchClass.CortexA -> {
            recommended = Backend.Qemu
            val binary = if (profile.elfIs64Bit) QemuBinary.SystemAarch64 else QemuBinary.SystemArm
            naturalResult = BackendSelection.Ok(
                backend = Backend.Qemu,
                recommended = Backend.Qemu,
                qemuMachine = "virt",
                qemuBinary = binary,
                qemuExtraArgs = emptyList(),
                replSource = null,
                rationale = "Cortex-A target uses QEMU -machine virt.",
            )
        }

        // Rule 3: CortexM / RiscV / unknown MCU → Renode.
        profile.archClass == ArchClass.CortexM || profile.archClass == ArchClass.RiscV -> {
            recommended = Backend.Renode
            val replSource = if (profile.boardId != null && isRenodeLibraryBoard(profile.boardId)) {
                ReplSource.Library
            } else {
                ReplSource.Generated
            }
            val boardDesc = profile.boardId ?: profile.archClass.name
            naturalResult = BackendSelection.Ok(
                backend = Backend.Renode,
                recommended = Backend.Renode,
                qemuMachine = null,
                qemuBinary = null,
                qemuExtraArgs = emptyList(),
                replSource = replSource,
                rationale = "MCU target '$boardDesc' emulated via Renode" +
                    if (replSource == ReplSource.Library) " (built-in platform)." else " (generated platform from zephyr.dts).",
            )
        }

        // Rule 4: unknown arch, no board table hit → failure.
        else -> {
            val eMachine = profile.elfMachine
            return BackendSelection.Fail(
                reason = "Cannot run this firmware: ELF machine $eMachine maps to no supported" +
                    " emulator (arch class Other).",
                alternative = "No QEMU machine and no Renode platform are available for this target.",
            )
        }
    }

    if (forced == null || forced == recommended) return naturalResult

    // Forced backend differs from recommended — validate the forced choice.
    return validateForcedBackend(forced, naturalResult, profile)
}

private fun validateForcedBackend(
    forced: Backend,
    natural: BackendSelection.Ok,
    profile: TargetProfile,
): BackendSelection {
    val boardDesc = profile.boardId ?: profile.archClass.name

    return when {
        // Forcing Renode on a CortexA / application-class target.
        forced == Backend.Renode && profile.archClass == ArchClass.CortexA -> BackendSelection.Fail(
            reason = "Cannot run $boardDesc with Renode: this Cortex-A / application-class" +
                " target is QEMU-only.",
            alternative = "Switch the Emulator selector to QEMU (recommended) or Recommended.",
        )

        // Forcing QEMU on an MCU with no QEMU machine mapping.
        forced == Backend.Qemu && natural.recommended == Backend.Renode -> BackendSelection.Fail(
            reason = "Cannot run $boardDesc with QEMU: no QEMU machine models this SoC.",
            alternative = "Switch to Renode (recommended), which will generate a platform" +
                " from zephyr.dts.",
        )

        // Forcing QEMU on a known QEMU board is always fine.
        forced == Backend.Qemu -> natural.copy(backend = Backend.Qemu)

        // Forcing Renode on a QEMU-mapped MCU-class board: allow (e.g. user wants Renode for
        // peripheral fidelity). Determine replSource from board library membership.
        forced == Backend.Renode -> {
            val replSource = if (profile.boardId != null && isRenodeLibraryBoard(profile.boardId)) {
                ReplSource.Library
            } else {
                ReplSource.Generated
            }
            natural.copy(
                backend = Backend.Renode,
                qemuMachine = null,
                qemuBinary = null,
                qemuExtraArgs = emptyList(),
                replSource = replSource,
                rationale = "Renode forced by user for '$boardDesc'.",
            )
        }

        else -> natural
    }
}

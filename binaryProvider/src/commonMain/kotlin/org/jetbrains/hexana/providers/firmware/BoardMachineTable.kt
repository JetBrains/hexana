package org.jetbrains.hexana.providers.firmware

/**
 * A curated entry mapping a Zephyr board ID to the QEMU machine parameters
 * needed to emulate it.
 *
 * @param binary The `qemu-system-*` binary variant.
 * @param machine The QEMU `-machine` argument value.
 * @param extraArgs Additional QEMU arguments (e.g. `-cpu` overrides). Empty by default.
 */
public data class QemuBoardEntry(
    val binary: QemuBinary,
    val machine: String,
    val extraArgs: List<String> = emptyList(),
)

/**
 * Curated map from Zephyr board identifier to [QemuBoardEntry].
 *
 * Sources: Zephyr `boards/<arch>/<board>/board.cmake` files and the QEMU machine
 * list (`qemu-system-arm -machine help`).
 *
 * The `qemu_cortex_m3` machine string is `lm3s6965evb` (TI Stellaris LM3S6965),
 * confirmed from Zephyr board.cmake and `qemu-system-arm -machine help`.
 *
 * Add rows as the corpus grows; this is the single source of truth for board-to-machine
 * mapping used by [selectBackend].
 */
public val BOARD_MACHINE_TABLE: Map<String, QemuBoardEntry> = mapOf(
    "qemu_cortex_m3" to QemuBoardEntry(
        binary = QemuBinary.SystemArm,
        machine = "lm3s6965evb",
        extraArgs = listOf("-cpu", "cortex-m3"),
    ),
    "mps2_an385" to QemuBoardEntry(
        binary = QemuBinary.SystemArm,
        machine = "mps2-an385",
        extraArgs = listOf("-cpu", "cortex-m3"),
    ),
    "mps2_an521" to QemuBoardEntry(
        binary = QemuBinary.SystemArm,
        machine = "mps2-an521",
        extraArgs = listOf("-cpu", "cortex-m33"),
    ),
    "qemu_cortex_a53" to QemuBoardEntry(
        binary = QemuBinary.SystemAarch64,
        machine = "virt,secure=on,gic-version=3",
        extraArgs = listOf("-cpu", "cortex-a53"),
    ),
    "qemu_riscv32" to QemuBoardEntry(
        binary = QemuBinary.SystemRiscv32,
        machine = "virt",
        extraArgs = listOf("-bios", "none", "-m", "256"),
    ),
    "qemu_riscv64" to QemuBoardEntry(
        binary = QemuBinary.SystemRiscv64,
        machine = "virt",
        extraArgs = listOf("-bios", "none", "-m", "256"),
    ),
)

/**
 * Looks up a [QemuBoardEntry] for the given [boardId], applying qualifier normalization.
 *
 * Zephyr board IDs sometimes appear with slash-delimited qualifiers, e.g.:
 * - `"qemu_cortex_m3/ti_lm3s6965"` → look up `"qemu_cortex_m3"` first, then `"qemu_cortex_m3_ti_lm3s6965"`.
 * - `"mps2/an521/cpu0"` → tries, from longest to shortest prefix: `"mps2_an521_cpu0"`,
 *   `"mps2_an521"`, `"mps2"`.
 *
 * Normalization steps tried in order:
 * 1. Exact match.
 * 2. All non-empty prefixes of the slash-split parts, joined with underscores, from longest to shortest.
 *
 * Returns null when no entry matches after normalization.
 */
public fun lookupBoard(boardId: String): QemuBoardEntry? {
    BOARD_MACHINE_TABLE[boardId]?.let { return it }

    val parts = boardId.split('/')
    if (parts.size > 1) {
        // Try all prefix lengths from longest to shortest (e.g. mps2_an521_cpu0, mps2_an521, mps2).
        for (len in parts.size downTo 1) {
            val candidate = parts.subList(0, len).joinToString("_")
            BOARD_MACHINE_TABLE[candidate]?.let { return it }
        }
    }
    return null
}

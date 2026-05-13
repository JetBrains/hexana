package org.jetbrains.hexana.ui.panel

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import java.awt.event.InputEvent
import javax.swing.KeyStroke

/**
 * Bridge from IntelliJ's [KeymapManager] to Compose's `onPreviewKeyEvent`.
 *
 * Compose-for-Desktop's [KeyEvent] wraps the underlying AWT [java.awt.event.KeyEvent]
 * (accessible via `nativeKeyEvent`). We compare that to the [KeyStroke] objects
 * the IDE's keymap stores for built-in action IDs (`GotoDeclaration`, `Back`,
 * `GotoFile`, …). That way every keystroke the panel listens for follows the
 * user's chosen keymap — Eclipse, Emacs, Vim plugins, custom remaps — without
 * us hardcoding macOS / Windows defaults.
 *
 * Multi-stroke shortcuts (e.g. `Ctrl+X, Ctrl+S` in Emacs) currently match only
 * their first stroke. That's good enough for navigation shortcuts (the IDE
 * itself uses single strokes for these by default); upgrading to true
 * multi-stroke needs a small state machine and is deferred.
 */
internal object KeymapShortcuts {

    /**
     * Returns predicates that fire on Compose key events matching ANY shortcut
     * the active keymap has bound to [actionId]. Empty when the keymap has no
     * binding for that action — the caller decides whether to install a
     * fallback default or leave the feature un-keyed.
     */
    private val cache = HashMap<String, List<(KeyEvent) -> Boolean>>()

    fun matchersFor(actionId: String): List<(KeyEvent) -> Boolean> {
        cache[actionId]?.let { return it }
        val keymap = KeymapManager.getInstance()?.activeKeymap
        if (keymap == null) {
            org.jetbrains.hexana.HexanaLog.LOG.warn(
                "[wat-nav-debug] keymap MISSING — KeymapManager.getInstance().activeKeymap is null " +
                    "for actionId=$actionId",
            )
            return emptyList()
        }
        val strokes = keymap.getShortcuts(actionId)
            .filterIsInstance<KeyboardShortcut>()
            .mapNotNull { it.firstKeyStroke }
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] keymap actionId=$actionId strokes=${strokes.map {
                "(keyCode=${it.keyCode} modifiers=0x${it.modifiers.toString(16)})"
            }}",
        )
        val matchers = strokes.map(::matcherFor)
        cache[actionId] = matchers
        return matchers
    }

    /**
     * Convenience: returns `true` when [event] matches any binding of
     * [actionId]. The caller does NOT need to remember the matcher list.
     * Suitable for one-off checks inside a key handler; for high-frequency
     * call sites, cache [matchersFor] once.
     */
    fun matches(event: KeyEvent, actionId: String): Boolean {
        if (isModifierOnly(event)) return false
        return matchersFor(actionId).any { it(event) }
    }

    /**
     * True when [event] is just a modifier key going down (Cmd alone, Shift
     * alone, …). Useful at the top of `onPreviewKeyEvent` handlers — Compose
     * still dispatches every modifier press, but no shortcut can fire from
     * one, so handlers can bail before doing any matcher work or hitting
     * breakpoints meant for actual combos.
     *
     * Uses Compose's [Key] constants rather than `nativeKeyEvent.keyCode`:
     * some Compose key events arrive with a null native event (focus traversal
     * synthetics, IDE accessibility plumbing), and a null-safe AWT path would
     * silently let modifier-only events through. [Key] is always populated.
     */
    fun isModifierOnly(event: KeyEvent): Boolean = event.key in MODIFIER_KEYS

    private val MODIFIER_KEYS: Set<Key> = setOf(
        Key.ShiftLeft, Key.ShiftRight,
        Key.CtrlLeft, Key.CtrlRight,
        Key.AltLeft, Key.AltRight,
        Key.MetaLeft, Key.MetaRight,
        Key.Function,
    )

    /**
     * Convert one [KeyStroke] into a Compose [KeyEvent] predicate.
     *
     * The IntelliJ Compose host can dispatch key events with `nativeKeyEvent
     * == null` (observed in idea.log: `awtKeyCode=null awtModifiersEx=null`).
     * The earlier AWT-based comparison silently failed for those events
     * because the safe-cast turned the whole stroke check into `false`. So
     * we read the target key + modifier flags out of the [KeyStroke] at
     * construction time and compare against Compose's own [Key] +
     * `isXxxPressed` properties at runtime — both are populated regardless
     * of the native event.
     *
     * Both legacy (`META_MASK`) and modern (`META_DOWN_MASK`) modifier bits
     * are checked because [KeyStroke] may carry either flavour depending on
     * how the shortcut was constructed inside the keymap.
     */
    private fun matcherFor(stroke: KeyStroke): (KeyEvent) -> Boolean {
        // IntelliJ's Compose host packs `(keyLocation shl 32) | awtKeyCode`
        // into `Key.keyCode`, so a raw inline-class equality against
        // `Key(awtKeyCode.toLong())` fails for every key. Compare only the
        // lower 32 bits — that's the AWT virtual key code, which is what the
        // IDE keymap stores in `KeyStroke.keyCode`.
        val targetVk: Int = stroke.keyCode
        val mods = stroke.modifiers
        val needMeta = (mods and (InputEvent.META_DOWN_MASK or InputEvent.META_MASK)) != 0
        val needCtrl = (mods and (InputEvent.CTRL_DOWN_MASK or InputEvent.CTRL_MASK)) != 0
        val needShift = (mods and (InputEvent.SHIFT_DOWN_MASK or InputEvent.SHIFT_MASK)) != 0
        val needAlt = (mods and (InputEvent.ALT_DOWN_MASK or InputEvent.ALT_MASK)) != 0
        return { event ->
            val eventVk = event.key.keyCode.toInt()
            val ok = eventVk == targetVk &&
                event.isMetaPressed == needMeta &&
                event.isCtrlPressed == needCtrl &&
                event.isShiftPressed == needShift &&
                event.isAltPressed == needAlt
            // Diagnostic — only log on a near-miss (right key, wrong
            // modifiers) so the log isn't flooded by every keystroke.
            if (!ok && eventVk == targetVk) {
                org.jetbrains.hexana.HexanaLog.LOG.warn(
                    "[wat-nav-debug] matcher near-miss vk=$eventVk " +
                        "isMeta=${event.isMetaPressed}(need=$needMeta) " +
                        "isCtrl=${event.isCtrlPressed}(need=$needCtrl) " +
                        "isShift=${event.isShiftPressed}(need=$needShift) " +
                        "isAlt=${event.isAltPressed}(need=$needAlt) " +
                        "strokeMods=0x${mods.toString(16)}",
                )
            }
            ok
        }
    }
}

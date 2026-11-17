package org.jetbrains.hexana

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Hexana's own plugin coordinates, resolved from the plugin descriptor.
 *
 * Single source of truth: this lookup was previously copy-pasted into the
 * what's-new, feedback-context, and issue-link paths, and the PostHog
 * `plugin_version` property would have made a fourth copy. The descriptor is
 * unavailable in some test fixtures and when the plugin is loaded outside a
 * normal install, which is what [UNKNOWN_VERSION] covers.
 */
object HexanaPluginInfo {
    const val PLUGIN_ID: String = "org.jetbrains.hexana"

    /** Reported as the version when the plugin descriptor cannot be resolved. */
    const val UNKNOWN_VERSION: String = "unknown"

    val descriptor: IdeaPluginDescriptor?
        get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))

    /** The plugin version, or [UNKNOWN_VERSION] when the descriptor is unavailable. */
    val version: String
        get() = descriptor?.version ?: UNKNOWN_VERSION
}

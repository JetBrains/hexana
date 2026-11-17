package org.jetbrains.hexana.analytics

import com.intellij.openapi.application.ApplicationInfo
import org.jetbrains.hexana.HexanaPluginInfo

/**
 * Properties attached to every event captured from the IDE surface.
 *
 * `surface` is a fallback: events that set their own still win, because
 * `PostHogJvmClient` merges `globalProperties + event.properties`. It matters
 * for the events that carry none — `wasm_unparsed_opcodes` is the current one —
 * which would otherwise arrive with no surface at all and drop out of any
 * surface-segmented query.
 *
 * `plugin_version` is what makes a change in the numbers attributable to a
 * release rather than to a change in user behaviour.
 */
internal fun ideAnalyticsGlobalProperties(
    appInfo: ApplicationInfo = ApplicationInfo.getInstance(),
    pluginVersion: String = HexanaPluginInfo.version,
): Map<String, Any> = mapOf(
    "surface" to HexanaEvent.Surface.IDE_PLUGIN.value,
    "ide_type" to appInfo.versionName,
    "ide_build" to appInfo.build.asString(),
    "plugin_version" to pluginVersion,
)

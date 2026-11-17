package org.jetbrains.hexana.webapp

import kotlinx.browser.document
import kotlin.js.JSON

private const val RELEASE_INFO_PLACEHOLDER = "__HEXANA_RELEASE_INFO__"

internal data class WebappReleaseInfo(
    val version: String,
    val versionSource: String,
    val revision: String,
    val changelog: String,
    val repositoryUrl: String,
    val websiteUrl: String,
)

internal val WebappReleaseInfo.isReleaseImage: Boolean
    get() = when (versionSource) {
        "webapp/gradle.properties" -> true
        "git-revision", "snapshot", "development" -> false
        else -> error("Bundled webapp release information has an unsupported versionSource: $versionSource")
    }

internal fun bundledWebappReleaseInfo(): WebappReleaseInfo {
    val source = requireNotNull(document.getElementById("hexana-release-info")?.textContent) {
        "Bundled webapp release information is missing"
    }
    if (source == RELEASE_INFO_PLACEHOLDER) {
        return WebappReleaseInfo(
            version = "development",
            versionSource = "development",
            revision = "unavailable",
            changelog = "",
            repositoryUrl = "https://github.com/JetBrains/hexana",
            websiteUrl = "https://jetbrains.github.io/hexana/",
        )
    }
    val metadata: dynamic = JSON.parse<dynamic>(source)
    fun value(value: dynamic, name: String): String = value as? String
        ?: error("Bundled webapp release information is missing $name")
    return WebappReleaseInfo(
        version = value(metadata.version, "version"),
        versionSource = value(metadata.versionSource, "versionSource"),
        revision = value(metadata.revision, "revision"),
        changelog = value(metadata.changelog, "changelog"),
        repositoryUrl = value(metadata.repositoryUrl, "repositoryUrl"),
        websiteUrl = value(metadata.websiteUrl, "websiteUrl"),
    )
}

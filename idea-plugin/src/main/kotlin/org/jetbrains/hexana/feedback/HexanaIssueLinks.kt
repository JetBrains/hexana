package org.jetbrains.hexana.feedback

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.HexanaPluginInfo
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.ln
import kotlin.math.pow

internal object HexanaIssueLinks {
    private const val NewIssueUrl = "https://github.com/JetBrains/hexana/issues/new"

    fun bugReportUrl(binaryProvider: BinaryProvider, isDarkTheme: Boolean): String = buildIssueUrl(
        title = "Bug: ",
        body = bugReportBody(diagnostics = feedbackContext(binaryProvider, isDarkTheme)),
    )

    fun bugReportUrl(contextPreview: TechnicalContextPreview): String = buildIssueUrl(
        title = "Bug: ",
        body = bugReportBody(diagnostics = feedbackContext(contextPreview)),
    )

    private fun bugReportBody(diagnostics: String): String = """
        |## Summary
        |Describe the problem.
        |
        |## Steps to reproduce
        |1.
        |2.
        |3.
        |
        |## Expected result
        |Describe what should have happened.
        |
        |## Actual result
        |Describe what happened instead.
        |
        |## Diagnostics
        |```text
        |$diagnostics
        |```
        |
        |## Notes
        |- Hexana does not attach file contents automatically.
        |- Remove any sensitive information before submitting.
    """.trimMargin()

    private fun buildIssueUrl(title: String, body: String): String {
        val encodedQuery = mapOf(
            "title" to title,
            "body" to body,
        ).entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "$NewIssueUrl?$encodedQuery"
    }

    private fun feedbackContext(binaryProvider: BinaryProvider, isDarkTheme: Boolean): String {
        val appInfo = ApplicationInfo.getInstance()
        val pluginVersion = HexanaPluginInfo.version

        return listOf(
            "- IDE: ${appInfo.versionName} ${appInfo.fullVersion}",
            "- IDE build: ${appInfo.build.asString()}",
            "- Hexana plugin: $pluginVersion",
            "- OS: ${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}",
            "- Theme: ${if (isDarkTheme) "Dark" else "Light"}",
            "- Binary kind: ${binaryProvider.binaryKind()}",
            "- File extension: ${binaryProvider.fileExtension()}",
            "- File size: ${binaryProvider.fileSize()}",
        ).joinToString("\n")
    }

    private fun feedbackContext(contextPreview: TechnicalContextPreview): String {
        val appInfo = ApplicationInfo.getInstance()

        return buildList {
            add("- IDE: ${contextPreview.ideProduct ?: "unknown"}")
            add("- IDE build: ${appInfo.build.asString()}")
            add("- Hexana plugin: ${contextPreview.hexanaPluginVersion ?: "unknown"}")
            add("- OS: ${contextPreview.os ?: "unknown"}")
            add("- Opened from: feedback note")
            contextPreview.fileTypes.entries
                .sortedBy { it.key }
                .forEach { (extension, count) -> add("- File type count: $extension = $count") }
        }.joinToString("\n")
    }

    private fun BinaryProvider.binaryKind(): String = when (this) {
        is WasmComponentBinaryProvider -> "WebAssembly component"
        is WasmBinaryProvider -> "WebAssembly module"
        is GenericBinaryProvider -> "Generic binary"
        else -> this::class.simpleName ?: "Binary"
    }

    private fun BinaryProvider.fileExtension(): String =
        (this as? BinaryVirtualFileProvider)?.file?.extension?.takeIf { it.isNotBlank() } ?: "unknown"

    private fun BinaryProvider.fileSize(): String {
        val fileSizeBytes = (this as? BinaryVirtualFileProvider)?.file?.length ?: bytes.limit().toLong()
        return humanReadableByteCount(fileSizeBytes)
    }

    private fun humanReadableByteCount(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val unit = 1024
        val exponent = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt().coerceAtMost(4)
        val value = bytes / unit.toDouble().pow(exponent.toDouble())
        val suffix = listOf("KB", "MB", "GB", "TB")[exponent - 1]
        return String.format("%.1f %s", value, suffix)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

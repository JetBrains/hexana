package org.jetbrains.hexana.feedback

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.hexana.HexanaPluginInfo
import org.jetbrains.hexana.WasmFileType

internal fun createTechnicalContextPreview(project: Project): TechnicalContextPreview {
    val appInfo = ApplicationInfo.getInstance()
    val pluginVersion = HexanaPluginInfo.version
    val wasmFileCount = runCatching {
        FileTypeIndex.getFiles(WasmFileType.INSTANCE, GlobalSearchScope.projectScope(project)).size
    }.getOrDefault(0)

    return TechnicalContextPreview(
        ideProduct = "${appInfo.versionName} ${appInfo.fullVersion}",
        hexanaPluginVersion = pluginVersion,
        os = "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}",
        fileTypes = mapOf(".wasm" to wasmFileCount),
    )
}

internal fun createFeedbackMarkdown(state: SurveyUiState, contextPreview: TechnicalContextPreview): String = buildString {
    appendLine("## What were you trying to do?")
    appendLine(state.selectedTasks.toLabelsOrPlaceholder())
    appendLine()
    appendLine("## Did Hexana help?")
    appendLine(state.outcome?.label ?: "_No answer_")
    appendLine()
    if (state.blockers.isNotEmpty()) {
        appendLine("## What got in the way?")
        appendLine(state.blockers.toLabelsOrPlaceholder())
        if (Blocker.Other in state.blockers) {
            appendLine("- Other: ${state.blockerOtherText.takeIf { it.isNotBlank() } ?: "No answer"}")
        }
        appendLine()
    }
    appendLine("## What should Hexana improve first?")
    appendLine(state.improvementText.takeIf { it.isNotBlank() } ?: "_No answer_")
    appendLine()
    appendLine("## More context")
    appendLine("- Alternative tools: ${state.alternativeTools.toLabelsOrPlaceholder()}")
    if (AlternativeTool.Other in state.alternativeTools) {
        appendLine("- Other alternative tool: ${state.alternativeToolOtherText.takeIf { it.isNotBlank() } ?: "No answer"}")
    }
    appendLine("- Work type: ${state.workTypes.toLabelsOrPlaceholder()}")
    if (WorkType.Other in state.workTypes) {
        appendLine("- Other work type: ${state.workTypeOtherText.takeIf { it.isNotBlank() } ?: "No answer"}")
    }
    appendLine("- Artifact inspection frequency: ${state.frequency?.label ?: "No answer"}")
    appendLine()
    appendLine("## Interview follow-up")
    appendLine("- Open to interview: ${state.interviewChoice?.label ?: "No answer"}")
    if (state.interviewChoice == InterviewChoice.Yes) {
        appendLine("- Preferred contact: ${state.contact.takeIf { it.isNotBlank() } ?: "No answer"}")
        appendLine("- Time zone: ${state.timeZone.takeIf { it.isNotBlank() } ?: "No answer"}")
        appendLine("- Topics: ${state.interviewTopics.toLabelsOrPlaceholder()}")
    }
    appendLine()
    appendLine("## Technical context")
    if (state.technicalContextEnabled) {
        appendLine("```json")
        appendLine(contextPreview.toJsonLike(enabled = true))
        appendLine("```")
    } else {
        appendLine("_Technical context disabled by user._")
    }
    appendLine()
    appendLine("_No file contents, file names, file paths, project names, or environment variables are included._")
}

internal fun createFeedbackCollectedDataPayload(
    state: SurveyUiState,
    contextPreview: TechnicalContextPreview,
): HexanaFeedbackCollectedData = HexanaFeedbackCollectedData(
    tasks = checkboxGroupPayload(
        options = TaskChoice.entries,
        selected = state.selectedTasks,
        other = TaskChoice.Other,
        optionId = TaskChoice::id,
    ),
    outcome = state.outcome?.id,
    blockers = checkboxGroupPayload(
        options = Blocker.entries,
        selected = state.blockers,
        other = Blocker.Other,
        otherText = state.blockerOtherText,
        optionId = Blocker::id,
    ),
    improvementText = state.improvementText.takeIf(String::isNotBlank),
    alternativeTools = checkboxGroupPayload(
        options = AlternativeTool.entries,
        selected = state.alternativeTools,
        other = AlternativeTool.Other,
        otherText = state.alternativeToolOtherText,
        optionId = AlternativeTool::id,
    ),
    workTypes = checkboxGroupPayload(
        options = WorkType.entries,
        selected = state.workTypes,
        other = WorkType.Other,
        otherText = state.workTypeOtherText,
        optionId = WorkType::id,
    ),
    frequency = state.frequency?.id,
    interviewChoice = state.interviewChoice?.id,
    timeZone = state.timeZone.takeIfSelectedAndNotBlank(state.interviewChoice == InterviewChoice.Yes),
    interviewTopics = if (state.interviewChoice == InterviewChoice.Yes) {
        checkboxGroupPayload(
            options = InterviewTopic.entries,
            selected = state.interviewTopics,
            other = InterviewTopic.Other,
            optionId = InterviewTopic::id,
        )
    } else {
        emptyCheckboxGroupPayload(InterviewTopic.entries, InterviewTopic.Other, InterviewTopic::id)
    },
    technicalContextEnabled = state.technicalContextEnabled,
    technicalContext = contextPreview.toPayload().takeIf { state.technicalContextEnabled },
)

internal fun TechnicalContextPreview.toJsonLike(enabled: Boolean): String {
    if (!enabled) return "{}"

    return buildString {
        appendLine("{")
        appendLine("  \"ideProduct\": ${ideProduct.toJsonValue()},")
        appendLine("  \"hexanaPluginVersion\": ${hexanaPluginVersion.toJsonValue()},")
        appendLine("  \"os\": ${os.toJsonValue()},")
        appendLine("  \"fileTypes\": {")
        fileTypes.entries.forEachIndexed { index, (extension, count) ->
            val comma = if (index == fileTypes.size - 1) "" else ","
            appendLine("    ${extension.toJsonValue()}: $count$comma")
        }
        appendLine("  }")
        append("}")
    }
}

private fun String?.toJsonValue(): String = this?.let { value ->
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
} ?: "null"

private fun TechnicalContextPreview.toPayload(): HexanaFeedbackTechnicalContext = HexanaFeedbackTechnicalContext(
    ideProduct = ideProduct,
    hexanaPluginVersion = hexanaPluginVersion,
    os = os,
    fileTypes = fileTypes,
)

private fun String.takeIfSelectedAndNotBlank(selected: Boolean): String? =
    takeIf { selected && it.isNotBlank() }

private fun <T> checkboxGroupPayload(
    options: List<T>,
    selected: Set<T>,
    other: T,
    optionId: (T) -> String,
    otherText: String? = null,
): JsonObject = buildJsonObject {
    options.filterNot { it == other }.forEach { option ->
        put(optionId(option), option in selected)
    }
    if (other in selected) {
        put("other", otherText.orEmpty())
    }
}

private fun <T> emptyCheckboxGroupPayload(options: List<T>, other: T, optionId: (T) -> String): JsonObject =
    checkboxGroupPayload(options = options, selected = emptySet(), other = other, optionId = optionId)

private fun Set<Enum<*>>.toLabelsOrPlaceholder(): String =
    if (isEmpty()) {
        "_No answer_"
    } else {
        joinToString("\n") { "- ${it.labelText()}" }
    }

private fun Enum<*>.labelText(): String = when (this) {
    is TaskChoice -> label
    is Blocker -> label
    is AlternativeTool -> label
    is WorkType -> label
    is InterviewTopic -> label
    else -> name
}

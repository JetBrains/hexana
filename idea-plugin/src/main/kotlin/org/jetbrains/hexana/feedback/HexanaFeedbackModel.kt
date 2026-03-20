package org.jetbrains.hexana.feedback

internal data class SurveyUiState(
    val selectedTasks: Set<TaskChoice> = emptySet(),
    val outcome: Outcome? = null,
    val blockers: Set<Blocker> = emptySet(),
    val blockerOtherText: String = "",
    val improvementText: String = "",
    val alternativeTools: Set<AlternativeTool> = emptySet(),
    val alternativeToolOtherText: String = "",
    val workTypes: Set<WorkType> = emptySet(),
    val workTypeOtherText: String = "",
    val frequency: Frequency? = null,
    val interviewChoice: InterviewChoice? = null,
    val contact: String = "",
    val timeZone: String = "",
    val interviewTopics: Set<InterviewTopic> = emptySet(),
    val optionalContextExpanded: Boolean = false,
    val technicalContextEnabled: Boolean = true,
    val technicalContextPreviewExpanded: Boolean = false,
    val submitInProgress: Boolean = false,
    val submitFailed: Boolean = false,
    val submitted: Boolean = false,
) {
    val hasMeaningfulInput: Boolean
        get() = selectedTasks.isNotEmpty() ||
                outcome != null ||
                blockers.isNotEmpty() ||
                blockerOtherText.isNotBlank() ||
                improvementText.isNotBlank() ||
                alternativeTools.isNotEmpty() ||
                alternativeToolOtherText.isNotBlank() ||
                workTypes.isNotEmpty() ||
                workTypeOtherText.isNotBlank() ||
                frequency != null ||
                interviewChoice != null ||
                contact.isNotBlank() ||
                timeZone.isNotBlank() ||
                interviewTopics.isNotEmpty() ||
                !technicalContextEnabled
}

internal sealed interface SurveyAction {
    data class ToggleTask(val task: TaskChoice) : SurveyAction
    data class SelectOutcome(val outcome: Outcome) : SurveyAction
    data class ToggleBlocker(val blocker: Blocker) : SurveyAction
    data class ChangeBlockerOtherText(val text: String) : SurveyAction
    data class ChangeImprovementText(val text: String) : SurveyAction
    data object ToggleOptionalContext : SurveyAction
    data class ToggleAlternativeTool(val tool: AlternativeTool) : SurveyAction
    data class ChangeAlternativeToolOtherText(val text: String) : SurveyAction
    data class ToggleWorkType(val workType: WorkType) : SurveyAction
    data class ChangeWorkTypeOtherText(val text: String) : SurveyAction
    data class SelectFrequency(val frequency: Frequency) : SurveyAction
    data class SelectInterviewChoice(val choice: InterviewChoice) : SurveyAction
    data class ChangeContact(val contact: String) : SurveyAction
    data class ChangeTimeZone(val timeZone: String) : SurveyAction
    data class ToggleInterviewTopic(val topic: InterviewTopic) : SurveyAction
    data class SetTechnicalContextEnabled(val enabled: Boolean) : SurveyAction
    data object ToggleTechnicalContextPreview : SurveyAction
    data object Submit : SurveyAction
    data object SubmitSucceeded : SurveyAction
    data object SubmitFailed : SurveyAction
    data object Later : SurveyAction
    data object OpenIssue : SurveyAction
    data object CloseTab : SurveyAction
}

internal enum class TaskChoice(
    val id: String,
    val label: String,
    val glyph: String,
) {
    ImportsExports("imports_exports", "Inspect imports / exports", "io"),
    ModuleStructure("module_structure", "Understand module structure", "mod"),
    MissingSymbol("missing_symbol", "Debug a missing symbol", "sym"),
    DebugInfo("debug_info_source_mapping", "Inspect debug info / source mapping", "dbg"),
    CompareBinaries("compare_binaries", "Compare builds or binaries", "diff"),
    UnfamiliarBinary("unfamiliar_binary", "Explore an unfamiliar binary", "?"),
    Other("other", "Other", "etc"),
}

internal enum class Outcome(
    val id: String,
    val label: String,
    val subtext: String,
) {
    Yes("yes", "Yes", "I got there"),
    Partly("partly", "Partly", "Some friction"),
    No("no", "No", "I got blocked"),
    Exploring("exploring", "Just exploring", "I was looking around"),
}

internal enum class Blocker(
    val id: String,
    val label: String,
) {
    CouldNotFindInfo("could_not_find_information", "Couldn’t find information"),
    NavigationUnclear("navigation_unclear", "Navigation unclear"),
    DataHardToUnderstand("data_hard_to_understand", "Data hard to understand"),
    TooSlow("too_slow", "Too slow"),
    BugOrError("bug_or_error", "Bug or error"),
    Other("other", "Other"),
}

internal enum class AlternativeTool(
    val id: String,
    val label: String,
    val mark: String,
) {
    CliTools("cli_tools", "CLI tools", ">_"),
    CustomScripts("custom_scripts", "Custom scripts", "{}"),
    Ghidra("ghidra", "Ghidra", "GH"),
    IdaPro("ida_pro", "IDA Pro", "IDA"),
    BinaryNinja("binary_ninja", "Binary Ninja", "BN"),
    AnotherIde("another_ide_plugin", "Another IDE/plugin", "IDE"),
    NothingGood("nothing_good", "Nothing good", "∅"),
    Other("other", "Other", "+"),
}

internal enum class WorkType(
    val id: String,
    val label: String,
) {
    WasmDeveloper("wasm_developer", "WebAssembly developer"),
    CompilerRuntimeEngineer("compiler_runtime_engineer", "Compiler/runtime engineer"),
    JvmNativeEngineer("jvm_native_engineer", "JVM/native engineer"),
    SecurityReverseEngineering("security_reverse_engineering", "Security/reverse engineering"),
    EmbeddedFirmware("embedded_firmware", "Embedded/firmware"),
    BuildCiEngineer("build_ci_engineer", "Build/CI engineer"),
    Other("other", "Other"),
}

internal enum class Frequency(
    val id: String,
    val label: String,
) {
    Daily("daily", "Daily"),
    Weekly("weekly", "Weekly"),
    Monthly("monthly", "Monthly"),
    Rarely("rarely", "Rarely"),
}

internal enum class InterviewChoice(
    val id: String,
    val label: String,
) {
    Yes("yes", "Yes"),
    MaybeLater("maybe_later", "Maybe later"),
    No("no", "No"),
}

internal enum class InterviewTopic(
    val id: String,
    val label: String,
) {
    WasmNavigation("wasm_navigation", "Wasm navigation"),
    ImportsExports("imports_exports", "Imports / exports"),
    DebugInfo("debug_info", "Debug info"),
    BinaryDiffing("binary_diffing", "Binary diffing"),
    McpAiWorkflows("mcp_ai_workflows", "MCP / AI workflows"),
    Performance("performance", "Performance"),
    Other("other", "Other"),
}

internal data class TechnicalContextPreview(
    val ideProduct: String?,
    val hexanaPluginVersion: String?,
    val os: String?,
    val fileTypes: Map<String, Int>,
)

internal fun reduceSurveyState(state: SurveyUiState, action: SurveyAction): SurveyUiState = when (action) {
    is SurveyAction.ToggleTask -> state.copy(selectedTasks = state.selectedTasks.toggle(action.task))
    is SurveyAction.SelectOutcome -> {
        val blockers = if (action.outcome == Outcome.Partly || action.outcome == Outcome.No) {
            state.blockers
        } else {
            emptySet()
        }
        state.copy(
            outcome = action.outcome,
            blockers = blockers,
            blockerOtherText = if (Blocker.Other in blockers) state.blockerOtherText else "",
        )
    }
    is SurveyAction.ToggleBlocker -> {
        val blockers = state.blockers.toggle(action.blocker)
        state.copy(
            blockers = blockers,
            blockerOtherText = if (Blocker.Other in blockers) state.blockerOtherText else "",
        )
    }
    is SurveyAction.ChangeBlockerOtherText -> state.copy(blockerOtherText = action.text)
    is SurveyAction.ChangeImprovementText -> state.copy(improvementText = action.text)
    SurveyAction.ToggleOptionalContext -> state.copy(optionalContextExpanded = !state.optionalContextExpanded)
    is SurveyAction.ToggleAlternativeTool -> {
        val alternativeTools = state.alternativeTools.toggle(action.tool)
        state.copy(
            alternativeTools = alternativeTools,
            alternativeToolOtherText = if (AlternativeTool.Other in alternativeTools) {
                state.alternativeToolOtherText
            } else {
                ""
            },
        )
    }
    is SurveyAction.ChangeAlternativeToolOtherText -> state.copy(alternativeToolOtherText = action.text)
    is SurveyAction.ToggleWorkType -> {
        val workTypes = state.workTypes.toggle(action.workType)
        state.copy(
            workTypes = workTypes,
            workTypeOtherText = if (WorkType.Other in workTypes) state.workTypeOtherText else "",
        )
    }
    is SurveyAction.ChangeWorkTypeOtherText -> state.copy(workTypeOtherText = action.text)
    is SurveyAction.SelectFrequency -> state.copy(
        frequency = if (state.frequency == action.frequency) null else action.frequency,
    )
    is SurveyAction.SelectInterviewChoice -> {
        val resetContact = action.choice != InterviewChoice.Yes
        state.copy(
            interviewChoice = action.choice,
            contact = if (resetContact) "" else state.contact,
            timeZone = if (resetContact) "" else state.timeZone,
            interviewTopics = if (resetContact) emptySet() else state.interviewTopics,
        )
    }
    is SurveyAction.ChangeContact -> state.copy(contact = action.contact)
    is SurveyAction.ChangeTimeZone -> state.copy(timeZone = action.timeZone)
    is SurveyAction.ToggleInterviewTopic -> state.copy(interviewTopics = state.interviewTopics.toggle(action.topic))
    is SurveyAction.SetTechnicalContextEnabled -> state.copy(technicalContextEnabled = action.enabled)
    SurveyAction.ToggleTechnicalContextPreview -> state.copy(
        technicalContextPreviewExpanded = !state.technicalContextPreviewExpanded,
    )
    SurveyAction.Submit -> state.copy(submitInProgress = true, submitFailed = false)
    SurveyAction.SubmitSucceeded -> state.copy(submitInProgress = false, submitFailed = false, submitted = true)
    SurveyAction.SubmitFailed -> state.copy(submitInProgress = false, submitFailed = true)
    SurveyAction.Later,
    SurveyAction.OpenIssue,
    SurveyAction.CloseTab -> state
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

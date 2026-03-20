package org.jetbrains.hexana.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.awt.Color as AwtColor
import javax.swing.UIManager
import kotlin.math.absoluteValue

@Composable
internal fun HexanaFeedbackScreen(
    initialState: SurveyUiState,
    technicalContextPreview: TechnicalContextPreview,
    onStateChange: (SurveyUiState) -> Unit,
    onAction: (SurveyAction, SurveyUiState, (SurveyAction) -> Unit) -> Unit,
) {
    var state by remember { mutableStateOf(initialState) }

    fun dispatch(action: SurveyAction) {
        val nextState = reduceSurveyState(state, action)
        state = nextState
        onStateChange(nextState)
        onAction(action, nextState, ::dispatch)
    }

    val colors = rememberFeedbackColors()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            HexPatternBackground(Modifier.matchParentSize())

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.submitted) {
                        ThankYouState(
                            showIssueFollowUp = Blocker.BugOrError in state.blockers,
                            onClose = { dispatch(SurveyAction.CloseTab) },
                            onIssue = { dispatch(SurveyAction.OpenIssue) },
                        )
                    } else {
                        SurveyHeroCard()
                        CoreFeedbackCard(state, ::dispatch)
                        OptionalContextSection(state, ::dispatch)
                        InterviewCard(state, ::dispatch)
                        TechnicalContextCard(
                            enabled = state.technicalContextEnabled,
                            previewExpanded = state.technicalContextPreviewExpanded,
                            contextPreview = technicalContextPreview,
                            onEnabledChange = { dispatch(SurveyAction.SetTechnicalContextEnabled(it)) },
                            onPreviewToggle = { dispatch(SurveyAction.ToggleTechnicalContextPreview) },
                        )
                        SurveyActions(
                            enabled = state.hasMeaningfulInput && !state.submitInProgress,
                            inProgress = state.submitInProgress,
                            failed = state.submitFailed,
                            onSubmit = { dispatch(SurveyAction.Submit) },
                            onLater = { dispatch(SurveyAction.Later) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HexPatternBackground(
    modifier: Modifier = Modifier,
    seed: Int = 0x48657861,
) {
    val colors = rememberFeedbackColors()
    val highContrast = rememberHighContrastMode()
    val tokens = remember(seed) { buildHexPatternTokens(seed) }
    val opacity = if (highContrast) 0.025f else if (JewelTheme.isDark) 0.11f else 0.10f

    BoxWithConstraints(
        modifier = modifier.clearAndSetSemantics { },
    ) {
        tokens.forEach { token ->
            Text(
                text = token.text,
                style = TextStyle(
                    color = colors.patternText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = token.fontSize.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .offset(x = maxWidth * token.x, y = maxHeight * token.y)
                    .alpha(opacity * token.opacity),
            )
        }
    }
}

@Composable
private fun SurveyHeroCard() {
    val colors = rememberFeedbackColors()

    SectionCard {
        BoxWithConstraints {
            val showDecor = maxWidth >= 620.dp

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.accentSoft)
                        .border(1.dp, colors.accentBorder, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Hx",
                        style = TextStyle(
                            color = colors.accent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                        ),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = "Core feedback · about 60 sec",
                        style = TextStyle(
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.accentSoft)
                            .border(1.dp, colors.accentBorder, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Text(
                        text = "Help shape Hexana",
                        style = TextStyle(
                            color = colors.text,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        text = "What were you trying to inspect today?",
                        style = TextStyle(
                            color = colors.text,
                            fontSize = 15.sp,
                        ),
                    )
                    Text(
                        text = "A few clicks are enough. Every question is optional. You can close this tab and come back later.",
                        style = TextStyle(
                            color = colors.muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        ),
                    )
                }

                if (showDecor) {
                    Text(
                        text = "00 61 73 6D",
                        style = TextStyle(
                            color = colors.patternText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier.alpha(if (rememberHighContrastMode()) 0.28f else 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CoreFeedbackCard(
    state: SurveyUiState,
    onAction: (SurveyAction) -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            SectionHeader(
                title = "Tell us what you were inspecting",
                helper = "Short, optional, and safe to leave blank.",
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestionLabel(HexanaFeedbackFields.Tasks.question)
                TaskChoiceGrid(
                    selected = state.selectedTasks,
                    onToggle = { onAction(SurveyAction.ToggleTask(it)) },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestionLabel(HexanaFeedbackFields.Outcome.question)
                OutcomeSelector(
                    selected = state.outcome,
                    onSelect = { onAction(SurveyAction.SelectOutcome(it)) },
                )
            }

            FrictionSelector(
                visible = state.outcome == Outcome.Partly || state.outcome == Outcome.No,
                selected = state.blockers,
                onToggle = { onAction(SurveyAction.ToggleBlocker(it)) },
                otherText = state.blockerOtherText,
                onOtherTextChange = { onAction(SurveyAction.ChangeBlockerOtherText(it)) },
                onIssueClick = { onAction(SurveyAction.OpenIssue) },
            )

            ImprovementScratchpad(
                text = state.improvementText,
                onTextChange = { onAction(SurveyAction.ChangeImprovementText(it)) },
            )
        }
    }
}

@Composable
internal fun TaskChoiceGrid(
    selected: Set<TaskChoice>,
    onToggle: (TaskChoice) -> Unit,
) {
    AdaptiveGrid(
        items = TaskChoice.entries.toList(),
        minTwoColumnWidth = 640.dp,
    ) { choice, modifier ->
        SelectableCard(
            selected = choice in selected,
            leading = choice.glyph,
            title = choice.label,
            onClick = { onToggle(choice) },
            modifier = modifier,
            role = Role.Checkbox,
        )
    }
}

@Composable
internal fun OutcomeSelector(
    selected: Outcome?,
    onSelect: (Outcome) -> Unit,
) {
    AdaptiveGrid(
        items = Outcome.entries.toList(),
        minTwoColumnWidth = 560.dp,
    ) { outcome, modifier ->
        SelectableCard(
            selected = selected == outcome,
            leading = outcome.label.take(1),
            title = outcome.label,
            subtitle = outcome.subtext,
            onClick = { onSelect(outcome) },
            modifier = modifier,
            role = Role.RadioButton,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FrictionSelector(
    visible: Boolean,
    selected: Set<Blocker>,
    onToggle: (Blocker) -> Unit,
    otherText: String,
    onOtherTextChange: (String) -> Unit,
    onIssueClick: () -> Unit,
) {
    AnimatedVisibility(visible = visible) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuestionLabel(HexanaFeedbackFields.Blockers.question)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Blocker.entries.forEach { blocker ->
                    SelectableChip(
                        selected = blocker in selected,
                        text = blocker.label,
                        onClick = { onToggle(blocker) },
                        role = Role.Checkbox,
                    )
                }
            }
            AnimatedVisibility(visible = Blocker.Other in selected) {
                SingleLineInput(
                    label = HexanaFeedbackFields.BlockerOtherText.question,
                    value = otherText,
                    onValueChange = onOtherTextChange,
                    placeholder = "Tell us briefly",
                )
            }
            AnimatedVisibility(visible = Blocker.BugOrError in selected) {
                IssueHint(onIssueClick = onIssueClick)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IssueHint(onIssueClick: () -> Unit) {
    val colors = rememberFeedbackColors()

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.issueSoft)
            .border(1.dp, colors.issueBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            text = "Hit a bug? You can also open an",
            style = TextStyle(color = colors.muted, fontSize = 12.sp),
        )
        Text(
            text = "issue",
            style = TextStyle(
                color = colors.issueLink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onIssueClick,
                )
                .focusable()
                .padding(horizontal = 2.dp),
        )
    }
}

@Composable
internal fun ImprovementScratchpad(
    text: String,
    onTextChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuestionLabel(HexanaFeedbackFields.ImprovementText.question)
        EditorLikeTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = "Example: “I wanted to jump from an export to its function body.”",
            minHeight = 126.dp,
        )
    }
}

@Composable
internal fun OptionalContextSection(
    state: SurveyUiState,
    onAction: (SurveyAction) -> Unit,
) {
    ExpandableCard(
        title = "More context",
        helper = "Optional, but useful for prioritizing Hexana work.",
        expanded = state.optionalContextExpanded,
        onToggle = { onAction(SurveyAction.ToggleOptionalContext) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestionLabel(HexanaFeedbackFields.AlternativeTool.question)
                AlternativeToolShelf(
                    selected = state.alternativeTools,
                    onToggle = { onAction(SurveyAction.ToggleAlternativeTool(it)) },
                )
                AnimatedVisibility(visible = AlternativeTool.Other in state.alternativeTools) {
                    SingleLineInput(
                        label = HexanaFeedbackFields.AlternativeToolOtherText.question,
                        value = state.alternativeToolOtherText,
                        onValueChange = { onAction(SurveyAction.ChangeAlternativeToolOtherText(it)) },
                        placeholder = "Name the tool or workflow",
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestionLabel(HexanaFeedbackFields.WorkTypes.question)
                WorkTypeChips(
                    selected = state.workTypes,
                    onToggle = { onAction(SurveyAction.ToggleWorkType(it)) },
                )
                AnimatedVisibility(visible = WorkType.Other in state.workTypes) {
                    SingleLineInput(
                        label = HexanaFeedbackFields.WorkTypeOtherText.question,
                        value = state.workTypeOtherText,
                        onValueChange = { onAction(SurveyAction.ChangeWorkTypeOtherText(it)) },
                        placeholder = "Describe it briefly",
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestionLabel(HexanaFeedbackFields.Frequency.question)
                FrequencySelector(
                    selected = state.frequency,
                    onSelect = { onAction(SurveyAction.SelectFrequency(it)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlternativeToolShelf(
    selected: Set<AlternativeTool>,
    onToggle: (AlternativeTool) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AlternativeTool.entries.forEach { tool ->
            SelectableChip(
                selected = tool in selected,
                text = "${tool.mark} ${tool.label}",
                onClick = { onToggle(tool) },
                role = Role.Checkbox,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkTypeChips(
    selected: Set<WorkType>,
    onToggle: (WorkType) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkType.entries.forEach { workType ->
            SelectableChip(
                selected = workType in selected,
                text = workType.label,
                onClick = { onToggle(workType) },
                role = Role.Checkbox,
            )
        }
    }
}

@Composable
internal fun FrequencySelector(
    selected: Frequency?,
    onSelect: (Frequency) -> Unit,
) {
    SegmentedChoice(
        items = Frequency.entries.toList(),
        selected = selected,
        label = { it.label },
        onSelect = onSelect,
    )
}

@Composable
internal fun InterviewCard(
    state: SurveyUiState,
    onAction: (SurveyAction) -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader(
                title = "Optional: talk to us",
                helper = "Open to a 30-minute call about your workflow? No pressure — your feedback above is already useful.",
            )

            SegmentedChoice(
                items = InterviewChoice.entries.toList(),
                selected = state.interviewChoice,
                label = { it.label },
                onSelect = { onAction(SurveyAction.SelectInterviewChoice(it)) },
            )

            AnimatedVisibility(visible = state.interviewChoice == InterviewChoice.Yes) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    BoxWithConstraints {
                        if (maxWidth >= 560.dp) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SingleLineInput(
                                    label = HexanaFeedbackFields.Contact.question,
                                    value = state.contact,
                                    onValueChange = { onAction(SurveyAction.ChangeContact(it)) },
                                    placeholder = "email or JetBrains handle",
                                    modifier = Modifier.weight(1f),
                                )
                                SingleLineInput(
                                    label = HexanaFeedbackFields.TimeZone.question,
                                    value = state.timeZone,
                                    onValueChange = { onAction(SurveyAction.ChangeTimeZone(it)) },
                                    placeholder = "UTC+1, CET, PST...",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SingleLineInput(
                                    label = HexanaFeedbackFields.Contact.question,
                                    value = state.contact,
                                    onValueChange = { onAction(SurveyAction.ChangeContact(it)) },
                                    placeholder = "email or JetBrains handle",
                                )
                                SingleLineInput(
                                    label = HexanaFeedbackFields.TimeZone.question,
                                    value = state.timeZone,
                                    onValueChange = { onAction(SurveyAction.ChangeTimeZone(it)) },
                                    placeholder = "UTC+1, CET, PST...",
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuestionLabel("Optional: what topic fits best?")
                        InterviewTopicChips(
                            selected = state.interviewTopics,
                            onToggle = { onAction(SurveyAction.ToggleInterviewTopic(it)) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterviewTopicChips(
    selected: Set<InterviewTopic>,
    onToggle: (InterviewTopic) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InterviewTopic.entries.forEach { topic ->
            SelectableChip(
                selected = topic in selected,
                text = topic.label,
                onClick = { onToggle(topic) },
                role = Role.Checkbox,
            )
        }
    }
}

@Composable
internal fun TechnicalContextCard(
    enabled: Boolean,
    previewExpanded: Boolean,
    contextPreview: TechnicalContextPreview,
    onEnabledChange: (Boolean) -> Unit,
    onPreviewToggle: () -> Unit,
) {
    val colors = rememberFeedbackColors()

    SectionCard(
        background = colors.transparentPanel,
        border = colors.border.copy(alpha = 0.86f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                SectionHeader(
                    title = "Technical context",
                    helper = "Included to help reproduce issues. You can review or turn this off.",
                    modifier = Modifier.weight(1f),
                )
                TogglePill(
                    enabled = enabled,
                    onToggle = { onEnabledChange(!enabled) },
                )
            }

            BoxWithConstraints {
                val twoColumns = maxWidth >= 620.dp
                if (twoColumns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ContextList(
                            title = "Included",
                            items = listOf(
                                "IDE product",
                                "Hexana plugin version",
                                "OS",
                                "File type counts, e.g. .wasm files",
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        ContextList(
                            title = "Not included",
                            items = listOf(
                                "File contents",
                                "File names",
                                "File paths",
                                "Project names",
                                "Environment variables",
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ContextList(
                            title = "Included",
                            items = listOf(
                                "IDE product",
                                "Hexana plugin version",
                                "OS",
                                "File type counts, e.g. .wasm files",
                            ),
                        )
                        ContextList(
                            title = "Not included",
                            items = listOf(
                                "File contents",
                                "File names",
                                "File paths",
                                "Project names",
                                "Environment variables",
                            ),
                        )
                    }
                }
            }

            ActionButton(
                text = if (previewExpanded) "Hide preview data" else "Preview data",
                onClick = onPreviewToggle,
                compact = true,
            )

            AnimatedVisibility(visible = previewExpanded) {
                JsonPreviewBlock(contextPreview.toJsonLike(enabled))
            }
        }
    }
}

@Composable
private fun SurveyActions(
    enabled: Boolean,
    inProgress: Boolean,
    failed: Boolean,
    onSubmit: () -> Unit,
    onLater: () -> Unit,
) {
    SectionCard {
        BoxWithConstraints {
            if (maxWidth >= 680.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SurveyActionHint(
                        enabled = enabled,
                        inProgress = inProgress,
                        failed = failed,
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(text = "Later", onClick = onLater)
                    ActionButton(
                        text = if (inProgress) "Sending..." else "Send feedback",
                        primary = true,
                        enabled = enabled,
                        onClick = onSubmit,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SurveyActionHint(enabled = enabled, inProgress = inProgress, failed = failed)
                    ActionButton(text = "Later", onClick = onLater)
                    ActionButton(
                        text = if (inProgress) "Sending..." else "Send feedback",
                        primary = true,
                        enabled = enabled,
                        onClick = onSubmit,
                    )
                }
            }
        }
    }
}

@Composable
private fun SurveyActionHint(
    enabled: Boolean,
    inProgress: Boolean,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = when {
            failed -> "Couldn’t send just now. Your answers are still here."
            inProgress -> "Sending to JetBrains feedback service..."
            enabled -> "Ready when you are."
            else -> "A few clicks are enough."
        },
        style = TextStyle(
            color = if (failed) rememberFeedbackColors().accent else rememberFeedbackColors().muted,
            fontSize = 12.sp,
        ),
        modifier = modifier,
    )
}

@Composable
private fun ThankYouState(
    showIssueFollowUp: Boolean,
    onClose: () -> Unit,
    onIssue: () -> Unit,
) {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Thanks — this helps shape Hexana.",
                style = TextStyle(
                    color = rememberFeedbackColors().text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = "Your feedback was sent to the JetBrains feedback service.",
                style = TextStyle(
                    color = rememberFeedbackColors().muted,
                    fontSize = 13.sp,
                ),
            )
            AnimatedVisibility(visible = showIssueFollowUp) {
                Text(
                    text = "Since you mentioned a bug, a GitHub issue can help us track the fix.",
                    style = TextStyle(
                        color = rememberFeedbackColors().muted,
                        fontSize = 12.sp,
                    ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(text = "Close tab", onClick = onClose)
                if (showIssueFollowUp) {
                    ActionButton(text = "Open GitHub issue", onClick = onIssue)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    helper: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = title,
            style = TextStyle(
                color = rememberFeedbackColors().text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Text(
            text = helper,
            style = TextStyle(
                color = rememberFeedbackColors().muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
        )
    }
}

@Composable
private fun QuestionLabel(text: String) {
    Text(
        text = text,
        style = TextStyle(
            color = rememberFeedbackColors().text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    background: Color = rememberFeedbackColors().panel,
    border: Color = rememberFeedbackColors().border,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(20.dp),
        content = content,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SelectableCard(
    selected: Boolean,
    leading: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    role: Role,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val colors = rememberFeedbackColors()
    val border by animateColorAsState(
        targetValue = when {
            selected -> colors.accentBorder
            hovered -> colors.hoverBorder
            else -> colors.border
        },
        animationSpec = tween(120),
        label = "${title}Border",
    )
    val background by animateColorAsState(
        targetValue = when {
            selected -> colors.accentSoft
            hovered -> colors.hover
            else -> colors.control
        },
        animationSpec = tween(120),
        label = "${title}Background",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 1.5.dp else 1.dp,
        animationSpec = tween(120),
        label = "${title}BorderWidth",
    )

    Row(
        modifier = modifier
            .heightIn(min = 70.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .border(borderWidth, border, RoundedCornerShape(9.dp))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = role,
                onClick = onClick,
            )
            .focusable()
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) colors.accentSoftStrong else colors.codeBackground)
                .border(1.dp, if (selected) colors.accentBorder else colors.border, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = leading,
                style = TextStyle(
                    color = if (selected) colors.accent else colors.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (leading.length > 2) 10.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = TextStyle(
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        color = colors.muted,
                        fontSize = 11.sp,
                    ),
                )
            }
        }

        Text(
            text = if (selected) "✓" else "",
            style = TextStyle(
                color = colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.width(14.dp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SelectableChip(
    selected: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    role: Role,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val colors = rememberFeedbackColors()
    val background by animateColorAsState(
        targetValue = when {
            selected -> colors.accentSoft
            hovered -> colors.hover
            else -> colors.control
        },
        animationSpec = tween(120),
        label = "${text}ChipBackground",
    )
    val border by animateColorAsState(
        targetValue = when {
            selected -> colors.accentBorder
            hovered -> colors.hoverBorder
            else -> colors.border
        },
        animationSpec = tween(120),
        label = "${text}ChipBorder",
    )

    Row(
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = role,
                onClick = onClick,
            )
            .focusable()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "✓" else "",
            style = TextStyle(
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.width(11.dp),
        )
        Text(
            text = text,
            style = TextStyle(
                color = if (selected) colors.text else colors.muted,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun <T> AdaptiveGrid(
    items: List<T>,
    minTwoColumnWidth: Dp,
    itemContent: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints {
        if (maxWidth >= minTwoColumnWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowItems.forEach { item ->
                            itemContent(item, Modifier.weight(1f))
                        }
                        if (rowItems.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { item ->
                    itemContent(item, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentedChoice(
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    BoxWithConstraints {
        if (maxWidth >= 560.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEach { item ->
                    SegmentedItem(
                        selected = selected == item,
                        label = label(item),
                        onClick = { onSelect(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    SegmentedItem(
                        selected = selected == item,
                        label = label(item),
                        onClick = { onSelect(item) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedItem(
    selected: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SelectableChip(
        selected = selected,
        text = label,
        modifier = modifier,
        role = Role.RadioButton,
        onClick = onClick,
    )
}

@Composable
private fun ExpandableCard(
    title: String,
    helper: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = rememberFeedbackColors()

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onToggle,
                    )
                    .focusable(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(title = title, helper = helper, modifier = Modifier.weight(1f))
                Text(
                    text = if (expanded) "Hide" else "Show",
                    style = TextStyle(
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            AnimatedVisibility(visible = expanded) {
                content()
            }
        }
    }
}

@Composable
private fun EditorLikeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = rememberFeedbackColors()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(9.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBackground)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "feedback.md",
                style = TextStyle(
                    color = colors.hint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "optional",
                style = TextStyle(
                    color = colors.hint,
                    fontSize = 11.sp,
                ),
            )
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                color = colors.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .padding(12.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                color = colors.hint,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                            ),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun SingleLineInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        QuestionLabel(label)
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.replace('\n', ' ')) },
            singleLine = true,
            textStyle = TextStyle(
                color = rememberFeedbackColors().text,
                fontSize = 13.sp,
            ),
            cursorBrush = SolidColor(rememberFeedbackColors().accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(rememberFeedbackColors().input)
                .border(1.dp, rememberFeedbackColors().border, RoundedCornerShape(8.dp))
                .padding(horizontal = 11.dp, vertical = 10.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                color = rememberFeedbackColors().hint,
                                fontSize = 13.sp,
                            ),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun TogglePill(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = rememberFeedbackColors()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) colors.accentSoft else colors.control)
            .border(1.dp, if (enabled) colors.accentBorder else colors.border, RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onClick = onToggle,
            )
            .focusable()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (enabled) "✓" else "",
            style = TextStyle(color = colors.accent, fontSize = 12.sp),
            modifier = Modifier.width(12.dp),
        )
        Text(
            text = if (enabled) "Included" else "Off",
            style = TextStyle(
                color = if (enabled) colors.text else colors.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun ContextList(
    title: String,
    items: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = rememberFeedbackColors()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.control.copy(alpha = 0.72f))
            .border(1.dp, colors.border.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        items.forEach { item ->
            Text(
                text = "• $item",
                style = TextStyle(
                    color = colors.muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
            )
        }
    }
}

@Composable
private fun JsonPreviewBlock(text: String) {
    val colors = rememberFeedbackColors()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBackground)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = colors.codeText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = rememberFeedbackColors()
    val background = when {
        !enabled -> colors.control.copy(alpha = 0.55f)
        primary -> colors.accent
        else -> colors.control
    }
    val foreground = when {
        !enabled -> colors.hint
        primary -> colors.primaryButtonText
        else -> colors.text
    }
    val border = if (primary && enabled) colors.accent else colors.border

    Text(
        text = text,
        style = TextStyle(
            color = foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier
            .heightIn(min = if (compact) 30.dp else 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .focusable(enabled)
            .padding(horizontal = if (compact) 11.dp else 13.dp, vertical = if (compact) 6.dp else 9.dp),
    )
}

private data class FeedbackColors(
    val background: Color,
    val panel: Color,
    val transparentPanel: Color,
    val control: Color,
    val input: Color,
    val codeBackground: Color,
    val border: Color,
    val hoverBorder: Color,
    val text: Color,
    val muted: Color,
    val hint: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentSoftStrong: Color,
    val accentBorder: Color,
    val issueLink: Color,
    val issueSoft: Color,
    val issueBorder: Color,
    val hover: Color,
    val patternText: Color,
    val codeText: Color,
    val primaryButtonText: Color,
)

@Composable
private fun rememberFeedbackColors(): FeedbackColors {
    val isDark = JewelTheme.isDark
    val highContrast = rememberHighContrastMode()

    return remember(isDark, highContrast) {
        if (isDark) {
            val background = uiColor("EditorPane.background", Color(0xFF111315))
            val panel = uiColor("Panel.background", Color(0xFF1E1F22))
            val text = uiColor("Panel.foreground", Color(0xFFECEFF4))

            FeedbackColors(
                background = background,
                panel = panel.copy(alpha = if (highContrast) 1f else 0.90f),
                transparentPanel = panel.copy(alpha = if (highContrast) 1f else 0.66f),
                control = Color(0xFF25282E),
                input = Color(0xFF171A1F),
                codeBackground = Color(0xFF14171B),
                border = Color(0xFF3B424D),
                hoverBorder = Color(0xFF596171),
                text = text,
                muted = Color(0xFFB7C0CE),
                hint = Color(0xFF8792A3),
                accent = Color(0xFF86B7FF),
                accentSoft = Color(0x2E5E9BFF),
                accentSoftStrong = Color(0x405E9BFF),
                accentBorder = Color(0xFF578FE6),
                issueLink = Color(0xFFFF9A9A),
                issueSoft = Color(0x24FF6B6B),
                issueBorder = Color(0x55FF8A8A),
                hover = Color(0xFF2C3038),
                patternText = Color(0xFFAFC7E8),
                codeText = Color(0xFFD4D9E3),
                primaryButtonText = Color(0xFF0D1420),
            )
        } else {
            val background = uiColor("EditorPane.background", Color.White)
            val panel = uiColor("Panel.background", Color(0xFFFFFFFF))
            val text = uiColor("Panel.foreground", Color(0xFF1F2328))

            FeedbackColors(
                background = background,
                panel = panel.copy(alpha = if (highContrast) 1f else 0.88f),
                transparentPanel = panel.copy(alpha = if (highContrast) 1f else 0.68f),
                control = Color(0xFFF7F9FC),
                input = Color(0xFFFFFFFF),
                codeBackground = Color(0xFFF1F4F8),
                border = Color(0xFFD3DAE5),
                hoverBorder = Color(0xFFB4C2D4),
                text = text,
                muted = Color(0xFF536173),
                hint = Color(0xFF7A8798),
                accent = Color(0xFF2F6FEB),
                accentSoft = Color(0x1F2F6FEB),
                accentSoftStrong = Color(0x2E2F6FEB),
                accentBorder = Color(0xFF83A9F7),
                issueLink = Color(0xFFB3261E),
                issueSoft = Color(0x12B3261E),
                issueBorder = Color(0x33B3261E),
                hover = Color(0xFFEFF4FC),
                patternText = Color(0xFF426FA8),
                codeText = Color(0xFF2B3340),
                primaryButtonText = Color.White,
            )
        }
    }
}

@Composable
private fun rememberHighContrastMode(): Boolean = remember {
    UIManager.getBoolean("Theme.isHighContrast") ||
            (UIManager.getLookAndFeel()?.name?.contains("High Contrast", ignoreCase = true) == true)
}

private fun uiColor(key: String, fallback: Color): Color =
    UIManager.getColor(key)?.toComposeColor() ?: fallback

private fun AwtColor.toComposeColor(): Color = Color(red = red, green = green, blue = blue, alpha = alpha)

private data class HexPatternToken(
    val text: String,
    val x: Float,
    val y: Float,
    val opacity: Float,
    val fontSize: Int,
)

private fun buildHexPatternTokens(seed: Int): List<HexPatternToken> {
    var state = seed

    fun nextFloat(): Float {
        state = state * 1664525 + 1013904223
        return ((state ushr 8) and 0xFFFFFF) / 0x1000000.toFloat()
    }

    fun nextToken(byteCount: Int): String = (0 until byteCount).joinToString(" ") {
        val value = ((nextFloat() * 256).toInt() + seed.absoluteValue + it * 17) and 0xFF
        value.toString(16).uppercase().padStart(2, '0')
    }

    val easterEggs = listOf(
        HexPatternToken("00 61 73 6D", 0.73f, 0.11f, 1f, 14),
        HexPatternToken("48 65 78 61 6E 61", 0.07f, 0.72f, 1f, 13),
    )
    val generated = buildList {
        val rows = 8
        val columns = 10
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val jitterX = (nextFloat() - 0.5f) * 0.055f
                val jitterY = (nextFloat() - 0.5f) * 0.070f
                add(
                    HexPatternToken(
                        text = nextToken(2 + (nextFloat() * 4).toInt()),
                        x = ((column + 0.25f) / columns + jitterX).coerceIn(0.02f, 0.92f),
                        y = ((row + 0.30f) / rows + jitterY).coerceIn(0.03f, 0.94f),
                        opacity = 0.62f + nextFloat() * 0.38f,
                        fontSize = 10 + (nextFloat() * 4).toInt(),
                    )
                )
            }
        }
    }

    return easterEggs + generated
}

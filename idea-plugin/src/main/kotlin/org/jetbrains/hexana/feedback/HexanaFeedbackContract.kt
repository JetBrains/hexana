package org.jetbrains.hexana.feedback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.enums.enumEntries

internal const val HexanaFeedbackTestUrl = "https://feedback.example.invalid/feedback"
internal const val HexanaFeedbackFormIdWithDetailedAnswer = "v2/feedback/ide_with_detailed_answer"
internal const val HexanaFeedbackConsentId = "rsch.statistics.feedback.common"
internal const val HexanaFeedbackType = "hexana_feedback"
internal const val HexanaFeedbackTitle = "Hexana feedback"

internal val HexanaFeedbackTags = listOf("hexana", "wasm", "feedback")

internal val FeedbackPayloadJson = Json

private val PrettyContractJson = Json { prettyPrint = true }

internal object HexanaFeedbackRequestFieldIds {
    const val FormId = "formid"
    const val AutoSolve = "autosolve"
    const val Email = "email"
    const val Subject = "subject"
    const val Comment = "comment"
    const val IntellijProduct = "intellij_product"
    const val FeedbackType = "feedback_type"
    const val PrivacyConsent = "privacy_consent"
    const val PrivacyConsentType = "privacy_consent_type"
    const val Tags = "tags"
    const val CollectedData = "collected_data"
}

internal object HexanaFeedbackFieldIds {
    const val Tasks = "tasks"
    const val Outcome = "outcome"
    const val Blockers = "blockers"
    const val BlockerOtherText = "blockerOtherText"
    const val ImprovementText = "improvementText"
    const val AlternativeTools = "alternativeTools"
    const val AlternativeToolOtherText = "alternativeToolOtherText"
    const val WorkTypes = "workTypes"
    const val WorkTypeOtherText = "workTypeOtherText"
    const val Frequency = "frequency"
    const val InterviewChoice = "interviewChoice"
    const val Contact = "contact"
    const val TimeZone = "timeZone"
    const val InterviewTopics = "interviewTopics"
    const val TechnicalContextEnabled = "technicalContextEnabled"
    const val TechnicalContext = "technicalContext"
    const val IdeProduct = "ideProduct"
    const val HexanaPluginVersion = "hexanaPluginVersion"
    const val Os = "os"
    const val FileTypes = "fileTypes"
}

@Serializable
internal data class HexanaFeedbackRequestData(
    @SerialName(HexanaFeedbackRequestFieldIds.FormId)
    val formId: String,
    @SerialName(HexanaFeedbackRequestFieldIds.AutoSolve)
    val autoSolve: Boolean,
    @SerialName(HexanaFeedbackRequestFieldIds.Email)
    val email: String,
    @SerialName(HexanaFeedbackRequestFieldIds.Subject)
    val subject: String,
    @SerialName(HexanaFeedbackRequestFieldIds.Comment)
    val comment: String,
    @SerialName(HexanaFeedbackRequestFieldIds.IntellijProduct)
    val intellijProduct: String,
    @SerialName(HexanaFeedbackRequestFieldIds.FeedbackType)
    val feedbackType: String,
    @SerialName(HexanaFeedbackRequestFieldIds.PrivacyConsent)
    val privacyConsent: Boolean,
    @SerialName(HexanaFeedbackRequestFieldIds.PrivacyConsentType)
    val privacyConsentType: String,
    @SerialName(HexanaFeedbackRequestFieldIds.Tags)
    val tags: List<String>,
    @SerialName(HexanaFeedbackRequestFieldIds.CollectedData)
    val collectedData: HexanaFeedbackCollectedData,
)

@Serializable
internal data class HexanaFeedbackCollectedData(
    @SerialName(HexanaFeedbackFieldIds.Tasks)
    val tasks: JsonObject,
    @SerialName(HexanaFeedbackFieldIds.Outcome)
    val outcome: String?,
    @SerialName(HexanaFeedbackFieldIds.Blockers)
    val blockers: JsonObject,
    @SerialName(HexanaFeedbackFieldIds.ImprovementText)
    val improvementText: String?,
    @SerialName(HexanaFeedbackFieldIds.AlternativeTools)
    val alternativeTools: JsonObject,
    @SerialName(HexanaFeedbackFieldIds.WorkTypes)
    val workTypes: JsonObject,
    @SerialName(HexanaFeedbackFieldIds.Frequency)
    val frequency: String?,
    @SerialName(HexanaFeedbackFieldIds.InterviewChoice)
    val interviewChoice: String?,
    @SerialName(HexanaFeedbackFieldIds.TimeZone)
    val timeZone: String?,
    @SerialName(HexanaFeedbackFieldIds.InterviewTopics)
    val interviewTopics: JsonObject,
    @SerialName(HexanaFeedbackFieldIds.TechnicalContextEnabled)
    val technicalContextEnabled: Boolean,
    @SerialName(HexanaFeedbackFieldIds.TechnicalContext)
    val technicalContext: HexanaFeedbackTechnicalContext?,
)

@Serializable
internal data class HexanaFeedbackTechnicalContext(
    @SerialName(HexanaFeedbackFieldIds.IdeProduct)
    val ideProduct: String?,
    @SerialName(HexanaFeedbackFieldIds.HexanaPluginVersion)
    val hexanaPluginVersion: String?,
    @SerialName(HexanaFeedbackFieldIds.Os)
    val os: String?,
    @SerialName(HexanaFeedbackFieldIds.FileTypes)
    val fileTypes: Map<String, Int>,
)

internal data class FeedbackQuestionSpec(
    val id: String,
    val question: String,
    val options: List<FeedbackOptionSpec> = emptyList(),
    val response: FeedbackResponseSpec? = null,
)

internal data class FeedbackOptionSpec(
    val id: String,
    val label: String,
    val subtext: String? = null,
)

internal data class FeedbackResponseSpec(
    val type: String,
    val other: FeedbackOtherResponseSpec? = null,
)

internal data class FeedbackOtherResponseSpec(
    val key: String,
    val label: String,
    val question: String? = null,
)

internal object HexanaFeedbackFields {
    val Tasks: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.Tasks,
        question = "What were you trying to do?",
        options = TaskChoice.entries
            .filterNot { it == TaskChoice.Other }
            .map { FeedbackOptionSpec(id = it.id, label = it.label) },
        response = checkboxGroupResponse(TaskChoice.Other.label),
    )

    val Outcome: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.Outcome,
        question = "Did Hexana help you complete that task?",
        options = enumEntries<Outcome>().map {
            FeedbackOptionSpec(id = it.id, label = it.label, subtext = it.subtext)
        },
    )

    val Blockers: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.Blockers,
        question = "What got in the way?",
        options = Blocker.entries
            .filterNot { it == Blocker.Other }
            .map { FeedbackOptionSpec(id = it.id, label = it.label) },
        response = checkboxGroupResponse(Blocker.Other.label, otherQuestion = "What else got in the way?"),
    )

    val BlockerOtherText: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.BlockerOtherText,
        question = "What else got in the way?",
    )

    val ImprovementText: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.ImprovementText,
        question = "One thing Hexana should improve first",
    )

    val AlternativeTool: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.AlternativeTools,
        question = "If Hexana was not available, what would you use instead?",
        options = enumEntries<AlternativeTool>()
            .filterNot { it == org.jetbrains.hexana.feedback.AlternativeTool.Other }
            .map { FeedbackOptionSpec(id = it.id, label = it.label) },
        response = checkboxGroupResponse(
            label = org.jetbrains.hexana.feedback.AlternativeTool.Other.label,
            otherQuestion = "What else would you use?",
        ),
    )

    val AlternativeToolOtherText: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.AlternativeToolOtherText,
        question = "What else would you use?",
    )

    val WorkTypes: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.WorkTypes,
        question = "Which best describes your work?",
        options = WorkType.entries
            .filterNot { it == WorkType.Other }
            .map { FeedbackOptionSpec(id = it.id, label = it.label) },
        response = checkboxGroupResponse(WorkType.Other.label, otherQuestion = "What kind of work?"),
    )

    val WorkTypeOtherText: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.WorkTypeOtherText,
        question = "What kind of work?",
    )

    val Frequency: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.Frequency,
        question = "How often do you inspect compiled artifacts?",
        options = enumEntries<org.jetbrains.hexana.feedback.Frequency>().map {
            FeedbackOptionSpec(id = it.id, label = it.label)
        },
    )

    val InterviewChoice: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.InterviewChoice,
        question = "Would you be open to a 30-minute interview to help improve Hexana?",
        options = enumEntries<org.jetbrains.hexana.feedback.InterviewChoice>().map {
            FeedbackOptionSpec(id = it.id, label = it.label)
        },
    )

    val Contact: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.Contact,
        question = "Preferred contact",
    )

    val TimeZone: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.TimeZone,
        question = "Time zone",
    )

    val InterviewTopics: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.InterviewTopics,
        question = "What topic fits best?",
        options = InterviewTopic.entries
            .filterNot { it == InterviewTopic.Other }
            .map { FeedbackOptionSpec(id = it.id, label = it.label) },
        response = checkboxGroupResponse(InterviewTopic.Other.label),
    )

    val TechnicalContextEnabled: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.TechnicalContextEnabled,
        question = "Include technical context",
    )

    val TechnicalContext: FeedbackQuestionSpec = FeedbackQuestionSpec(
        id = HexanaFeedbackFieldIds.TechnicalContext,
        question = "Technical context",
    )
}

private val CollectedDataQuestionSpecs: Map<String, FeedbackQuestionSpec> by lazy {
    listOf(
        HexanaFeedbackFields.Tasks,
        HexanaFeedbackFields.Outcome,
        HexanaFeedbackFields.Blockers,
        HexanaFeedbackFields.ImprovementText,
        HexanaFeedbackFields.AlternativeTool,
        HexanaFeedbackFields.WorkTypes,
        HexanaFeedbackFields.Frequency,
        HexanaFeedbackFields.InterviewChoice,
        HexanaFeedbackFields.TimeZone,
        HexanaFeedbackFields.InterviewTopics,
        HexanaFeedbackFields.TechnicalContextEnabled,
        HexanaFeedbackFields.TechnicalContext,
    ).associateBy { it.id }
}

internal fun createHexanaFeedbackFormContractJson(): JsonObject = buildJsonObject {
    put("submitEndpoint", HexanaFeedbackTestUrl)
    put("requestBody", HexanaFeedbackRequestData.serializer().descriptor.toContractJson())
    put("constants", buildJsonObject {
        put(HexanaFeedbackRequestFieldIds.FormId, HexanaFeedbackFormIdWithDetailedAnswer)
        put(HexanaFeedbackRequestFieldIds.Subject, HexanaFeedbackTitle)
        put(HexanaFeedbackRequestFieldIds.FeedbackType, HexanaFeedbackType)
        put(HexanaFeedbackRequestFieldIds.PrivacyConsent, true)
        put(HexanaFeedbackRequestFieldIds.PrivacyConsentType, HexanaFeedbackConsentId)
        put(HexanaFeedbackRequestFieldIds.Tags, buildJsonArray {
            HexanaFeedbackTags.forEach { add(it) }
        })
    })
}

internal fun createHexanaFeedbackFormContractPrettyJson(): String =
    PrettyContractJson.encodeToString(JsonObject.serializer(), createHexanaFeedbackFormContractJson())

fun main(args: Array<String>) {
    val contractJson = createHexanaFeedbackFormContractPrettyJson()
    val outputPath = args.singleOrNull()?.let(Path::of)

    if (outputPath == null) {
        println(contractJson)
        return
    }

    outputPath.parent?.let(Files::createDirectories)
    Files.writeString(outputPath, contractJson)
    println("Generated Hexana feedback form contract: $outputPath")
}

private fun SerialDescriptor.toContractJson(): JsonObject = buildJsonObject {
    put("type", contractType())
    put("kotlinType", serialName.substringAfterLast('.'))
    if (kind == StructureKind.CLASS) {
        put("fields", buildJsonArray {
            for (index in 0 until elementsCount) {
                add(elementContractJson(index))
            }
        })
    }
}

private fun SerialDescriptor.elementContractJson(index: Int): JsonObject {
    val id = getElementName(index)
    val descriptor = getElementDescriptor(index)
    val questionSpec = CollectedDataQuestionSpecs[id]

    return buildJsonObject {
        put("id", id)
        put("type", questionSpec?.response?.type ?: descriptor.contractType())
        put("sent", "always")
        put("nullable", descriptor.isNullable)
        put("optionalAnswer", id in CollectedDataQuestionSpecs)
        questionSpec?.let { spec ->
            put("question", spec.question)
            if (spec.options.isNotEmpty()) {
                put("options", buildJsonArray {
                    spec.options.forEach { option ->
                        add(option.toJsonObject(responseKey = spec.response?.optionKey(option.id)))
                    }
                })
            }
            spec.response?.let { response ->
                put("response", response.toJsonObject())
            }
        }
        if (descriptor.kind == StructureKind.CLASS) {
            put("fields", buildJsonArray {
                for (nestedIndex in 0 until descriptor.elementsCount) {
                    add(descriptor.elementContractJson(nestedIndex))
                }
            })
        }
        if (descriptor.kind == StructureKind.MAP && questionSpec?.response == null) {
            put("keyType", descriptor.getElementDescriptor(0).contractType())
            put("valueType", descriptor.getElementDescriptor(1).contractType())
        }
    }
}

private fun SerialDescriptor.contractType(): String {
    val baseType = when (kind) {
        PrimitiveKind.STRING -> "string"
        PrimitiveKind.BOOLEAN -> "boolean"
        PrimitiveKind.INT -> "int"
        StructureKind.LIST -> "array<${getElementDescriptor(0).contractType().removeSuffix("|null")}>"
        StructureKind.MAP -> "object<${getElementDescriptor(0).contractType()},${getElementDescriptor(1).contractType()}>"
        StructureKind.CLASS -> "object"
        else -> serialName.substringAfterLast('.')
    }

    return if (isNullable) "$baseType|null" else baseType
}

private fun checkboxGroupResponse(
    label: String,
    otherQuestion: String? = null,
): FeedbackResponseSpec = FeedbackResponseSpec(
    type = "checkbox_group",
    other = FeedbackOtherResponseSpec(
        key = "other",
        label = label,
        question = otherQuestion,
    ),
)

private fun FeedbackResponseSpec.optionKey(optionId: String): String? =
    when (type) {
        "checkbox_group" -> optionId
        else -> null
    }

private fun FeedbackOptionSpec.toJsonObject(responseKey: String? = null): JsonObject = buildJsonObject {
    put("id", id)
    put("label", label)
    responseKey?.let { put("responseKey", it) }
    subtext?.let { put("subtext", it) }
}

private fun FeedbackResponseSpec.toJsonObject(): JsonObject = buildJsonObject {
    put("type", type)
    other?.let { put("other", it.toJsonObject()) }
}

private fun FeedbackOtherResponseSpec.toJsonObject(): JsonObject = buildJsonObject {
    put("key", key)
    put("label", label)
    question?.let { put("question", it) }
}

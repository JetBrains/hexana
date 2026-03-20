package org.jetbrains.hexana.feedback

import com.intellij.testFramework.UsefulTestCase
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HexanaFeedbackContractTest : UsefulTestCase() {
    fun testFeedbackFieldsCanInitializeBeforeContractLookup() {
        assertEquals("What were you trying to do?", HexanaFeedbackFields.Tasks.question)
    }

    fun testCheckboxGroupsAreSerializedAsBooleanResponseKeysPlusOtherText() {
        val payload = createFeedbackCollectedDataPayload(
            state = SurveyUiState(
                selectedTasks = setOf(TaskChoice.ImportsExports, TaskChoice.Other),
                outcome = Outcome.No,
                blockers = setOf(Blocker.NavigationUnclear, Blocker.Other),
                blockerOtherText = "Missing jump target",
                alternativeTools = setOf(AlternativeTool.CliTools),
                workTypes = setOf(WorkType.Other),
                workTypeOtherText = "Tooling engineer",
                interviewChoice = InterviewChoice.Yes,
                interviewTopics = setOf(InterviewTopic.Performance, InterviewTopic.Other),
            ),
            contextPreview = TechnicalContextPreview(
                ideProduct = null,
                hexanaPluginVersion = null,
                os = null,
                fileTypes = emptyMap(),
            ),
        )

        assertEquals(true, payload.tasks.getValue("checkbox_0").jsonPrimitive.boolean)
        assertEquals(false, payload.tasks.getValue("checkbox_1").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(""), payload.tasks.getValue("other"))

        assertEquals(false, payload.blockers.getValue("checkbox_0").jsonPrimitive.boolean)
        assertEquals(true, payload.blockers.getValue("checkbox_1").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive("Missing jump target"), payload.blockers.getValue("other"))

        assertEquals(true, payload.alternativeTools.getValue("checkbox_0").jsonPrimitive.boolean)
        assertFalse(payload.alternativeTools.containsKey("other"))

        assertEquals(JsonPrimitive("Tooling engineer"), payload.workTypes.getValue("other"))
        assertEquals(true, payload.interviewTopics.getValue("checkbox_5").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(""), payload.interviewTopics.getValue("other"))
    }

    fun testGeneratedContractDocumentsCheckboxGroupResponseKeys() {
        val collectedDataFields = createHexanaFeedbackFormContractJson()
            .getValue("requestBody").jsonObject
            .getValue("fields").jsonArray
            .single { it.jsonObject.getValue("id") == JsonPrimitive(HexanaFeedbackRequestFieldIds.CollectedData) }
            .jsonObject
            .getValue("fields").jsonArray

        val blockers = collectedDataFields
            .single { it.jsonObject.getValue("id") == JsonPrimitive(HexanaFeedbackFieldIds.Blockers) }
            .jsonObject

        assertEquals(JsonPrimitive("checkbox_group"), blockers.getValue("response").jsonObject.getValue("type"))
        assertEquals(
            JsonPrimitive("checkbox_0"),
            blockers.getValue("options").jsonArray.first().jsonObject.getValue("responseKey"),
        )
        assertEquals(
            JsonPrimitive("other"),
            blockers.getValue("response").jsonObject.getValue("other").jsonObject.getValue("key"),
        )
    }
}

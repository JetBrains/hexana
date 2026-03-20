package org.jetbrains.hexana.feedback

import com.intellij.ide.RegionUrlMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.PlatformUtils
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.HttpRequests.JSON_CONTENT_TYPE
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection

private const val RequestIdKey = "Request-Id"

private val EmailRegex = Regex("^[a-zA-Z0-9\\\\._%+!$&*=^|~#{}-]+@([a-zA-Z0-9\\\\-]+\\.)+([a-zA-Z]{2,22})$")
private val SpaceRegex = Regex("\\s")
private val Log = Logger.getInstance("org.jetbrains.hexana.feedback.HexanaFeedbackSubmit")

internal fun submitHexanaFeedback(
    state: SurveyUiState,
    technicalContextPreview: TechnicalContextPreview,
    onDone: () -> Unit,
    onError: () -> Unit,
) {
    ApplicationManager.getApplication().executeOnPooledThread {
        val feedbackUrl = RegionUrlMapper.tryMapUrlBlocking(HexanaFeedbackTestUrl)
        val requestData = buildFeedbackRequest(state, technicalContextPreview).toString()

        try {
            HttpRequests
                .post(feedbackUrl, JSON_CONTENT_TYPE)
                .productNameAsUserAgent()
                .accept("application/json")
                .connect { request ->
                    try {
                        request.write(requestData)
                        val connection = request.connection

                        if (connection is HttpsURLConnection && connection.responseCode != 200) {
                            val requestId = connection.getHeaderField(RequestIdKey)
                            val errorResponse = request.readError()
                            Log.info(
                                "Failed to submit Hexana feedback. Status code:${connection.responseCode}\n" +
                                        "Server response:${errorResponse}\nRequest ID:${requestId}",
                            )
                            onError()
                            return@connect
                        }

                        val bytes = request.inputStream.readAllBytes()
                        Log.info(bytes.toString(Charsets.UTF_8))
                        val requestId = request.connection.getHeaderField(RequestIdKey)
                        Log.info("Hexana feedback submitted successfully. Record ID is ${requestId}")
                    } catch (e: IOException) {
                        val errorResponse = (request.connection as? HttpURLConnection)
                            ?.errorStream
                            ?.readAllBytes()
                            ?.toString(Charsets.UTF_8)
                        Log.info(
                            "Failed to submit Hexana feedback.\n" +
                                    "Server response:\n$errorResponse\n" +
                                    "Exception: ${e.stackTraceToString()}",
                        )
                        onError()
                        return@connect
                    }
                    onDone()
                }
        } catch (e: IOException) {
            Log.info("Failed to submit Hexana feedback. Error message:\n${e.message}")
            onError()
        }
    }
}

private fun buildFeedbackRequest(
    state: SurveyUiState,
    technicalContextPreview: TechnicalContextPreview,
) = FeedbackPayloadJson.encodeToJsonElement(
    HexanaFeedbackRequestData.serializer(),
    HexanaFeedbackRequestData(
        formId = HexanaFeedbackFormIdWithDetailedAnswer,
        autoSolve = false,
        email = state.contact.takeIf { it.matches(EmailRegex) } ?: "",
        subject = HexanaFeedbackTitle,
        comment = createFeedbackMarkdown(state, technicalContextPreview),
        intellijProduct = getProductTag(),
        feedbackType = HexanaFeedbackType,
        privacyConsent = true,
        privacyConsentType = HexanaFeedbackConsentId,
        tags = HexanaFeedbackTags.map { it.replace(SpaceRegex, "_") },
        collectedData = createFeedbackCollectedDataPayload(state, technicalContextPreview),
    ),
).jsonObject

private fun getProductTag(): String = when {
    PlatformUtils.isIntelliJ() -> "ij_idea1"
    PlatformUtils.isPhpStorm() -> "ij_phpstorm1"
    PlatformUtils.isWebStorm() -> "ij_webstorm1"
    PlatformUtils.isPyCharm() -> "ij_pycharm1"
    PlatformUtils.isRubyMine() -> "ij_rubymine1"
    PlatformUtils.isAppCode() -> "ij_appcode1"
    PlatformUtils.isCLion() && !isCLionNova() -> "ij_clion1"
    PlatformUtils.isCLion() && isCLionNova() -> "ij_clion_nova1"
    PlatformUtils.isDataGrip() -> "ij_datagrip1"
    PlatformUtils.isGoIde() -> "ij_goland1"
    PlatformUtils.isJetBrainsClient() -> "ij_code_with_me1"
    PlatformUtils.isDataSpell() -> "ij_dataspell1"
    PlatformUtils.isRider() -> "ij_rider1"
    PlatformUtils.isRustRover() -> "ij_rustrover1"
    PlatformUtils.isAqua() -> "ij_aqua1"
    else -> "undefined"
}

private fun isCLionNova(): Boolean {
    return ApplicationNamesInfo.getInstance().fullProductNameWithEdition.contains("Nova")
}

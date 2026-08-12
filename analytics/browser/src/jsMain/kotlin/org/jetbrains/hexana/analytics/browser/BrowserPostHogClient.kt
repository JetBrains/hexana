package org.jetbrains.hexana.analytics.browser

import org.jetbrains.hexana.analytics.AnalyticsClient
import org.jetbrains.hexana.analytics.AnalyticsConfig
import org.jetbrains.hexana.analytics.HexanaEvent

@JsModule("posthog-js")
@JsNonModule
private external object PostHogBrowserModule {
    @JsName("default")
    val default: PostHogJs
}

private external interface PostHogJs {
    fun init(apiKey: String, options: dynamic = definedExternally)

    fun capture(event: String, properties: dynamic = definedExternally)

    fun opt_in_capturing(options: dynamic = definedExternally)

    fun opt_out_capturing()
}

data class BrowserAnalyticsConfig(
    val apiKey: String = AnalyticsConfig.DEFAULT_API_KEY,
    val apiHost: String = AnalyticsConfig.DEFAULT_HOST,
    val capturePageview: Boolean = false,
    val capturePageleave: Boolean = false,
    val autocapture: Boolean = false,
    val disableSessionRecording: Boolean = true,
    val personProfiles: String = "never",
    val requestBatching: Boolean? = null,
    val advancedDisableDecide: Boolean? = null,
    val optOutUseragentFilter: Boolean? = null,
)

interface BrowserAnalyticsClient : AnalyticsClient {
    fun initialize()

    fun setConsent(enabled: Boolean)
}

fun createBrowserAnalyticsClient(
    config: BrowserAnalyticsConfig = BrowserAnalyticsConfig(),
): BrowserAnalyticsClient = PostHogBrowserAnalyticsClient(config)

private class PostHogBrowserAnalyticsClient(
    private val configuration: BrowserAnalyticsConfig,
) : BrowserAnalyticsClient {
    private var initialized = false
    private var consentEnabled = false

    override fun initialize() {
        if (initialized) return
        initialized = true
        val options = js("{}")
        options.api_host = configuration.apiHost
        options.capture_pageview = configuration.capturePageview
        options.capture_pageleave = configuration.capturePageleave
        options.autocapture = configuration.autocapture
        options.disable_session_recording = configuration.disableSessionRecording
        options.person_profiles = configuration.personProfiles
        options.opt_out_capturing_by_default = true
        configuration.requestBatching?.let { options.request_batching = it }
        configuration.advancedDisableDecide?.let { options.advanced_disable_decide = it }
        configuration.optOutUseragentFilter?.let { options.opt_out_useragent_filter = it }
        options.before_send = ::sanitizeCapture
        PostHogBrowserModule.default.init(configuration.apiKey, options)
        PostHogBrowserModule.default.opt_out_capturing()
    }

    override fun setConsent(enabled: Boolean) {
        initialize()
        consentEnabled = enabled
        if (enabled) {
            val options = js("{}")
            options.captureEventName = false
            PostHogBrowserModule.default.opt_in_capturing(options)
        } else {
            PostHogBrowserModule.default.opt_out_capturing()
        }
    }

    override fun capture(event: HexanaEvent) {
        if (!consentEnabled) return
        val properties = js("{}")
        val eventProperties = js("{}")
        for ((key, value) in event.properties) {
            val browserValue = value.toBrowserPostHogValue()
            properties[key] = browserValue
            eventProperties[key] = browserValue
        }
        properties[EVENT_PROPERTIES_MARKER] = eventProperties
        PostHogBrowserModule.default.capture(event.name, properties)
    }

    override fun flush() = Unit

    override fun shutdown() = Unit
}

private const val EVENT_PROPERTIES_MARKER = "__hexana_event_properties"

private fun sanitizeCapture(capture: dynamic): dynamic {
    val sourceProperties = capture?.properties ?: return null
    val eventProperties = sourceProperties[EVENT_PROPERTIES_MARKER] ?: return null
    val sanitizedProperties = js("{}")
    val distinctId = sourceProperties.distinct_id
    if (distinctId != null) sanitizedProperties.distinct_id = distinctId
    val keys = js("Object.keys(eventProperties)") as Array<String>
    for (key in keys) sanitizedProperties[key] = eventProperties[key]
    capture.properties = sanitizedProperties
    return capture
}

private fun Any.toBrowserPostHogValue(): Any = when (this) {
    is Long -> toDouble()
    is Int -> toDouble()
    else -> this
}

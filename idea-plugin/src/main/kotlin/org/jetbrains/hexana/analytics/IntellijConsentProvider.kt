package org.jetbrains.hexana.analytics

import com.intellij.internal.statistic.utils.StatisticsUploadAssistant

class IntellijConsentProvider : ConsentProvider {
    override fun isAnalyticsAllowed(): Boolean {
        return StatisticsUploadAssistant.isSendAllowed()
    }
}

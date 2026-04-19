package org.jetbrains.hexana.analytics

import org.jetbrains.hexana.HexanaSettings

class IntellijConsentProvider : ConsentProvider {
    override fun isAnalyticsAllowed(): Boolean {
        return HexanaSettings.getInstance().sendAnalytics
    }
}

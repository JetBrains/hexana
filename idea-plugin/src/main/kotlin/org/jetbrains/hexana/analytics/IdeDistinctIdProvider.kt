package org.jetbrains.hexana.analytics

import com.intellij.openapi.application.PermanentInstallationID

class IdeDistinctIdProvider : DistinctIdProvider {
    override fun distinctId(): String = PermanentInstallationID.get()
}

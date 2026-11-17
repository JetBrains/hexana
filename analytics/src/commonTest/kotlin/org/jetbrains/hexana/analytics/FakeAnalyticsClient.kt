package org.jetbrains.hexana.analytics

class FakeAnalyticsClient : AnalyticsClient {
    val captured = mutableListOf<HexanaEvent>()
    var flushed = false
        private set
    var shutDown = false
        private set

    override fun capture(event: HexanaEvent) {
        captured += event
    }

    override fun flush() {
        flushed = true
    }

    override fun shutdown() {
        shutDown = true
    }
}

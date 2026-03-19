package org.jetbrains.hexana.statistics

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventId
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.openapi.project.Project

@Suppress("UnstableApiUsage")
internal object HexanaCounterUsagesCollector : CounterUsagesCollector() {
  val GROUP: EventLogGroup =
    EventLogGroup("org.jetbrains.hexana", 1, "FUS", "Hexana plugin feature usage")

  private val WASM_FILE_OPENED: EventId = GROUP.registerEvent(
    "wasm.file.opened",
    "A .wasm file was opened in the Hexana editor"
  )

  override fun getGroup(): EventLogGroup = GROUP

  fun logWasmFileOpened(project: Project) {
    WASM_FILE_OPENED.log(project)
  }
}

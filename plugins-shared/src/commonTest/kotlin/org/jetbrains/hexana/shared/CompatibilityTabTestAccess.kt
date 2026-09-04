package org.jetbrains.hexana.shared

import org.jetbrains.hexana.shared.features.ProposalCompatibility

/**
 * Test-only bridge to the internal tab builder. The builder stays `internal` because hosts
 * must go through [WasmTableTabsProvider] to get it, but its column layout and cell text are
 * worth pinning directly rather than through a full `WasmBinaryProvider` fixture.
 */
internal fun compatibilityTabDescriptorForTest(
    proposals: List<ProposalCompatibility>,
    runtimeLabels: List<String>,
    openUrl: ((String) -> Unit)?,
    openRuntimeSettings: (() -> Unit)?,
): TableTabDescriptor? = compatibilityTabDescriptor(proposals, runtimeLabels, openUrl, openRuntimeSettings)

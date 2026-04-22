package org.jetbrains.hexana.ui.panel.components

import org.jetbrains.hexana.shared.componentExternDescriptionLabel
import org.jetbrains.hexana.shared.componentSortIndexLabel
import org.jetbrains.hexana.wasm.components.*

internal fun ComponentExternDescription.toStringRepresentation() = componentExternDescriptionLabel(this)

internal fun ComponentSortIndex.toStringRepresentation(): String = componentSortIndexLabel(this)

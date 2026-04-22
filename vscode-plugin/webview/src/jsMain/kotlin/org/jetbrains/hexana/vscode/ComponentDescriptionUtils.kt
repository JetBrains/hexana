package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.shared.componentSortIndexLabel as sharedComponentSortIndexLabel
import org.jetbrains.hexana.shared.componentExternDescriptionLabel as sharedComponentExternDescriptionLabel
import org.jetbrains.hexana.wasm.components.ComponentExternDescription
import org.jetbrains.hexana.wasm.components.ComponentSortIndex

internal fun componentSortIndexLabel(sortIndex: ComponentSortIndex): String =
    sharedComponentSortIndexLabel(sortIndex)

internal fun componentExternDescriptionLabel(desc: ComponentExternDescription): String =
    sharedComponentExternDescriptionLabel(desc)

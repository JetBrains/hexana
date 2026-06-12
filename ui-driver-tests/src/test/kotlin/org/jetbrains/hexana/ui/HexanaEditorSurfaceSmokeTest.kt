package org.jetbrains.hexana.ui

import org.jetbrains.hexana.ui.framework.HexanaEditorTab
import org.jetbrains.hexana.ui.framework.HexanaProviderKind
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("hexana")
class HexanaEditorSurfaceSmokeTest {
    @Test
    fun coreWasmEditorReportsCoreTabs() {
        hexanaIde(testName = "hexana-editor-surface-core-wasm") {
            hexanaEditor("hello.wasm") {
                expect {
                    providerKind(HexanaProviderKind.CORE_WASM)
                    tabsContain(
                        HexanaEditorTab.FUNCTIONS,
                        HexanaEditorTab.EXPORTS,
                        HexanaEditorTab.IMPORTS,
                        HexanaEditorTab.TOP,
                        HexanaEditorTab.WAT,
                    )
                }
            }
        }
    }

    @Test
    fun componentWasmEditorReportsComponentTabsOnly() {
        hexanaIde(testName = "hexana-editor-surface-component-wasm") {
            hexanaEditor("hello_component.wasm") {
                expect {
                    providerKind(HexanaProviderKind.COMPONENT_WASM)
                    tabsContain(
                        HexanaEditorTab.EXPORTS,
                        HexanaEditorTab.IMPORTS,
                        HexanaEditorTab.MODULES,
                        HexanaEditorTab.TOP,
                        HexanaEditorTab.WAT,
                    )
                    tabsDoNotContain(
                        HexanaEditorTab.FUNCTIONS,
                        HexanaEditorTab.GARBAGE,
                        HexanaEditorTab.DOMINATORS,
                    )
                }
            }
        }
    }

    @Test
    fun invalidWasmEditorFallsBackToGenericBinary() {
        hexanaIde(testName = "hexana-editor-surface-invalid-wasm") {
            hexanaEditor("invalid_magic.wasm") {
                expect {
                    providerKind(HexanaProviderKind.GENERIC)
                    fileName("invalid_magic.wasm")
                    noWasmTabs()
                }
            }
        }
    }

    @Test
    fun genericBinaryEditorReportsGenericWithoutWasmTabs() {
        hexanaIde(testName = "hexana-editor-surface-generic-bin") {
            hexanaEditor("generic_payload.bin") {
                expect {
                    providerKind(HexanaProviderKind.GENERIC)
                    fileName("generic_payload.bin")
                    noWasmTabs()
                }
            }
        }
    }

    @Test
    fun dwarfWasmEditorReportsDwarfTabs() {
        hexanaIde(testName = "hexana-editor-surface-dwarf-wasm") {
            closeProjectToolWindow()
            hexanaEditor("debug-add.wasm") {
                expect {
                    providerKind(HexanaProviderKind.CORE_WASM)
                    fileName("debug-add.wasm")
                }

                val dwarfTabs = snapshot().panelNames.filter { it.startsWith(".debug_") }
                assertTrue(dwarfTabs.isNotEmpty(), "Expected DWARF tabs to be reported")
                expect {
                    tabsContain(".debug_abbrev", ".debug_info", ".debug_line")
                }
            }
        }
    }

    private fun org.jetbrains.hexana.ui.framework.HexanaEditorExpectations.noWasmTabs() {
        tabsDoNotContain(
            HexanaEditorTab.FUNCTIONS,
            HexanaEditorTab.EXPORTS,
            HexanaEditorTab.IMPORTS,
            HexanaEditorTab.MODULES,
            HexanaEditorTab.MONOS,
            HexanaEditorTab.GARBAGE,
            HexanaEditorTab.DOMINATORS,
            HexanaEditorTab.WAT,
            HexanaEditorTab.WAT_VIRTUALIZED,
        )
    }
}

package org.jetbrains.hexana.webapp

import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.vscode.Host
import org.jetbrains.hexana.vscode.LocalHost
import org.jetbrains.hexana.vscode.ScriptTabState
import org.jetbrains.hexana.vscode.WebUiCapabilities
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private var workerDomRootSequence = 0

class WorkerWasmEditorContentDomTest {
    @Test
    fun initialCoreWorkerRouteRendersPresentationWithoutWindowWasmConstruction(): Promise<Unit> = Promise { resolve, reject ->
        val root = workerRoot()
        val factory = CountingLegacyProviderFactory()
        var composition: Composition? = null
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                var legacyTab by remember { mutableStateOf<String?>(null) }
                CompositionLocalProvider(LocalHost provides WorkerDomHost()) {
                    WorkerBackedWasmContent(
                        fileName = "addition.wasm",
                        parentFileName = null,
                        bytes = additionFixtureBytes(),
                        response = coreResponse(),
                        capabilities = WebUiCapabilities(),
                        legacyWasmTab = legacyTab,
                        onLegacyWasmTabChanged = { legacyTab = it },
                        onLegacyWasmReparseRequested = { legacyTab = "Sections" },
                        legacyWasmProviderFactory = factory,
                    )
                }
            }

            afterWorkerDomFrames {
                try {
                    assertEquals(0, factory.calls)
                    assertTrue(root.textContent.orEmpty().contains("Toolchain: GraalVM Web Image"))
                    assertTrue(root.textContent.orEmpty().contains("via: compat, interop"))
                    assertNotNull(workerTab(root, "Sections"))
                    assertNotNull(workerTab(root, "Imports"))
                    assertNotNull(workerTab(root, "Top"))
                    assertNotNull(workerTab(root, "Wat"))
                    assertNull(workerTab(root, "Data"))
                    assertNotNull(root.querySelector(".hexana-proposal-badge"))
                    assertNotNull(root.querySelector("[title='Run Wasm module']"))
                    assertNotNull(root.querySelector("[title='Debug Wasm module']"))
                    assertTrue(root.textContent.orEmpty().contains("header"))
                    finishWorkerDom(composition, root, resolve, reject)
                } catch (failure: Throwable) {
                    finishWorkerDom(composition, root, resolve, reject, failure)
                }
            }
        } catch (failure: Throwable) {
            finishWorkerDom(composition, root, resolve, reject, failure)
        }
    }

    @Test
    fun workerBackedWasmScriptTabUsesTheTypedSemanticProvider(): Promise<Unit> = Promise { resolve, reject ->
        val root = workerRoot()
        val factory = CountingLegacyProviderFactory()
        var composition: Composition? = null
        var addSemanticScript: (() -> Unit)? = null
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                var hasSemanticScript by remember { mutableStateOf(false) }
                addSemanticScript = { hasSemanticScript = true }
                CompositionLocalProvider(LocalHost provides WorkerDomHost()) {
                    WorkerBackedWasmContent(
                        fileName = "addition.wasm",
                        parentFileName = null,
                        bytes = additionFixtureBytes(),
                        response = coreResponse(),
                        capabilities = WebUiCapabilities(),
                        legacyWasmTab = null,
                        onLegacyWasmTabChanged = {},
                        onLegacyWasmReparseRequested = {},
                        legacyWasmProviderFactory = factory,
                        scriptTabs = if (hasSemanticScript) {
                            listOf(ScriptTabState("semantic.js", "print(\"semantic-import-count:\" + binary.imports.length)"))
                        } else emptyList(),
                    )
                }
            }

            afterWorkerDomFrames {
                try {
                    assertEquals(0, factory.calls)
                    addSemanticScript?.invoke() ?: error("The script-tab test composition did not initialize.")
                    awaitWorkerDomCondition(
                        description = "semantic script tab",
                        condition = { workerScriptTab(root, "semantic.js") != null },
                        onReady = {
                            try {
                                workerScriptTab(root, "semantic.js")!!.click()
                                awaitWorkerDomCondition(
                                    description = "typed semantic script output",
                                    condition = { root.textContent.orEmpty().contains("semantic-import-count:1") },
                                    onReady = {
                                        assertEquals(0, factory.calls)
                                        assertEquals("semantic.js\u2715", root.querySelector(".hexana-tab-active")?.textContent)
                                        finishWorkerDom(composition, root, resolve, reject)
                                    },
                                    onFailure = { failure -> finishWorkerDom(composition, root, resolve, reject, failure) },
                                )
                            } catch (failure: Throwable) {
                                finishWorkerDom(composition, root, resolve, reject, failure)
                            }
                        },
                        onFailure = { failure -> finishWorkerDom(composition, root, resolve, reject, failure) },
                    )
                } catch (failure: Throwable) {
                    finishWorkerDom(composition, root, resolve, reject, failure)
                }
            }
        } catch (failure: Throwable) {
            finishWorkerDom(composition, root, resolve, reject, failure)
        }
    }

    @Test
    fun initialComponentWorkerRouteRendersGraphAndPartialMarkerWithoutWindowConstruction(): Promise<Unit> = Promise { resolve, reject ->
        val root = workerRoot()
        val factory = CountingLegacyProviderFactory()
        var composition: Composition? = null
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                var legacyTab by remember { mutableStateOf<String?>(null) }
                CompositionLocalProvider(LocalHost provides WorkerDomHost()) {
                    WorkerBackedWasmContent(
                        fileName = "component.wasm",
                        parentFileName = null,
                        bytes = additionFixtureBytes(),
                        response = componentResponse(partial = true),
                        capabilities = WebUiCapabilities(),
                        legacyWasmTab = legacyTab,
                        onLegacyWasmTabChanged = { legacyTab = it },
                        onLegacyWasmReparseRequested = { legacyTab = "Sections" },
                        legacyWasmProviderFactory = factory,
                    )
                }
            }

            afterWorkerDomFrames {
                try {
                    assertEquals(0, factory.calls)
                    assertTrue(root.textContent.orEmpty().contains("Component Model"))
                    assertNotNull(workerTab(root, "Imports"))
                    assertNotNull(workerTab(root, "Dependencies"))
                    assertNotNull(root.querySelector("[data-parser-worker-partial='byte_limit']"))
                    workerTab(root, "Dependencies")!!.click()
                    afterWorkerDomFrames {
                        try {
                            assertEquals(0, factory.calls)
                            assertNotNull(root.querySelector("[data-parser-worker-partial='byte_limit']"))
                            assertNotNull(root.querySelector("canvas"))
                            finishWorkerDom(composition, root, resolve, reject)
                        } catch (failure: Throwable) {
                            finishWorkerDom(composition, root, resolve, reject, failure)
                        }
                    }
                } catch (failure: Throwable) {
                    finishWorkerDom(composition, root, resolve, reject, failure)
                }
            }
        } catch (failure: Throwable) {
            finishWorkerDom(composition, root, resolve, reject, failure)
        }
    }

    @Test
    fun nonWasmDeferredActionsStayReadOnlyAndNeverConstructTheLegacyProvider(): Promise<Unit> = Promise { resolve, reject ->
        val root = workerRoot()
        val factory = CountingLegacyProviderFactory()
        var composition: Composition? = null
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                CompositionLocalProvider(LocalHost provides WorkerDomHost()) {
                    WorkerBackedWasmContent(
                        fileName = "application.apk",
                        parentFileName = null,
                        bytes = ByteArray(32),
                        response = zipResponseWithDeferredAction(),
                        capabilities = WebUiCapabilities(),
                        legacyWasmTab = null,
                        onLegacyWasmTabChanged = {},
                        onLegacyWasmReparseRequested = {},
                        legacyWasmProviderFactory = factory,
                    )
                }
            }

            afterWorkerDomFrames {
                try {
                    assertEquals(0, factory.calls)
                    afterWorkerDomFrames {
                        try {
                            val entry = root.querySelector(".hexana-panel-row") as? HTMLElement
                                ?: error("The Worker-backed ZIP entries table did not render a row.")
                            entry.click()
                            afterWorkerDomFrames {
                                try {
                                    assertEquals(0, factory.calls)
                                    assertTrue(root.textContent.orEmpty().contains("classes.dex"))
                                    finishWorkerDom(composition, root, resolve, reject)
                                } catch (failure: Throwable) {
                                    finishWorkerDom(composition, root, resolve, reject, failure)
                                }
                            }
                        } catch (failure: Throwable) {
                            finishWorkerDom(composition, root, resolve, reject, failure)
                        }
                    }
                } catch (failure: Throwable) {
                    finishWorkerDom(composition, root, resolve, reject, failure)
                }
            }
        } catch (failure: Throwable) {
            finishWorkerDom(composition, root, resolve, reject, failure)
        }
    }

    @Test
    fun nativeWorkerPresentationRendersBadgesSbomAndWorkerDerivedSizeTooltip(): Promise<Unit> = Promise { resolve, reject ->
        val root = workerRoot()
        val factory = CountingLegacyProviderFactory()
        var composition: Composition? = null
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                CompositionLocalProvider(LocalHost provides WorkerDomHost()) {
                    WorkerBackedWasmContent(
                        fileName = "native-image",
                        parentFileName = null,
                        bytes = ByteArray(32),
                        response = nativeResponse(),
                        capabilities = WebUiCapabilities(),
                        legacyWasmTab = null,
                        onLegacyWasmTabChanged = {},
                        onLegacyWasmReparseRequested = {},
                        legacyWasmProviderFactory = factory,
                    )
                }
            }

            afterWorkerDomFrames {
                try {
                    assertEquals(0, factory.calls)
                    assertNotNull(root.querySelector(".hexana-ni-badge"))
                    val sbomBadge = root.querySelector(".hexana-sbom-badge") as? HTMLElement
                        ?: error("The Worker-backed native presentation did not render the SBOM badge.")
                    assertNotNull(workerTab(root, "Top"))
                    assertNotNull(workerTab(root, "SBOM"))
                    val size = workerElementWithExactText(root, "32 B")?.parentElement as? HTMLElement
                        ?: error("The Worker-backed toolbar did not render the file size.")
                    workerMouseEnter(size)
                    afterWorkerDomFrames {
                        try {
                            assertTrue(root.textContent.orEmpty().contains("Contents (×2)"))
                            sbomBadge.click()
                            afterWorkerDomFrames {
                                try {
                                    assertEquals(0, factory.calls)
                                    assertEquals("SBOM", root.querySelector(".hexana-tab-active")?.textContent)
                                    finishWorkerDom(composition, root, resolve, reject)
                                } catch (failure: Throwable) {
                                    finishWorkerDom(composition, root, resolve, reject, failure)
                                }
                            }
                        } catch (failure: Throwable) {
                            finishWorkerDom(composition, root, resolve, reject, failure)
                        }
                    }
                } catch (failure: Throwable) {
                    finishWorkerDom(composition, root, resolve, reject, failure)
                }
            }
        } catch (failure: Throwable) {
            finishWorkerDom(composition, root, resolve, reject, failure)
        }
    }

    @Test
    fun legacyWatAndTopTabsAreTheOnlyClickTransitionsToTheInjectedLegacyConstructor(): Promise<Unit> = Promise { resolve, reject ->
        val root = workerRoot()
        val factory = CountingLegacyProviderFactory()
        var composition: Composition? = null
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                var legacyTab by remember { mutableStateOf<String?>(null) }
                CompositionLocalProvider(LocalHost provides WorkerDomHost()) {
                    WorkerBackedWasmContent(
                        fileName = "addition.wasm",
                        parentFileName = null,
                        bytes = additionFixtureBytes(),
                        response = coreResponse(),
                        capabilities = WebUiCapabilities(),
                        legacyWasmTab = legacyTab,
                        onLegacyWasmTabChanged = { legacyTab = it },
                        onLegacyWasmReparseRequested = { legacyTab = "Sections" },
                        legacyWasmProviderFactory = factory,
                    )
                }
            }

            afterWorkerDomFrames {
                try {
                    assertEquals(0, factory.calls)
                    workerTab(root, "Wat")!!.click()
                    awaitWorkerDomCondition(
                        description = "legacy Wat view",
                        condition = {
                            factory.calls == 1 && root.querySelector(".hexana-tab-active")?.textContent == "Wat"
                        },
                        onReady = {
                            assertEquals(1, factory.calls)
                            assertEquals("Wat", root.querySelector(".hexana-tab-active")?.textContent)
                            finishWorkerDom(composition, root, resolve, reject)
                        },
                        onFailure = { failure -> finishWorkerDom(composition, root, resolve, reject, failure) },
                    )
                } catch (failure: Throwable) {
                    finishWorkerDom(composition, root, resolve, reject, failure)
                }
            }
        } catch (failure: Throwable) {
            finishWorkerDom(composition, root, resolve, reject, failure)
        }
    }

    @Test
    fun sizeClickTransitionsToLegacyTopExactlyOnce(): Promise<Unit> = Promise { resolve, reject ->
        val root = workerRoot()
        val factory = CountingLegacyProviderFactory()
        var composition: Composition? = null
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                var legacyTab by remember { mutableStateOf<String?>(null) }
                CompositionLocalProvider(LocalHost provides WorkerDomHost()) {
                    WorkerBackedWasmContent(
                        fileName = "addition.wasm",
                        parentFileName = null,
                        bytes = additionFixtureBytes(),
                        response = coreResponse(),
                        capabilities = WebUiCapabilities(),
                        legacyWasmTab = legacyTab,
                        onLegacyWasmTabChanged = { legacyTab = it },
                        onLegacyWasmReparseRequested = { legacyTab = "Sections" },
                        legacyWasmProviderFactory = factory,
                    )
                }
            }

            afterWorkerDomFrames {
                try {
                    assertEquals(0, factory.calls)
                    workerElementWithExactText(root, "55 B")!!.parentElement!!.unsafeCast<HTMLElement>().click()
                    awaitWorkerDomCondition(
                        description = "legacy Top view after size click",
                        condition = {
                            factory.calls == 1 && root.querySelector(".hexana-tab-active")?.textContent == "Top"
                        },
                        onReady = {
                            assertEquals(1, factory.calls)
                            assertEquals("Top", root.querySelector(".hexana-tab-active")?.textContent)
                            finishWorkerDom(composition, root, resolve, reject)
                        },
                        onFailure = { failure -> finishWorkerDom(composition, root, resolve, reject, failure) },
                    )
                } catch (failure: Throwable) {
                    finishWorkerDom(composition, root, resolve, reject, failure)
                }
            }
        } catch (failure: Throwable) {
            finishWorkerDom(composition, root, resolve, reject, failure)
        }
    }

    @Test
    fun editReparseTransitionsToLegacySectionsExactlyOnce(): Promise<Unit> = Promise { resolve, reject ->
        val root = workerRoot()
        val factory = CountingLegacyProviderFactory()
        var composition: Composition? = null
        var reparsedBytes: ByteArray? = null
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                var legacyTab by remember { mutableStateOf<String?>(null) }
                CompositionLocalProvider(LocalHost provides WorkerDomHost()) {
                    WorkerBackedWasmContent(
                        fileName = "addition.wasm",
                        parentFileName = null,
                        bytes = additionFixtureBytes(),
                        response = coreResponse(),
                        capabilities = WebUiCapabilities(),
                        legacyWasmTab = legacyTab,
                        onLegacyWasmTabChanged = { legacyTab = it },
                        onLegacyWasmReparseRequested = { edited ->
                            reparsedBytes = edited
                            legacyTab = "Sections"
                        },
                        legacyWasmProviderFactory = factory,
                    )
                }
            }

            afterWorkerDomFrames {
                try {
                    assertEquals(0, factory.calls)
                    val firstByte = root.querySelector(".hex-byte") as? HTMLElement
                        ?: error("The Worker-backed hex viewer did not render a byte cell.")
                    workerMouseDown(firstByte)
                    val hexViewer = root.querySelector(".hex-viewer-scroll") as? HTMLElement
                        ?: error("The Worker-backed hex viewer did not render its scroll container.")
                    workerKeyDown(hexViewer, "f")
                    workerKeyDown(hexViewer, "f")
                    awaitWorkerDomCondition(
                        description = "Discard control after a hex edit",
                        condition = { workerElementWithExactText(root, "Discard") != null },
                        onReady = {
                            assertNotNull(root.querySelector(".hex-edited"))
                            workerElementWithExactText(root, "Discard")!!.click()
                            awaitWorkerDomCondition(
                                description = "legacy Sections view after edit reparse",
                                condition = {
                                    factory.calls == 1 && reparsedBytes != null &&
                                        root.querySelector(".hexana-tab-active")?.textContent == "Sections"
                                },
                                onReady = {
                                    assertEquals(1, factory.calls)
                                    assertTrue(reparsedBytes!!.contentEquals(additionFixtureBytes()))
                                    assertEquals("Sections", root.querySelector(".hexana-tab-active")?.textContent)
                                    finishWorkerDom(composition, root, resolve, reject)
                                },
                                onFailure = { failure -> finishWorkerDom(composition, root, resolve, reject, failure) },
                            )
                        },
                        onFailure = { failure -> finishWorkerDom(composition, root, resolve, reject, failure) },
                    )
                } catch (failure: Throwable) {
                    finishWorkerDom(composition, root, resolve, reject, failure)
                }
            }
        } catch (failure: Throwable) {
            finishWorkerDom(composition, root, resolve, reject, failure)
        }
    }
}

private class CountingLegacyProviderFactory : LegacyWasmProviderFactory {
    var calls = 0

    override fun create(bytes: ByteArray, fileName: String): BinaryProvider {
        calls++
        return WasmBinaryProvider(bytes.toCommonByteBuffer())
    }
}

private class WorkerDomHost : Host {
    override val canRunExport: Boolean = true
    override fun showWat(watText: String, warning: String?) = Unit
    override fun runExport(exportName: String, invokeArgs: String, programArgs: String, isComponent: Boolean, wasmBytes: ByteArray?, runtime: String?, action: String?) = Unit
    override fun tabSelected(tabName: String) = Unit
    override fun updateRuntimeSetting(runtime: String) = Unit
    override fun navigateToParent() = Unit
    override fun saveAs() = Unit
    override fun openZipEntry(name: String, dataOffset: Long, compressedSize: Long, uncompressedSize: Long, compressionMethod: Int) = Unit
    override fun openTarEntry(name: String, dataOffset: Long, size: Long) = Unit
    override fun overwriteFile(bytes: ByteArray) = Unit
    override fun saveCopy(outputName: String, bytes: ByteArray) = Unit
    override fun openUrl(url: String) = Unit
    override fun openModule(moduleIndex: Int, fileName: String, data: ByteArray) = Unit
    override fun openDts(raw: Boolean) = Unit
    override fun openHttpBody(fileName: String, bodyBytes: ByteArray, httpContentType: String?, httpContentEncoding: String?) = Unit
    override fun openTextFile(fileName: String, content: String) = Unit
    override fun pickScriptFile() = Unit
    override fun createScriptFromTemplate(fileName: String, template: String?, jsConfigContent: String?) = Unit
    override fun openScriptForEditing(scriptPath: String, jsConfigContent: String?) = Unit
    override fun readScriptFile(scriptPath: String) = Unit
    override fun scriptTabAdded(scriptPath: String) = Unit
    override fun scriptTabRemoved(scriptPath: String) = Unit
    override fun ready() = Unit
}

private fun coreResponse(): BoundedParserWorkerSuccess = BoundedParserWorkerSuccess(
    presentation = ParserWorkerPresentation(
        format = "core_wasm",
        capabilities = listOf("summary", "structural_ranges", "tables", "diagnostics"),
        summary = ParserWorkerSummary(
            "DedicatedWorkerGlobalScope", true, 55, 5, 1, 1, 1, 0.0,
            exportCount = 1,
            proposalCount = 1,
            graalWebImage = true,
            graalWebImageMatchedImportModules = listOf("compat", "interop"),
        ),
        ranges = listOf(ParserWorkerRange(0, 8, "header")),
        tables = listOf(ParserWorkerTable("Imports", listOf(ParserWorkerRecord("console.log", "console.log", offset = 18, size = 15)))),
        legacyTabs = listOf("Top", "Wat"),
        toolbar = ParserWorkerToolbar("_start", listOf("BulkMemoryOperations")),
        displayLabel = "Core Wasm",
    ),
    partial = ParserWorkerPartial(false, emptyList(), 2, 2, null),
    wireBytes = 0,
)

private fun componentResponse(partial: Boolean): BoundedParserWorkerSuccess = BoundedParserWorkerSuccess(
    presentation = ParserWorkerPresentation(
        format = "wasm_component",
        capabilities = listOf("summary", "structural_ranges", "tables", "component_dependency_graph"),
        summary = ParserWorkerSummary("DedicatedWorkerGlobalScope", true, 55, 4, 1, 0, 0, 0.0, exportCount = 1, moduleCount = 1),
        ranges = listOf(ParserWorkerRange(0, 8, "component header")),
        tables = listOf(ParserWorkerTable("Imports", listOf(ParserWorkerRecord("wasi:cli/run", "func", offset = 8, size = 1)))),
        componentGraph = ParserWorkerComponentGraph(
            nodes = listOf(
                ParserWorkerComponentGraphNode("module:0", "module", "core module 0"),
                ParserWorkerComponentGraphNode("export:0", "export", "run"),
            ),
            edges = listOf(ParserWorkerComponentGraphEdge("module:0", "export:0")),
        ),
        legacyTabs = listOf("Wat"),
        displayLabel = "Component Model",
    ),
    partial = if (partial) ParserWorkerPartial(true, listOf("byte_limit"), 4, 5, null) else ParserWorkerPartial(false, emptyList(), 4, 4, null),
    wireBytes = 0,
)

private fun zipResponseWithDeferredAction(): BoundedParserWorkerSuccess = BoundedParserWorkerSuccess(
    presentation = ParserWorkerPresentation(
        format = "zip",
        capabilities = listOf("summary", "tables"),
        summary = ParserWorkerSummary("DedicatedWorkerGlobalScope", true, 32, 0, 0, 0, 0, 0.0),
        tables = listOf(
            ParserWorkerTable(
                id = "Entries",
                records = listOf(
                    ParserWorkerRecord(
                        key = "classes.dex",
                        value = "classes.dex",
                        values = listOf("classes.dex", "deferred action"),
                        offset = 0,
                        size = 1,
                        actions = listOf(
                            ParserWorkerAction(
                                kind = "zip_entry",
                                name = "classes.dex",
                                dataOffset = 0,
                                compressedSize = 0,
                                uncompressedSize = 0,
                                compressionMethod = 0,
                            ),
                        ),
                    ),
                ),
                columns = listOf("Name", "Status"),
            ),
        ),
        displayLabel = "Zip File",
    ),
    partial = ParserWorkerPartial(false, emptyList(), 1, 1, null),
    wireBytes = 0,
)

private fun nativeResponse(): BoundedParserWorkerSuccess = BoundedParserWorkerSuccess(
    presentation = ParserWorkerPresentation(
        format = "elf",
        capabilities = listOf("summary", "tables"),
        summary = ParserWorkerSummary("DedicatedWorkerGlobalScope", true, 32, 0, 0, 0, 0, 0.0),
        tables = listOf(
            ParserWorkerTable(
                id = "Top",
                records = listOf(
                    ParserWorkerRecord("Contents", "16 B", listOf("Contents", "16 B"), numericValues = listOf(null, 16.0)),
                    ParserWorkerRecord("Contents", "16 B", listOf("Contents", "16 B"), numericValues = listOf(null, 16.0)),
                ),
                columns = listOf("Type", "Size"),
                defaultSortColumn = 1,
            ),
            ParserWorkerTable(
                id = "Native Image",
                records = listOf(ParserWorkerRecord("Version", "24.1.0", listOf("Version", "24.1.0"))),
                columns = listOf("Property", "Value"),
            ),
            ParserWorkerTable(
                id = "SBOM",
                records = listOf(ParserWorkerRecord("example", "1.0", listOf("example", "1.0"))),
                columns = listOf("Component", "Version"),
                prologueText = "CycloneDX 1.5 • 1 component • 1 dependency",
            ),
            ParserWorkerTable(
                id = "SBOM Dependencies",
                records = listOf(ParserWorkerRecord("example", "dependency", listOf("example", "dependency"))),
                columns = listOf("Component", "Depends on"),
            ),
        ),
        toolbar = ParserWorkerToolbar(
            nativeImage = ParserWorkerNativeImage(
                version = "24.1.0",
                edition = "community",
                platform = "linux-amd64",
                hasSbom = true,
            ),
        ),
        displayLabel = "ELF64",
    ),
    partial = ParserWorkerPartial(false, emptyList(), 5, 5, null),
    wireBytes = 0,
)

private fun additionFixtureBytes(): ByteArray = byteArrayOf(
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02,
    0x60, 0x01, 0x7f, 0x00, 0x60, 0x00, 0x00, 0x02, 0x0f, 0x01, 0x07,
    0x63, 0x6f, 0x6e, 0x73, 0x6f, 0x6c, 0x65, 0x03, 0x6c, 0x6f, 0x67,
    0x00, 0x00, 0x03, 0x02, 0x01, 0x01, 0x08, 0x01, 0x01, 0x0a, 0x0b,
    0x01, 0x09, 0x00, 0x41, 0x0a, 0x41, 0x03, 0x6a, 0x10, 0x00, 0x0b,
)

private fun workerRoot(): HTMLDivElement = document.createElement("div").unsafeCast<HTMLDivElement>().also {
    it.id = "worker-wasm-editor-test-root-${workerDomRootSequence++}"
}

private fun workerTab(root: HTMLElement, label: String): HTMLElement? =
    workerElements(root, ".hexana-tab").firstOrNull { it.textContent == label }

private fun workerScriptTab(root: HTMLElement, label: String): HTMLElement? =
    workerElements(root, ".hexana-tab").firstOrNull { it.textContent?.startsWith(label) == true }

private fun workerElementWithExactText(root: HTMLElement, text: String): HTMLElement? =
    workerElements(root, "span").firstOrNull { it.textContent == text }

private fun workerElements(root: HTMLElement, selector: String): List<HTMLElement> {
    val nodes = root.querySelectorAll(selector)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? HTMLElement }
}

private fun afterWorkerDomFrames(block: () -> Unit) {
    window.requestAnimationFrame {
        window.requestAnimationFrame { block() }
    }
}

private fun awaitWorkerDomCondition(
    description: String,
    condition: () -> Boolean,
    onReady: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    val deadline = window.performance.now() + WORKER_DOM_TRANSITION_TIMEOUT_MS
    fun poll() {
        try {
            if (condition()) {
                onReady()
            } else if (window.performance.now() >= deadline) {
                onFailure(Throwable("Timed out waiting for $description."))
            } else {
                window.requestAnimationFrame { poll() }
            }
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }
    window.requestAnimationFrame { poll() }
}

private fun workerMouseDown(target: HTMLElement) {
    val event = js("new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 })")
    target.dispatchEvent(event.unsafeCast<Event>())
}

private fun workerMouseEnter(target: HTMLElement) {
    val event = js("new MouseEvent('mouseenter', { bubbles: true, cancelable: true })")
    target.dispatchEvent(event.unsafeCast<Event>())
}

private fun workerKeyDown(target: HTMLElement, key: String) {
    val event = js("new KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: key })")
    target.dispatchEvent(event.unsafeCast<Event>())
}

private const val WORKER_DOM_TRANSITION_TIMEOUT_MS = 5_000.0

private fun finishWorkerDom(
    composition: Composition?,
    root: HTMLElement,
    resolve: (Unit) -> Unit,
    reject: (Throwable) -> Unit,
    failure: Throwable? = null,
) {
    composition?.dispose()
    root.parentNode?.removeChild(root)
    if (failure == null) resolve(Unit) else reject(failure)
}

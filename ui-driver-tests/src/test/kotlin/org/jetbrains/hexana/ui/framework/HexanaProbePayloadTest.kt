package org.jetbrains.hexana.ui.framework

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HexanaProbePayloadTest {
    @Test
    fun snapshotParsesVersionedPayload() {
        val snapshot = HexanaProbePayload.snapshot(
            listOf(
                "schema=hexana-editor-snapshot",
                "version=1",
                "fileName=hello.wasm",
                "providerKind=core-wasm",
                "selectedTabName=Functions",
                "cursorOffset=42",
                "panel=Functions",
                "panel=Exports",
                "panelsResolved=true",
            ),
        )

        assertEquals("hello.wasm", snapshot.fileName)
        assertEquals("core-wasm", snapshot.providerKind)
        assertEquals(listOf("Functions", "Exports"), snapshot.panelNames)
        assertEquals("Functions", snapshot.selectedTabName)
        assertEquals(42, snapshot.cursorOffset)
        assertTrue(snapshot.panelsResolved)
    }

    @Test
    fun emptyOptionalValuesParseAsNull() {
        val snapshot = HexanaProbePayload.snapshot(
            listOf(
                "schema=hexana-editor-snapshot",
                "version=1",
                "fileName=hello.wasm",
                "providerKind=core-wasm",
                "selectedTabName=",
                "cursorOffset=",
                "panelsResolved=false",
            ),
        )

        assertNull(snapshot.selectedTabName)
        assertNull(snapshot.cursorOffset)
    }

    @Test
    fun searchStateParsesVersionedPayload() {
        val state = HexanaProbePayload.searchState(
            listOf(
                "schema=hexana-editor-search-state",
                "version=1",
                "query=00 61 73 6d",
                "binaryMode=true",
                "resultCount=2",
                "currentResultIndex=1",
                "cursorOffset=8",
            ),
        )

        assertEquals("00 61 73 6d", state.query)
        assertTrue(state.binaryMode)
        assertEquals(2, state.resultCount)
        assertEquals(1, state.currentResultIndex)
        assertEquals(8, state.cursorOffset)
    }

    @Test
    fun watStateParsesVersionedPayload() {
        val state = HexanaProbePayload.watState(
            listOf(
                "schema=hexana-editor-wat-state",
                "version=1",
                "selectedTabName=Wat",
                "conversionState=show-lite-editor",
                "containsModuleText=true",
                "containsFunctionText=true",
                "textSummary=(module | (func)",
                "error=",
            ),
        )

        assertEquals("Wat", state.selectedTabName)
        assertEquals("show-lite-editor", state.conversionState)
        assertTrue(state.containsModuleText)
        assertTrue(state.containsFunctionText)
        assertEquals("(module | (func)", state.textSummary)
        assertNull(state.error)
        assertTrue(state.isSuccess)
    }

    @Test
    fun wasmDebuggerTabStateParsesVersionedPayload() {
        val state = HexanaProbePayload.wasmDebuggerTabState(
            listOf(
                "schema=hexana-wasm-debugger-tab-state",
                "version=1",
                "sessionActive=true",
                "sessionSuspended=true",
                "tabRegistered=true",
                "tabSelected=true",
                "disassemblyRowCount=12",
                "selectedRow=3",
                "selectedOffset=155",
                "selectedInstruction=  local.get 0",
                "operandStackRowCount=2",
            ),
        )

        assertTrue(state.sessionActive)
        assertTrue(state.sessionSuspended)
        assertTrue(state.tabRegistered)
        assertTrue(state.tabSelected)
        assertEquals(12, state.disassemblyRowCount)
        assertEquals(3, state.selectedRow)
        assertEquals(155, state.selectedOffset)
        assertEquals("  local.get 0", state.selectedInstruction)
        assertEquals(2, state.operandStackRowCount)
        assertTrue(state.hasHighlightedInstruction)
    }

    @Test
    fun wasmDebuggerTabStateWithoutSessionHasNoHighlight() {
        val state = HexanaProbePayload.wasmDebuggerTabState(
            listOf(
                "schema=hexana-wasm-debugger-tab-state",
                "version=1",
                "sessionActive=false",
                "sessionSuspended=false",
                "tabRegistered=false",
                "tabSelected=false",
                "disassemblyRowCount=0",
                "selectedRow=-1",
                "selectedOffset=-1",
                "selectedInstruction=",
                "operandStackRowCount=0",
            ),
        )

        assertFalse(state.sessionActive)
        assertFalse(state.hasHighlightedInstruction)
        assertEquals("", state.selectedInstruction)
    }

    @Test
    fun schemaMismatchFailsWithPayloadContext() {
        val error = assertThrows(AssertionError::class.java) {
            HexanaProbePayload.searchState(
                listOf(
                    "schema=hexana-editor-snapshot",
                    "version=1",
                    "fileName=hello.wasm",
                ),
            )
        }

        assertTrue(error.message!!.contains("schema mismatch"))
        assertTrue(error.message!!.contains("expected schema=hexana-editor-search-state version=1"))
        assertTrue(error.message!!.contains("actual schema=hexana-editor-snapshot version=1"))
        assertTrue(error.message!!.contains("payload="))
    }

    @Test
    fun malformedEntryFailsWithPayloadContext() {
        val error = assertThrows(AssertionError::class.java) {
            HexanaProbePayload.snapshot(
                listOf(
                    "schema=hexana-editor-snapshot",
                    "version=1",
                    "fileName",
                ),
            )
        }

        assertTrue(error.message!!.contains("malformed entry 'fileName'"))
        assertTrue(error.message!!.contains("payload="))
    }
}

package org.jetbrains.hexana.ui.framework

import org.junit.jupiter.api.Assertions.assertEquals
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
            ),
        )

        assertEquals("hello.wasm", snapshot.fileName)
        assertEquals("core-wasm", snapshot.providerKind)
        assertEquals(listOf("Functions", "Exports"), snapshot.panelNames)
        assertEquals("Functions", snapshot.selectedTabName)
        assertEquals(42, snapshot.cursorOffset)
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

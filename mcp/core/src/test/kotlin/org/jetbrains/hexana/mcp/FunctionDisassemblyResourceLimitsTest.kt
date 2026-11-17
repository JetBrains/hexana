package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.query.BoundedTextBuilder
import org.jetbrains.hexana.mcp.query.readFunctionDisassembly
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FunctionDisassemblyResourceLimitsTest {
    @Test
    fun directCallerRemainsUnboundedWhileConfiguredServiceRejectsExpansion() {
        val directResult = WasmBinaryProvider(SIXTY_THOUSAND_AND_ONE_LOCALS_WASM.toCommonByteBuffer())
            .readFunctionDisassembly(funcidx = 0)

        assertEquals(60_001, directResult.localGroups.single().count)
        assertTrue(directResult.wat.orEmpty().encodeToByteArray().size > HexanaAnalysisLimits.MAX_OUTPUT_BYTES)
        assertTrue(!directResult.wat.orEmpty().contains("[truncated:"))

        val error = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService().disassembleFunction(
                WasmInput.RawBytes(SIXTY_THOUSAND_AND_ONE_LOCALS_WASM),
                funcidx = 0
            )
        }
        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
    }

    @Test(timeout = 5_000)
    fun rejectsAttackerControlledLocalExpansionBeforeRendering() {
        val error = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService().disassembleFunction(
                WasmInput.RawBytes(ONE_MILLION_LOCALS_WASM),
                funcidx = 0
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.contains("more than 60000 locals"))
    }

    @Test
    fun localExpansionGuardUsesConfiguredOutputLimit() {
        val service = DefaultHexanaAnalysisService(
            limits = HexanaResourceLimits(maxOutputBytes = 128)
        )

        val error = assertFailsWith<HexanaAnalysisException> {
            service.disassembleFunction(WasmInput.RawBytes(ONE_HUNDRED_TWENTY_NINE_LOCALS_WASM), funcidx = 0)
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.contains("more than 128 locals"))
    }

    @Test
    fun boundsWatAndHexPreviewsWithExplicitTruncationMarkers() {
        val maxOutputBytes = 8_000
        val maxPreviewBytes = 250
        val service = DefaultHexanaAnalysisService(
            limits = HexanaResourceLimits(maxOutputBytes = maxOutputBytes)
        )
        val instructions = List(100) { "nop" }.joinToString("\n")
        val wasm = wat2wasm("(module (func $instructions))")

        val result = service.disassembleFunction(WasmInput.RawBytes(wasm), funcidx = 0)

        assertTrue(result.wat.orEmpty().contains("[truncated:"))
        assertTrue(result.hex.orEmpty().contains("[truncated:"))
        assertTrue(result.wat.orEmpty().encodeToByteArray().size <= maxPreviewBytes)
        assertTrue(result.hex.orEmpty().encodeToByteArray().size <= maxPreviewBytes)
    }

    @Test
    fun truncationMarkerMustFitCompletely() {
        val marker = "[truncated]"
        val markerBytes = marker.encodeToByteArray().size

        listOf(1, markerBytes - 1).forEach { budget ->
            val output = BoundedTextBuilder(budget, marker, "test preview")
            assertTrue(!output.append("x".repeat(markerBytes + 1)))
            val error = assertFailsWith<HexanaAnalysisException> { output.truncatedText() }
            assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
            assertTrue(error.message.contains("requires $markerBytes bytes"))
        }

        val exact = BoundedTextBuilder(markerBytes, marker, "test preview")
        assertTrue(!exact.append("x".repeat(markerBytes + 1)))
        assertEquals(marker, exact.truncatedText())
    }

    private companion object {
        val ONE_MILLION_LOCALS_WASM = hexBytes(
            "00 61 73 6d 01 00 00 00 01 04 01 60 00 00 03 02 01 00 " +
                "0a 08 01 06 01 c0 84 3d 7f 0b"
        )
        val ONE_HUNDRED_TWENTY_NINE_LOCALS_WASM = hexBytes(
            "00 61 73 6d 01 00 00 00 01 04 01 60 00 00 03 02 01 00 " +
                "0a 07 01 05 01 81 01 7f 0b"
        )
        val SIXTY_THOUSAND_AND_ONE_LOCALS_WASM = hexBytes(
            "00 61 73 6d 01 00 00 00 01 04 01 60 00 00 03 02 01 00 " +
                "0a 08 01 06 01 e1 d4 03 7f 0b"
        )

        fun hexBytes(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    }
}

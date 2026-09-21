package org.jetbrains.hexana.cli.scripting.sarif

import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import org.junit.Test
import kotlin.test.assertEquals

class SarifFingerprintTest {

    @Test
    fun fingerprintForFunctionAnchor() {
        val finding = CheckFinding(
            checkId = "check.id.error",
            severity = CheckSeverity.ERROR,
            message = "some error",
            anchor = ScriptAnchor.Function(funcidx = 42),
        )
        assertEquals(
            "0b5ef7f77626955fe0d352e36d8389d96680ad350abbb5f73741577477c9117a",
            findingFingerprint(finding),
        )
    }

    @Test
    fun fingerprintForNullAnchor() {
        val finding = CheckFinding(
            checkId = "check.id.error",
            severity = CheckSeverity.ERROR,
            message = "some error",
            anchor = null,
        )
        assertEquals(
            "b991ca875cd82f85ba6abe3161a32d6424b4bd1b13eb80d46580772fe19cf274",
            findingFingerprint(finding),
        )
    }

    @Test
    fun fingerprintForByteRangeAnchor() {
        val finding = CheckFinding(
            checkId = "check.id.warn",
            severity = CheckSeverity.WARNING,
            message = "warn",
            anchor = ScriptAnchor.ByteRange(offset = 100, length = 32),
        )
        assertEquals(
            "44043b1d6a9b2478ce1b95e62592721f758dde745a6cf4599a03d1abdf838f4a",
            findingFingerprint(finding),
        )
    }

    @Test
    fun fingerprintForFunctionZero() {
        val finding = CheckFinding(
            checkId = "check.id.info",
            severity = CheckSeverity.INFO,
            message = "info",
            anchor = ScriptAnchor.Function(funcidx = 0),
        )
        assertEquals(
            "c2f23544768682a08263cd155af057c8292a0f791df3ba80eda00a5ca983e409",
            findingFingerprint(finding),
        )
    }

    @Test
    fun canonicalAnchorStrings() {
        assertEquals("function:42", canonicalAnchorString(ScriptAnchor.Function(funcidx = 42)))
        assertEquals("bytes:0:8", canonicalAnchorString(ScriptAnchor.ByteRange(offset = 0, length = 8)))
        assertEquals("file", canonicalAnchorString(null))
    }
}

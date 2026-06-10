package org.jetbrains.hexana.nativebin.vuln

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CvssTest {

    @Test
    fun `computes v3_1 base scores`() {
        val critical = Cvss.parse("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H")
        assertEquals(9.8, critical.base!!, 0.0001)
        assertEquals(CvssSeverity.CRITICAL, critical.severity)

        val high = Cvss.parse("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N")
        assertEquals(7.5, high.base!!, 0.0001)
        assertEquals(CvssSeverity.HIGH, high.severity)

        // Log4Shell — scope changed, max score.
        val log4shell = Cvss.parse("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H")
        assertEquals(10.0, log4shell.base!!, 0.0001)
        assertEquals(CvssSeverity.CRITICAL, log4shell.severity)
    }

    @Test
    fun `unparseable or non-v3 vectors are unknown`() {
        val v2 = Cvss.parse("CVSS:2.0/AV:N/AC:L/Au:N/C:P/I:P/A:P")
        assertNull(v2.base)
        assertEquals(CvssSeverity.UNKNOWN, v2.severity)

        val garbage = Cvss.parse("not a vector")
        assertNull(garbage.base)
        assertEquals(CvssSeverity.UNKNOWN, garbage.severity)
    }

    @Test
    fun `severity buckets follow the spec`() {
        assertEquals(CvssSeverity.NONE, Cvss.severityForScore(0.0))
        assertEquals(CvssSeverity.LOW, Cvss.severityForScore(3.9))
        assertEquals(CvssSeverity.MEDIUM, Cvss.severityForScore(4.0))
        assertEquals(CvssSeverity.HIGH, Cvss.severityForScore(7.0))
        assertEquals(CvssSeverity.CRITICAL, Cvss.severityForScore(9.0))
    }
}

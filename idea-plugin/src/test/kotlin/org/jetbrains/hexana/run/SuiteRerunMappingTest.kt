package org.jetbrains.hexana.run

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.testFramework.UsefulTestCase

/**
 * Rerun-failed mapping: SM test proxies -> engine describe paths via
 * [describePathForProxy] (parent walk, SM root and file-level suite excluded).
 */
class SuiteRerunMappingTest : UsefulTestCase() {

    fun testNestedProxyPath() {
        val root = SMTestProxy.SMRootTestProxy()
        val fileSuite = SMTestProxy("math.test.js", true, null)
        root.addChild(fileSuite)
        val vectors = SMTestProxy("vectors", true, null)
        fileSuite.addChild(vectors)
        val inner = SMTestProxy("norms", true, null)
        vectors.addChild(inner)
        val test = SMTestProxy("adds", false, null)
        inner.addChild(test)
        assertEquals(listOf("vectors", "norms", "adds"), describePathForProxy(test))
    }

    fun testTopLevelProxyPath() {
        val root = SMTestProxy.SMRootTestProxy()
        val fileSuite = SMTestProxy("math.test.js", true, null)
        root.addChild(fileSuite)
        val test = SMTestProxy("adds", false, null)
        fileSuite.addChild(test)
        assertEquals(listOf("adds"), describePathForProxy(test))
    }

    fun testFileSuiteItselfMapsToNull() {
        val root = SMTestProxy.SMRootTestProxy()
        val fileSuite = SMTestProxy("math.test.js", true, null)
        root.addChild(fileSuite)
        assertNull(describePathForProxy(fileSuite))
    }
}

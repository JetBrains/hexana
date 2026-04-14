package org.jetbrains.hexana.javascript

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil
import com.intellij.lang.javascript.psi.types.JSContextualUnionTypeImpl
import com.intellij.lang.javascript.psi.types.JSFunctionTypeImpl
import com.intellij.lang.javascript.psi.types.JSStringLiteralTypeImpl
import com.intellij.lang.javascript.psi.types.primitives.JSNumberType
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class JsResolveTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String {
        return Path.of("src/test/data/js").toAbsolutePath().toString()
    }

    fun testCalculatorWasmExportResolve() {
        myFixture.copyFileToProject("calculator.wasm")
        val psiFile = myFixture.configureByFile("calculatorWasmExportResolve.js")
        assert(psiFile is JSFile)

        val callExpr = SyntaxTraverser.psiTraverser(psiFile)
            .filterIsInstance<JSCallExpression>()
            .find { (it.methodExpression as? JSReferenceExpression)?.referencedName == "calculate" }
        assertNotNull("Could not find calculate() call expression", callExpr)

        val methodRef = callExpr!!.methodExpression as JSReferenceExpression
        val jsType = JSResolveUtil.getExpressionJSType(methodRef)
        assertNotNull("calculate reference has no JS type", jsType)
        assertInstanceOf(jsType, JSFunctionTypeImpl::class.java)

        val functionType = jsType as JSFunctionTypeImpl
        assertEquals("calculate should have 3 parameters", 3, functionType.parameters.size)
        val firstArgAsUnion = functionType.parameters[0].simpleType as? JSContextualUnionTypeImpl
        assert(firstArgAsUnion != null)
        assert(firstArgAsUnion!!.types.size == 2)
        assert(firstArgAsUnion.types.all { it is JSStringLiteralTypeImpl && (it.literal == "add" || it.literal == "sub") })
        assert(functionType.parameters[1].simpleType is JSNumberType)
        assert(functionType.parameters[2].simpleType is JSNumberType)
    }

    fun testClassicModule() {
        myFixture.copyFileToProject("add.wasm")
        val psiFile = myFixture.configureByFile("classicModule.js")
        assert(psiFile is JSFile)

        val callExpr = SyntaxTraverser.psiTraverser(psiFile)
            .filterIsInstance<JSCallExpression>()
            .find { (it.methodExpression as? JSReferenceExpression)?.referencedName == "add" }
        assertNotNull("Could not find add() call expression", callExpr)

        val methodRef = callExpr!!.methodExpression as JSReferenceExpression
        val jsType = JSResolveUtil.getExpressionJSType(methodRef)
        assertNotNull("add reference has no JS type", jsType)
        assertInstanceOf(jsType, JSFunctionTypeImpl::class.java)

        val functionType = jsType as JSFunctionTypeImpl
        assertEquals("add should have 2 parameters", 2, functionType.parameters.size)
        assert(functionType.parameters[0].simpleType is JSNumberType)
        assert(functionType.parameters[1].simpleType is JSNumberType)
    }

    fun testCompileStreaming() {
        doTestSimpleWasm("compileStreaming.js")
    }

    fun testInstantiateBytesHack() {
        doTestSimpleWasm("instantiateBytesHack.js")
    }

    fun testTrickyInstantiate() {
        doTestSimpleWasm("trickyInstantiate.js")
    }

    private fun doTestSimpleWasm(jsFile: String) {
        myFixture.copyFileToProject("simple.wasm")
        val psiFile = myFixture.configureByFile(jsFile)
        assert(psiFile is JSFile)

        val callExpr = SyntaxTraverser.psiTraverser(psiFile)
            .filterIsInstance<JSCallExpression>()
            .find { (it.methodExpression as? JSReferenceExpression)?.referencedName == "exported_func" }
        assertNotNull("Could not find exported_func() call expression", callExpr)

        val methodRef = callExpr!!.methodExpression as JSReferenceExpression
        val jsType = JSResolveUtil.getExpressionJSType(methodRef)
        assertNotNull("exported_func reference has no JS type", jsType)
        assertInstanceOf(jsType, JSFunctionTypeImpl::class.java)

        val functionType = jsType as JSFunctionTypeImpl
        assertEquals("add should have 0 parameters", 0, functionType.parameters.size)
    }
}

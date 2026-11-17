package org.jetbrains.hexana.run

import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.hexana.suite.HexanaSuiteRunConfiguration
import org.jetbrains.hexana.suite.HexanaSuiteRunConfigurationProducer

/**
 * Producer disambiguation between the Hexana Script and Hexana Suite run
 * configurations: `*.test.js` belongs to the suite producer, plain `*.js`
 * stays with the script producer. No GraalJS involved: producers only look at
 * file names.
 */
class SuiteRunConfigurationProducerTest : BasePlatformTestCase() {

    private fun contextFor(fileName: String): ConfigurationContext {
        val psiFile = myFixture.configureByText(fileName, "// suite producer test\n")
        val location = PsiLocation.fromPsiElement(psiFile)
        assertNotNull(location)
        return ConfigurationContext.createEmptyContextForLocation(location!!)
    }

    private val suiteProducer: HexanaSuiteRunConfigurationProducer
        get() = RunConfigurationProducer.getInstance(HexanaSuiteRunConfigurationProducer::class.java)

    private val scriptProducer: HexanaScriptRunConfigurationProducer
        get() = RunConfigurationProducer.getInstance(HexanaScriptRunConfigurationProducer::class.java)

    fun testSuiteProducerClaimsTestJs() {
        val context = contextFor("foo.test.js")
        val fromContext = suiteProducer.createConfigurationFromContext(context)
        assertNotNull("suite producer must claim foo.test.js", fromContext)
        val configuration = fromContext!!.configuration as HexanaSuiteRunConfiguration
        assertTrue(configuration.suitePath.endsWith("foo.test.js"))
        assertEquals("foo", configuration.name)
        assertTrue(suiteProducer.isConfigurationFromContext(configuration, context))
    }

    fun testSuiteProducerRejectsPlainJs() {
        val context = contextFor("foo.js")
        assertNull("suite producer must not claim foo.js", suiteProducer.createConfigurationFromContext(context))
    }

    fun testScriptProducerRejectsTestJs() {
        val context = contextFor("foo.test.js")
        assertNull(
            "script producer must no longer claim foo.test.js",
            scriptProducer.createConfigurationFromContext(context),
        )
        // Reverse direction: even with a matching scriptPath the script
        // configuration is not "from" a .test.js context.
        val configuration = HexanaScriptRunConfiguration(
            project,
            HexanaScriptConfigurationFactory(HexanaScriptRunConfigurationType()),
            "tmp",
        )
        configuration.scriptPath = context.location!!.virtualFile!!.path
        assertFalse(scriptProducer.isConfigurationFromContext(configuration, context))
    }

    fun testScriptProducerStillClaimsPlainJs() {
        val context = contextFor("foo.js")
        val fromContext = scriptProducer.createConfigurationFromContext(context)
        assertNotNull("script producer must still claim foo.js", fromContext)
        val configuration = fromContext!!.configuration as HexanaScriptRunConfiguration
        assertTrue(configuration.scriptPath.endsWith("foo.js"))
        assertTrue(scriptProducer.isConfigurationFromContext(configuration, context))
    }
}

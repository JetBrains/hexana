package org.jetbrains.hexana.wit.formatter

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import org.jetbrains.hexana.wit.WitLanguage

class WitCodeStyleSettingsProvider  : CodeStyleSettingsProvider() {
    override fun createConfigurable(settings: CodeStyleSettings,
                                    originalSettings: CodeStyleSettings): CodeStyleConfigurable {
        return object : CodeStyleAbstractConfigurable(settings, originalSettings, "WIT") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel {
                val language: Language = WitLanguage
                val currentSettings = currentSettings
                return object : TabbedLanguageCodeStylePanel(language, currentSettings, settings) {
                    override fun initTabs(settings: CodeStyleSettings) {
                        addIndentOptionsTab(settings)
                        addSpacesTab(settings)
                        addBlankLinesTab(settings)
                        addWrappingAndBracesTab(settings)
                    }
                }
            }

            override fun getHelpTopic(): String {
                return "reference.settingsdialog.codestyle.wit"
            }
        }
    }

    override fun getConfigurableDisplayName(): String {
        return WitLanguage.displayName
    }

    override fun getLanguage(): Language {
        return WitLanguage
    }
}
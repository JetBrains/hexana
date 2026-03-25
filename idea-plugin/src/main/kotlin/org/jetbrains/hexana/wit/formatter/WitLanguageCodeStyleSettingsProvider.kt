package org.jetbrains.hexana.wit.formatter

import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import org.jetbrains.hexana.wit.WitLanguage

class WitLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    private object Holder {
        val SAMPLE: String = """
        package component:calculator@0.1.0;

        interface calculate {
          enum op {
            add,
            sub,
          }
          calculate: func(op: op, x: u32, y: u32) -> u32;
        }

        world calculator {
          import component:adder/add@0.1.0;
          import component:subtractor/subtract@0.1.0;
          export calculate;
        }
      """.trimIndent()
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        when (settingsType) {
            SettingsType.SPACING_SETTINGS -> {
                consumer.showStandardOptions("SPACE_WITHIN_BRACKETS",
                    "SPACE_WITHIN_BRACES",
                    "SPACE_AFTER_COMMA",
                    "SPACE_BEFORE_COMMA")
            }
            SettingsType.BLANK_LINES_SETTINGS -> {
                consumer.showStandardOptions("KEEP_BLANK_LINES_IN_CODE")
            }
            SettingsType.WRAPPING_AND_BRACES_SETTINGS -> {
                consumer.showStandardOptions("RIGHT_MARGIN",
                    "WRAP_ON_TYPING",
                    "KEEP_LINE_BREAKS",
                    "WRAP_LONG_LINES")
            }
            else -> { /* other sections not implemented */ }
        }
    }

    override fun getLanguage(): Language {
        return WitLanguage
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor {
        return SmartIndentOptionsEditor()
    }

    override fun getCodeSample(settingsType: SettingsType): String {
        return Holder.SAMPLE
    }

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings,
                                   indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 2
        // strip all blank lines by default
        commonSettings.KEEP_BLANK_LINES_IN_CODE = 0
    }
}
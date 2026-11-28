package io.legado.app.data.repository

import java.util.Locale
import io.legado.app.domain.model.TranslationConstants

internal object TranslationCacheFileNames {
    private val completedPattern = Regex("""^\p{Nd}+-[0-9a-fA-F]{16}\.([a-zA-Z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*)\.nb$""")
    private val languages = TranslationConstants.targetLanguages.map { it.first }.toSet() + Locale.getISOLanguages().toSet() +
        Locale.getAvailableLocales().map(Locale::getLanguage) +
        setOf("iw", "ji", "in", "fil", "yue", "cmn", "haw", "hmn", "ceb")

    fun completed(subject: String, targetLanguage: String) = "$subject.$targetLanguage.nb"

    fun isCompleted(name: String): Boolean {
        val tag = completedPattern.matchEntire(name)?.groupValues?.get(1) ?: return false
        return tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT) in languages
    }
}

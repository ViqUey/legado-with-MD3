package io.legado.app.model.localBook

import io.legado.app.help.DefaultData

/** A title suggestion only. Never partitions, removes or rewrites chapter text. */
internal object RecoveredChapterTitle {
    const val prefixBytes = 512
    private const val maxTitleLength = 80
    private val chapterCue = Regex(
        """^(?:第\s*[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\s*[章节回卷部集篇]|序章|楔子|终章|后记|尾声|番外|(?:chapter|part|section)\s+\d+\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val builtInRules by lazy {
        DefaultData.txtTocRules.filter { it.enable }.mapNotNull {
            runCatching { Regex(it.chapterRule, RegexOption.MULTILINE) }.getOrNull()
        }
    }

    fun suggest(firstLine: String): String? =
        candidate(firstLine)?.takeIf { title ->
            chapterCue.containsMatchIn(title) &&
                builtInRules.any { pattern -> matchesLine(pattern, firstLine, title) }
        }

    fun match(prefix: String, pattern: Regex): String? {
        val line = prefix.lineSequence().take(4).firstOrNull { it.removePrefix("\uFEFF").isNotBlank() }
            ?: return null
        return candidate(line)?.takeIf { matchesLine(pattern, line, it) }
    }

    private fun candidate(line: String): String? = line.removePrefix("\uFEFF").trim().takeIf {
        it.isNotEmpty() && it.length <= maxTitleLength && '\u0000' !in it
    }

    private fun matchesLine(pattern: Regex, raw: String, title: String): Boolean {
        val line = raw.removePrefix("\uFEFF")
        if (line.length > maxTitleLength + 16) return false
        return pattern.matches(title) || pattern.find(line)?.value?.trim() == title
    }
}

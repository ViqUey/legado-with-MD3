package io.legado.app.data.repository

/** A stable, bounded file-name stem. Numbers resolve publication conflicts, not book metadata. */
internal class RecoveryExportNames(title: String, author: String, private val suffix: String) {
    private val titlePart: String
    private val authorPart: String

    init {
        val cleanTitle = clean(title).trim { it == '.' || it.isWhitespace() }.ifBlank { "Recovered" }
        val cleanAuthor = if (author.isBlank()) "" else clean(author).trim()
        // Reserve the largest possible number up front so every candidate keeps the same stem.
        val budget = MAX_BYTES - bytes(suffix) - bytes(number(LIMIT - 1))
        val titleReserve = minOf(bytes(cleanTitle), MIN_TITLE_BYTES)
        authorPart = truncate(cleanAuthor, budget - titleReserve - bytes(AUTHOR_SEPARATOR)).trimEnd()
        val authorBytes = if (authorPart.isEmpty()) 0 else bytes(AUTHOR_SEPARATOR) + bytes(authorPart)
        titlePart = truncate(cleanTitle, budget - authorBytes).trimEnd { it == '.' || it.isWhitespace() }
    }

    fun candidates(): Sequence<String> = (0 until LIMIT).asSequence().map { index ->
        titlePart + number(index) + (if (authorPart.isEmpty()) "" else AUTHOR_SEPARATOR + authorPart) + suffix
    }

    private fun number(index: Int) = if (index == 0) "" else "（$index）"

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8).size

    private fun clean(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val ch = value[index++]
            if (ch.isHighSurrogate() && index < value.length && value[index].isLowSurrogate()) {
                append(ch)
                append(value[index++])
            } else {
                append(if (ch.isSurrogate() || ch.isISOControl() || ch in "\\/:*?\"<>|") '_' else ch)
            }
        }
    }

    private fun truncate(value: String, budget: Int): String {
        var used = 0
        var end = 0
        while (end < value.length) {
            val point = value.codePointAt(end)
            val width = when {
                point <= 0x7f -> 1
                point <= 0x7ff -> 2
                point <= 0xffff -> 3
                else -> 4
            }
            if (used + width > budget) break
            used += width
            end += Character.charCount(point)
        }
        return value.substring(0, end)
    }

    companion object {
        private const val MAX_BYTES = 240
        private const val MIN_TITLE_BYTES = 48
        private const val LIMIT = 10_000
        private const val AUTHOR_SEPARATOR = " 作者："
    }
}

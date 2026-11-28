package io.legado.app.data.repository

import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.utils.MD5Utils

internal object RecoveryExportTitle {
    /** Remove at most one plain leading title from the export projection, never the cache. */
    fun body(text: String, selection: LocalRecoverySelection): String {
        val end = text.indexOfAny(charArrayOf('\r', '\n')).let { if (it < 0) text.length else it }
        val rawLine = text.substring(0, end).removePrefix("\uFEFF")
        val line = rawLine.trim()
        if (line.isEmpty()) return text
        // A line carrying resources or presentation markers is not a plain duplicate heading.
        if (line.contains("<img", ignoreCase = true) || line.contains("<usehtml>", ignoreCase = true) ||
            line.contains("</usehtml>", ignoreCase = true) || line.contains("[newpage]", ignoreCase = true)
        ) return text
        val hash = selection.source.titleHash
        val matches = line == selection.title.trim() || line == selection.source.originalTitle?.trim() ||
            (hash != null && (MD5Utils.md5Encode16(rawLine) == hash ||
                (line != rawLine && MD5Utils.md5Encode16(line) == hash)))
        // A suggested title alone is not evidence that the first line can be removed.
        if (!matches) return text
        if (end == text.length) return ""
        val separatorLength = if (text[end] == '\r' && text.getOrNull(end + 1) == '\n') 2 else 1
        return text.substring(end + separatorLength)
    }
}

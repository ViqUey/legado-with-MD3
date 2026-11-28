package io.legado.app.data.repository

import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.OrphanText
import io.legado.app.domain.model.OrphanTextId
import io.legado.app.utils.MD5Utils
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryExportTitleTest {
    private fun selection(
        title: String = "导出章名",
        original: String? = null,
        hash: String? = null,
        suggestion: String? = null,
    ) = LocalRecoverySelection(
        OrphanText(OrphanTextId("history.nb"), null, hash, original, 0, 0, suggestion),
        title, null,
    )

    @Test fun preservesBodyWithoutLeadingTitleAndDoesNotRemoveInteriorTitle() {
        val text = "正文第一段\n导出章名\n正文末段\n"
        assertEquals(text, RecoveryExportTitle.body(text, selection()))
        val prefix = "导出章名与正文连在同一行\n后文"
        assertEquals(prefix, RecoveryExportTitle.body(prefix, selection()))
    }

    @Test fun removesOneExactFinalTitleOnly() {
        assertEquals("导出章名\n正文", RecoveryExportTitle.body("导出章名\n导出章名\n正文", selection()))
        assertEquals("", RecoveryExportTitle.body("导出章名", selection()))
    }

    @Test fun removesKnownHistoricalTitleEvenWhenExportTitleWasEdited() {
        assertEquals("正文\n", RecoveryExportTitle.body("旧标题\n正文\n", selection(original = "旧标题")))
    }

    @Test fun matchesHistoricalHashWithoutKnownOriginalTitle() {
        val hash = MD5Utils.md5Encode16("历史标题")
        assertEquals("正文", RecoveryExportTitle.body("历史标题\n正文", selection(hash = hash)))
        val rawHash = MD5Utils.md5Encode16("　历史标题 ")
        assertEquals("正文", RecoveryExportTitle.body("　历史标题 \n正文", selection(hash = rawHash)))
    }

    @Test fun suggestionAloneDoesNotAuthorizeDeletion() {
        val text = "第八章 开始\n正文"
        assertEquals(text, RecoveryExportTitle.body(text, selection(suggestion = "第八章 开始")))
        assertEquals("正文", RecoveryExportTitle.body(text, selection(title = "第八章 开始", suggestion = "第八章 开始")))
    }

    @Test fun ignoresOnlyLeadingLineEdgeWhitespaceAndBomAndPreservesTheRest() {
        val selection = selection(hash = MD5Utils.md5Encode16("旧标题"))
        val remainder = "\n　正文 \r\n后文\r"
        assertEquals(remainder, RecoveryExportTitle.body("\uFEFF　旧标题 \t\r\n$remainder", selection))
        assertEquals("正文", RecoveryExportTitle.body("旧标题\r正文", selection))
        val noMatch = "\uFEFF　正文 \r\n后文"
        assertEquals(noMatch, RecoveryExportTitle.body(noMatch, selection))
        val blankFirstLine = "\n旧标题\n正文"
        assertEquals(blankFirstLine, RecoveryExportTitle.body(blankFirstLine, selection))
    }

    @Test fun doesNotUseFuzzyMatchingOrNormalizeInteriorWhitespace() {
        val selected = selection(title = "Chapter 1", original = "第一章")
        for (text in listOf("chapter 1\n正文", "Chapter  1\n正文", "第一章！\n正文", "第一章的正文\n后文")) {
            assertEquals(text, RecoveryExportTitle.body(text, selected))
        }
    }

    @Test fun keepsResourceAndPresentationMarkersEvenWhenTitleMatches() {
        for (line in listOf("章名<img src=\"image.png\">", "<usehtml><h1>章名</h1></usehtml>", "章名[newpage]")) {
            val text = "$line\n正文"
            assertEquals(text, RecoveryExportTitle.body(text, selection(title = line)))
        }
    }
}

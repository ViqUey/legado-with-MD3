package io.legado.app.data.repository

import io.legado.app.domain.model.RecoveryLine
import io.legado.app.domain.model.RecoveryLineKind
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OrphanRecoveryRulesTest {
    @Test fun fileIndexMayBeLongAndUseLocaleDigits() {
        val hash = "f0abcdef01234567"
        assertEquals(100001 to hash, OrphanRecoveryRules.parseName("100001-$hash"))
        assertEquals(12 to hash, OrphanRecoveryRules.parseName("٠٠٠١٢-$hash"))
        assertEquals(null to null, OrphanRecoveryRules.parseName("historical"))
    }

    @Test fun completedTranslationsUseLocalizedDecimalDigitsAndKnownHistoricalLanguages() {
        val hash = "f0abcdef01234567"
        for (language in listOf("zh-Hant", "zh_CN", "iw", "in", "fil", "ja")) {
            assertTrue(language, TranslationCacheFileNames.isCompleted("٠٠٠١٢-$hash.$language.nb"))
        }
        for (name in listOf("１２-$hash.zz.nb", "12-$hash.zh.Hant.nb", "chapter.2019.ja.nb", "12-$hash.nb")) {
            assertFalse(name, TranslationCacheFileNames.isCompleted(name))
        }
    }

    @Test fun convertsOnlyTheMarkedHtmlAndPreservesVisibleTextAndImageOrder() {
        val plainImage = "https://img.test/old?id=x%2Fy&mode=2"
        val richImage = "https://img.test/new?id=x%2Fy&mode=3"
        val inlineImage = "data:image/png;base64,ABCD=="
        val source = """
            2 < 3 > 1 & <p>字面标签</p> &amp;
            <img src="$plainImage"><usehtml>
            <h3>小标题 &amp; 标记</h3><p>段落<br><a href="javascript:bad()">链接正文</a> &lt;继续&gt;</p>
            <table><tr><td>表格文字</td></tr></table><fiction-tag>未知标签文字</fiction-tag>
            <style>CSS HIDDEN</style><script>JS HIDDEN</script>
            <template><img src="hidden.png">TEMPLATE HIDDEN</template>
            <img src="https://img.test/new?id=x%2Fy&amp;mode=3" onerror="DANGEROUS">
            <img src="bubble"><img src='$inlineImage'>
            </usehtml>结束 <h1>另一处字面标签</h1>
        """.trimIndent()
        val seen = mutableListOf<String>()
        val xhtml = RecoveryChapterMarkup.render(source, { it == "bubble" }, "missing", {}, { error("Unexpected missing image") }) {
            seen += it
            "image-${seen.size}.png"
        }
        assertEquals(listOf(plainImage, richImage, inlineImage), seen)
        val output = Jsoup.parseBodyFragment(xhtml).body()
        assertEquals(listOf("image-1.png", "image-2.png", "image-3.png"), output.select("img").eachAttr("src"))
        val visible = output.text()
        assertTrue(visible.startsWith("2 < 3 > 1 & <p>字面标签</p> &amp;"))
        assertEquals("小标题 & 标记", output.select("h3").single().text())
        assertEquals("段落 链接正文 <继续>", output.select("p").single().text())
        assertEquals("表格文字", output.select("td").single().text())
        assertTrue(visible.contains("未知标签文字"))
        assertTrue(visible.endsWith("结束 <h1>另一处字面标签</h1>"))
        assertTrue(output.select("script,style,template,fiction-tag,a,[onerror],[href]").isEmpty())
        assertFalse("HIDDEN" in visible)
        assertFalse(RecoveryChapterMarkup.hasImages("<usehtml><template><img src=x></template><img src=bubble></usehtml>") { it == "bubble" })
    }

    @Test fun lineComparisonPreservesWhitespaceAndTerminators() {
        val lines = OrphanRecoveryRules.diff("same\nnew \n", "same\nold\n")
        assertEquals(listOf(
            RecoveryLine(RecoveryLineKind.Same, "same"),
            RecoveryLine(RecoveryLineKind.Removed, "old"),
            RecoveryLine(RecoveryLineKind.Added, "new "),
            RecoveryLine(RecoveryLineKind.Same, ""),
        ), lines)
    }

    @Test fun largeComparisonFallsBackToCompletePreviews() {
        assertNull(OrphanRecoveryRules.diff("章".repeat(100_000), "current"))
        assertNull(OrphanRecoveryRules.diff("line\n".repeat(500), "current"))
    }
}

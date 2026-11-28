package io.legado.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryExportNamesTest {
    @Test fun ordinaryNamesAndNumberingUseTheExistingChineseAuthorSeparator() {
        for (suffix in listOf(".txt", ".epub")) {
            val names = RecoveryExportNames("連幸村都惹只會害了你", "拉格朗日的猫", suffix)
            assertEquals(listOf(
                "連幸村都惹只會害了你 作者：拉格朗日的猫$suffix",
                "連幸村都惹只會害了你（1） 作者：拉格朗日的猫$suffix",
                "連幸村都惹只會害了你（2） 作者：拉格朗日的猫$suffix",
            ), names.candidates().take(3).toList())
        }
    }

    @Test fun blankAuthorIsOmittedAndBlankTitleHasASafeFallback() {
        assertEquals(listOf("Stand by Me.txt", "Stand by Me（1）.txt"),
            RecoveryExportNames("Stand by Me", " \t\n", ".txt").candidates().take(2).toList())
        assertEquals("Recovered.epub", RecoveryExportNames(" .　. ", "", ".epub").candidates().first())
    }

    @Test fun cleansBothComponentsWithoutAddingBookTitleBrackets() {
        assertEquals("书_名_ 作者：作_者.txt",
            RecoveryExportNames("  .书/名\u0000.  ", "作:者", ".txt").candidates().first())
        val invalid = "\\/:*?\"<>|\u0001\u007f"
        assertEquals("_".repeat(invalid.length) + ".txt",
            RecoveryExportNames(invalid, "", ".txt").candidates().first())
        assertEquals("A_🐾 作者：B_.txt",
            RecoveryExportNames("A\uD800🐾", "B\uDC00", ".txt").candidates().first())
        assertEquals("我在《红楼梦》里 作者：甲 作者：乙.txt",
            RecoveryExportNames("我在《红楼梦》里", "甲 作者：乙", ".txt").candidates().first())
    }

    @Test fun longTitleKeepsCompleteAuthorAndASingleStableStem() {
        for (suffix in listOf(".txt", ".epub")) {
            val names = RecoveryExportNames("長".repeat(150), "完整作者🐾", suffix).candidates().toList()
            val first = names.first().substringBefore(" 作者：")
            assertTrue(first.length < 150)
            for ((index, name) in names.withIndex()) {
                val number = if (index == 0) "" else "（$index）"
                assertEquals("$first$number 作者：完整作者🐾$suffix", name)
                assertTrue(name.toByteArray(Charsets.UTF_8).size <= 240)
            }
            assertEquals(10_000, names.size)
            assertEquals(names.size, names.toSet().size)
        }
    }

    @Test fun longTitleAndAuthorKeepRoomForBothAndNeverSplitSupplementaryCharacters() {
        val title = "𠮷".repeat(100)
        val author = "🐾".repeat(100)
        val names = RecoveryExportNames(title, author, ".epub").candidates().toList()
        val first = names.first().removeSuffix(".epub")
        val titlePart = first.substringBefore(" 作者：")
        val authorPart = first.substringAfter(" 作者：")
        assertEquals("𠮷".repeat(12), titlePart) // 48 bytes reserved for a long title.
        assertTrue(authorPart.isNotEmpty())
        assertTrue(author.startsWith(authorPart))
        assertFalse(authorPart == author)
        for ((index, name) in names.withIndex()) {
            val number = if (index == 0) "" else "（$index）"
            assertEquals("$titlePart$number 作者：$authorPart.epub", name)
            val bytes = name.toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size <= 240)
            assertEquals(name, String(bytes, Charsets.UTF_8))
        }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun aTitleEndingInARealNumberDoesNotAliasAnyNumberedCandidate() {
        val title = "a".repeat(216) + "（1）"
        val names = RecoveryExportNames(title, "", ".txt").candidates().toList()
        assertEquals("$title.txt", names.first())
        assertEquals("$title（1）.txt", names[1])
        assertEquals(10_000, names.toSet().size)
        assertTrue(names.all { it.toByteArray(Charsets.UTF_8).size <= 240 })
    }
}

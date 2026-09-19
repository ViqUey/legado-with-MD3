package io.legado.app.feature.reader.core.selection

import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderPageId
import io.legado.app.feature.reader.core.model.ReaderRect
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class ReaderSelectionTest {
    private val style = ReaderTextStyle(0, 16f)
    private val page = ReaderPage(
        ReaderPageId(0, 0), "", "甲乙丙", 100, 100, 0f, 20f,
        listOf(
            ReaderElement.Text(ReaderRect(0f, 0f, 10f, 20f), 15f, "甲", style, false, false, chapterPosition = 0),
            ReaderElement.Text(ReaderRect(10f, 0f, 20f, 20f), 15f, "乙", style, false, false, chapterPosition = 1),
            ReaderElement.Text(ReaderRect(20f, 0f, 30f, 20f), 15f, "丙", style, false, false, chapterPosition = 2),
        ), 1L,
    )

    private fun textPage(
        text: String,
        paragraphIndex: Int = 0,
    ): ReaderPage {
        val elements = mutableListOf<ReaderElement.Text>()
        var position = 0
        var x = 0f
        text.forEach { char ->
            elements += ReaderElement.Text(
                ReaderRect(x, 0f, x + 10f, 20f), 15f, char.toString(), style,
                selected = false, emphasized = false, chapterPosition = position,
                paragraphIndex = paragraphIndex,
            )
            position++
            x += 10f
        }
        return page.copy(text = text, elements = elements)
    }

    private fun elementCenter(page: ReaderPage, text: String): Pair<Float, Float> {
        val element = page.elements.filterIsInstance<ReaderElement.Text>()
            .first { it.value == text }
        return (element.bounds.left + element.bounds.right) / 2f to
            (element.bounds.top + element.bounds.bottom) / 2f
    }

    @Test fun reverseSelectionNormalizesAndPreservesTextOrder() {
        val selection = ReaderSelection(0, 2, 0)
        assertEquals(0, selection.start)
        assertEquals(2, selection.endInclusive)
        assertEquals("甲乙丙", selection.selectedText(page))
    }

    @Test fun hitTestStartsAndExtendsSelection() {
        val started = ReaderSelectionPolicy.start(page, 5f, 10f)!!
        val extended = ReaderSelectionPolicy.extend(started, page, 25f, 10f)
        assertEquals("甲乙丙", extended.selectedText(page))
    }

    @Test fun snapToTextKeepsDirectHits() {
        assertEquals(1, ReaderSelectionPolicy.snapToText(page, 15f, 10f)!!.chapterPosition)
    }

    @Test fun snapToTextSnapsHandleDragsBelowTheRowToTheNearestGlyph() {
        assertEquals(0, ReaderSelectionPolicy.snapToText(page, 5f, 28f)!!.chapterPosition)
    }

    @Test fun snapToTextSnapsPastTheRowEdgeToTheNearestGlyph() {
        assertEquals(2, ReaderSelectionPolicy.snapToText(page, 35f, 28f)!!.chapterPosition)
    }

    @Test fun snapToTextIgnoresTouchesFarFromAnyText() {
        assertNull(ReaderSelectionPolicy.snapToText(page, 5f, 60f))
    }

    @Test fun longPressSelectsTheWholeWordAcrossVisualLines() {
        val values = listOf("read", "er", " ", "canvas")
        var position = 0
        val elements = values.mapIndexed { index, value ->
            ReaderElement.Text(
                bounds = ReaderRect(
                    if (index == 1) 0f else index * 20f,
                    if (index == 1) 20f else 0f,
                    if (index == 1) 20f else index * 20f + 20f,
                    if (index == 1) 40f else 20f,
                ),
                baselinePx = if (index == 1) 35f else 15f,
                value = value,
                style = style,
                selected = false,
                emphasized = false,
                chapterPosition = position.also { position += value.length },
                paragraphIndex = 0,
            )
        }
        val wrapped = page.copy(text = values.joinToString(""), elements = elements)

        val selection = ReaderSelectionPolicy.startWord(wrapped, 5f, 30f, Locale.ENGLISH)!!

        assertEquals("reader", selection.selectedText(wrapped))
        assertEquals(0, selection.start)
        assertEquals(4, selection.endInclusive)
    }

    @Test fun endpointSnappingKeepsAWordWholeAcrossVisualLines() {
        val values = listOf("read", "er", " ", "canvas")
        var position = 0
        val elements = values.mapIndexed { index, value ->
            ReaderElement.Text(
                bounds = ReaderRect(
                    if (index == 1) 0f else index * 20f,
                    if (index == 1) 20f else 0f,
                    if (index == 1) 20f else index * 20f + 20f,
                    if (index == 1) 40f else 20f,
                ),
                baselinePx = if (index == 1) 35f else 15f,
                value = value,
                style = style,
                selected = false,
                emphasized = false,
                chapterPosition = position.also { position += value.length },
                paragraphIndex = 0,
            )
        }
        val wrapped = page.copy(text = values.joinToString(""), elements = elements)
        val started = ReaderSelectionPolicy.startWord(wrapped, 65f, 10f, Locale.ENGLISH)!!

        val extended = ReaderSelectionPolicy.moveEndpoint(
            started,
            wrapped,
            5f,
            30f,
            started.visualStartEndpoint(),
            Locale.ENGLISH,
        )

        assertEquals("reader canvas", extended.selectedText(wrapped))
    }

    @Test fun longPressSelectsSimpleLatinWord() {
        val latin = textPage("interesting")

        val selection = ReaderSelectionPolicy.startWord(latin, 45f, 10f, Locale.ENGLISH)!!

        assertEquals("interesting", selection.selectedText(latin))
    }

    @Test fun tinyMovementAfterLongPressDoesNotCollapseInitialWord() {
        val latin = textPage("interesting")
        val started = ReaderSelectionPolicy.startWord(latin, 45f, 10f, Locale.ENGLISH)!!
        val drag = ReaderSelectionDragState().update(
            longPressed = true,
            handleGrabbed = false,
            distancePx = 3f,
            touchSlopPx = 8f,
        )

        val afterJitter = if (drag.started) {
            ReaderSelectionPolicy.moveEndpoint(
                started, latin, 55f, 10f, ReaderSelectionEndpoint.FOCUS, Locale.ENGLISH,
            )
        } else started

        assertEquals("interesting", afterJitter.selectedText(latin))
        assertEquals(started, afterJitter)
    }

    @Test fun selectionExtensionBeginsAfterDeliberateDragTransition() {
        val latin = textPage("quick brown fox")
        val started = ReaderSelectionPolicy.startWord(latin, 15f, 10f, Locale.ENGLISH)!!
        val drag = ReaderSelectionDragState().update(
            longPressed = true,
            handleGrabbed = false,
            distancePx = 9f,
            touchSlopPx = 8f,
        )

        val extended = if (drag.started) {
            ReaderSelectionPolicy.moveEndpoint(
                started, latin, 125f, 10f, ReaderSelectionEndpoint.FOCUS, Locale.ENGLISH,
            )
        } else started

        assertEquals("quick brown fox", extended.selectedText(latin))
    }

    @Test fun forwardLatinDragSnapsFocusToWholeWord() {
        val latin = textPage("quick brown fox")
        val started = ReaderSelectionPolicy.startWord(latin, 15f, 10f, Locale.ENGLISH)!!

        val extended = ReaderSelectionPolicy.moveEndpoint(
            started, latin, 125f, 10f, ReaderSelectionEndpoint.FOCUS, Locale.ENGLISH,
        )

        assertEquals("quick brown fox", extended.selectedText(latin))
    }

    @Test fun reverseLatinDragSnapsFocusToTheOuterWordBoundary() {
        val latin = textPage("one two three")
        val started = ReaderSelectionPolicy.startWord(latin, 95f, 10f, Locale.ENGLISH)!!
        val endpoint = ReaderSelectionPolicy.dragEndpoint(started, latin, 55f, 10f)!!

        val extended = ReaderSelectionPolicy.moveEndpoint(
            started, latin, 55f, 10f, endpoint, Locale.ENGLISH,
        )

        assertEquals(started.visualStartEndpoint(), endpoint)
        assertEquals("two three", extended.selectedText(latin))
    }

    @Test fun forwardContinuationChoosesTheVisualEndEndpoint() {
        val latin = textPage("quick brown fox")
        val started = ReaderSelectionPolicy.startWord(latin, 15f, 10f, Locale.ENGLISH)!!

        assertEquals(
            started.visualEndEndpoint(),
            ReaderSelectionPolicy.dragEndpoint(started, latin, 125f, 10f),
        )
    }

    @Test fun handleDraggingUsesTheSameLatinWordSnapping() {
        val latin = textPage("one two three")
        val selection = ReaderSelection(0, 0, 12)
        val endpoint = selection.visualStartEndpoint()

        val moved = ReaderSelectionPolicy.moveEndpoint(
            selection, latin, 55f, 10f, endpoint, Locale.ENGLISH,
        )

        assertEquals("two three", moved.selectedText(latin))
    }

    /**
     * Only the moving endpoint snaps. The fixed endpoint deliberately stays at its original
     * element (inside "one" in this synthetic setup), so the resulting "e two three" proves
     * semantic endpoint identity rather than promising to repair a pre-existing partial word.
     */
    @Test fun crossingKeepsMovingEndpointIdentityWithoutRelocatingTheFixedEndpoint() {
        val latin = textPage("one two three")
        val selection = ReaderSelection(0, 0, 2)
        val endpoint = selection.visualStartEndpoint()
        val crossed = ReaderSelectionPolicy.moveEndpoint(
            selection, latin, 55f, 10f, endpoint, Locale.ENGLISH,
        )
        val continued = ReaderSelectionPolicy.moveEndpoint(
            crossed, latin, 105f, 10f, endpoint, Locale.ENGLISH,
        )

        assertEquals("e two three", continued.selectedText(latin))
        assertEquals(12, continued.anchor)
        assertEquals(2, continued.focus)
    }

    @Test fun apostrophesRemainInsideLatinTokens() {
        listOf("don't", "don’t", "reader's", "reader’s").forEach { word ->
            val latin = textPage(word)
            val selection = ReaderSelectionPolicy.startWord(
                latin, latin.widthPx / 2f, 10f, Locale.ENGLISH,
            )!!
            assertEquals(word, selection.selectedText(latin))
        }
    }

    @Test fun accentedAndCombiningLatinRemainWhole() {
        listOf("café", "cafe\u0301").forEach { word ->
            val latin = textPage(word)
            val selection = ReaderSelectionPolicy.startWord(
                latin, 15f, 10f, Locale.ENGLISH,
            )!!
            assertEquals(word, selection.selectedText(latin))
        }
    }

    @Test fun multiCodeUnitCombiningGraphemeRemainsOneSelectionElement() {
        val values = listOf("c", "a", "f", "e\u0301", " ", "x")
        val positions = listOf(0, 1, 2, 3, 5, 6)
        val elements = values.mapIndexed { index, value ->
            ReaderElement.Text(
                ReaderRect(index * 10f, 0f, index * 10f + 10f, 20f),
                15f,
                value,
                style,
                selected = false,
                emphasized = false,
                chapterPosition = positions[index],
                paragraphIndex = 0,
            )
        }
        val combining = page.copy(text = values.joinToString(""), elements = elements)

        val selection = ReaderSelectionPolicy.startWord(
            combining, 35f, 10f, Locale.ENGLISH,
        )!!

        assertEquals("cafe\u0301", selection.selectedText(combining))
        assertEquals(0, selection.anchor)
        assertEquals(3, selection.focus)
        assertEquals("e\u0301", elements[3].value)
        assertEquals(2, elements[3].value.length)
        assertEquals(5, elements[4].chapterPosition)
    }

    @Test fun digitsAndAlphanumericTokensSnapAsWholeTokens() {
        listOf("12345", "r2d2").forEach { word ->
            val latin = textPage(word)
            val selection = ReaderSelectionPolicy.startWord(
                latin, 15f, 10f, Locale.ENGLISH,
            )!!
            assertEquals(word, selection.selectedText(latin))
        }
    }

    @Test fun surroundingPunctuationIsNotIncludedInLatinWord() {
        listOf("hello,", "(hello)", "“hello”", "'hello'", "’hello’").forEach { text ->
            val latin = textPage(text)
            val hit = latin.elements.filterIsInstance<ReaderElement.Text>()
                .first { it.value == "e" }
            val selection = ReaderSelectionPolicy.startWord(
                latin, (hit.bounds.left + hit.bounds.right) / 2f, 10f, Locale.ENGLISH,
            )!!
            assertEquals("hello", selection.selectedText(latin))
        }
    }

    @Test fun dotAndHyphenRemainNaturalWordBoundaries() {
        listOf(
            Triple("README.md", "A", "README"),
            Triple("README.md", "d", "md"),
            Triple("mother-in-law", "i", "in"),
        ).forEach { (text, hitValue, expected) ->
            val latin = textPage(text)
            val (x, y) = elementCenter(latin, hitValue)
            val selection = ReaderSelectionPolicy.startWord(latin, x, y, Locale.ENGLISH)!!
            assertEquals(expected, selection.selectedText(latin))
        }
    }

    @Test fun mixedCjkAndLatinOnlySnapsTheLatinToken() {
        listOf("中文English中文", "中文 English 中文").forEach { text ->
            val mixed = textPage(text)
            val (x, y) = elementCenter(mixed, "g")
            val selection = ReaderSelectionPolicy.startWord(mixed, x, y, Locale.ENGLISH)!!
            assertEquals("English", selection.selectedText(mixed))
        }
    }

    @Test fun continuousDragSwitchesFromLatinToCjkAndBackToLatinGranularity() {
        val mixed = textPage("中文 one 中文 two 中文")
        val started = ReaderSelectionPolicy.startWord(mixed, 45f, 10f, Locale.ENGLISH)!!

        val onCjk = ReaderSelectionPolicy.moveEndpoint(
            started, mixed, 75f, 10f, ReaderSelectionEndpoint.FOCUS, Locale.ENGLISH,
        )
        val onSecondLatin = ReaderSelectionPolicy.moveEndpoint(
            onCjk, mixed, 115f, 10f, ReaderSelectionEndpoint.FOCUS, Locale.ENGLISH,
        )

        assertEquals("one 中", onCjk.selectedText(mixed))
        assertEquals(7, onCjk.focus)
        assertEquals("one 中文 two", onSecondLatin.selectedText(mixed))
        assertEquals(12, onSecondLatin.focus)
    }

    @Test fun pureCjkEndpointRemainsGraphemeGranular() {
        val cjk = textPage("我喜欢辣椒")
        // Endpoint dragging is independent of startWord's preserved BreakIterator behavior.
        // Start from an existing one-grapheme selection so this test only covers drag granularity.
        val started = ReaderSelectionPolicy.start(cjk, 5f, 10f)!!
        val moved = ReaderSelectionPolicy.moveEndpoint(
            started, cjk, 35f, 10f, ReaderSelectionEndpoint.FOCUS, Locale.CHINESE,
        )

        assertEquals("我喜欢辣", moved.selectedText(cjk))
    }

    @Test fun longPressKeepsWordSelectionInsideTheHitParagraph() {
        val first = page.elements.filterIsInstance<ReaderElement.Text>().map {
            it.copy(paragraphIndex = 0)
        }
        val second = listOf(
            ReaderElement.Text(
                ReaderRect(0f, 30f, 20f, 50f), 45f, "word", style, false, false,
                chapterPosition = 4, paragraphIndex = 1,
            ),
        )
        val paragraphs = page.copy(elements = first + second)

        val selection = ReaderSelectionPolicy.startWord(paragraphs, 5f, 40f, Locale.ENGLISH)!!

        assertEquals("word", selection.selectedText(paragraphs))
    }

    @Test
    fun longPressSnapsAcrossLetterSpacingGaps() {
        val spaced = page.copy(
            elements = listOf(
                ReaderElement.Text(
                    ReaderRect(0f, 0f, 10f, 20f), 15f, "甲", style, false, false,
                    chapterPosition = 0,
                ),
                ReaderElement.Text(
                    ReaderRect(14f, 0f, 24f, 20f), 15f, "乙", style, false, false,
                    chapterPosition = 1,
                ),
            )
        )

        val selection = ReaderSelectionPolicy.startWord(spaced, 12f, 10f, Locale.CHINESE)

        assertEquals(0, selection?.anchor)
        assertEquals("甲乙", selection?.selectedText(spaced))
    }

    @Test fun handlesKeepSemanticStartWhenSelectionIsReversed() {
        val reversed = ReaderSelection(0, 2, 0)
        assertEquals(1, reversed.moveStart(1).start)
        assertEquals(1, reversed.moveEnd(1).endInclusive)
    }

    @Test fun draggedEndpointIdentityStaysStableAfterHandlesCross() {
        val selection = ReaderSelection(0, 0, 2)
        val draggedEndpoint = selection.visualStartEndpoint()

        val crossed = selection.moveEndpoint(draggedEndpoint, 3)
        val continued = crossed.moveEndpoint(draggedEndpoint, 4)

        assertEquals(2, continued.start)
        assertEquals(4, continued.endInclusive)
        assertEquals(4, continued.anchor)
        assertEquals(2, continued.focus)
    }

    private val titledPage = page.copy(elements = listOf(
        ReaderElement.Text(ReaderRect(0f, 30f, 10f, 50f), 45f, "题", style, false, true, chapterPosition = 0),
    ) + page.elements)

    @Test fun bodySelectionDoesNotIncludeTitleWithTheSameOffset() {
        val selection = ReaderSelectionPolicy.start(titledPage, 5f, 10f)!!
        assertEquals("甲", selection.selectedText(titledPage))
        assertEquals(1, selection.bounds(titledPage).size)
    }

    @Test fun titleSelectionDoesNotIncludeBodyWithTheSameOffset() {
        val selection = ReaderSelectionPolicy.start(titledPage, 5f, 40f)!!
        assertEquals("题", selection.selectedText(titledPage))
        assertEquals(1, selection.bounds(titledPage).size)
    }

    @Test fun crossTitleAndBodySelectionPreservesDocumentOrderInBothDirections() {
        val title = ReaderSelectionPolicy.start(titledPage, 5f, 40f)!!
        val forward = ReaderSelectionPolicy.extend(title, titledPage, 15f, 10f)
        val body = ReaderSelectionPolicy.start(titledPage, 15f, 10f)!!
        val backward = ReaderSelectionPolicy.extend(body, titledPage, 5f, 40f)
        assertEquals("题\n甲乙", forward.selectedText(titledPage))
        assertEquals(forward.selectedText(titledPage), backward.selectedText(titledPage))
    }

    @Test fun draggingIntoAnotherChapterDoesNotChangeSelection() {
        val selection = ReaderSelection(0, 0, 0)
        val otherChapter = page.copy(id = ReaderPageId(1, 0))
        assertEquals(selection, ReaderSelectionPolicy.extend(selection, otherChapter, 25f, 10f))
    }

    @Test fun handlesCanCrossTheTitleBodyBoundaryWithoutConfusingOffsets() {
        val title = ReaderSelectionPolicy.start(titledPage, 5f, 40f)!!
        assertNull(title.bodyStart)
        val forward = title.moveEnd(1)
        assertEquals("题\n甲乙", forward.selectedText(titledPage))
        assertEquals(0, forward.bodyStart)
        assertEquals("甲乙", forward.moveStart(0).selectedText(titledPage))
        val backward = ReaderSelection(0, 1, 0, focusIsTitle = true)
        assertEquals("甲乙", backward.moveStart(0).selectedText(titledPage))
        assertEquals("题", backward.moveEnd(0, isTitle = true).selectedText(titledPage))
    }

    @Test fun selectionBoundsFollowDocumentOrderInsteadOfPhysicalColumnHeight() {
        val shuffled = page.copy(elements = page.elements.reversed())
        val selection = ReaderSelection(0, 2, 0)
        assertEquals("甲乙丙", selection.selectedText(shuffled))
        assertEquals(page.elements.map { it.bounds }, selection.bounds(shuffled))
    }

    @Test fun copyingPreservesParagraphBreaksButNotVisualLineWraps() {
        val elements = page.elements.filterIsInstance<ReaderElement.Text>().mapIndexed { index, text ->
            text.copy(
                paragraphIndex = if (index < 2) 0 else 1,
                chapterPosition = if (index < 2) index else 3,
                bounds = ReaderRect(0f, index * 20f, 10f, (index + 1) * 20f),
            )
        }
        val paragraphs = page.copy(elements = elements)
        assertEquals("甲乙\n丙", ReaderSelection(0, 0, 3).selectedText(paragraphs))
        assertEquals("乙\n丙", ReaderSelection(0, 3, 1).selectedText(paragraphs))
        assertEquals("甲乙", ReaderSelection(0, 0, 1).selectedText(paragraphs))
    }

    @Test fun copyingAcrossPagesUsesDocumentOrderAndRemovesBoundaryDuplicates() {
        val first = page.copy(
            elements = page.elements.take(2),
            id = ReaderPageId(0, 0),
        )
        val second = page.copy(
            elements = listOf(
                page.elements[1],
                page.elements[2],
            ),
            id = ReaderPageId(0, 1),
        )

        assertEquals("甲乙丙", ReaderSelection(0, 0, 2).selectedText(listOf(second, first)))
    }

    /**
     * 滚动模式视口里堆叠的下邻页属于下一章（旧 View 的选区分词同样遍历 relativePage 0..2，
     * 而 `TextPageFactory.nextPlusPage` 在章末给出下一章首页），因此选区必须能跨过去，
     * 且两页都要参与高亮绘制。
     */
    @Test
    fun scrollModeSelectionExtendsIntoTheNextChapterPage() {
        val nextChapter = page.copy(
            id = ReaderPageId(1, 0),
            elements = listOf(
                ReaderElement.Text(
                    ReaderRect(0f, 0f, 10f, 20f), 15f, "丁", style, false, false,
                    chapterPosition = 0,
                ),
            ),
        )
        val started = ReaderSelectionPolicy.start(page, 25f, 10f)!!
        val extended = ReaderSelectionPolicy.extend(
            started, nextChapter, 5f, 10f, allowChapterCrossing = true,
        )

        assertEquals(0, extended.startChapterIndex)
        assertEquals(1, extended.endChapterIndex)
        assertEquals(1, extended.focusChapterIndex)
        assertEquals("丙\n丁", extended.selectedText(listOf(nextChapter, page)))
        assertEquals(1, extended.bounds(page).size)
        assertEquals(1, extended.bounds(nextChapter).size)
    }

    @Test
    fun pagedModeKeepsTheSelectionInsideOneChapter() {
        val nextChapter = page.copy(id = ReaderPageId(1, 0))
        val started = ReaderSelectionPolicy.start(page, 25f, 10f)!!

        assertEquals(started, ReaderSelectionPolicy.extend(started, nextChapter, 5f, 10f))
        assertEquals(0, started.endChapterIndex)
    }
}

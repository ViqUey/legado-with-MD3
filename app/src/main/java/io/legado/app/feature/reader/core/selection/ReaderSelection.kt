package io.legado.app.feature.reader.core.selection

import androidx.compose.runtime.Stable
import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderPageWindow
import io.legado.app.feature.reader.core.model.ReaderRect
import java.text.BreakIterator
import java.util.Locale

enum class ReaderSelectionEndpoint {
    ANCHOR,
    FOCUS,
}

data class ReaderSelectionMoveResult(
    val selection: ReaderSelection,
    val contractionPreview: ReaderSelection?,
)

/**
 * 选区可跨的页：对照旧 `ContentTextView.upSelectChars`（`relativePage(0..2)`）——当前页、
 * 下一页、下下页，**不含上一页**；滚动模式的连续堆叠允许选到邻章首页（`nextPlusPage` 语义），
 * 分页模式实际只有当前页可见。
 */
fun ReaderPageWindow.selectionPages(): List<ReaderPage> =
    listOfNotNull(current, next, nextPlus)

@Stable
data class ReaderSelection(
    val chapterIndex: Int,
    val anchor: Int,
    val focus: Int,
    val anchorIsTitle: Boolean = false,
    val focusIsTitle: Boolean = anchorIsTitle,
    /**
     * focus 所在的章；[chapterIndex] 始终是 anchor 所在的章。滚动模式的连续堆叠允许选区
     * 跨到邻章页——旧 View 的 `ContentTextView.upSelectChars` 在滚动模式遍历 relativePage
     * 0..2，而 `TextPageFactory.nextPlusPage` 在章末会给出下一章首页，因此那时选区含邻章
     * 首页的文字；分页模式两边恒相等。
     */
    val focusChapterIndex: Int = chapterIndex,
) {
    // Title offsets and body offsets are independent. Document order puts the title first
    // and the body after it; the chapter is the outermost key.
    private val forward: Boolean
        get() = comparePosition(
            chapterIndex, anchorIsTitle, anchor,
            focusChapterIndex, focusIsTitle, focus,
        ) <= 0
    val startChapterIndex: Int get() = if (forward) chapterIndex else focusChapterIndex
    val endChapterIndex: Int get() = if (forward) focusChapterIndex else chapterIndex
    internal val startIsTitle: Boolean get() = if (forward) anchorIsTitle else focusIsTitle
    private val endIsTitle: Boolean get() = if (forward) focusIsTitle else anchorIsTitle
    val start: Int get() = if (forward) anchor else focus
    val endInclusive: Int get() = if (forward) focus else anchor
    val includesTitle: Boolean get() = anchorIsTitle || focusIsTitle
    val bodyStart: Int? get() = when {
        anchorIsTitle && focusIsTitle -> null
        includesTitle -> 0
        else -> start
    }

    fun contains(element: ReaderElement.Text, pageChapterIndex: Int): Boolean =
        comparePosition(
            pageChapterIndex, element.emphasized, element.chapterPosition,
            startChapterIndex, startIsTitle, start,
        ) >= 0 && comparePosition(
            pageChapterIndex, element.emphasized, element.chapterPosition,
            endChapterIndex, endIsTitle, endInclusive,
        ) <= 0

    fun moveStart(
        position: Int,
        isTitle: Boolean = false,
        chapter: Int = startChapterIndex,
    ): ReaderSelection =
        if (forward) copy(anchor = position, anchorIsTitle = isTitle, chapterIndex = chapter)
        else copy(focus = position, focusIsTitle = isTitle, focusChapterIndex = chapter)

    fun moveEnd(
        position: Int,
        isTitle: Boolean = false,
        chapter: Int = endChapterIndex,
    ): ReaderSelection =
        if (forward) copy(focus = position, focusIsTitle = isTitle, focusChapterIndex = chapter)
        else copy(anchor = position, anchorIsTitle = isTitle, chapterIndex = chapter)

    fun visualStartEndpoint(): ReaderSelectionEndpoint =
        if (forward) ReaderSelectionEndpoint.ANCHOR else ReaderSelectionEndpoint.FOCUS

    fun visualEndEndpoint(): ReaderSelectionEndpoint =
        if (forward) ReaderSelectionEndpoint.FOCUS else ReaderSelectionEndpoint.ANCHOR

    fun moveEndpoint(
        endpoint: ReaderSelectionEndpoint,
        position: Int,
        isTitle: Boolean = false,
        chapter: Int = when (endpoint) {
            ReaderSelectionEndpoint.ANCHOR -> chapterIndex
            ReaderSelectionEndpoint.FOCUS -> focusChapterIndex
        },
    ): ReaderSelection = when (endpoint) {
        ReaderSelectionEndpoint.ANCHOR ->
            copy(anchor = position, anchorIsTitle = isTitle, chapterIndex = chapter)

        ReaderSelectionEndpoint.FOCUS ->
            copy(focus = position, focusIsTitle = isTitle, focusChapterIndex = chapter)
    }

    fun selectedText(page: ReaderPage): String {
        return selectedText(listOf(page))
    }

    /**
     * Collects a selection across every available page without duplicating page-boundary
     * glyphs. Pages from neighbouring chapters are included when the selection spans them.
     */
    fun selectedText(pages: List<ReaderPage>): String {
        val ordered = pages.asSequence()
            .flatMap { page ->
                page.elements.asSequence()
                    .filterIsInstance<ReaderElement.Text>()
                    .map { page.id.chapterIndex to it }
            }
            .filter { (chapter, text) -> contains(text, chapter) }
            .distinctBy { (chapter, text) ->
                Triple(chapter, text.emphasized, text.chapterPosition) to text.value
            }
            .sortedWith(documentOrderByChapter)
            .toList()
        return buildString {
            var previous: Pair<Int, ReaderElement.Text>? = null
            ordered.forEach { (chapter, text) ->
                previous?.let { (priorChapter, prior) ->
                    if (priorChapter != chapter ||
                        prior.emphasized != text.emphasized ||
                        (prior.paragraphIndex >= 0 && text.paragraphIndex >= 0 &&
                                prior.paragraphIndex != text.paragraphIndex)
                    ) append('\n')
                }
                append(text.value)
                previous = chapter to text
            }
        }
    }

    fun bounds(page: ReaderPage): List<ReaderRect> = page.elements
        .filterIsInstance<ReaderElement.Text>()
        .filter { contains(it, page.id.chapterIndex) }
        .sortedWith(documentOrder)
        .map(ReaderElement.Text::bounds)

    private companion object {
        val documentOrder = compareBy<ReaderElement.Text> { !it.emphasized }.thenBy { it.chapterPosition }
        val documentOrderByChapter = compareBy<Pair<Int, ReaderElement.Text>> { it.first }
            .thenBy { !it.second.emphasized }
            .thenBy { it.second.chapterPosition }

        fun comparePosition(
            leftChapter: Int,
            leftIsTitle: Boolean,
            left: Int,
            rightChapter: Int,
            rightIsTitle: Boolean,
            right: Int,
        ): Int {
            if (leftChapter != rightChapter) return leftChapter.compareTo(rightChapter)
            return if (leftIsTitle == rightIsTitle) left.compareTo(right)
            else if (leftIsTitle) -1 else 1
        }
    }
}

object ReaderSelectionPolicy {
    private data class ParagraphHit(
        val hit: ReaderElement.Text,
        val elements: List<ReaderElement.Text>,
        val text: String,
        val hitOffset: Int,
    )

    private data class ElementRange(
        val first: ReaderElement.Text,
        val last: ReaderElement.Text,
    )

    fun start(page: ReaderPage, x: Float, y: Float): ReaderSelection? =
        (page.elementAt(x, y) as? ReaderElement.Text)?.let {
            ReaderSelection(page.id.chapterIndex, it.chapterPosition, it.chapterPosition, it.emphasized)
        }

    /**
     * Selection handles hang below the text row, so a handle drag often moves through the
     * leading where [ReaderPage.elementAt] misses. Snap a miss to the nearest row by vertical
     * distance, then to the glyph closest to the finger's x within that row.
     */
    fun snapToText(page: ReaderPage, x: Float, y: Float): ReaderElement.Text? {
        (page.elementAt(x, y) as? ReaderElement.Text)?.let { return it }
        val textElements = page.elements.filterIsInstance<ReaderElement.Text>()
        if (textElements.isEmpty()) return null
        fun verticalDistance(bounds: ReaderRect): Float =
            (y - bounds.bottom).coerceAtLeast(0f).coerceAtLeast(bounds.top - y)
        val nearest = textElements.minByOrNull { verticalDistance(it.bounds) } ?: return null
        if (verticalDistance(nearest.bounds) > nearest.bounds.height) return null
        return textElements
            .filter { it.bounds.bottom > nearest.bounds.top && it.bounds.top < nearest.bounds.bottom }
            .minByOrNull { element ->
                val bounds = element.bounds
                when {
                    x < bounds.left -> bounds.left - x
                    x > bounds.right -> x - bounds.right
                    else -> 0f
                }
            }
    }

    /** Matches the View reader's long-press behavior: select one word in the hit paragraph. */
    fun startWord(
        page: ReaderPage,
        x: Float,
        y: Float,
        locale: Locale = Locale.getDefault(),
    ): ReaderSelection? {
        // Glyph bounds intentionally omit letter- and justification-spacing. Long presses in
        // those visual gaps should start selection just like handle drags do.
        val context = paragraphHit(page, x, y, snapMisses = true) ?: return null
        // Preserve the original cross-language long-press behavior. Latin-only validation is
        // intentionally limited to moving endpoints; CJK and other scripts keep BreakIterator's
        // existing initial selection semantics.
        val range = breakIteratorRange(context, locale)?.let { elementRange(context, it) }
        return ReaderSelection(
            chapterIndex = page.id.chapterIndex,
            anchor = range?.first?.chapterPosition ?: context.hit.chapterPosition,
            focus = range?.last?.chapterPosition ?: context.hit.chapterPosition,
            anchorIsTitle = context.hit.emphasized,
        )
    }

    private fun paragraphHit(
        page: ReaderPage,
        x: Float,
        y: Float,
        snapMisses: Boolean,
    ): ParagraphHit? {
        val hit = (
            (page.elementAt(x, y) as? ReaderElement.Text)
                ?: if (snapMisses) snapToText(page, x, y) else null
            ) ?: return null
        // Deliberately limited to the current page fragment. Reconstructing a token that crosses
        // a page boundary needs paginator context and remains outside selection gesture policy.
        val paragraph = page.elements.filterIsInstance<ReaderElement.Text>()
            .filter {
                it.emphasized == hit.emphasized &&
                    (hit.paragraphIndex < 0 || it.paragraphIndex == hit.paragraphIndex)
            }
            .sortedBy(ReaderElement.Text::chapterPosition)
        val hitIndex = paragraph.indexOf(hit)
        if (hitIndex < 0) return null
        val text = paragraph.joinToString(separator = "", transform = ReaderElement.Text::value)
        val hitOffset = paragraph.take(hitIndex).sumOf { it.value.length }
        return ParagraphHit(hit, paragraph, text, hitOffset)
    }

    private fun breakIteratorRange(context: ParagraphHit, locale: Locale): IntRange? {
        val text = context.text
        val boundary = BreakIterator.getWordInstance(locale).apply { setText(text) }
        var start = boundary.first()
        var end = boundary.next()
        while (end != BreakIterator.DONE && context.hitOffset !in start until end) {
            start = end
            end = boundary.next()
        }
        if (end == BreakIterator.DONE) return null
        return start until end
    }

    private fun latinWordRange(context: ParagraphHit, locale: Locale): ElementRange? {
        val text = context.text
        val candidate = breakIteratorRange(context, locale) ?: return null
        val expanded = expandLatinToken(text, candidate.first, candidate.last + 1)
            ?.takeIf { text.substring(it.first, it.last + 1).isLatinLexicalToken() }
            ?: expandLatinToken(
                text,
                context.hitOffset,
                context.hitOffset + context.hit.value.length,
            )?.takeIf { text.substring(it.first, it.last + 1).isLatinLexicalToken() }
            ?: return null
        return elementRange(context, expanded)
    }

    private fun elementRange(context: ParagraphHit, range: IntRange): ElementRange? {
        var offset = 0
        var first: ReaderElement.Text? = null
        var last: ReaderElement.Text? = null
        context.elements.forEach { element ->
            val elementEnd = offset + element.value.length
            if (offset <= range.last && elementEnd > range.first) {
                if (first == null) first = element
                last = element
            }
            offset = elementEnd
        }
        return first?.let { ElementRange(it, checkNotNull(last)) }
    }

    private fun expandLatinToken(text: String, seedStart: Int, seedEnd: Int): IntRange? {
        if (seedStart !in 0 until text.length || seedEnd !in 1..text.length) return null
        var start = seedStart
        var end = seedEnd
        while (start > 0) {
            val previous = text.offsetByCodePoints(start, -1)
            if (!text.isLatinTokenPartAt(previous)) break
            start = previous
        }
        while (end < text.length) {
            if (!text.isLatinTokenPartAt(end)) break
            end = text.offsetByCodePoints(end, 1)
        }
        return start until end
    }

    private fun String.isLatinLexicalToken(): Boolean {
        val codePoints = codePoints().toArray()
        var hasCore = false
        codePoints.forEachIndexed { index, codePoint ->
            when {
                Character.isLetter(codePoint) -> {
                    if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN) {
                        return false
                    }
                    hasCore = true
                }
                Character.isDigit(codePoint) -> hasCore = true
                Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() ||
                    Character.getType(codePoint) == Character.COMBINING_SPACING_MARK.toInt() -> Unit
                codePoint == '\''.code || codePoint == 0x2019 -> {
                    if (index == 0 || index == codePoints.lastIndex ||
                        !isLatinTokenCoreOrMark(codePoints[index - 1]) ||
                        !isLatinTokenCoreOrMark(codePoints[index + 1])
                    ) return false
                }
                else -> return false
            }
        }
        return hasCore
    }

    private fun String.isLatinTokenPartAt(index: Int): Boolean {
        val codePoint = codePointAt(index)
        if (codePoint != '\''.code && codePoint != 0x2019) {
            return isLatinTokenCoreOrMark(codePoint)
        }
        if (index == 0) return false
        val next = offsetByCodePoints(index, 1)
        if (next >= length) return false
        val previous = offsetByCodePoints(index, -1)
        return isLatinTokenCoreOrMark(codePointAt(previous)) &&
            isLatinTokenCoreOrMark(codePointAt(next))
    }

    private fun isLatinTokenCoreOrMark(codePoint: Int): Boolean =
        Character.isDigit(codePoint) ||
            Character.isLetter(codePoint) &&
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN ||
            Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(codePoint) == Character.COMBINING_SPACING_MARK.toInt()

    fun dragEndpoint(
        selection: ReaderSelection,
        page: ReaderPage,
        x: Float,
        y: Float,
    ): ReaderSelectionEndpoint? {
        val hit = page.elementAt(x, y) as? ReaderElement.Text ?: return null
        if (selection.contains(hit, page.id.chapterIndex)) return null
        return if (comparePosition(
                page.id.chapterIndex, hit.emphasized, hit.chapterPosition,
                selection.startChapterIndex, selection.startIsTitle, selection.start,
            ) < 0
        ) selection.visualStartEndpoint() else selection.visualEndEndpoint()
    }

    fun moveEndpoint(
        selection: ReaderSelection,
        page: ReaderPage,
        x: Float,
        y: Float,
        endpoint: ReaderSelectionEndpoint,
        locale: Locale = Locale.getDefault(),
        allowChapterCrossing: Boolean = false,
        snapMisses: Boolean = false,
    ): ReaderSelection = moveEndpointWithPreview(
        selection,
        page,
        x,
        y,
        endpoint,
        locale,
        allowChapterCrossing,
        snapMisses,
    ).selection

    fun moveEndpointWithPreview(
        selection: ReaderSelection,
        page: ReaderPage,
        x: Float,
        y: Float,
        endpoint: ReaderSelectionEndpoint,
        locale: Locale = Locale.getDefault(),
        allowChapterCrossing: Boolean = false,
        snapMisses: Boolean = false,
    ): ReaderSelectionMoveResult {
        if (!allowChapterCrossing && selection.chapterIndex != page.id.chapterIndex) {
            return ReaderSelectionMoveResult(selection, null)
        }
        val context = paragraphHit(page, x, y, snapMisses)
            ?: return ReaderSelectionMoveResult(selection, null)
        val range = latinWordRange(context, locale)
        val fixed = when (endpoint) {
            ReaderSelectionEndpoint.ANCHOR -> Triple(
                selection.focusChapterIndex, selection.focusIsTitle, selection.focus,
            )
            ReaderSelectionEndpoint.FOCUS -> Triple(
                selection.chapterIndex, selection.anchorIsTitle, selection.anchor,
            )
        }
        val hitBeforeFixed = comparePosition(
            page.id.chapterIndex, context.hit.emphasized, context.hit.chapterPosition,
            fixed.first, fixed.second, fixed.third,
        ) < 0
        val target = when {
            range == null -> context.hit
            hitBeforeFixed -> range.first
            else -> range.last
        }
        val semantic = selection.moveEndpoint(
            endpoint,
            target.chapterPosition,
            target.emphasized,
            page.id.chapterIndex,
        )
        val raw = selection.moveEndpoint(
            endpoint,
            context.hit.chapterPosition,
            context.hit.emphasized,
            page.id.chapterIndex,
        )
        val preview = raw.takeIf {
            range != null &&
                raw != semantic &&
                movesTowardFixedEndpoint(
                    selection,
                    endpoint,
                    page.id.chapterIndex,
                    context.hit.emphasized,
                    context.hit.chapterPosition,
                )
        }
        return ReaderSelectionMoveResult(semantic, preview)
    }

    private fun movesTowardFixedEndpoint(
        selection: ReaderSelection,
        endpoint: ReaderSelectionEndpoint,
        rawChapter: Int,
        rawIsTitle: Boolean,
        rawPosition: Int,
    ): Boolean {
        val moving = when (endpoint) {
            ReaderSelectionEndpoint.ANCHOR -> Triple(
                selection.chapterIndex, selection.anchorIsTitle, selection.anchor,
            )
            ReaderSelectionEndpoint.FOCUS -> Triple(
                selection.focusChapterIndex, selection.focusIsTitle, selection.focus,
            )
        }
        val fixed = when (endpoint) {
            ReaderSelectionEndpoint.ANCHOR -> Triple(
                selection.focusChapterIndex, selection.focusIsTitle, selection.focus,
            )
            ReaderSelectionEndpoint.FOCUS -> Triple(
                selection.chapterIndex, selection.anchorIsTitle, selection.anchor,
            )
        }
        val movingToFixed = comparePosition(
            moving.first, moving.second, moving.third,
            fixed.first, fixed.second, fixed.third,
        )
        val rawToMoving = comparePosition(
            rawChapter, rawIsTitle, rawPosition,
            moving.first, moving.second, moving.third,
        )
        val rawToFixed = comparePosition(
            rawChapter, rawIsTitle, rawPosition,
            fixed.first, fixed.second, fixed.third,
        )
        return when {
            movingToFixed < 0 -> rawToMoving > 0 && rawToFixed <= 0
            movingToFixed > 0 -> rawToMoving < 0 && rawToFixed >= 0
            else -> false
        }
    }

    /**
     * [allowChapterCrossing] 只在滚动模式传 true：视口里堆叠的就是当前页与下一章首页
     * （旧 View 同样把这一页纳入选区分词，见 [ReaderSelection.focusChapterIndex]）。
     * 分页模式保持单章，与旧 View 的 `last = if (isScroll) 2 else 0` 一致。
     */
    fun extend(
        selection: ReaderSelection,
        page: ReaderPage,
        x: Float,
        y: Float,
        allowChapterCrossing: Boolean = false,
    ): ReaderSelection {
        return moveEndpoint(
            selection = selection,
            page = page,
            x = x,
            y = y,
            endpoint = ReaderSelectionEndpoint.FOCUS,
            allowChapterCrossing = allowChapterCrossing,
        )
    }

    private fun comparePosition(
        leftChapter: Int,
        leftIsTitle: Boolean,
        left: Int,
        rightChapter: Int,
        rightIsTitle: Boolean,
        right: Int,
    ): Int {
        if (leftChapter != rightChapter) return leftChapter.compareTo(rightChapter)
        return if (leftIsTitle == rightIsTitle) left.compareTo(right)
        else if (leftIsTitle) -1 else 1
    }
}

package io.legado.app.data.repository

import io.legado.app.constant.AppPattern
import io.legado.app.help.book.MixedContentSegments
import io.legado.app.help.book.MixedContentSegments.Segment
import io.legado.app.lib.epub.TextEpubExport.Companion.xml
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

internal object RecoveryChapterMarkup {
    private val imageTags = Regex("""(?is)<img\b[^>]*?\ssrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>""")
    private const val discarded = "script,style,template,noscript,iframe,object,embed,svg,math,canvas,input,button,select,textarea"
    private val tags = setOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "br", "hr", "blockquote",
        "ul", "ol", "li", "dl", "dt", "dd", "ruby", "rt", "rp", "em", "strong",
        "b", "i", "sup", "sub", "pre", "code", "section", "article", "figure", "figcaption",
        "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th",
    )
    private val voidTags = setOf("br", "hr")

    private data class Reference(val range: IntRange, val source: String)

    private fun references(raw: String): List<Reference> {
        val base = AppPattern.imgPattern.findAll(raw).associate { it.range.first to Reference(it.range, it.groupValues[1]) }
        val other = imageTags.findAll(raw).filter { it.range.first !in base }.map {
            Reference(it.range, it.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty())
        }
        return (base.values + other).sortedBy { it.range.first }
    }

    private fun richBody(html: String) = Jsoup.parseBodyFragment(html).body().apply {
        select(discarded).remove()
    }

    private fun readableImage(src: String, isBubble: (String) -> Boolean) =
        src.isNotBlank() && !isBubble(src)

    fun hasImages(raw: String, excluded: (String) -> Boolean): Boolean =
        MixedContentSegments.split(raw).any { segment ->
            when (segment) {
                is Segment.Plain -> references(segment.text).any {
                    readableImage(Parser.unescapeEntities(it.source, true), excluded)
                }
                is Segment.Rich -> richBody(segment.html).select("img").any { readableImage(it.attr("src"), excluded) }
            }
        }

    fun render(
        raw: String,
        isBubble: (String) -> Boolean,
        missingText: String,
        check: () -> Unit,
        missing: () -> Unit,
        resolve: (String) -> String?,
    ): String = buildString {
        fun appendImage(src: String) {
            if (!readableImage(src, isBubble)) return
            val url = resolve(src)
            if (url != null) append("<img src=\"${xml(url)}\" alt=\"\"/>")
            else {
                missing()
                append("<span>${xml(missingText)}</span>")
            }
        }

        fun appendPlain(text: String) {
            append(xml(text).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br/>"))
        }

        append("<div>")
        for (segment in MixedContentSegments.split(raw)) {
            check()
            when (segment) {
                is Segment.Plain -> {
                    var cursor = 0
                    for ((range, src) in references(segment.text)) {
                        check()
                        if (range.first < cursor) continue
                        appendPlain(segment.text.substring(cursor, range.first))
                        appendImage(src)
                        cursor = range.last + 1
                    }
                    appendPlain(segment.text.substring(cursor))
                }
                is Segment.Rich -> {
                    append("<div>")
                    NodeTraversor.traverse(object : NodeVisitor {
                        override fun head(node: Node, depth: Int) {
                            check()
                            when (node) {
                                is TextNode -> append(xml(node.wholeText))
                                is Element -> when (val tag = node.normalName()) {
                                    "img" -> appendImage(node.attr("src"))
                                    in voidTags -> append("<$tag/>")
                                    in tags -> append("<$tag>")
                                }
                            }
                        }

                        override fun tail(node: Node, depth: Int) {
                            if (node is Element) {
                                val tag = node.normalName()
                                if (tag in tags && tag !in voidTags) append("</$tag>")
                            }
                        }
                    }, richBody(segment.html))
                    append("</div>")
                }
            }
        }
        append("</div>")
    }
}

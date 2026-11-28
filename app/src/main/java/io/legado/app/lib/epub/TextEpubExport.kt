package io.legado.app.lib.epub

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes the small EPUB 3 profile used for recovered text, without calling a tolerant writer. */
internal class TextEpubExport(
    private val title: String,
    private val author: String,
    private val description: String?,
    private val check: () -> Unit,
) {
    data class Image(val bytes: ByteArray, val extension: String, val mime: String)

    private val identifier = "urn:uuid:${UUID.randomUUID()}"
    private val chapterTitles = ArrayList<String>()
    private val assets = LinkedHashMap<String, Pair<String, String>>()
    private val digests = linkedMapOf<String, String>()
    private var output: ZipOutputStream? = null

    fun write(file: File, chapters: TextEpubExport.() -> Unit): EpubExportIntegrity.Expected {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            output = zip
            val mime = "application/epub+zip".toByteArray(UTF_8)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            recordDigest("mimetype", mime)
            put("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            chapters()
            if (chapterTitles.isEmpty()) throw IOException("No chapters")
            val nav = chapterTitles.mapIndexed { i, heading -> """<li><a href="chapter-${i + 1}.xhtml">${xml(heading)}</a></li>""" }
            put("EPUB/nav.xhtml", xhtml(title, """<nav epub:type="toc" id="toc"><h1>${xml(title)}</h1><ol>${nav.joinToString("")}</ol></nav>"""))
            val items = assets.entries.joinToString("") { (path, spec) ->
                """<item id="${spec.first}" href="$path" media-type="${spec.second}"${if (spec.first == "cover") """ properties="cover-image"""" else ""}/>"""
            }
            val manifest = chapterTitles.indices.joinToString("") { i ->
                """<item id="c${i + 1}" href="chapter-${i + 1}.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = chapterTitles.indices.joinToString("") { i -> """<itemref idref="c${i + 1}"/>""" }
            val creator = author.takeIf(String::isNotBlank)?.let { "<dc:creator>${xml(it)}</dc:creator>" }.orEmpty()
            val summary = description?.takeIf(String::isNotBlank)?.let { "<dc:description>${xml(it)}</dc:description>" }.orEmpty()
            val modified = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            put("EPUB/book.opf", """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">$identifier</dc:identifier>
                <dc:title>${xml(title)}</dc:title><dc:language>und</dc:language>$creator$summary
                <meta property="dcterms:modified">$modified</meta></metadata>
                <manifest><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>$manifest$items</manifest>
                <spine>$spine</spine></package>""")
            output = null
        }
        return EpubExportIntegrity.Expected(chapterTitles.indices.map { "EPUB/chapter-${it + 1}.xhtml" }, digests.toMap())
    }

    fun cover(image: Image) {
        val path = "cover.${image.extension}"
        assets[path] = "cover" to image.mime
        put("EPUB/$path", image.bytes)
    }

    fun chapter(title: String, bodyXhtml: String) {
        chapterTitles += title
        put("EPUB/chapter-${chapterTitles.size}.xhtml", xhtml(title, "<h1>${xml(title)}</h1>$bodyXhtml"))
    }

    fun image(image: Image): String {
        val id = "image-${assets.size + 1}"
        val path = "$id.${image.extension}"
        assets[path] = id to image.mime
        put("EPUB/$path", image.bytes)
        return path
    }

    private fun put(name: String, text: String) = put(name, text.toByteArray(UTF_8))

    private fun put(name: String, bytes: ByteArray) {
        val zip = checkNotNull(output)
        recordDigest(name, bytes)
        zip.putNextEntry(ZipEntry(name))
        for (offset in bytes.indices step 65536) {
            check()
            zip.write(bytes, offset, minOf(65536, bytes.size - offset))
        }
        zip.closeEntry()
    }

    private fun recordDigest(name: String, bytes: ByteArray) {
        digests[name] = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    }

    private fun xhtml(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <head><title>${xml(title)}</title></head><body>$body</body></html>"""

    companion object {
        fun xml(value: String): String = buildString(value.length) {
            value.codePoints().forEach { c ->
                when (c) {
                    38 -> append("&amp;")
                    60 -> append("&lt;")
                    62 -> append("&gt;")
                    34 -> append("&quot;")
                    39 -> append("&apos;")
                    9, 10, 13, in 32..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> appendCodePoint(c)
                    else -> append('\uFFFD')
                }
            }
        }
    }
}

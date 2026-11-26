package io.legado.app.model.localBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import io.legado.app.domain.model.RecoveredSourceDeletionException
import java.util.UUID

/** A managed UTF-8 TXT. File-specific headings delimit raw chapter bodies and also serve as its TOC. */
internal object RecoveredTextFile {
    private const val MAX_TITLE_BYTES = 4096
    private val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    val directory get() = File(appCtx.filesDir, "recovered_chapters")

    fun newFile() = File(directory, "recovered-${UUID.randomUUID()}.txt")

    fun fileOf(book: Book): File? {
        val file = File(book.bookUrl)
        val name = file.name
        if (!name.startsWith("recovered-") || !name.endsWith(".txt") ||
            !uuid.matches(name.removePrefix("recovered-").removeSuffix(".txt"))
        ) return null
        return file.takeIf { it.parentFile == directory }
    }

    /** Only the managed source, never an exported file or an arbitrary directory. */
    fun deleteSource(book: Book): Boolean {
        val file = fileOf(book) ?: return false
        try {
            val parent = directory.toPath()
            if (Files.exists(parent, NOFOLLOW_LINKS) && !Files.isDirectory(parent, NOFOLLOW_LINKS)) {
                throw IOException("Invalid recovered book directory")
            }
            val path = file.toPath()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) throw IOException("Invalid recovered book source")
            Files.deleteIfExists(path)
        } catch (_: NoSuchFileException) {
            // An already-missing source does not block removing its shelf record.
        } catch (error: IOException) {
            throw RecoveredSourceDeletionException(error)
        } catch (error: SecurityException) {
            throw RecoveredSourceDeletionException(error)
        }
        return true
    }

    data class Section(val title: String, val start: Long, val end: Long)

    class Writer(private val output: OutputStream, file: File) {
        private val prefix = headingPrefix(file)
        private var offset = 0L

        fun append(title: String, text: String, original: ByteArray): Section {
            val singleLine = title.lineSequence().joinToString(" ").trim()
            val titleBytes = singleLine.toByteArray()
            require(titleBytes.size in 1..MAX_TITLE_BYTES)
            check(!text.startsWith(prefix) && !text.contains("\n$prefix")) { "Recovery heading collision" }
            val heading = "$prefix$singleLine\n".toByteArray()
            output.write(heading)
            val start = offset + heading.size
            output.write(original)
            output.write('\n'.code)
            offset = start + original.size + 1
            return Section(singleLine, start, offset - 1)
        }
    }

    private fun headingPrefix(file: File) = "[Legado ${file.nameWithoutExtension}] "

    fun sections(file: File, check: () -> Unit = {}): List<Section> {
        val prefix = headingPrefix(file).toByteArray()
        val result = mutableListOf<Section>()
        var offset = 0L
        var lineStart = 0L
        var matched = 0
        val titleBytes = ByteArrayOutputStream()
        var lastTitle: String? = null
        var lastStart = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val length = input.read(buffer)
                if (length < 0) break
                for (at in 0 until length) {
                    val byte = buffer[at]
                    if (byte == '\n'.code.toByte()) {
                        if (matched == prefix.size) {
                            lastTitle?.let {
                                if (lineStart <= lastStart) throw IOException("Invalid recovered chapter boundary")
                                result += Section(it, lastStart, lineStart - 1)
                            }
                            if (lastTitle == null && lineStart != 0L) throw IOException("Missing first recovery heading")
                            lastTitle = titleBytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
                                .takeIf { it.isNotBlank() } ?: throw IOException("Empty recovery heading")
                            lastStart = offset + 1
                        }
                        titleBytes.reset()
                        matched = 0
                        lineStart = offset + 1
                    } else if (matched == prefix.size) {
                        if (titleBytes.size() == MAX_TITLE_BYTES) throw IOException("Invalid recovery heading")
                        titleBytes.write(byte.toInt())
                    } else if (matched >= 0) {
                        matched = if (byte == prefix[matched]) matched + 1 else -1
                    }
                    offset++
                }
            }
        }
        val title = lastTitle ?: throw IOException("No recovery headings")
        if (offset != lineStart || offset <= lastStart) throw IOException("Incomplete recovered chapter")
        result += Section(title, lastStart, offset - 1)
        return result
    }

    fun chapters(book: Book, sections: List<Section>): ArrayList<BookChapter> =
        ArrayList(sections.mapIndexed { index, section ->
            BookChapter(
                bookUrl = book.bookUrl,
                url = "${book.originName}#$index",
                index = index,
                title = section.title,
                start = section.start,
                end = section.end,
            )
        })

    fun rebuild(
        book: Book,
        file: File,
        previous: List<BookChapter>,
        pattern: Regex? = null,
        beforeTitleChange: (BookChapter) -> Unit = {},
        onTitleMatch: (changed: Boolean) -> Unit = {},
        check: () -> Unit = {},
    ): ArrayList<BookChapter> {
        val byUrl = previous.associateBy(BookChapter::url)
        val chapters = chapters(book, sections(file, check))
        if (pattern == null) {
            for (chapter in chapters) {
                check()
                previousSection(byUrl, chapter)?.let { chapter.title = it.title }
            }
            return chapters
        }
        RandomAccessFile(file, "r").use { input ->
            for (chapter in chapters) {
                check()
                val old = previousSection(byUrl, chapter)
                val detected = RecoveredChapterTitle.match(prefix(input, chapter), pattern)
                val previousTitle = old?.title ?: chapter.title
                if (detected != null) onTitleMatch(detected != previousTitle)
                chapter.title = detected ?: previousTitle
                if (old != null && old.title != chapter.title) beforeTitleChange(old)
            }
        }
        return chapters
    }

    private fun previousSection(byUrl: Map<String, BookChapter>, chapter: BookChapter): BookChapter? =
        byUrl[chapter.url]?.takeIf {
            it.index == chapter.index && it.start == chapter.start && it.end == chapter.end
        }

    private fun prefix(input: RandomAccessFile, chapter: BookChapter): String {
        val start = checkNotNull(chapter.start)
        val length = checkNotNull(chapter.end) - start
        input.seek(start)
        val bytes = ByteArray(minOf(length, RecoveredChapterTitle.prefixBytes.toLong()).toInt())
        input.readFully(bytes)
        val end = if (bytes.size.toLong() < length) {
            // Do not accept the start of a longer line, or decode a partial UTF-8 sequence.
            bytes.indexOfLast { it == '\n'.code.toByte() || it == '\r'.code.toByte() } + 1
        } else bytes.size
        return bytes.decodeToString(endIndex = end, throwOnInvalidSequence = true)
    }

    fun content(file: File, chapter: BookChapter): String = RandomAccessFile(file, "r").use { input ->
        val start = checkNotNull(chapter.start)
        val end = checkNotNull(chapter.end)
        if (start < 0 || end - start !in 1..Int.MAX_VALUE.toLong() || end > input.length()) {
            throw IOException("Invalid recovered chapter offsets")
        }
        input.seek(start)
        ByteArray((end - start).toInt()).also(input::readFully).decodeToString(throwOnInvalidSequence = true)
    }
}

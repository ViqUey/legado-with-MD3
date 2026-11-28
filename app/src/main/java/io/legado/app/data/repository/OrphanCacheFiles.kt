package io.legado.app.data.repository

import io.legado.app.domain.model.OrphanTextId
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal object OrphanCacheFiles {
    data class Stamp(val size: Long, val modified: Long)
    data class Snapshot(val stamp: Stamp, val bytes: ByteArray, val text: String, val digest: String)

    private fun segment(value: String): Boolean =
        value.isNotEmpty() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it == '\u0000' }

    fun directory(file: File) = Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)

    fun bookDirectory(root: File, folder: String): File {
        require(segment(folder))
        return File(root, folder).also {
            if (!directory(root) || !directory(it)) throw IOException("Unavailable book cache directory")
        }
    }

    fun source(bookDirectory: File, id: OrphanTextId): File {
        require(segment(id.entry) && id.entry != "images")
        val entry = File(bookDirectory, id.entry)
        if (id.child == null) {
            require(entry.name.endsWith(".nb"))
            return entry
        }
        require(segment(id.child) && id.child.endsWith(".nb"))
        if (!directory(entry)) throw IOException("Unavailable chapter cache directory")
        return File(entry, id.child)
    }

    fun textIds(entry: File): List<OrphanTextId> = if (directory(entry)) {
        (entry.listFiles() ?: throw IOException("Cannot list chapter cache directory")).filter { it.name.endsWith(".nb") && !TranslationCacheFileNames.isCompleted(it.name) }
            .map { OrphanTextId(entry.name, it.name) }
    } else if (entry.name.endsWith(".nb") && !TranslationCacheFileNames.isCompleted(entry.name)) {
        listOf(OrphanTextId(entry.name))
    } else {
        emptyList()
    }

    fun stamp(file: File): Stamp? {
        val path = file.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) return null
        return Stamp(attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    /** Validate UTF-8 throughout, retaining only the beginning for title discovery. */
    fun firstLine(file: File, check: () -> Unit): String? = InputStreamReader(open(file), Charsets.UTF_8.newDecoder()).buffered().use { reader ->
        val first = StringBuilder()
        var firstLine = true
        var nonBlank = false
        val buffer = CharArray(2048)
        while (true) {
            check()
            val count = reader.read(buffer)
            if (count < 0) return@use first.toString().takeIf { nonBlank }
            for (i in 0 until count) {
                val char = buffer[i]
                if (char == '\u0000') return@use null
                if (!char.isWhitespace()) nonBlank = true
                if (firstLine && (char == '\n' || char == '\r')) firstLine = false
                if (firstLine && first.length < 4096) first.append(char)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    fun snapshot(file: File, check: () -> Unit): Snapshot? {
        val before = stamp(file) ?: return null
        val path = file.toPath()
        val identity = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (before.size > Int.MAX_VALUE) throw IOException("Chapter exceeds supported text size")
        val bytes = ByteArray(before.size.toInt())
        val digest = MessageDigest.getInstance("SHA-256")
        open(file).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                check()
                val count = input.read(bytes, offset, minOf(bytes.size - offset, 64 * 1024))
                if (count < 0) throw IOException("Chapter changed while reading")
                digest.update(bytes, offset, count)
                offset += count
            }
            if (input.read() != -1) throw IOException("Chapter changed while reading")
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || after.size() != before.size ||
            after.fileKey() != identity.fileKey() || after.lastModifiedTime() != identity.lastModifiedTime()
        ) throw IOException("Chapter changed while reading")
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        if ('\u0000' in text) throw IOException("Invalid chapter text")
        return Snapshot(before, bytes, text, digest.digest().toHexString())
    }

    fun digest(file: File, expected: Stamp, check: () -> Unit): String {
        val path = file.toPath()
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.size() != expected.size ||
            before.lastModifiedTime().toMillis() != expected.modified
        ) throw IOException("Chapter changed before grouping")
        val digest = MessageDigest.getInstance("SHA-256")
        val bytesRead = open(file).use { stream ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                check()
                val length = stream.read(buffer)
                if (length < 0) break
                digest.update(buffer, 0, length)
                total += length
                if (total > expected.size) throw IOException("Chapter grew while grouping")
            }
            total
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || bytesRead != expected.size || before.size() != after.size() ||
            before.fileKey() != after.fileKey() || before.lastModifiedTime() != after.lastModifiedTime()
        ) throw IOException("Chapter changed while grouping")
        return digest.digest().toHexString()
    }

    private fun open(file: File) = Channels.newInputStream(Files.newByteChannel(file.toPath(), READ, NOFOLLOW_LINKS))
}

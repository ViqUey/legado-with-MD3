package io.legado.app.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.coil.CoverFileCache
import io.legado.app.help.coil.isDefaultCoverPath
import io.legado.app.help.coil.isLocalCoverPath
import io.legado.app.lib.epub.TextEpubExport
import org.jsoup.parser.Parser
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

internal object RecoveryIllustrations {
    private const val maxImageBytes = 24 * 1024 * 1024
    fun hasImages(raw: String, book: Book): Boolean {
        val covers = listOfNotNull(book.coverUrl, book.customCoverUrl).filter {
            isHttp(it) || it.startsWith("data:", true) || it.startsWith("file:", true) || it.startsWith("content:", true)
        }
        return RecoveryChapterMarkup.hasImages(raw) { src ->
            // Relative chapter sources need not have the same base as the cover. Compare full addresses with options intact.
            BookHelp.isRecoveryBubble(src) || src in covers
        }
    }

    fun body(
        raw: String, book: Book, subject: String, writer: TextEpubExport,
        missingText: String, check: () -> Unit, missing: () -> Unit,
    ): String {
        val resolved = HashMap<String, String?>()
        return RecoveryChapterMarkup.render(raw, BookHelp::isRecoveryBubble, missingText, check, missing) { src ->
            if (src in resolved) resolved[src]
            else readIllustration(book, subject, src)?.let(writer::image).also { resolved[src] = it }
        }
    }

    fun cover(book: Book): TextEpubExport.Image? {
        val cover = book.getDisplayCover()?.takeUnless(::isDefaultCoverPath) ?: return null
        val local = isLocalCoverPath(cover) || File(cover).isAbsolute
        val source = when {
            cover.startsWith("data:", true) -> "inline"
            local -> "local"
            else -> "cached"
        }
        val image = try {
            when (source) {
                "inline" -> readInlineImage(cover)
                "local" -> openLocalCover(cover)?.let(::validated)
                else -> {
                    val file = CoverFileCache.read(cover) ?: if (book.customCoverUrl.isNullOrEmpty() && isHttp(cover)) {
                        CoverFileCache.readByBookUrl(book.bookUrl)
                    } else null
                    file?.inputStream()?.let(::validated)
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (image == null) AppLog.put("Orphan recovery: $source cover unavailable from local data")
        return image
    }

    private fun openLocalCover(address: String): InputStream? {
        val uri = Uri.parse(address).normalizeScheme()
        return when (uri.scheme) {
            "content" -> appCtx.contentResolver.openInputStream(uri)
            "file" -> uri.path?.let { File(it).inputStream() }
            else -> File(address).inputStream()
        }
    }

    private fun isHttp(source: String) = source.startsWith("http://", true) || source.startsWith("https://", true)

    private fun readInlineImage(source: String): TextEpubExport.Image? {
        if (!source.substringBefore(',').endsWith(";base64", true)) return null
        val encoded = source.substringAfter(',', "")
        if (encoded.length > maxImageBytes * 2) return null
        return validated(Base64.decode(encoded, Base64.DEFAULT))
    }

    private fun readIllustration(book: Book, subject: String, source: String): TextEpubExport.Image? {
        val decoded = Parser.unescapeEntities(source, false)
        for (src in listOf(source, decoded).distinct()) {
            try {
                val image = when {
                    src.startsWith("data:image/", true) -> readInlineImage(src)
                    isHttp(src) -> validated(BookHelp.openRecoveryImage(book, subject, src))
                    else -> null
                }
                if (image != null) return image
            } catch (_: IOException) {
                // Try the HTML entity-decoded representation without modifying URL parameters.
            } catch (_: IllegalArgumentException) {
                // Malformed Base64 or URL data is reported as a missing illustration.
            }
        }
        return null
    }

    private fun validated(stream: InputStream): TextEpubExport.Image? {
        val bytes = stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32768)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (out.size() + size > maxImageBytes) return null
                out.write(buffer, 0, size)
            }
            out.toByteArray()
        }
        return validated(bytes)
    }

    private fun validated(bytes: ByteArray): TextEpubExport.Image? {
        if (bytes.size > maxImageBytes) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth.toLong() * bounds.outHeight > 16_000_000) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            // The decoder validates the image. Use its encoded signature to preserve the
            // original bytes even when this platform decoder omits the reported MIME type.
            val signature = bytes.take(8).map { it.toInt() and 0xff }
            val type = when {
                signature == listOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ->
                    "png" to "image/png"
                signature.take(3) == listOf(0xff, 0xd8, 0xff) -> "jpg" to "image/jpeg"
                signature.take(6) in listOf(
                    listOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61),
                    listOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61),
                ) -> "gif" to "image/gif"
                else -> null
            }
            if (type != null) return TextEpubExport.Image(bytes, type.first, type.second)
            val encoded = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)) return null
            return TextEpubExport.Image(encoded.toByteArray(), "png", "image/png")
        } finally {
            bitmap.recycle()
        }
    }
}

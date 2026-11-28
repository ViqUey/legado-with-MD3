package io.legado.app.data.repository

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** An exclusive creation kept open until the Room update commits or rolls back. */
internal class TitleCacheFile(val path: File, private val handle: Handle) : Closeable {
    private var written = 0

    internal interface Handle : Closeable {
        fun write(bytes: ByteArray, offset: Int, length: Int): Int
        fun sync()
        fun stillNamed(): Boolean
    }

    fun write(bytes: ByteArray, check: () -> Unit) {
        while (written < bytes.size) {
            check()
            val count = handle.write(bytes, written, minOf(64 * 1024, bytes.size - written))
            if (count <= 0) throw IOException("Writing renamed chapter cache made no progress")
            written += count
        }
        handle.sync()
        check()
    }

    fun stillNamed() = handle.stillNamed()

    fun removeUncommitted(bytes: ByteArray): Boolean {
        if (!stillNamed()) return false
        val attrs = Files.readAttributes(path.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attrs.isRegularFile || attrs.size() != written.toLong()) return false
        val prefixDigest = MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, written) }
            .digest().toHexString()
        val stamp = OrphanCacheFiles.Stamp(attrs.size(), attrs.lastModifiedTime().toMillis())
        if (OrphanCacheFiles.digest(path, stamp) {} != prefixDigest || !stillNamed()) return false
        return Files.deleteIfExists(path.toPath())
    }

    override fun close() = handle.close()

    companion object {
        fun create(path: File): TitleCacheFile {
            val flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW
            val fd = try {
                Os.open(path.path, flags, 0x180) // 0600
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EEXIST) throw FileAlreadyExistsException(path.path)
                throw IOException(error)
            }
            return TitleCacheFile(path, object : Handle {
                override fun write(bytes: ByteArray, offset: Int, length: Int) =
                    Os.write(fd, bytes, offset, length)

                override fun sync() = Os.fsync(fd)

                override fun stillNamed(): Boolean {
                    val opened = Os.fstat(fd)
                    val named = try {
                        Os.lstat(path.path)
                    } catch (error: ErrnoException) {
                        if (error.errno == OsConstants.ENOENT) return false
                        throw error
                    }
                    return opened.st_ino != 0L && OsConstants.S_ISREG(named.st_mode) &&
                        opened.st_dev == named.st_dev && opened.st_ino == named.st_ino
                }

                override fun close() = Os.close(fd)
            })
        }
    }
}

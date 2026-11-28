package io.legado.app.data.repository

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.IdentityHashMap

/** Uses real seekable files for the three OS operations AndroidZipFile uses through PfdHelper. */
@Implements(Os::class)
class EpubSeekableFilesShadow {
    companion object {
        private val channels = IdentityHashMap<FileDescriptor, FileChannel>()

        private fun channel(fd: FileDescriptor): FileChannel = synchronized(channels) {
            channels.getOrPut(fd) { FileInputStream(fd).channel }
        }

        @JvmStatic
        @Implementation
        fun fstat(fd: FileDescriptor): StructStat =
            StructStat(0, 0, OsConstants.S_IFREG, 1, 0, 0, 0, channel(fd).size(), 0, 0, 0, 0, 0)

        @JvmStatic
        @Implementation
        fun lseek(fd: FileDescriptor, offset: Long, whence: Int): Long {
            val file = channel(fd)
            val position = when (whence) {
                OsConstants.SEEK_SET -> offset
                OsConstants.SEEK_CUR -> file.position() + offset
                OsConstants.SEEK_END -> file.size() + offset
                else -> error("Unknown seek origin $whence")
            }
            return file.position(position).position()
        }

        @JvmStatic
        @Implementation
        fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int =
            channel(fd).read(ByteBuffer.wrap(bytes, offset, count))

        @JvmStatic
        @Resetter
        fun reset() = synchronized(channels) {
            channels.values.forEach(FileChannel::close)
            channels.clear()
        }
    }
}

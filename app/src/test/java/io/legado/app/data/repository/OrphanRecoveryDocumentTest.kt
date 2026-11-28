package io.legado.app.data.repository

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import io.legado.app.data.repository.RecoveryExportFiles.Child
import io.legado.app.data.repository.RecoveryExportFiles.DocumentAccess
import io.legado.app.domain.model.RecoveryExportFormat
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class OrphanRecoveryDocumentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val tree = Uri.parse("content://recovery.fixture/tree/root")
    private val name = "Original.txt"
    private val names = RecoveryExportNames("Original", "", ".txt")
    private val format = RecoveryExportFormat.Txt

    @Test fun fullNameIsSentAndSafeProviderNormalizationIsAccepted() {
        val docs = Documents(temporary.newFolder())
        docs.name = "Normalized name (1).TXT"
        val source = temporary.newFile().apply { writeText("expected chapters") }
        val result = publish(source, docs)
        assertEquals(name, docs.requested)
        assertEquals(docs.name, result.name)
        assertEquals(docs.newUri, result.uri)
        assertFalse(result.durableRead)
        assertEquals(source.readText(), docs.newFile.readText())
        assertEquals("unrelated", docs.original.readText())
    }

    @Test fun choosesFirstAvailableNumberAcrossCaseInsensitiveChildrenAndDatabaseNames() {
        val docs = Documents(temporary.newFolder()).apply {
            originalName = "original.TXT"
            name = "Original（2）.txt"
        }
        val source = temporary.newFile().apply { writeText("new chapters") }
        val result = publish(source, docs, occupied = { candidate, _ -> candidate == "Original（1）.txt" })
        assertEquals("Original（2）.txt", docs.requested)
        assertEquals("Original（2）.txt", result.name)
        assertEquals(1, docs.createCalls)
        assertEquals("new chapters", docs.newFile.readText())
        assertEquals("unrelated", docs.original.readText())
    }

    @Test fun latePreCreateCollisionFailsWithoutOpeningOrDeletingTheOtherDocument() {
        val docs = Documents(temporary.newFolder()).apply { lateName = "Original.TXT" }
        val source = temporary.newFile().apply { writeText("new chapters") }
        assertThrows(IOException::class.java) { publish(source, docs) }
        assertEquals(0, docs.createCalls)
        assertEquals(0, docs.opens)
        assertEquals(0, docs.deletes)
        assertEquals("unrelated", docs.original.readText())
    }

    @Test fun exhaustedOrCancelledCandidateSearchDoesNotCreateADocument() {
        val source = temporary.newFile().apply { writeText("new chapters") }
        val exhausted = Documents(temporary.newFolder())
        var attempts = 0
        assertThrows(IOException::class.java) {
            publish(source, exhausted, occupied = { _, _ -> attempts++; true })
        }
        assertEquals(10_000, attempts)
        assertEquals(0, exhausted.createCalls)
        val cancelled = Documents(temporary.newFolder())
        var checks = 0
        assertThrows(CancellationException::class.java) {
            publish(source, cancelled, occupied = { _, _ -> true }, check = {
                if (++checks == 3) throw CancellationException("candidate search")
            })
        }
        assertEquals(0, cancelled.createCalls)
        assertEquals("unrelated", exhausted.original.readText())
        assertEquals("unrelated", cancelled.original.readText())
    }

    @Test fun ownedDocumentIsRemovedAfterFailuresInTheExternalPublicationStage() {
        val source = temporary.newFile().apply { writeBytes(ByteArray(128 * 1024) { 7 }) }
        for (failure in listOf("cancel", "partial", "name", "type", "read", "digest", "truncated", "database name", "database uri")) {
            val docs = Documents(temporary.newFolder())
            docs.fault = failure
            val occupied: (String, Uri?) -> Boolean = { actual, uri ->
                failure == "database name" && actual == docs.name || failure == "database uri" && uri == docs.newUri
            }
            val check = {
                if (failure == "cancel" && docs.newFile.exists()) throw CancellationException("after create")
            }
            val error = if (failure == "cancel") CancellationException::class.java else IOException::class.java
            assertThrows(failure, error) { publish(source, docs, occupied, check) }
            assertTrue(failure, docs.created)
            assertFalse(failure, docs.newFile.exists())
            assertEquals(failure, "unrelated", docs.original.readText())
            if (failure == "partial") assertTrue(docs.partialBytesWritten > 0)
        }
    }

    @Test fun unknownOwnershipAndExistingFilesAreNeverDeletedOrOpenedForWrite() {
        val source = temporary.newFile().apply { writeText("new content") }
        for (failure in listOf("existing id", "other parent", "name collision", "cannot list before")) {
            val docs = Documents(temporary.newFolder())
            docs.fault = failure
            assertThrows(failure, IOException::class.java) { publish(source, docs) }
            assertEquals(failure, "unrelated", docs.original.readText())
            assertEquals(failure, 0, docs.opens)
            if (failure == "other parent") assertTrue(docs.newFile.exists())
            if (failure == "cannot list before") assertFalse(docs.created)
            if (failure == "name collision") {
                assertFalse(docs.newFile.exists())
                assertEquals(1, docs.createCalls)
            }
        }
    }

    private fun publish(
        source: File,
        docs: Documents,
        occupied: (String, Uri?) -> Boolean = { _, _ -> false },
        check: () -> Unit = {},
    ) = RecoveryExportFiles.publishDocument(source, tree, names, format, occupied, check, docs)

    private inner class Documents(directory: File) : DocumentAccess {
        val original = directory.resolve("existing").apply { writeText("unrelated") }
        val newFile = directory.resolve("new")
        val newUri: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, "new")
        var originalName = "existing.txt"
        var name = "Provider normalized.txt"
        var requested: String? = null
        var created = false
        var createCalls = 0
        var lateName: String? = null
        private var listings = 0
        var fault: String? = null
        var opens = 0
        var deletes = 0
        var partialBytesWritten = 0L

        private fun child(): Child {
            val actual = when (fault) {
                "name" -> "Provider removed extension"
                "name collision" -> originalName
                else -> name
            }
            return Child("new", actual, if (fault == "type") "image/png" else format.mime)
        }

        override fun children(): List<Child> {
            if (fault == "cannot list before") throw IOException("Parent query failed")
            listings++
            return buildList {
                add(Child("existing", originalName, format.mime))
                if (listings > 1) lateName?.let { add(Child("late", it, format.mime)) }
                if (newFile.exists() && fault != "other parent") add(child())
            }
        }

        override fun create(mime: String, name: String): Uri {
            assertEquals(format.mime, mime)
            createCalls++
            requested = name
            if (fault == "existing id") return DocumentsContract.buildDocumentUri(tree.authority, "existing")
            created = newFile.createNewFile()
            return DocumentsContract.buildDocumentUri(tree.authority, "new")
        }

        override fun metadata(uri: Uri) = child().takeIf { uri == newUri && newFile.exists() }

        override fun openWrite(uri: Uri): FilterOutputStream {
            assertEquals(newUri, uri)
            opens++
            return object : FilterOutputStream(newFile.outputStream()) {
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (fault == "partial") {
                        out.write(bytes, offset, 4)
                        partialBytesWritten = newFile.length()
                        throw IOException("Partial write")
                    }
                    out.write(bytes, offset, count)
                }
            }
        }

        override fun openRead(uri: Uri) = when (fault) {
            "read" -> throw IOException("Read failed")
            "digest", "truncated" -> {
                RandomAccessFile(newFile, "rw").use {
                    if (fault == "digest") it.write(99) else it.setLength(8)
                }
                newFile.inputStream()
            }
            else -> newFile.inputStream()
        }

        override fun delete(uri: Uri) {
            deletes++
            assertEquals(newUri, uri)
            assertTrue(newFile.delete())
        }

        override fun hasDurableRead() = false
    }
}

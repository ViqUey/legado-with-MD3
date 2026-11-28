package io.legado.app.data.repository

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.legado.app.domain.model.RecoveryExportFormat
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal object RecoveryExportFiles {
    data class Published(val name: String, val uri: Uri, val durableRead: Boolean)

    fun publish(
        temporary: File,
        directory: String,
        title: String,
        format: RecoveryExportFormat,
        occupied: (String, Uri?) -> Boolean,
        check: () -> Unit,
    ): Published {
        val base = title.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
            .joinToString("").take(70).trim('.', ' ').ifBlank { "Recovered" }
        val suffix = " - ${UUID.randomUUID().toString().take(12)}${format.suffix}"
        val name = base.trimEnd() + suffix
        val parsed = Uri.parse(directory)
        return if (parsed.scheme == "content") {
            publishDocument(temporary, parsed, name, format, occupied, check)
        } else {
            val path = File(parsed.path.takeIf { parsed.scheme == "file" } ?: directory)
            publishFile(temporary, path, name, occupied, check)
        }
    }

    private fun sharedRoots(): List<File> = buildList {
        val primary = Environment.getExternalStorageDirectory()
        add(primary)
        for (alias in listOf(File("/sdcard"), File("/storage/self/primary"))) {
            if (alias.canonicalFile == primary.canonicalFile) add(alias)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val manager = appCtx.getSystemService(StorageManager::class.java)
            manager?.storageVolumes?.mapNotNullTo(this) { it.directory }
        } else {
            // Before API 30 the volume root is exposed through its standard app files directory.
            for (files in appCtx.getExternalFilesDirs(null)) {
                val data = files?.parentFile?.parentFile
                val android = data?.parentFile
                if (data?.name == "data" && android?.name == "Android") {
                    android.parentFile?.let { add(it) }
                }
            }
        }
    }

    private fun publishFile(
        temporary: File, directory: File, name: String,
        occupied: (String, Uri?) -> Boolean, check: () -> Unit,
    ): Published {
        if (!directory.isAbsolute) throw IOException("An absolute export directory is required")
        val canonical = directory.canonicalFile
        val selected = directory.toPath().normalize()
        val roots = sharedRoots()
        fun insideSharedRoot() = roots.any { root ->
            val actual = root.canonicalFile.toPath()
            val anchor = listOf(root.absoluteFile.toPath(), actual).firstOrNull(selected::startsWith)
                ?: return@any false
            // Allow platform aliases while refusing links inside the chosen volume.
            var current = actual
            for (part in anchor.relativize(selected)) {
                current = current.resolve(part)
                if (!Files.isDirectory(current, NOFOLLOW_LINKS)) return@any false
            }
            current == canonical.toPath()
        }
        fun directoryAttributes() = Files.readAttributes(canonical.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val target = canonical.resolve(name)
        val uri = Uri.fromFile(target)
        if (!insideSharedRoot() || !Files.isDirectory(canonical.toPath(), NOFOLLOW_LINKS) || occupied(name, uri)) {
            throw IOException("Export directory or file name is unavailable")
        }
        val parentKey = directoryAttributes().fileKey()
        fun requireOriginalDirectory() {
            if (!insideSharedRoot() || !Files.isDirectory(canonical.toPath(), NOFOLLOW_LINKS) ||
                directoryAttributes().fileKey() != parentKey
            ) throw IOException("Export directory changed")
        }
        check()
        requireOriginalDirectory()
        val path = target.toPath()
        val output = try {
            Files.newOutputStream(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        } catch (error: FileAlreadyExistsException) {
            throw IOException("Another file already uses this name", error)
        }
        fun identity() = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        var key: Any? = null
        try {
            output.use { out ->
                key = identity()
                temporary.inputStream().use { input -> transfer(input, out, check) }
            }
            requireOriginalDirectory()
            if (key != null && key != identity()) throw IOException("Exported file was replaced")
            verify(temporary, Files.newInputStream(path, NOFOLLOW_LINKS), check)
            requireOriginalDirectory()
            if (key != null && key != identity()) throw IOException("Exported file was replaced")
            return Published(name, uri, durableRead = true)
        } catch (error: Throwable) {
            runCatching {
                requireOriginalDirectory()
                if (key != null && key == identity()) Files.deleteIfExists(path)
            }
            throw error
        }
    }

    internal data class Child(val id: String, val name: String, val mime: String)

    internal interface DocumentAccess {
        fun children(): List<Child>
        fun create(mime: String, name: String): Uri?
        fun metadata(uri: Uri): Child?
        fun openWrite(uri: Uri): OutputStream?
        fun openRead(uri: Uri): InputStream?
        fun delete(uri: Uri)
        fun hasDurableRead(): Boolean
    }

    internal fun publishDocument(
        temporary: File, tree: Uri, name: String, format: RecoveryExportFormat,
        occupied: (String, Uri?) -> Boolean, check: () -> Unit,
        access: DocumentAccess = documentAccess(tree),
    ): Published {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val existing = access.children()
        if (occupied(name, null) || existing.any { it.name.equals(name, ignoreCase = true) }) {
            throw IOException("Another file already uses this name")
        }
        check()
        val created = access.create(format.mime, name)
            ?: throw IOException("Document provider could not create the file")
        if (created.scheme != "content" || created.authority != tree.authority) {
            throw IOException("Document provider did not return a file in the selected directory")
        }
        val id = DocumentsContract.getDocumentId(created)
        if (id == parentId || existing.any { it.id == id }) {
            throw IOException("Document provider did not return a new file")
        }
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        // A returned URI alone is insufficient: require one newly listed child in this parent.
        val children = access.children()
        val child = children.singleOrNull { it.id == id }
            ?: throw IOException("Cannot establish ownership of the created document")
        try {
            val actualName = child.name
            if (access.metadata(uri) != child || child.mime != format.mime ||
                !actualName.endsWith(format.suffix, ignoreCase = true) || actualName.length <= format.suffix.length ||
                actualName.any { it.isISOControl() || it == '/' || it == '\\' }
            ) throw IOException("Document provider returned an incompatible file")
            if ((existing + children).any { it.id != id && it.name.equals(actualName, ignoreCase = true) } ||
                occupied(name, uri) || occupied(actualName, uri)
            ) throw IOException("Another file or local book uses this name or URI")
            check()
            access.openWrite(uri)?.use { out ->
                temporary.inputStream().use { input -> transfer(input, out, check) }
            } ?: throw IOException("File is not writable")
            val read = access.openRead(uri) ?: throw IOException("Cannot verify exported file")
            verify(temporary, read, check)
            if (access.metadata(uri) != child || access.children().singleOrNull { it.id == id } != child) {
                throw IOException("The exported document changed")
            }
            val durableRead = access.hasDurableRead()
            check()
            return Published(actualName, uri, durableRead)
        } catch (error: Throwable) {
            runCatching {
                if (access.children().singleOrNull { it.id == id } == child && access.metadata(uri) == child) {
                    access.delete(uri)
                }
            }
            throw error
        }
    }

    private fun documentAccess(tree: Uri): DocumentAccess {
        if (!DocumentsContract.isTreeUri(tree)) throw IOException("A document tree is required")
        val resolver = appCtx.contentResolver
        val id = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        fun query(uri: Uri): List<Child> {
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            return resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) {
                    throw IOException("Document provider is still loading")
                }
                val idColumn = cursor.getColumnIndexOrThrow(columns[0])
                val nameColumn = cursor.getColumnIndexOrThrow(columns[1])
                val mimeColumn = cursor.getColumnIndexOrThrow(columns[2])
                buildList {
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(idColumn) ?: throw IOException("Missing document identity")
                        add(Child(documentId, cursor.getString(nameColumn).orEmpty(), cursor.getString(mimeColumn).orEmpty()))
                    }
                }
            } ?: throw IOException("Cannot read document provider metadata")
        }
        return object : DocumentAccess {
            override fun children() = query(children)
            override fun create(mime: String, name: String) = DocumentsContract.createDocument(resolver, parent, mime, name)
            override fun metadata(uri: Uri) = query(uri).singleOrNull()
            override fun openWrite(uri: Uri) = resolver.openOutputStream(uri, "w")
            override fun openRead(uri: Uri) = resolver.openInputStream(uri)
            override fun delete(uri: Uri) {
                DocumentsContract.deleteDocument(resolver, uri)
            }
            override fun hasDurableRead() =
                resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        }
    }

    fun rememberTree(tree: Uri): String {
        val resolver = appCtx.contentResolver
        for (mode in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            runCatching { resolver.takePersistableUriPermission(tree, mode) }
        }
        return tree.toString()
    }

    private fun transfer(input: java.io.InputStream, output: java.io.OutputStream, check: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private fun verify(temporary: File, published: java.io.InputStream, check: () -> Unit) {
        fun digest(stream: java.io.InputStream): ByteArray = stream.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            transfer(input, java.security.DigestOutputStream(object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            }, digest), check)
            digest.digest()
        }
        val actual = digest(published)
        if (!actual.contentEquals(digest(temporary.inputStream()))) throw IOException("Exported file contents differ")
        check()
    }
}

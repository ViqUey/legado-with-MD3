package io.legado.app.data.repository

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.gateway.OrphanCacheRecoveryGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.gateway.PrivateAccessGateway
import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.PrivateBookFacts
import io.legado.app.domain.model.RecoveryExportPlan
import io.legado.app.domain.model.RecoveryExportFormat
import io.legado.app.domain.model.RecoveryExportResult
import io.legado.app.domain.model.OrphanRecoveryException
import io.legado.app.domain.model.OrphanText
import io.legado.app.domain.model.OrphanTextCatalog
import io.legado.app.domain.model.OrphanTextComparison
import io.legado.app.domain.model.OrphanTextCopy
import io.legado.app.domain.model.OrphanTextPreview
import io.legado.app.domain.model.RecoveryActivity
import io.legado.app.domain.model.RecoveryDifference
import io.legado.app.domain.model.RecoveryProblem
import io.legado.app.domain.model.RecoveryTarget
import io.legado.app.domain.model.RecoveryTargetVersion
import io.legado.app.domain.model.RecoveryTitleChange
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.supportsOrphanRecovery
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.cache.CacheDownloadActivity
import io.legado.app.model.localBook.RecoveredChapterTitle
import io.legado.app.model.localBook.LocalBook
import io.legado.app.lib.epub.TextEpubExport
import io.legado.app.lib.epub.EpubExportIntegrity
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.postEvent
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.coroutines.coroutineContext

class OrphanCacheRecoveryRepository internal constructor(
    private val database: AppDatabase,
    private val maintenance: BookCacheMaintenanceRepository,
    private val readSettings: ReadSettingsGateway,
    private val createTitleCache: (File) -> TitleCacheFile,
    private val canOpen: (String, PrivateBookFacts) -> Boolean = { _, facts -> !facts.isPrivate },
) : OrphanCacheRecoveryGateway {
    constructor(
        database: AppDatabase,
        maintenance: BookCacheMaintenanceRepository,
        readSettings: ReadSettingsGateway,
        privateAccess: PrivateAccessGateway,
    ) : this(database, maintenance, readSettings, TitleCacheFile::create, privateAccess::canOpenBookNow)

    // Serializes only confirmations from this tool. Ordinary cache writes do not take this mutex.
    private val replaceMutex = Mutex()

    private fun availableBook(url: String): Book {
        try {
            val book = database.bookDao.getBook(url)?.takeIf { it.supportsOrphanRecovery }
                ?: fail(RecoveryProblem.Unavailable)
            if (!canOpen(url, database.bookDao.privateFacts(url))) fail(RecoveryProblem.PrivateBook)
            return book
        } catch (error: Exception) {
            if (error is CancellationException || error is OrphanRecoveryException) throw error
            AppLog.put("Could not query recovery book", error)
            fail(RecoveryProblem.QueryFailed)
        }
    }

    private fun checkWriteAdmission(url: String) {
        val activity = when {
            ReadBook.hasAttachedReader(url) -> RecoveryActivity.Reader
            BaseReadAloudService.isAlive && ReadAloud.bookSession.owns(url) -> RecoveryActivity.ReadAloud
            WebBook.isRequestingContent(url) -> RecoveryActivity.ContentRequest
            else -> when (CacheBook.recoveryActivity(url)) {
                CacheDownloadActivity.Admission -> RecoveryActivity.Admission
                CacheDownloadActivity.Waiting -> RecoveryActivity.Waiting
                CacheDownloadActivity.Running -> RecoveryActivity.Running
                CacheDownloadActivity.Retrying -> RecoveryActivity.Retrying
                null -> null
            }
        }
        if (activity != null) throw OrphanRecoveryException(RecoveryProblem.BusyBook, activity)
    }

    private fun chapters(url: String): List<BookChapter> = try {
        database.bookChapterDao.getChapterList(url)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        AppLog.put("Could not query recovery chapters", error)
        fail(RecoveryProblem.QueryFailed)
    }

    private data class BookCache(
        val book: Book,
        val directory: File,
        val chapters: List<BookChapter>,
        val orphanEntries: Map<String, File>,
    )

    private fun bookCache(url: String): BookCache {
        val book = availableBook(url)
        val root = File(BookHelp.cachePath)
        val name = book.getFolderName()
        val chapters = chapters(url)
        val candidate = File(root, name)
        if (Files.notExists(candidate.toPath(), NOFOLLOW_LINKS)) {
            return BookCache(book, candidate, chapters, emptyMap())
        }
        val directory = try {
            OrphanCacheFiles.bookDirectory(root, name)
        } catch (error: IOException) {
            AppLog.put("Could not open recovery cache directory", error)
            fail(RecoveryProblem.ScanFailed)
        }
        val legal = chapters.mapTo(HashSet()) { it.getFileName().substringBeforeLast('.') }
        val orphans = maintenance.collectOrphanEntries(directory.name, legal)
            ?: fail(RecoveryProblem.ScanFailed)
        return BookCache(book, directory, chapters, orphans.associateBy(File::getName))
    }

    override suspend fun scanBook(bookUrl: String): OrphanTextCatalog = withContext(Dispatchers.IO) {
        val cache = bookCache(bookUrl)
        val titles = HashMap<String, String>()
        fun addTitle(title: String?) {
            if (!title.isNullOrBlank()) titles.putIfAbsent(MD5Utils.md5Encode16(title), title)
        }
        cache.chapters.forEach { addTitle(it.title) }
        addTitle(cache.book.durChapterTitle)
        addTitle(cache.book.latestChapterTitle)
        val texts = ArrayList<OrphanText>()
        var unreadable = 0
        val job = coroutineContext
        val facts = database.bookDao.privateFacts(bookUrl)
        val check: () -> Unit = {
            job.ensureActive()
            if (!canOpen(bookUrl, facts)) fail(RecoveryProblem.PrivateBook)
        }

        for (entry in cache.orphanEntries.values) {
            coroutineContext.ensureActive()
            val ids = try {
                OrphanCacheFiles.textIds(entry)
            } catch (_: IOException) {
                unreadable++
                continue
            }
            for (id in ids) {
                coroutineContext.ensureActive()
                val (file, stamp, firstLine) = try {
                    val candidate = OrphanCacheFiles.source(cache.directory, id)
                    val attributes = OrphanCacheFiles.stamp(candidate) ?: throw IOException("Unavailable chapter")
                    val titleLine = OrphanCacheFiles.firstLine(candidate, check)
                        ?: throw IOException("Empty or invalid chapter")
                    if (OrphanCacheFiles.stamp(candidate) != attributes) throw IOException("Chapter changed")
                    Triple(candidate, attributes, titleLine)
                } catch (_: IOException) {
                    unreadable++
                    continue
                }
                val (index, hash) = OrphanRecoveryRules.parseName(file.name.removeSuffix(".nb"))
                val title = hash?.let(titles::get) ?: firstLine.takeIf {
                    hash != null && it.isNotBlank() && MD5Utils.md5Encode16(it.removePrefix("\uFEFF")) == hash
                }?.removePrefix("\uFEFF")
                val suggestion = if (title == null) RecoveredChapterTitle.suggest(firstLine) else null
                texts += OrphanText(id, index, hash, title, stamp.size, stamp.modified, suggestion)
            }
        }
        val groups = groupCopies(cache.directory, texts, check) { unreadable++ }
        availableBook(bookUrl)
        OrphanTextCatalog(cache.book.name, groups.sortedWith(OrphanRecoveryRules.sourceOrder), unreadable)
    }

    private fun groupCopies(
        folder: File, texts: List<OrphanText>, check: () -> Unit, unreadable: () -> Unit,
    ): List<OrphanText> {
        return texts.groupBy(OrphanText::bytes).values.flatMap { candidates ->
            check()
            if (candidates.size == 1) return@flatMap candidates
            val clusters = HashMap<Pair<Int?, String>, MutableList<OrphanText>>()
            // Prefer the canonical nested file, then a stable path. The representative
            // never changes mid-session even if a different copy remains on disk.
            val ordered = candidates.sortedWith(compareBy<OrphanText> {
                it.id.child != "${it.id.entry}.nb"
            }.thenBy { it.id.relativePath })
            for (candidate in ordered) {
                check()
                val digest = try {
                    val file = OrphanCacheFiles.source(folder, candidate.id)
                    OrphanCacheFiles.digest(file, OrphanCacheFiles.Stamp(candidate.bytes, candidate.modified), check)
                } catch (_: IOException) {
                    unreadable()
                    continue
                }
                // SHA-256 is computed from the actual file bytes, with a stable identity check.
                clusters.getOrPut(candidate.index to digest) { mutableListOf() } += candidate.copy(contentDigest = digest)
            }
            clusters.values.map { members ->
                val primary = members.first()
                if (members.size == 1) primary else primary.copy(
                    copies = members.map {
                        OrphanTextCopy(it.id, it.titleHash, it.originalTitle, it.suggestedTitle, it.modified, it.index)
                    }.toImmutableList(),
                )
            }
        }
    }

    private fun readSource(cache: BookCache, item: OrphanText, expected: String?, check: () -> Unit): OrphanCacheFiles.Snapshot {
        if (item.id.entry !in cache.orphanEntries ||
            TranslationCacheFileNames.isCompleted(item.id.child ?: item.id.entry)
        ) fail(RecoveryProblem.Changed)
        val url = cache.book.bookUrl
        val facts = database.bookDao.privateFacts(url)
        val liveCheck: () -> Unit = {
            check()
            if (!canOpen(url, facts)) fail(RecoveryProblem.PrivateBook)
        }
        val file = OrphanCacheFiles.source(cache.directory, item.id)
        val snapshot = try {
            OrphanCacheFiles.snapshot(file, liveCheck)
        } catch (_: IOException) {
            fail(RecoveryProblem.Changed)
        } ?: fail(RecoveryProblem.Changed)
        if (snapshot.stamp.size != item.bytes || snapshot.stamp.modified != item.modified ||
            snapshot.text.isBlank() || expected != null && expected != snapshot.digest ||
            item.contentDigest != null && item.contentDigest != snapshot.digest
        ) fail(RecoveryProblem.Changed)
        availableBook(url)
        return snapshot
    }

    override suspend fun preview(bookUrl: String, source: OrphanText): OrphanTextPreview =
        withContext(Dispatchers.IO) {
            val job = coroutineContext
            val body = readSource(bookCache(bookUrl), source, null) { job.ensureActive() }
            if (source.id.entry !in bookCache(bookUrl).orphanEntries) fail(RecoveryProblem.Changed)
            OrphanTextPreview(body.text, body.digest)
        }

    override suspend fun targets(bookUrl: String, source: OrphanText): List<RecoveryTarget> =
        withContext(Dispatchers.IO) {
            val cache = bookCache(bookUrl)
            if (source.id.entry !in cache.orphanEntries) fail(RecoveryProblem.Changed)
            val options = cache.chapters.filterNot { it.isVolume }.map { chapter ->
                val match = OrphanRecoveryRules.match(source, chapter.index, MD5Utils.md5Encode16(chapter.title))
                RecoveryTarget(chapter.index, chapter.url, chapter.title, match)
            }
            OrphanRecoveryRules.orderedTargets(source, options)
        }

    private fun BookCache.target(request: RecoveryTarget): BookChapter =
        chapters.firstOrNull { it.index == request.index && it.url == request.url && it.title == request.title && !it.isVolume }
            ?: fail(RecoveryProblem.Changed)

    private data class TargetCache(
        val path: File,
        val version: RecoveryTargetVersion,
        val usableCopy: OrphanCacheFiles.Snapshot?,
        val readerText: OrphanCacheFiles.Snapshot?,
    ) {
        val hasShadowedCopy: Boolean get() = usableCopy != null && readerText == null
    }

    private fun targetLocation(cache: BookCache, chapter: BookChapter): File {
        val path = BookHelp.getChapterContentFile(cache.book, chapter)
        val parent = checkNotNull(path.parentFile)
        if (parent.parentFile != cache.directory ||
            Files.exists(parent.toPath(), NOFOLLOW_LINKS) && !OrphanCacheFiles.directory(parent)
        ) fail(RecoveryProblem.Unavailable)
        return path
    }

    private fun targetCache(cache: BookCache, chapter: BookChapter, check: () -> Unit): TargetCache {
        val path = targetLocation(cache, chapter)
        val legacy = File(cache.directory, path.name)
        val current = targetSnapshot(path, check)
        val flat = targetSnapshot(legacy, check)
        return TargetCache(
            path,
            RecoveryTargetVersion(current?.digest, flat?.digest),
            current?.takeIf { it.text.isNotBlank() } ?: flat?.takeIf { it.text.isNotBlank() },
            // BookHelp selects an existing nested file before considering the flat layout.
            (current ?: flat)?.takeIf { it.text.isNotBlank() },
        )
    }

    private fun targetSnapshot(file: File, check: () -> Unit): OrphanCacheFiles.Snapshot? =
        OrphanCacheFiles.snapshot(file, check).also {
            if (it == null && Files.exists(file.toPath(), NOFOLLOW_LINKS)) fail(RecoveryProblem.Unavailable)
        }

    override suspend fun compare(
        bookUrl: String,
        source: OrphanText,
        previewDigest: String,
        target: RecoveryTarget,
        newTitle: String?,
    ): OrphanTextComparison = withContext(Dispatchers.IO) {
        val job = coroutineContext
        val check = { job.ensureActive() }
        val cache = bookCache(bookUrl)
        val body = readSource(cache, source, previewDigest, check)
        val chapter = cache.target(target)
        val targetCache = targetCache(cache, chapter, check)
        val titleChange = newTitle?.let {
            val renamed = chapterWithTitle(chapter, it)
            val destination = titleDestination(cache, chapter, renamed, body.digest, check)
            RecoveryTitleChange(target.copy(title = renamed.title), destination.version)
        }
        val current = targetCache.usableCopy
        val difference = when {
            targetCache.readerText?.digest == body.digest -> RecoveryDifference.Identical
            current == null || current.digest == body.digest -> RecoveryDifference.Missing
            else -> RecoveryDifference.Different
        }
        val latest = bookCache(bookUrl)
        latest.target(target)
        if (latest.directory != cache.directory || source.id.entry !in latest.orphanEntries) fail(RecoveryProblem.Changed)
        OrphanTextComparison(
            target, targetCache.version, current?.text, difference,
            if (difference == RecoveryDifference.Different) OrphanRecoveryRules.diff(body.text, checkNotNull(current).text) else null,
            hasShadowedCopy = targetCache.hasShadowedCopy,
            titleChange = titleChange,
        )
    }

    override suspend fun inspectExport(
        bookUrl: String, selections: List<LocalRecoverySelection>,
    ): RecoveryExportPlan = withContext(Dispatchers.IO) {
        val cache = bookCache(bookUrl)
        val job = coroutineContext
        var images = false
        val checked = selections.map { selection ->
            val body = readSource(cache, selection.source, selection.previewDigest) { job.ensureActive() }
            images = images || RecoveryIllustrations.hasImages(body.text, cache.book)
            selection.copy(previewDigest = body.digest)
        }
        availableBook(bookUrl)
        RecoveryExportPlan(checked.toImmutableList(), images)
    }

    override suspend fun export(
        bookUrl: String,
        plan: RecoveryExportPlan,
        format: RecoveryExportFormat,
        directory: String,
        addToShelf: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): RecoveryExportResult = withCommitResult { recordCommit ->
        if (plan.selections.isEmpty()) fail(RecoveryProblem.Unavailable)
        val cache = bookCache(bookUrl)
        val job = coroutineContext
        val facts = database.bookDao.privateFacts(bookUrl)
        val check: () -> Unit = {
            job.ensureActive()
            if (!canOpen(bookUrl, facts)) fail(RecoveryProblem.PrivateBook)
        }
        val name = cache.book.name
        val author = cache.book.getRealAuthor()
        val intro = cache.book.getDisplayIntro()
        val temporary = File.createTempFile("orphan-export-", format.suffix, appCtx.cacheDir)
        val count = plan.selections.size
        var missingImages = 0
        try {
            when (format) {
                RecoveryExportFormat.Txt -> temporary.bufferedWriter(Charsets.UTF_8).use { output ->
                    check()
                    // Match the ordinary TXT export preface; use the same metadata as recovery EPUB.
                    output.write(name)
                    output.write("\n")
                    output.write(appCtx.getString(R.string.author_show, author))
                    output.write("\n")
                    output.write(appCtx.getString(
                        R.string.intro_show, "\n" + HtmlFormatter.format(intro),
                    ))
                    output.write("\n\n")
                    plan.selections.forEachIndexed { index, selection ->
                        check()
                        val body = readSource(cache, selection.source, requireNotNull(selection.previewDigest), check)
                        output.write(selection.title)
                        output.write("\n")
                        output.write(RecoveryExportTitle.body(body.text, selection))
                        output.write("\n\n")
                        onProgress(index + 1, count)
                    }
                }
                RecoveryExportFormat.Epub -> {
                    val writer = TextEpubExport(name, author, intro, check)
                    val expected = writer.write(temporary) {
                        check()
                        RecoveryIllustrations.cover(cache.book)?.let(::cover)
                        plan.selections.forEachIndexed { index, selection ->
                            check()
                            val body = readSource(cache, selection.source, requireNotNull(selection.previewDigest), check)
                            val id = selection.source.id
                            val subject = if (id.child != null) id.entry else id.entry.removeSuffix(".nb")
                            val xhtml = RecoveryIllustrations.body(
                                RecoveryExportTitle.body(body.text, selection), cache.book, subject, this,
                                appCtx.getString(R.string.feature_orphan_recovery_missing_image), check,
                            ) { missingImages++ }
                            chapter(selection.title, xhtml)
                            onProgress(index + 1, count)
                        }
                    }
                    EpubExportIntegrity.verify(temporary, expected, check)
                }
            }
            // Recheck eligibility and the identities selected for export before publishing.
            val latest = bookCache(bookUrl)
            if (latest.directory != cache.directory) fail(RecoveryProblem.Changed)
            plan.selections.forEach {
                readSource(latest, it.source, requireNotNull(it.previewDigest), check)
            }
            val checkPublication: () -> Unit = {
                job.ensureActive()
                availableBook(bookUrl)
            }
            checkPublication()
            val published = RecoveryExportFiles.publish(
                temporary, directory, name, author, format,
                occupied = { fileName, uri ->
                    database.bookDao.hasFile(fileName) || uri?.let {
                        val url = if (it.scheme == "content") it.toString() else it.path
                        url != null && database.bookDao.getBook(url) != null
                    } == true
                },
                check = checkPublication,
            )
            var result = RecoveryExportResult(
                published.name, missingImages,
                importAttempted = addToShelf,
                missingDurableRead = addToShelf && !published.durableRead,
            )
            recordCommit(result)
            if (addToShelf && published.durableRead) {
                try {
                    job.ensureActive()
                    val imported = database.runInTransaction(java.util.concurrent.Callable {
                        job.ensureActive()
                        val sourceBook = availableBook(bookUrl)
                        LocalBook.importNewExport(
                            published.uri, sourceBook.isPrivate, database.bookDao.privateGroupsForBook(bookUrl),
                            knownNameAuthor = if (format == RecoveryExportFormat.Txt) name to author else null,
                        )
                    })
                    result = result.copy(importedBookUrl = imported.bookUrl)
                    recordCommit(result)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    AppLog.put("Orphan cache export committed, import failed", error)
                }
            }
            result
        } finally {
            temporary.delete()
        }
    }

    override suspend fun readRestoredChapter(bookUrl: String, target: RecoveryTarget, digest: String): String =
        withContext(Dispatchers.IO) {
            val cache = bookCache(bookUrl)
            val chapter = cache.target(target)
            val job = coroutineContext
            val check = { job.ensureActive() }
            val actual = try {
                val path = targetLocation(cache, chapter)
                val legacy = File(cache.directory, path.name)
                (targetSnapshot(path, check) ?: targetSnapshot(legacy, check))?.takeIf { it.text.isNotBlank() }
            } catch (_: IOException) {
                fail(RecoveryProblem.ResultMissing)
            } catch (error: OrphanRecoveryException) {
                if (error.problem != RecoveryProblem.Unavailable) throw error
                fail(RecoveryProblem.ResultMissing)
            } ?: fail(RecoveryProblem.ResultMissing)
            if (actual.digest != digest) fail(RecoveryProblem.ResultChanged)
            val latest = bookCache(bookUrl)
            latest.target(target)
            if (latest.directory != cache.directory) fail(RecoveryProblem.Changed)
            actual.text
        }

    override suspend fun restoreChapter(
        bookUrl: String,
        source: OrphanText,
        previewDigest: String,
        target: RecoveryTarget,
        targetVersion: RecoveryTargetVersion,
        titleChange: RecoveryTitleChange?,
    ): RecoveryTarget = withCommitResult { recordCommit ->
        val job = coroutineContext
        val check = { job.ensureActive() }
        replaceMutex.withLock {
            val cache = bookCache(bookUrl)
            checkWriteAdmission(bookUrl)
            val chapter = cache.target(target)
            val body = readSource(cache, source, previewDigest, check)
            val cached = targetCache(cache, chapter, check)
            if (cached.version != targetVersion) fail(RecoveryProblem.Changed)
            if (titleChange != null) {
                val finalChapter = restoreWithTitle(bookUrl, source, body, target, targetVersion, titleChange, check)
                val committed = target.copy(title = finalChapter.title)
                recordCommit(committed)
                notifyRestored(cache.book, committed, finalChapter, body.text)
                return@withLock committed
            }
            if (cached.readerText?.digest == body.digest) return@withLock target
            val parent = checkNotNull(cached.path.parentFile)
            if (!OrphanCacheFiles.directory(parent) && !parent.mkdir() && !OrphanCacheFiles.directory(parent)) {
                throw IOException("Cannot create chapter cache directory")
            }
            val pending = Files.createTempFile(parent.toPath(), ".recover-", ".tmp").toFile()
            try {
                FileOutputStream(pending).use { stream ->
                    stream.write(body.bytes)
                    stream.fd.sync()
                }
                val current = bookCache(bookUrl)
                val latestChapter = current.target(target)
                readSource(current, source, previewDigest, check)
                val latest = targetCache(current, latestChapter, check)
                if (latest.path != cached.path || latest.version != targetVersion) fail(RecoveryProblem.Changed)
                availableBook(bookUrl)
                checkWriteAdmission(bookUrl)
                check()
                Files.move(pending.toPath(), latest.path.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                recordCommit(target)
                notifyRestored(current.book, target, latestChapter, body.text)
                target
            } finally {
                pending.delete()
            }
        }
    }

    private fun chapterWithTitle(chapter: BookChapter, title: String): BookChapter {
        val normalized = title.trim()
        if (normalized.isEmpty() || normalized.length > 140 ||
            normalized.any(Char::isISOControl) || normalized == chapter.title
        ) fail(RecoveryProblem.InvalidTitle)
        // titleMD5 lives outside the copy constructor; a fresh entity computes
        // the cache key from the requested name rather than the old lazy hash.
        return chapter.copy(title = normalized)
    }

    private fun titleDestination(
        cache: BookCache,
        old: BookChapter,
        renamed: BookChapter,
        sourceDigest: String,
        check: () -> Unit,
        expected: RecoveryTargetVersion? = null,
    ): TargetCache {
        val previousPath = targetLocation(cache, old)
        val destinationPath = targetLocation(cache, renamed)
        if (destinationPath == previousPath) fail(RecoveryProblem.TitleDestinationConflict)
        val destination = try {
            val flatPath = File(cache.directory, destinationPath.name)
            val nested = targetSnapshot(destinationPath, check)
            val flat = if (nested == null) targetSnapshot(flatPath, check) else {
                if (Files.exists(flatPath.toPath(), NOFOLLOW_LINKS) && OrphanCacheFiles.stamp(flatPath) == null) {
                    fail(RecoveryProblem.Unavailable)
                }
                null
            }
            val readable = (nested ?: flat)?.takeIf { it.text.isNotBlank() }
            TargetCache(
                destinationPath, RecoveryTargetVersion(nested?.digest, flat?.digest),
                readable, readable,
            )
        } catch (_: IOException) {
            fail(RecoveryProblem.TitleDestinationConflict)
        }
        if (expected != null && destination.version != expected) fail(RecoveryProblem.Changed)
        if ((destination.version.current ?: destination.version.legacy)?.let { it != sourceDigest } == true) {
            fail(RecoveryProblem.TitleDestinationConflict)
        }
        return destination
    }

    private fun restoreWithTitle(
        bookUrl: String,
        source: OrphanText,
        body: OrphanCacheFiles.Snapshot,
        target: RecoveryTarget,
        expectedOriginal: RecoveryTargetVersion,
        titleChange: RecoveryTitleChange,
        check: () -> Unit,
    ): BookChapter {
        if (titleChange.renamedTarget.copy(title = target.title) != target) fail(RecoveryProblem.Changed)
        var created: TitleCacheFile? = null
        return try {
            database.runInTransaction(java.util.concurrent.Callable {
                val cache = bookCache(bookUrl)
                val chapter = cache.target(target)
                val renamed = chapterWithTitle(chapter, titleChange.renamedTarget.title)
                readSource(cache, source, body.digest, check)
                val original = targetCache(cache, chapter, check)
                if (original.version != expectedOriginal) fail(RecoveryProblem.Changed)
                val destination = titleDestination(
                    cache, chapter, renamed, body.digest, check, titleChange.destinationVersion,
                )
                checkWriteAdmission(bookUrl)
                check()
                if (destination.readerText == null) {
                    // This path is not a legal chapter until Room commits. Exclusive creation
                    // cannot replace an existing destination, even on emulated storage.
                    val parent = checkNotNull(destination.path.parentFile)
                    if (!OrphanCacheFiles.directory(parent) &&
                        !parent.mkdir() && !OrphanCacheFiles.directory(parent)
                    ) throw IOException("Cannot create renamed chapter cache directory")
                    try {
                        val file = createTitleCache(destination.path)
                        created = file
                        file.write(body.bytes, check)
                    } catch (_: FileAlreadyExistsException) {
                        fail(RecoveryProblem.Changed)
                    }
                }
                // A shadowed flat file remains untouched and does not determine the reader's result.
                val expectedDestination = if (destination.readerText == null) {
                    RecoveryTargetVersion(body.digest, null)
                } else {
                    destination.version
                }
                val finalDestination = titleDestination(
                    cache, chapter, renamed, body.digest, check, expectedDestination,
                )
                if (finalDestination.readerText?.digest != body.digest || created?.stillNamed() == false) {
                    fail(RecoveryProblem.Changed)
                }
                readSource(cache, source, body.digest, check)
                if (targetCache(cache, chapter, check).version != expectedOriginal) fail(RecoveryProblem.Changed)
                checkWriteAdmission(bookUrl)
                check()
                availableBook(bookUrl)
                val rows = database.bookChapterDao.renameRecoveredChapter(
                    bookUrl, chapter.url, chapter.index, chapter.title, renamed.title,
                )
                if (rows != 1) fail(RecoveryProblem.Changed)
                renamed
            })
        } catch (error: Exception) {
            created?.let { cleanupUncommittedTitle(bookUrl, titleChange, it, body.bytes, error) }
            throw error
        } finally {
            try {
                created?.close()
            } catch (error: Exception) {
                AppLog.put("Could not close renamed recovery cache", error)
            }
        }
    }

    private fun cleanupUncommittedTitle(
        bookUrl: String,
        titleChange: RecoveryTitleChange,
        file: TitleCacheFile,
        body: ByteArray,
        originalError: Exception,
    ) {
        try {
            // Database lookup failure is ambiguous. Do not remove possibly
            // referenced bytes, or a destination since replaced by another writer.
            val target = titleChange.renamedTarget
            val chapter = database.bookChapterDao.getChapter(bookUrl, target.index)
            if (chapter?.getFileName() == file.path.name) return
            if (!file.removeUncommitted(body) && Files.exists(file.path.toPath(), NOFOLLOW_LINKS)) {
                AppLog.put("Left renamed recovery cache in place because its ownership or bytes changed")
            }
        } catch (cleanupError: Exception) {
            originalError.addSuppressed(cleanupError)
            AppLog.put("Could not roll back renamed recovery cache", cleanupError)
        }
    }

    private fun notifyRestored(book: Book, target: RecoveryTarget, chapter: BookChapter, text: String) {
        try {
            ContentProcessor.invalidateSemanticContent(book.bookUrl, chapter.index)
            ReadBook.onRecoveredChapterPublished(book.bookUrl)
            updateChapterCount(book.bookUrl, target, text)
            postEvent(EventBus.SAVE_CONTENT, book to chapter)
        } catch (error: Exception) {
            AppLog.put("Recovered chapter notification could not be sent", error)
        }
    }

    // A dispatcher switch can discard the return value after the durable write has completed.
    private suspend fun <T : Any> withCommitResult(
        block: suspend CoroutineScope.(recordCommit: (T) -> Unit) -> T,
    ): T {
        var committed: T? = null
        return try {
            withContext(Dispatchers.IO) { block { committed = it } }
        } catch (cancelled: CancellationException) {
            committed ?: throw cancelled
        }
    }

    private fun updateChapterCount(bookUrl: String, target: RecoveryTarget, content: String) {
        // The restored file is already committed. A statistics failure must not report it as lost.
        try {
            if (readSettings.currentSettings.tocCountWords) {
                val wordCount = StringUtils.wordCountFormat(HtmlFormatter.countReadableTextLength(content))
                database.runInTransaction {
                    val chapter = database.bookChapterDao.getChapter(bookUrl, target.index)
                    if (chapter?.url == target.url && chapter.title == target.title) {
                        database.bookChapterDao.upWordCount(bookUrl, target.url, wordCount)
                    }
                }
            }
        } catch (error: Exception) {
            AppLog.put("Recovered chapter word count could not be updated", error)
        }
    }

    private fun fail(problem: RecoveryProblem): Nothing = throw OrphanRecoveryException(problem)
}

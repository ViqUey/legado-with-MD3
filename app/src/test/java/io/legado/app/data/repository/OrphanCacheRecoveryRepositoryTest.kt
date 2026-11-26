package io.legado.app.data.repository

import android.app.Application
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.gateway.BookSourceCallbackGateway
import io.legado.app.domain.usecase.DeleteBooksUseCase
import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.OrphanDeleteRequest
import io.legado.app.domain.model.OrphanRecoveryException
import io.legado.app.domain.model.OrphanText
import io.legado.app.domain.model.OrphanTextId
import io.legado.app.domain.model.RecoveryDifference
import io.legado.app.domain.model.RecoveryLineKind
import io.legado.app.domain.model.RecoveryMatch
import io.legado.app.domain.model.RecoveryProblem
import io.legado.app.domain.model.settings.ReadSettings
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocalTxt
import io.legado.app.model.CacheBook
import io.legado.app.model.LegacyReaderSession
import io.legado.app.model.ReadBook
import io.legado.app.data.entities.BookSource
import io.legado.app.model.cache.CacheDownloadRequest
import io.legado.app.model.cache.ChapterSelection
import io.legado.app.model.cache.CacheDownloadActivity
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.RecoveredTextFile
import io.legado.app.model.localBook.TextFile
import io.legado.app.support.InMemoryAppDatabaseFixture
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.legado.app.exception.ContentEmptyException
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class OrphanCacheRecoveryRepositoryTest {
    private val book = Book(
        bookUrl = "https://example.test/deleted/novel",
        origin = "https://example.test",
        type = BookType.text,
        name = "不浪漫罪名",
        durChapterTitle = "经规则修改后的标题",
        latestChapterTitle = "保存过的末章",
        wordCount = "12.7万字",
    )
    private val current = chapter(0, "当前目录", "current")

    private fun chapter(index: Int, title: String, key: String) =
        BookChapter(bookUrl = book.bookUrl, index = index, url = "https://example.test/$key", title = title)

    private val root get() = File(BookHelp.cachePath)
    private val folder get() = File(root, book.getFolderName())

    private fun name(index: Int, title: String) = chapter(index, title, "old-$index").getFileName()

    private fun flat(name: String, text: String): File = write(File(folder, name), text)

    private fun nested(name: String, text: String): File =
        write(File(File(folder, name.removeSuffix(".nb")), name), text)

    private fun write(file: File, text: String): File = file.apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    @Test fun discoversBothLayoutsDeduplicatesEqualCopiesAndSkipsNonText() = fixture { repo, db, maintenance, _ ->
        val formerly = chapter(6, "旧目录标题", "formerly")
        db.bookChapterDao.insert(formerly)
        val nowLegalThenOrphan = formerly.getFileName()
        flat(nowLegalThenOrphan, "无正文标题")
        assertTrue(repo.scanBook(book.bookUrl).texts.isEmpty())
        db.bookChapterDao.delByBook(book.bookUrl)
        assertTrue(repo.scanBook(book.bookUrl).texts.isEmpty())
        assertTrue(maintenance.scanOrphanedChapters().isEmpty())
        db.bookChapterDao.insert(current, chapter(1, formerly.title, "newly"))

        val repeated = name(7, "重复")
        flat(repeated, "两份一样")
        nested(repeated, "两份一样")
        val different = name(8, "不同版本")
        flat(different, "旧版本")
        nested(different, "新版本")
        val emptyNested = name(8, "同索引另一章")
        flat(emptyNested, "唯一有效版本")
        nested(emptyNested, "")
        flat("old-source.nb", "无命名规则的历史正文")
        write(File(folder, "historical-directory/unfinished.nb"), "不同历史正文")
        flat("whitespace.nb", " \t\n ")
        flat("empty.nb", "")
        File(folder, "binary.nb").writeBytes(byteArrayOf(0, 0xFF.toByte()))
        File(folder, "empty-directory").mkdirs()
        write(File(folder, "only-images/images/image.jpeg"), "image")
        write(File(folder, "images/chapter.nb"), "excluded book image directory")
        flat(current.getFileName(), "current reading")

        val result = repo.scanBook(book.bookUrl).texts
        assertEquals(7, result.size)
        assertEquals("旧目录标题", result.single { it.id.entry == nowLegalThenOrphan }.originalTitle)
        assertEquals(1, result.count { it.id.child == repeated })
        assertEquals(2, result.count { it.id.entry == different || it.id.child == different })
        assertEquals(2, result.filter { it.index == 8 }.map { it.titleHash }.distinct().size)
        assertEquals("唯一有效版本", repo.preview(book.bookUrl, result.single { it.id.entry == emptyNested }).text)
        assertEquals("无命名规则的历史正文", repo.preview(book.bookUrl, result.single { it.id.entry == "old-source.nb" }).text)
    }

    @Test fun verifiesHeaderHashesAndSeparatesIndexSuggestionsFromRealMatches() = fixture { repo, db, _, _ ->
        val raw = "未经替换的标题"
        val currentAtIndex = chapter(3, "别的章", "same-index")
        db.bookChapterDao.insert(chapter(15, raw, "far"), chapter(10, raw, "near"), currentAtIndex)
        flat(name(3, raw), "与标题不符的首行\n正文")
        flat(name(100001, "标题在正文里"), "标题在正文里\n正文")
        flat(name(9, "真实阅读标题"), "经规则修改后的标题\n正文")
        flat(name(100000, book.latestChapterTitle!!), "正文")
        val items = repo.scanBook(book.bookUrl).texts
        assertEquals(listOf(3, 9, 100000, 100001), items.map { it.index })
        assertEquals(listOf(raw, null, "保存过的末章", "标题在正文里"), items.map { it.originalTitle })
        assertEquals(
            listOf(10 to RecoveryMatch.TitleHash, 15 to RecoveryMatch.TitleHash, 3 to RecoveryMatch.Index, 0 to RecoveryMatch.Manual),
            repo.targets(book.bookUrl, items.first()).map { it.index to it.match },
        )
    }

    @Test fun rejectsSymlinksAndPathsOutsideTheOneBook() = fixture { repo, _, _, app ->
        val outside = write(File(app.filesDir, "outside.nb"), "should never be read")
        folder.mkdirs()
        val flatLink = Files.createSymbolicLink(File(folder, "link.nb").toPath(), outside.toPath())
        val folderLink = Files.createSymbolicLink(File(folder, "outside-entry").toPath(), app.filesDir.toPath())
        try {
            assertTrue(repo.scanBook(book.bookUrl).texts.isEmpty())
            val forged = OrphanText(OrphanTextId("../outside.nb"), null, null, null, outside.length(), outside.lastModified())
            assertEquals(RecoveryProblem.Changed, problem { repo.preview(book.bookUrl, forged) })
        } finally {
            Files.deleteIfExists(flatLink)
            Files.deleteIfExists(folderLink)
        }
    }

    @Test fun recoversTwoSelectedChaptersWithExactBytesAfterReopenRebuildAndOldCacheDeletion() =
        fixture { repo, db, maintenance, _ ->
            val first = " 第一行：中文和🙂\r\n第九章 这是正文，不是目录\n\n"
            val second = "  " + "长章𠮷".repeat(40_000) + "\n末行"
            val oldFirst = chapter(12, "旧标题甲", "old-a")
            val oldSecond = chapter(42, "旧标题乙", "old-b")
            db.bookChapterDao.insert(oldFirst, oldSecond)
            val one = flat(oldFirst.getFileName(), first)
            val two = nested(oldSecond.getFileName(), second)
            assertTrue(repo.scanBook(book.bookUrl).texts.isEmpty())
            db.bookChapterDao.delByBook(book.bookUrl)
            db.bookChapterDao.insert(current.copy(isVolume = true))
            flat(name(19, "不选择本章"), "应跳过")
            val selections = repo.scanBook(book.bookUrl).texts.filter { it.index != 19 }
                .mapIndexed { i, text -> LocalRecoverySelection(text, listOf("第一段", "第二段")[i], null) }

            assertTrue(repo.targets(book.bookUrl, selections.first().source).isEmpty())
            val url = repo.recoverLocal(book.bookUrl, selections) { _, _ -> }
            val again = repo.recoverLocal(book.bookUrl, selections) { _, _ -> }
            assertNotEquals(url, again)
            val local = db.bookDao.getBook(url)!!
            assertEquals(2, local.totalChapterNum)
            assertEquals(first, one.readText())
            assertEquals(second, two.readText())
            assertTrue(db.bookDao.getBook(book.bookUrl) != null)
            assertEquals(1, maintenance.scanOrphanedChapters().count { it.bookUrl == book.bookUrl })
            val expected = listOf(first, second)
            TextFile.clear()
            assertEquals(expected, db.bookChapterDao.getChapterList(url).map { BookHelp.getContent(local, it) })

            val stillOrphan = maintenance.scanOrphanedChapters().single { it.bookUrl == book.bookUrl }
            maintenance.deleteOrphanedChapters(listOf(OrphanDeleteRequest(book.bookUrl, stillOrphan.entryNames.toSet())))
            assertFalse(one.exists())
            assertFalse(two.exists())
            local.tocUrl = "^第.+章.*$"
            local.setSplitLongChapter(true)
            local.latestChapterTime = 0
            TextFile.clear()
            val rebuilt = TextFile.getChapterList(local)
            assertEquals(listOf("第一段", "第二段"), rebuilt.map { it.title })
            assertEquals(expected, rebuilt.map { BookHelp.getContent(local, it) })
        }

    @Test fun localCancellationAndFailureDoNotPublishIncompleteBooks() = fixture { repo, db, _, _ ->
        flat(name(4, "第一"), "正文甲")
        flat(name(5, "第二"), "正文乙")
        val chosen = repo.scanBook(book.bookUrl).texts.map { LocalRecoverySelection(it, "缓存", null) }
        for (error in listOf(CancellationException("abort"), IOException("simulated disk error"))) {
            val thrown = runCatching { repo.recoverLocal(book.bookUrl, chosen) { _, _ -> throw error } }.exceptionOrNull()
            assertEquals(error.javaClass, thrown?.javaClass)
            assertEquals(error.message, thrown?.message)
            assertEquals(listOf(book.bookUrl), db.bookDao.allBookUrls)
            assertTrue(RecoveredTextFile.directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun roomFailureRollsBackTheBookAndRemovesThePublishedFile() = fixture { repo, db, _, _ ->
        val original = flat(name(4, "孤立"), "原始正文")
        val chosen = repo.scanBook(book.bookUrl).texts.map { LocalRecoverySelection(it, "缓存", null) }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_recovered_chapters BEFORE INSERT ON chapters " +
                "BEGIN SELECT RAISE(ABORT, 'test transaction failure'); END",
        )
        assertTrue(runCatching { repo.recoverLocal(book.bookUrl, chosen) { _, _ -> } }.isFailure)
        assertEquals(listOf(book.bookUrl), db.bookDao.allBookUrls)
        assertTrue(RecoveredTextFile.directory.listFiles().orEmpty().isEmpty())
        assertEquals("原始正文", original.readText())
    }

    @Test fun missingTargetRestoresAndInvalidatesReaderCacheWithoutChangingBookSourceCount() =
        fixture { repo, db, _, _ ->
            val old = flat(name(4, "孤立"), "恢复后的正文")
            val source = repo.scanBook(book.bookUrl).texts.single()
            val preview = repo.preview(book.bookUrl, source)
            val target = repo.targets(book.bookUrl, source).single()
            val compare = repo.compare(book.bookUrl, source, preview.digest, target)
            assertEquals(RecoveryDifference.Missing, compare.difference)
            val revision = ContentProcessor.chapterRevision(book.bookUrl, current.index)
            repo.restoreChapter(book.bookUrl, source, preview.digest, target, compare.version)
            assertTrue(ContentProcessor.chapterRevision(book.bookUrl, current.index) > revision)
            assertEquals(preview.text, BookHelp.getContent(book, current))
            assertEquals(preview.text, old.readText())
            assertEquals("6字", db.bookChapterDao.getChapter(book.bookUrl, 0)?.wordCount)
            assertEquals(book.wordCount, db.bookDao.getBook(book.bookUrl)?.wordCount)
            val same = repo.compare(book.bookUrl, source, preview.digest, target)
            assertEquals(RecoveryDifference.Identical, same.difference)
            val after = ContentProcessor.chapterRevision(book.bookUrl, current.index)
            repo.restoreChapter(book.bookUrl, source, preview.digest, target, same.version)
            assertEquals(after, ContentProcessor.chapterRevision(book.bookUrl, current.index))
        }

    @Test fun disabledWordCountLeavesStoredChapterAndBookCountsUntouched() =
        fixture(tocCountWords = false) { repo, db, _, _ ->
            db.bookChapterDao.update(current.copy(wordCount = "39字"))
            flat(name(4, "孤立"), "替换后的正文")
            nested(current.getFileName(), "原正文")
            val source = repo.scanBook(book.bookUrl).texts.single()
            val preview = repo.preview(book.bookUrl, source)
            val target = repo.targets(book.bookUrl, source).single()
            val compare = repo.compare(book.bookUrl, source, preview.digest, target)
            repo.restoreChapter(book.bookUrl, source, preview.digest, target, compare.version)
            assertEquals("39字", db.bookChapterDao.getChapter(book.bookUrl, 0)?.wordCount)
            assertEquals(book.wordCount, db.bookDao.getBook(book.bookUrl)?.wordCount)
        }

    @Test fun zeroByteNestedTargetDoesNotHideLegacyAndDiffRequiresFreshConfirmation() = fixture { repo, _, _, _ ->
        val old = flat(name(7, "孤立"), "common\nold")
        val legacy = flat(current.getFileName(), "common\nnew")
        val empty = nested(current.getFileName(), "")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val preview = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val comparison = repo.compare(book.bookUrl, source, preview.digest, target)
        assertEquals("common\nnew", comparison.currentText)
        assertEquals(listOf(RecoveryLineKind.Same, RecoveryLineKind.Removed, RecoveryLineKind.Added), comparison.lines!!.map { it.kind })
        assertEquals("", empty.readText())
        legacy.writeText("changed after review")
        assertEquals(RecoveryProblem.Changed, problem { repo.restoreChapter(book.bookUrl, source, preview.digest, target, comparison.version) })
        assertEquals("", empty.readText())
        val refreshed = repo.compare(book.bookUrl, source, preview.digest, target)
        repo.restoreChapter(book.bookUrl, source, preview.digest, target, refreshed.version)
        assertEquals("common\nold", BookHelp.getContent(book, current))
        assertEquals("common\nold", old.readText())
        assertEquals("changed after review", legacy.readText())
    }

    @Test fun identicalFlatCopyHiddenByEmptyNestedFileCanBeRestored() = fixture { repo, _, _, _ ->
        val body = "同文副本"
        flat(name(4, "孤立"), body)
        val legacy = flat(current.getFileName(), body)
        val empty = nested(current.getFileName(), "")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val preview = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()

        val shadowed = repo.compare(book.bookUrl, source, preview.digest, target)
        assertEquals(RecoveryDifference.Missing, shadowed.difference)
        assertEquals(body, shadowed.currentText)
        assertTrue(shadowed.hasShadowedCopy)

        // The reader deletes the empty nested file and returns null for this first read.
        assertNull(BookHelp.getContent(book, current))
        assertFalse(empty.exists())
        assertEquals(body, BookHelp.getContent(book, current))
        val directlyReadable = repo.compare(book.bookUrl, source, preview.digest, target)
        assertEquals(RecoveryDifference.Identical, directlyReadable.difference)
        assertFalse(directlyReadable.hasShadowedCopy)

        nested(current.getFileName(), "")
        val confirmation = repo.compare(book.bookUrl, source, preview.digest, target)
        repo.restoreChapter(book.bookUrl, source, preview.digest, target, confirmation.version)
        assertEquals(body, empty.readText())
        assertEquals(body, legacy.readText())
        assertEquals(body, BookHelp.getContent(book, current))
    }

    @Test fun committedLocalBookResultSurvivesCancellationBeforeIoReturns() = fixture { repo, db, _, _ ->
        flat(name(4, "孤立"), "恢复正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val selection = listOf(LocalRecoverySelection(source, "本地章名", null))

        val url = cancelAroundIoReturn { repo.recoverLocal(book.bookUrl, selection) { _, _ -> } }.getOrThrow()
        val recovered = db.bookDao.getBook(url)!!
        val chapter = db.bookChapterDao.getChapterList(url).single()
        assertEquals("恢复正文", BookHelp.getContent(recovered, chapter))
        assertTrue(File(url).isFile)
    }

    @Test fun chapterCancellationRespectsAtomicPublicationBoundary() = fixture { repo, _, _, _ ->
        flat(name(4, "孤立"), "恢复后的正文")
        val existing = nested(current.getFileName(), "原正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val preview = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val confirmation = repo.compare(book.bookUrl, source, preview.digest, target)
        val restore: suspend () -> Unit = {
            repo.restoreChapter(book.bookUrl, source, preview.digest, target, confirmation.version)
        }

        val cancelled = cancelAroundIoReturn(cancelBeforeIo = true, action = restore)
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
        assertEquals("原正文", existing.readText())

        cancelAroundIoReturn(action = restore).getOrThrow()
        assertEquals("恢复后的正文", BookHelp.getContent(book, current))
    }

    @Test fun recoveredTxtEditsRemainInCacheAndSourceFollowsLocalDeletionChoice() = fixture { repo, db, _, app ->
        val original = flat(name(4, "孤立"), "原始恢复正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val url = repo.recoverLocal(book.bookUrl, listOf(LocalRecoverySelection(source, "源文件章名", null))) { _, _ -> }
        val local = db.bookDao.getBook(url)!!
        val file = File(url)
        val originalBytes = file.readText()
        val ordinary = Book(
            bookUrl = File(app.filesDir, "ordinary.txt").absolutePath,
            originName = "ordinary.txt",
            type = BookType.local or BookType.text,
        )
        assertTrue(local.isLocalTxt)
        assertFalse(BookHelp.canWriteLocalTxtSource(local))
        assertTrue(BookHelp.canWriteLocalTxtSource(ordinary))
        assertFalse(BookHelp.canWriteLocalTxtSource(book))

        val edited = db.bookChapterDao.getChapterList(url).single()
        BookHelp.saveChapterTitle(edited, "编辑后的章名")
        BookHelp.saveText(local, edited, "缓存编辑", saveToSource = true)
        val currentChapter = db.bookChapterDao.getChapterList(url).single()
        assertEquals("编辑后的章名", currentChapter.title)
        assertEquals("缓存编辑", BookHelp.getContent(local, currentChapter))
        assertEquals(originalBytes, file.readText())

        val renamed = local.copy(name = "新书名")
        BookHelp.updateCacheFolder(local, renamed)
        assertEquals(file, RecoveredTextFile.fileOf(renamed))
        assertEquals("缓存编辑", BookHelp.getContent(renamed, currentChapter))
        BookHelp.clearCache(renamed)
        assertEquals("原始恢复正文", BookHelp.getContent(renamed, currentChapter))
        val rebuilt = TextFile.getChapterList(renamed)
        BookRepository(db.bookDao, db.bookChapterDao, db).replaceChaptersAndUpdateBook(renamed, rebuilt)
        // A normal rebuild preserves the explicit Room edit while the original TXT stays intact.
        assertEquals("编辑后的章名", db.bookChapterDao.getChapterList(url).single().title)
        assertEquals("源文件章名", RecoveredTextFile.sections(file).single().title)

        LocalBook.deleteBook(renamed, deleteOriginal = false)
        assertEquals(originalBytes, file.readText())
        LocalBook.deleteBook(renamed, deleteOriginal = true)
        assertFalse(file.exists())
        assertEquals("原始恢复正文", original.readText())
    }

    private fun <T> cancelAroundIoReturn(
        cancelBeforeIo: Boolean = false,
        action: suspend () -> T,
    ): Result<T> {
        val callerTasks = LinkedBlockingQueue<Runnable>()
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                callerTasks.add(block)
            }
        }
        var result: Result<T>? = null
        val task = CoroutineScope(caller).launch {
            if (cancelBeforeIo) coroutineContext.cancel()
            result = runCatching { action() }
        }
        checkNotNull(callerTasks.poll(10, TimeUnit.SECONDS)).run()
        if (!cancelBeforeIo) {
            // Keep the completed IO result queued until after cancellation.
            val resumeCaller = checkNotNull(callerTasks.poll(10, TimeUnit.SECONDS))
            task.cancel()
            resumeCaller.run()
        }
        assertTrue(task.isCancelled)
        return checkNotNull(result)
    }

    @Test fun sourceVersionAndRenamedOrRemovedTargetAreRejected() = fixture { repo, db, _, _ ->
        val old = flat(name(4, "孤立"), "original")
        val targetFile = nested(current.getFileName(), "keep me")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val preview = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val comparison = repo.compare(book.bookUrl, source, preview.digest, target)
        // Same size and timestamp deliberately exercise the digest guard as well.
        old.writeText("modified")
        assertTrue(old.setLastModified(source.modified))
        assertEquals(RecoveryProblem.Changed, problem { repo.restoreChapter(book.bookUrl, source, preview.digest, target, comparison.version) })
        old.writeText("original")
        assertTrue(old.setLastModified(source.modified))
        db.bookChapterDao.update(current.copy(title = "renamed"))
        assertEquals(RecoveryProblem.Changed, problem { repo.restoreChapter(book.bookUrl, source, preview.digest, target, comparison.version) })
        db.bookChapterDao.delByBook(book.bookUrl)
        assertEquals(RecoveryProblem.Changed, problem { repo.restoreChapter(book.bookUrl, source, preview.digest, target, comparison.version) })
        assertEquals("keep me", targetFile.readText())
    }

    @Test fun privateFlagAndGroupProtectTheEntryAndAreRecheckedOnCommit() = fixture { repo, db, maintenance, _ ->
        flat(name(4, "孤立"), "原始正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val preview = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val compare = repo.compare(book.bookUrl, source, preview.digest, target)
        db.bookDao.update(book.copy(isPrivate = true))
        assertTrue(book.bookUrl in repo.privateBookUrls().first())
        assertEquals(RecoveryProblem.PrivateBook, problem { repo.preview(book.bookUrl, source) })
        assertEquals(1, maintenance.scanOrphanedChapters().size)
        db.bookGroupDao.insert(BookGroup(groupId = 4, isPrivate = true))
        db.bookDao.update(book.copy(group = 4))
        assertTrue(book.bookUrl in repo.privateBookUrls().first())
        assertEquals(RecoveryProblem.PrivateBook, problem { repo.scanBook(book.bookUrl) })
        assertEquals(RecoveryProblem.PrivateBook, problem { repo.restoreChapter(book.bookUrl, source, preview.digest, target, compare.version) })
        assertNull(BookHelp.getContent(book, current))
        assertEquals(RecoveryProblem.PrivateBook, problem {
            repo.recoverLocal(book.bookUrl, listOf(LocalRecoverySelection(source, "缓存", preview.digest))) { _, _ -> }
        })
    }

    @Test fun detachedReaderAndFailureRecordDoNotPreventSingleBookRecovery() = fixture { repo, db, maintenance, _ ->
        flat(name(4, "孤立"), "保留正文")
        val session = LegacyReaderSession()
        ReadBook.replaceCurrentBook(book)
        session.attach()
        try {
            val source = repo.scanBook(book.bookUrl).texts.single()
            val body = repo.preview(book.bookUrl, source)
            val target = repo.targets(book.bookUrl, source).single()
            val diff = repo.compare(book.bookUrl, source, body.digest, target)
            assertEquals(RecoveryProblem.BusyBook, problem {
                repo.restoreChapter(book.bookUrl, source, body.digest, target, diff.version)
            })
            val restoredLocal = repo.recoverLocal(
                book.bookUrl, listOf(LocalRecoverySelection(source, "副本", body.digest)),
            ) { _, _ -> }
            assertTrue(File(restoredLocal).isFile)
            assertTrue(maintenance.scanOrphanedChapters().isEmpty())
            session.detach()
            assertEquals(book.bookUrl, ReadBook.book?.bookUrl)
            CacheBook.markBookFailed(book.bookUrl, "past admission failure")
            repo.restoreChapter(book.bookUrl, source, body.digest, target, diff.version)
            assertEquals("保留正文", BookHelp.getContent(book, current))
            assertEquals("past admission failure", CacheBook.downloadStateFlow.value.books[book.bookUrl]?.failureMessage)
            val unchangedBook = checkNotNull(db.bookDao.getBook(book.bookUrl))
            assertEquals(book.durChapterIndex, unchangedBook.durChapterIndex)
            assertEquals(book.durChapterPos, unchangedBook.durChapterPos)
            repo.readRestoredChapter(book.bookUrl, target, body.digest)
            db.bookChapterDao.update(current.copy(title = "新目录标题"))
            assertEquals(RecoveryProblem.Changed, problem { repo.readRestoredChapter(book.bookUrl, target, body.digest) })
        } finally {
            if (ReadBook.callBack === session) session.detach()
            ReadBook.clearCurrentBook()
            CacheBook.close(clearFailureState = true)
        }
    }

    @Test fun scanWithoutDirectoryIsEmptyButDoesNotHideUnsafeCacheDirectory() =
        fixture { repo, _, _, app ->
            assertTrue(repo.scanBook(book.bookUrl).texts.isEmpty())
            root.mkdirs()
            val outside = File(app.filesDir, "foreign-cache-dir").apply { mkdirs() }
            Files.createSymbolicLink(folder.toPath(), outside.toPath())
            assertEquals(RecoveryProblem.ScanFailed, problem { repo.scanBook(book.bookUrl) })
        }

    @Test fun titleSuggestionsDoNotImplyTitleHashTrustOrReadFromTheMiddle() = fixture { repo, _, _, _ ->
        flat(name(10, "第七章 相符"), "第七章 相符\n原正文")
        flat(name(11, "无法验证"), "第八章 开始\n原正文")
        flat(name(12, "无法验证"), "原正文\n第九章 中间出现的章名\n继续")
        flat(name(13, "无法验证"), "第十章 " + "侧记".repeat(100) + "\n原正文")

        val list = repo.scanBook(book.bookUrl).texts
        assertEquals(listOf("第七章 相符", null, null, null), list.map(OrphanText::originalTitle))
        assertEquals(listOf(null, "第八章 开始", null, null), list.map(OrphanText::suggestedTitle))
        assertEquals("第八章 开始\n原正文", repo.preview(book.bookUrl, list[1]).text)
    }

    @Test fun recoveredTocPreviewApplyAndRebuildPreserveOffsetsTextAndLegacyEdits() =
        fixture { repo, db, _, _ ->
            val first = "\uFEFF\r\n第七章 开始\r\n保留原文\n"
            val second = "这是正文开头\n第八章 正文中间"
            flat(name(10, "无法验证"), first)
            flat(name(11, "无法验证"), second)
            val sources = repo.scanBook(book.bookUrl).texts
            val url = repo.recoverLocal(book.bookUrl, sources.mapIndexed { i, source ->
                LocalRecoverySelection(source, "缓存索引 $i", null)
            }) { _, _ -> }
            val bookRepository = BookRepository(db.bookDao, db.bookChapterDao, db)
            val local = checkNotNull(db.bookDao.getBook(url))
            val chapters = db.bookChapterDao.getChapterList(url)
            val rawFile = File(url).readBytes()

            val firstChapter = chapters[0].copy(title = "手工标题")
            val secondChapter = chapters[1].copy(title = "保持手工标题")
            db.bookChapterDao.update(firstChapter)
            db.bookChapterDao.update(secondChapter)
            val legacyEdit = write(
                File(File(root, local.getFolderName()), firstChapter.getFileName()),
                "用户修改正文",
            )
            assertEquals("用户修改正文", BookHelp.getContent(local, firstChapter))
            assertEquals(listOf("手工标题", "保持手工标题"), TextFile.getChapterList(local).map { it.title })

            val rule = "^第.+章.+$"
            val preview = bookRepository.previewRecoveredTextTitles(local, Regex(rule, RegexOption.MULTILINE))
            assertTrue(legacyEdit.isFile)
            local.tocUrl = rule
            val applied = TextFile.getChapterList(local, applyRecoveredTitleRule = true)
            bookRepository.replaceChaptersAndUpdateBook(local, applied)
            assertEquals(listOf("第七章 开始", "保持手工标题"), preview?.titles)
            assertEquals(preview?.titles, applied.map { it.title })
            assertEquals(1, preview?.matches)
            assertEquals(1, preview?.changed)
            val zero = checkNotNull(bookRepository.previewRecoveredTextTitles(local, Regex("^ZZZ$")))
            assertEquals(0, zero.matches)
            assertEquals(0, zero.changed)
            assertEquals(applied.map { it.title }, zero.titles)
            val all = checkNotNull(bookRepository.previewRecoveredTextTitles(local, Regex(".+")))
            assertEquals(2, all.matches)
            assertEquals(1, all.changed)
            val identical = checkNotNull(bookRepository.previewRecoveredTextTitles(local, Regex(rule)))
            assertEquals(1, identical.matches)
            assertEquals(0, identical.changed)
            assertEquals(chapters.map { listOf(it.url, it.index, it.start, it.end) },
                applied.map { listOf(it.url, it.index, it.start, it.end) })
            assertEquals("用户修改正文", BookHelp.getContent(local, applied[0]))
            assertEquals(first, TextFile.getContent(local, applied[0]))
            assertEquals(second, TextFile.getContent(local, applied[1]))
            assertTrue(rawFile.contentEquals(File(url).readBytes()))

            db.bookChapterDao.update(applied[0].copy(title = "重新手工命名"))
            TextFile.clear()
            val reloaded = TextFile.getChapterList(checkNotNull(db.bookDao.getBook(url)))
            assertEquals("重新手工命名", reloaded[0].title)
            assertEquals("用户修改正文", BookHelp.getContent(local, reloaded[0]))
            assertEquals("第七章 开始", TextFile.getChapterList(local, applyRecoveredTitleRule = true)[0].title)
        }

    @Test fun groupingUsesSameIndexAndExactBytesAndKeepsSourceEvidence() = fixture { repo, db, _, _ ->
        val firstName = name(7, book.durChapterTitle!!)
        val secondName = name(7, book.latestChapterTitle!!)
        flat(firstName, "完全相同")
        val chosen = nested(secondName, "完全相同")
        flat(name(7, "版本"), "正文不同")
        flat(name(8, "其他"), "完全相同")
        val items = repo.scanBook(book.bookUrl).texts
        val group = items.single { it.copies.isNotEmpty() }
        assertEquals(3, items.size)
        assertEquals(2, group.copies.size)
        assertEquals(setOf(book.durChapterTitle, book.latestChapterTitle), group.copies.map { it.originalTitle }.toSet())
        assertTrue(group.hasConflictingTitles)
        assertEquals(OrphanTextId(secondName.removeSuffix(".nb"), secondName), group.id)
        assertNull(group.originalTitle)
        val recovered = repo.recoverLocal(book.bookUrl, items.mapIndexed { i, source ->
            LocalRecoverySelection(source, "选择 ${i+1}", null)
        }) { _, _ -> }
        assertEquals(3, db.bookChapterDao.getChapterCount(recovered))
        assertTrue(File(folder, firstName).isFile)
        assertTrue(chosen.isFile)

        // Same length and old visible timestamp are insufficient for trusting the group.
        chosen.writeText("完全不同")
        assertTrue(chosen.setLastModified(group.modified))
        assertEquals(RecoveryProblem.Changed, problem { repo.preview(book.bookUrl, group) })
    }

    @Test fun restoredChapterSnapshotRejectsMissingChangedAndShadowedText() = fixture { repo, _, _, _ ->
        flat(name(7, "旧章"), "正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val preview = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val comparison = repo.compare(book.bookUrl, source, preview.digest, target)
        repo.restoreChapter(book.bookUrl, source, preview.digest, target, comparison.version)
        val path = BookHelp.getChapterContentFile(book, current)
        assertEquals(preview.text, repo.readRestoredChapter(book.bookUrl, target, preview.digest))
        path.writeText("不是恢复结果")
        assertEquals(RecoveryProblem.ResultChanged, problem { repo.readRestoredChapter(book.bookUrl, target, preview.digest) })
        flat(current.getFileName(), preview.text)
        path.writeText("")
        assertEquals(RecoveryProblem.ResultMissing, problem { repo.readRestoredChapter(book.bookUrl, target, preview.digest) })
        path.delete()
        assertEquals(preview.text, repo.readRestoredChapter(book.bookUrl, target, preview.digest))
        File(folder, current.getFileName()).delete()
        assertEquals(RecoveryProblem.ResultMissing, problem { repo.readRestoredChapter(book.bookUrl, target, preview.digest) })
    }

    @Test fun liveQueueAndSingleChapterPauseDoNotUseSubtractedProjection() = fixture { repo, _, _, _ ->
        val sourceFile = flat(name(7, "旧章"), "待恢复正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val preview = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val diff = repo.compare(book.bookUrl, source, preview.digest, target)
        val site = BookSource(bookSourceUrl = book.origin)
        val model = CacheBook.getOrCreate(site, book)
        val another = CacheBook.getOrCreate(site, book.copy(bookUrl = book.bookUrl + "-unrelated"))
        try {
            model.addRequest(CacheDownloadRequest(book.bookUrl, ChapterSelection.Range(0, 3)))
            assertTrue(model.pauseDownload(0))
            assertTrue(model.pauseDownload(1))
            assertTrue(model.pauseDownload(2))
            assertEquals(1, model.queueCounts().first)
            assertEquals(3, model.pausedIndices().size)
            assertEquals(RecoveryProblem.BusyBook, problem {
                repo.restoreChapter(book.bookUrl, source, preview.digest, target, diff.version)
            })
            assertTrue(model.pause())
            another.addRequest(CacheDownloadRequest(another.book.bookUrl, ChapterSelection.Single(0)))
            repo.restoreChapter(book.bookUrl, source, preview.digest, target, diff.version)
            assertEquals("待恢复正文", BookHelp.getContent(book, current))
            assertEquals("待恢复正文", sourceFile.readText())
        } finally {
            CacheBook.close(clearFailureState = true)
        }
    }

    @Test fun actualRequestTrackingSurvivesCancelUntilItsBodyExitsAndTracksOtherBooks() =
        fixture { repo, _, _, _ ->
            flat(name(7, "旧章"), "原内容")
            val source = repo.scanBook(book.bookUrl).texts.single()
            val preview = repo.preview(book.bookUrl, source)
            val target = repo.targets(book.bookUrl, source).single()
            val diff = repo.compare(book.bookUrl, source, preview.digest, target)
            coroutineScope {
                val allowCleanup = CompletableDeferred<Unit>()
                val first = launch(start = CoroutineStart.UNDISPATCHED) {
                    WebBook.trackContentRequest(book.bookUrl) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { allowCleanup.await() }
                        }
                    }
                }
                val other = launch(start = CoroutineStart.UNDISPATCHED) {
                    WebBook.trackContentRequest(book.bookUrl + "-other") { awaitCancellation() }
                }
                try {
                    assertTrue(runCatching {
                        WebBook.trackContentRequest(book.bookUrl) { throw IOException("synthetic failure") }
                    }.isFailure)
                    first.cancel()
                    assertTrue(WebBook.isRequestingContent(book.bookUrl))
                    assertEquals(RecoveryProblem.BusyBook, problem {
                        repo.restoreChapter(book.bookUrl, source, preview.digest, target, diff.version)
                    })
                    allowCleanup.complete(Unit)
                    first.join()
                    assertFalse(WebBook.isRequestingContent(book.bookUrl))
                    repo.restoreChapter(book.bookUrl, source, preview.digest, target, diff.version)
                    assertTrue(WebBook.isRequestingContent(book.bookUrl + "-other"))
                } finally {
                    allowCleanup.complete(Unit)
                    first.cancel()
                    other.cancel()
                }
            }
        }

    @Test fun offlineChapterFailureAndBackoffActuallyFinishWithoutRestart() = fixture { repo, db, _, _ ->
        flat(name(7, "旧章"), "原内容")
        val requested = current.copy(url = book.bookUrl)
        db.bookChapterDao.insert(requested)
        val source = repo.scanBook(book.bookUrl).texts.single()
        val body = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val comparison = repo.compare(book.bookUrl, source, body.digest, target)
        val site = BookSource(bookSourceUrl = book.origin, ruleContent = ContentRule(content = "class.missing@text"))
        val model = CacheBook.getOrCreate(site, book.copy().apply { tocHtml = "<html><body></body></html>" })
        startDownloadReadSettings()
        try {
            val parsed = runCatching { WebBook.getContentAwait(site, model.book, requested) }
            assertTrue(parsed.exceptionOrNull().toString(), parsed.exceptionOrNull() is ContentEmptyException)
            model.addRequest(CacheDownloadRequest(book.bookUrl, ChapterSelection.Single(0)))
            coroutineScope {
                val failed = launch(Dispatchers.IO) { coroutineScope { model.download(this, Dispatchers.IO) } }
                withTimeout(4_000) {
                    while (CacheBook.recoveryActivity(book.bookUrl) != CacheDownloadActivity.Retrying) delay(5)
                }
                assertEquals(RecoveryProblem.BusyBook, problem {
                    repo.restoreChapter(book.bookUrl, source, body.digest, target, comparison.version)
                })
                failed.join()
            }
            assertEquals(CacheDownloadActivity.Waiting, CacheBook.recoveryActivity(book.bookUrl))
            repeat(2) { coroutineScope { model.download(this, Dispatchers.IO) } }
            assertEquals(setOf(0), CacheBook.errorIndices(book.bookUrl))
            assertFalse(WebBook.isRequestingContent(book.bookUrl))
            assertNull(CacheBook.recoveryActivity(book.bookUrl))
            repo.restoreChapter(book.bookUrl, source, body.digest, target, comparison.version)
            assertEquals(body.text, BookHelp.getContent(book, requested))
        } finally {
            CacheBook.close(clearFailureState = true)
            stopKoin()
        }
    }

    @Test fun overlappingRealBackoffsRemainVisibleAfterFirstFinishesAndModelIsDetached() =
        fixture { repo, db, _, _ ->
            val sourceFile = flat(name(7, "旧章"), "必须保留")
            val first = current.copy(url = book.bookUrl)
            val second = chapter(1, "另一个下载", "unused").copy(url = "http://[invalid")
            db.bookChapterDao.insert(first, second)
            val source = repo.scanBook(book.bookUrl).texts.single()
            val body = repo.preview(book.bookUrl, source)
            val target = repo.targets(book.bookUrl, source).last()
            val comparison = repo.compare(book.bookUrl, source, body.digest, target)
            val site = BookSource(bookSourceUrl = book.origin, ruleContent = ContentRule(content = "class.missing@text"))
            val model = CacheBook.getOrCreate(site, book.copy().apply { tocHtml = "<html><body></body></html>" })
            startDownloadReadSettings()
            try {
                // Warm both local failure paths before measuring overlapping real retry delays.
                val empty = runCatching { WebBook.getContentAwait(site, model.book, first) }
                assertTrue(empty.exceptionOrNull().toString(), empty.exceptionOrNull() is ContentEmptyException)
                assertTrue(runCatching { WebBook.getContentAwait(site, model.book, second) }.isFailure)
                model.addRequest(CacheDownloadRequest(book.bookUrl, ChapterSelection.Single(first.index)))
                coroutineScope {
                    val firstTask = launch(Dispatchers.IO) { coroutineScope { model.download(this, Dispatchers.IO) } }
                    withTimeout(4_000) {
                        while (first.index !in CacheBook.errorIndices(book.bookUrl)) delay(5)
                    }
                    delay(450)
                    model.addRequest(CacheDownloadRequest(book.bookUrl, ChapterSelection.Single(second.index)))
                    val secondTask = launch(Dispatchers.IO) { coroutineScope { model.download(this, Dispatchers.IO) } }
                    withTimeout(4_000) {
                        while (second.index !in CacheBook.errorIndices(book.bookUrl)) delay(5)
                    }
                    assertFalse("both callbacks must be active", firstTask.isCompleted)
                    firstTask.join()
                    assertFalse("second callback must still be in backoff", secondTask.isCompleted)
                    assertFalse(model.diagnostics().waitingRetry)
                    assertEquals(CacheDownloadActivity.Retrying, CacheBook.recoveryActivity(book.bookUrl))
                    assertFalse(model.pauseDownload(second.index))
                    assertFalse(model.removeDownload(second.index))
                    assertTrue(model.pause())

                    // Simulate a lookup after retirement without changing the old callback.
                    assertTrue(CacheBook.cacheBookMap.remove(book.bookUrl, model))
                    assertEquals(RecoveryProblem.BusyBook, problem {
                        repo.restoreChapter(book.bookUrl, source, body.digest, target, comparison.version)
                    })
                    secondTask.join()
                }
                assertNull(CacheBook.recoveryActivity(book.bookUrl))
                assertTrue(model.isPaused())
                assertTrue(model.isWaiting(second.index))
                assertFalse(model.hasLaunchableChapters())
                assertTrue(model.resume())
                assertTrue(model.hasLaunchableChapters())
                model.stop()
                assertEquals(0, model.diagnostics().waitingChapterCount)
                val restarted = CacheBook.getOrCreate(site, book)
                assertTrue(restarted !== model)
                assertNull(CacheBook.recoveryActivity(book.bookUrl))
                assertEquals(body.text, sourceFile.readText())
            } finally {
                model.stop()
                CacheBook.close(clearFailureState = true)
                stopKoin()
            }
        }

    private fun startDownloadReadSettings() {
        startKoin {
            modules(module {
                single<ReadSettingsGateway> {
                    object : ReadSettingsGateway {
                        override val settings = MutableStateFlow(ReadSettings())
                        override val currentSettings get() = settings.value
                        override suspend fun update(transform: (ReadSettings) -> ReadSettings) {
                            settings.value = transform(settings.value)
                        }
                    }
                }
            })
        }
    }

    @Test fun mixedShelfRemovalKeepsFailedRecoveredSourceAndKeepsOtherCache() = fixture { repo, db, _, app ->
        val original = flat(name(7, "旧章"), "原网络缓存")
        val source = repo.scanBook(book.bookUrl).texts.single()
        suspend fun recover(): String = repo.recoverLocal(
            book.bookUrl, listOf(LocalRecoverySelection(source, "本地章", null)),
        ) { _, _ -> }
        val good = recover()
        val gone = recover()
        assertTrue(File(gone).delete())
        val rejected = recover()
        File(rejected).apply { assertTrue(delete()); assertTrue(mkdir()) }
        val ordinarySource = File(app.filesDir, "ordinary.txt").apply { writeText("normal text") }
        db.bookDao.insert(Book(bookUrl = ordinarySource.path, name = "ordinary",
            originName = ordinarySource.name, type = BookType.local or BookType.text))
        val exported = File(app.filesDir, "export.txt").apply { writeText("export") }
        val local = checkNotNull(db.bookDao.getBook(good))
        val section = db.bookChapterDao.getChapterList(good).single()
        BookHelp.saveText(local, section, "edited content")

        val useCase = DeleteBooksUseCase(
            BookDomainRepositoryImpl(db.bookDao, db.bookChapterDao, db.bookSeriesDao),
            LocalBookRepository(db.bookDao),
            object : BookSourceCallbackGateway {
                override suspend fun onDeleteFromShelf(bookUrl: String) = Unit
            },
        )
        val result = useCase.execute(setOf(good, gone, rejected, ordinarySource.path, book.bookUrl), true)
        assertEquals(setOf(good, gone, ordinarySource.path, book.bookUrl), result.deletedBookUrls)
        assertEquals(1, result.failedRecoveredSources)
        assertTrue(File(rejected).isDirectory)
        assertTrue(db.bookDao.getBook(rejected) != null)
        assertEquals(1, db.bookChapterDao.getChapterCount(rejected))
        assertFalse(File(good).exists())
        assertFalse(BookHelp.getChapterContentFile(local, section).exists())
        assertFalse(ordinarySource.exists())
        assertEquals("export", exported.readText())
        assertEquals("原网络缓存", original.readText())
    }

    @Test fun recoveredSourceRollbackAfterDatabaseInsertFailure() = fixture { repo, db, _, _ ->
        flat(name(7, "旧章"), "原缓存内容")
        val source = repo.scanBook(book.bookUrl).texts.single()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_recovered_book BEFORE INSERT ON books " +
                "WHEN NEW.bookUrl LIKE '%/recovered_chapters/%' BEGIN SELECT RAISE(ABORT, 'fixture'); END",
        )
        assertTrue(runCatching {
            repo.recoverLocal(book.bookUrl, listOf(LocalRecoverySelection(source, "本地章", null))) { _, _ -> }
        }.isFailure)
        assertTrue(RecoveredTextFile.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun resetRecoveredChapterReturnsToSourceForNestedLegacyAndStableEdits() = fixture { repo, db, _, _ ->
        flat(name(7, "旧章"), "原文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val url = repo.recoverLocal(book.bookUrl, listOf(LocalRecoverySelection(source, "恢复章", null))) { _, _ -> }
        val local = checkNotNull(db.bookDao.getBook(url))
        val section = db.bookChapterDao.getChapterList(url).single()
        val oldName = section.getFileName()
        val localCache = File(root, local.getFolderName())
        val legacyNested = write(File(localCache, "${oldName.removeSuffix(".nb")}/$oldName"), "旧嵌套正文")
        val legacyFlat = write(File(localCache, oldName), "旧平铺备用")
        assertEquals("旧嵌套正文", BookHelp.getContent(local, section))
        BookHelp.preserveRecoveredTextEdit(local, section)
        assertFalse(legacyNested.exists())
        assertTrue(legacyFlat.isFile)
        assertEquals("旧嵌套正文", BookHelp.getContent(local, section))
        BookHelp.saveText(local, section, "稳定路径")
        assertEquals("稳定路径", BookHelp.getContent(local, section))
        BookHelp.delContent(local, section)
        assertEquals("原文", BookHelp.getContent(local, section))
        assertTrue(File(url).isFile)
    }

    @Test fun groupingDigestChecksCancellationBetweenChunks() = fixture { _, _, _, _ ->
        val large = flat(name(3, "旧章"), "正文".repeat(60_000))
        val stamp = checkNotNull(OrphanCacheFiles.stamp(large))
        var chunks = 0
        val result = runCatching {
            OrphanCacheFiles.digest(large, stamp) {
                if (++chunks == 3) throw CancellationException("group scan cancelled")
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(3, chunks)
        assertEquals(stamp.size, large.length())
    }

    @Test fun restoringVerifiedTitleKeepsUrlIndexAndOriginalCacheAndReadsNewPath() = fixture { repo, db, _, _ ->
        val oldTitle = "第三章 原标题"
        val sourceFile = flat(name(3, oldTitle), "$oldTitle\n缓存正文")
        val oldTarget = nested(current.getFileName(), "之前的内容")
        val source = repo.scanBook(book.bookUrl).texts.single()
        assertEquals(oldTitle, source.originalTitle)
        val body = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val reviewed = repo.compare(book.bookUrl, source, body.digest, target, oldTitle)
        assertEquals("之前的内容", reviewed.currentText)
        val renamed = repo.restoreChapter(book.bookUrl, source, body.digest, target, reviewed.version, reviewed.titleChange)
        assertEquals(target.copy(title = oldTitle), renamed)
        val section = checkNotNull(db.bookChapterDao.getChapter(book.bookUrl, target.index))
        val expected = current.copy(title = oldTitle)
        assertEquals(expected.getFileName(), section.getFileName())
        assertNotEquals(current.getFileName(), section.getFileName())
        assertEquals(target.url, section.url)
        assertEquals(body.text, BookHelp.getContent(book, section))
        assertEquals(body.text, repo.readRestoredChapter(book.bookUrl, renamed, body.digest))
        assertEquals(body.text, sourceFile.readText())
        assertEquals("之前的内容", oldTarget.readText())
        assertEquals(book.durChapterPos, db.bookDao.getBook(book.bookUrl)?.durChapterPos)
    }

    @Test fun titleOnlyRestoreReviewsTheNewPathAndKeepsIdenticalOriginalText() = fixture { repo, db, _, _ ->
        val sourceFile = flat(name(7, "旧章"), "相同的正文")
        val original = nested(current.getFileName(), sourceFile.readText())
        val source = repo.scanBook(book.bookUrl).texts.single()
        val body = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val title = "仅修改章名"
        val comparison = repo.compare(book.bookUrl, source, body.digest, target, title)
        assertEquals(RecoveryDifference.Identical, comparison.difference)
        val reviewedTitle = checkNotNull(comparison.titleChange)

        val updated = repo.restoreChapter(book.bookUrl, source, body.digest, target, comparison.version, reviewedTitle)

        assertEquals(target.copy(title = title), updated)
        assertEquals(body.text, repo.readRestoredChapter(book.bookUrl, updated, body.digest))
        assertEquals(body.text, original.readText())
        assertEquals(body.text, sourceFile.readText())
        assertEquals(book.durChapterIndex, db.bookDao.getBook(book.bookUrl)?.durChapterIndex)
        assertEquals(book.durChapterPos, db.bookDao.getBook(book.bookUrl)?.durChapterPos)
    }

    @Test fun sourceAtRenamedDestinationStaysOnDiskButBecomesLegal() = fixture { repo, db, _, _ ->
        val title = "新名字"
        val path = nested(name(0, title), "恢复内容")
        val original = flat(current.getFileName(), "旧正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val body = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val reviewed = repo.compare(book.bookUrl, source, body.digest, target, title)
        val stamp = path.lastModified()
        val result = repo.restoreChapter(book.bookUrl, source, body.digest, target, reviewed.version, reviewed.titleChange)
        assertEquals(stamp, path.lastModified())
        assertEquals("恢复内容", path.readText())
        assertEquals("旧正文", original.readText())
        assertEquals(title, db.bookChapterDao.getChapter(book.bookUrl, 0)?.title)
        assertFalse(repo.scanBook(book.bookUrl).texts.any { it.id == source.id })
        assertEquals(body.text, repo.readRestoredChapter(book.bookUrl, result, body.digest))
    }

    @Test fun conflictingLayoutsAndChapterAssetsPreventRenameWithoutChangingFiles() = fixture { repo, db, _, _ ->
        flat(name(3, "旧章"), "将恢复的内容")
        val original = nested(current.getFileName(), "原章当前内容")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val body = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val title = "新标题"
        val newName = name(0, title)
        val matching = nested(newName, body.text)
        val conflict = flat(newName, "另一份文本")
        assertEquals(RecoveryProblem.TitleDestinationConflict, problem {
            repo.compare(book.bookUrl, source, body.digest, target, title)
        })
        assertEquals("另一份文本", conflict.readText())
        assertTrue(conflict.delete())
        val marker = write(File(original.parentFile, current.getFileName("nr")), "")
        assertEquals(RecoveryProblem.TitleAssetsConflict, problem {
            repo.compare(book.bookUrl, source, body.digest, target, title)
        })
        assertTrue(marker.delete())

        val reviewed = repo.compare(book.bookUrl, source, body.digest, target, title)
        flat(newName, "确认后写入的正文")
        assertEquals(RecoveryProblem.Changed, problem {
            repo.restoreChapter(book.bookUrl, source, body.digest, target, reviewed.version, reviewed.titleChange)
        })
        assertEquals(current.title, db.bookChapterDao.getChapter(book.bookUrl, 0)?.title)
        assertEquals("原章当前内容", original.readText())
        assertEquals(body.text, matching.readText())
    }

    @Test fun newTitleFileIsRemovedWhenFirstWriteFails() = titleWriteFailure(TitleFault.FirstWrite)

    @Test fun newTitleFileIsRemovedWhenPartialWriteFails() = titleWriteFailure(TitleFault.PartialWrite)

    @Test fun newTitleFileIsRemovedWhenPartialWriteIsCancelled() = titleWriteFailure(TitleFault.CancelPartial)

    @Test fun newTitleFileIsRemovedWhenSyncFails() = titleWriteFailure(TitleFault.Sync)

    @Test fun newTitleFileIsRemovedWhenCancelledAfterSync() = titleWriteFailure(TitleFault.CancelAfterSync)

    @Test fun replacingUncommittedTitlePathPreservesTheOtherWritersFile() = titleWriteFailure(TitleFault.Replacement)

    @Test fun editingOwnedTitleFileInPlacePreservesTheOtherWritersBytes() = titleWriteFailure(TitleFault.OtherBytes)

    private enum class TitleFault {
        FirstWrite, PartialWrite, CancelPartial, Sync, CancelAfterSync, Replacement, OtherBytes,
    }

    private fun titleWriteFailure(fault: TitleFault) {
        lateinit var caller: Job
        var didInject = false
        var replacementText: String? = null
        fixture(makeTitleFile = { path ->
            val handle = TestTitleHandle(path)
            TitleCacheFile(path, object : TitleCacheFile.Handle by handle {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    when {
                        fault == TitleFault.FirstWrite -> {
                            didInject = true
                            throw IOException("first write fixture")
                        }
                        offset == 0 -> {
                            val written = handle.write(bytes, offset, minOf(8, length))
                            if (fault == TitleFault.CancelPartial) {
                                didInject = true
                                caller.cancel()
                            }
                            return written
                        }
                        fault == TitleFault.PartialWrite -> {
                            didInject = true
                            throw IOException("partial write fixture")
                        }
                        fault == TitleFault.Replacement || fault == TitleFault.OtherBytes -> {
                            if (fault == TitleFault.Replacement) {
                                // Same prefix and byte count, but the name now points at a different inode.
                                val issued = path.readBytes()
                                Files.move(path.toPath(), path.resolveSibling("detached").toPath())
                                path.writeBytes(issued)
                            } else {
                                path.writeText("different".take(offset))
                            }
                            replacementText = path.readText()
                            didInject = true
                            throw IOException("another writer fixture")
                        }
                        else -> return handle.write(bytes, offset, length)
                    }
                }

                override fun sync() {
                    if (fault == TitleFault.Sync) {
                        didInject = true
                        throw IOException("sync fixture")
                    }
                    handle.sync()
                    if (fault == TitleFault.CancelAfterSync) {
                        didInject = true
                        caller.cancel()
                    }
                }
            })
        }) { repo, db, _, _ ->
            val sourceFile = flat(name(3, "旧章"), "正文原样保留。")
            val oldTargetFile = nested(current.getFileName(), "不删除原正文")
            val source = repo.scanBook(book.bookUrl).texts.single()
            val body = repo.preview(book.bookUrl, source)
            val target = repo.targets(book.bookUrl, source).single()
            val title = "新标题"
            val reviewed = repo.compare(book.bookUrl, source, body.digest, target, title)
            var failure: Throwable? = null
            coroutineScope {
                launch {
                    caller = coroutineContext[Job]!!
                    failure = runCatching {
                        repo.restoreChapter(book.bookUrl, source, body.digest, target, reviewed.version, reviewed.titleChange)
                    }.exceptionOrNull()
                }.join()
            }

            assertTrue(didInject)
            if (fault == TitleFault.CancelPartial || fault == TitleFault.CancelAfterSync) {
                assertTrue(failure.toString(), failure is CancellationException)
            } else {
                assertTrue(failure.toString(), failure is IOException)
            }
            val destination = BookHelp.getChapterContentFile(book, current.copy(title = title))
            if (replacementText == null) assertFalse(destination.exists())
            else assertEquals(replacementText, destination.readText())
            assertEquals(target.title, db.bookChapterDao.getChapter(book.bookUrl, 0)?.title)
            assertEquals("不删除原正文", oldTargetFile.readText())
            assertEquals(body.text, sourceFile.readText())
        }
    }

    // Robolectric's ShadowLinux returns zero inode identities and ignores O_EXCL.
    // Exercise the real filesystem with NIO while using the production compensation logic.
    private class TestTitleHandle(private val path: File) : TitleCacheFile.Handle {
        private val channel = FileChannel.open(path.toPath(), WRITE, CREATE_NEW, NOFOLLOW_LINKS)
        private val key = checkNotNull(attributes().fileKey())

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
            channel.write(ByteBuffer.wrap(bytes, offset, length))

        override fun sync() = channel.force(true)

        override fun stillNamed(): Boolean =
            Files.exists(path.toPath(), NOFOLLOW_LINKS) && attributes().fileKey() == key

        private fun attributes() =
            Files.readAttributes(path.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)

        override fun close() = channel.close()
    }

    @Test fun titleRoomFailureRollsBackOwnedNewFileAndBothOriginalsSurvive() = fixture { repo, db, _, _ ->
        val sourceFile = flat(name(3, "旧章"), "恢复正文")
        val original = nested(current.getFileName(), "旧正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val body = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val title = "候选新标题"
        val reviewed = repo.compare(book.bookUrl, source, body.digest, target, title)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_title BEFORE UPDATE OF title ON chapters " +
                "WHEN NEW.title != OLD.title BEGIN SELECT RAISE(ABORT, 'fixture'); END",
        )
        val result = runCatching {
            repo.restoreChapter(book.bookUrl, source, body.digest, target, reviewed.version, reviewed.titleChange)
        }
        assertTrue(result.isFailure)
        val destination = BookHelp.getChapterContentFile(book, current.copy(title = title))
        assertFalse(destination.exists())
        assertEquals(target.title, db.bookChapterDao.getChapter(book.bookUrl, 0)?.title)
        assertEquals("旧正文", original.readText())
        assertEquals(body.text, sourceFile.readText())
    }

    @Test fun titleCommitRetainsResultWhenCallerCancelledAroundRoomCommit() = fixture { repo, db, _, _ ->
        flat(name(3, "旧章"), "恢复正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val body = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val title = "提交后的标题"
        val reviewed = repo.compare(book.bookUrl, source, body.digest, target, title)
        val restore = suspend {
            repo.restoreChapter(book.bookUrl, source, body.digest, target, reviewed.version, reviewed.titleChange)
        }
        val cancelled = cancelAroundIoReturn(cancelBeforeIo = true, action = restore)
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
        assertEquals(target.title, db.bookChapterDao.getChapter(book.bookUrl, 0)?.title)
        val committed = cancelAroundIoReturn(action = restore).getOrThrow()
        assertEquals(title, committed.title)
        assertEquals(title, db.bookChapterDao.getChapter(book.bookUrl, 0)?.title)
        assertEquals(body.text, repo.readRestoredChapter(book.bookUrl, committed, body.digest))
    }

    @Test fun largeDirectoryScans3435CandidatesInNumericOrder() = fixture { repo, _, _, _ ->
        val total = 3435
        repeat(total) { i ->
            val index = 100001 - i
            flat(name(index, "历史$i"), "第 $i 段正文")
            if (i % 3 == 0) nested(name(index, "另一标题$i"), "第 $i 段正文")
        }
        val result = repo.scanBook(book.bookUrl).texts
        assertEquals(total, result.size)
        assertEquals((total + 2) / 3, result.count { it.copies.size == 2 })
        assertEquals(100001 - total + 1, result.first().index)
        assertEquals(100001, result.last().index)
        assertTrue(result.all { it.originalTitle == null })
    }

    private suspend fun problem(block: suspend () -> Any?): RecoveryProblem =
        (runCatching { block() }.exceptionOrNull() as OrphanRecoveryException).problem

    private fun fixture(
        tocCountWords: Boolean = true,
        makeTitleFile: (File) -> TitleCacheFile = { TitleCacheFile(it, TestTitleHandle(it)) },
        test: suspend (OrphanCacheRecoveryRepository, AppDatabase, BookCacheMaintenanceRepository, Application) -> Unit,
    ) {
        val app = RuntimeEnvironment.getApplication()
        app.injectAsAppCtx()
        root.deleteRecursively()
        RecoveredTextFile.directory.deleteRecursively()
        InMemoryAppDatabaseFixture(app).use { fixture ->
            val db = fixture.database
            val settings = object : ReadSettingsGateway {
                override val settings = MutableStateFlow(ReadSettings(tocCountWords = tocCountWords))
                override val currentSettings get() = settings.value
                override suspend fun update(transform: (ReadSettings) -> ReadSettings) {
                    settings.value = transform(settings.value)
                }
            }
            val maintenance = BookCacheMaintenanceRepository(db.bookDao, db.bookChapterDao)
            try {
                db.bookDao.insert(book)
                db.bookChapterDao.insert(current)
                runBlocking { test(OrphanCacheRecoveryRepository(db, maintenance, settings, makeTitleFile), db, maintenance, app) }
            } finally {
                root.deleteRecursively()
                RecoveredTextFile.directory.deleteRecursively()
                TextFile.clear()
            }
        }
    }
}

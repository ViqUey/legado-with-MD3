package io.legado.app.data.repository

import android.app.Application
import android.net.Uri
import com.sun.net.httpserver.HttpServer
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.gateway.ImportBookSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.model.settings.OtherSettings
import io.legado.app.domain.model.settings.ImportBookSettings
import io.legado.app.domain.gateway.BookSourceCallbackGateway
import io.legado.app.domain.usecase.DeleteBooksUseCase
import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.LocalBookMetricInvalidation
import io.legado.app.domain.model.RecoveryExportFormat
import io.legado.app.domain.model.PrivateBookFacts
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
import io.legado.app.help.book.supportsOrphanRecovery
import io.legado.app.help.coil.CoverFileCache
import io.legado.app.help.coil.DefaultCoverPath
import io.legado.app.lib.epub.inspectGeneratedEpub
import io.legado.app.model.CacheBook
import io.legado.app.model.LegacyReaderSession
import io.legado.app.model.ReadBook
import io.legado.app.data.entities.BookSource
import io.legado.app.model.cache.CacheDownloadRequest
import io.legado.app.model.cache.ChapterSelection
import io.legado.app.model.cache.CacheDownloadActivity
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.LocalBook
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
import org.robolectric.Shadows.shadowOf
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
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
        assertTrue(book.bookUrl in db.bookDao.flowPrivacyProtectedBookUrls().first())
        assertEquals(RecoveryProblem.PrivateBook, problem { repo.preview(book.bookUrl, source) })
        assertEquals(1, maintenance.scanOrphanedChapters().size)
        db.bookGroupDao.insert(BookGroup(groupId = 4, isPrivate = true))
        db.bookDao.update(book.copy(group = 4))
        assertTrue(book.bookUrl in db.bookDao.flowPrivacyProtectedBookUrls().first())
        assertEquals(RecoveryProblem.PrivateBook, problem { repo.scanBook(book.bookUrl) })
        assertEquals(RecoveryProblem.PrivateBook, problem { repo.restoreChapter(book.bookUrl, source, preview.digest, target, compare.version) })
        assertNull(BookHelp.getContent(book, current))
        assertEquals(RecoveryProblem.PrivateBook, problem {
            repo.inspectExport(book.bookUrl, listOf(LocalRecoverySelection(source, "缓存", preview.digest)))
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
            val export = repo.inspectExport(book.bookUrl, listOf(LocalRecoverySelection(source, "副本", body.digest)))
            assertEquals(body.digest, export.selections.single().previewDigest)
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

    @Test fun groupingUsesExactBytesAndKeepsEachHistoricalIndexAndTitle() = fixture { repo, db, _, _ ->
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
        assertEquals(setOf(7), group.copies.mapNotNull { it.index }.toSet())
        val differentIndex = items.single { it.index == 8 }
        assertEquals(group.contentDigest, differentIndex.contentDigest)
        assertTrue(differentIndex.copies.isEmpty())
        assertEquals("完全相同", repo.preview(book.bookUrl, differentIndex).text)
        assertEquals(setOf(book.durChapterTitle, book.latestChapterTitle), group.copies.mapNotNull { it.originalTitle }.toSet())
        assertTrue(group.hasConflictingTitles)
        assertEquals(OrphanTextId(secondName.removeSuffix(".nb"), secondName), group.id)
        assertEquals(book.latestChapterTitle, group.originalTitle)
        val historical = group.copies.single { it.originalTitle == book.durChapterTitle }
        val chosenSource = group.copy(
            id = historical.id, titleHash = historical.titleHash, originalTitle = historical.originalTitle,
            index = historical.index, modified = historical.modified,
        )
        val planned = repo.inspectExport(book.bookUrl, listOf(LocalRecoverySelection(chosenSource, "明确版本", null)))
        assertEquals(historical.id, planned.selections.single().source.id)
        assertEquals(historical.titleHash, planned.selections.single().source.titleHash)
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

    @Test fun sameReaderBodyAllowsRenameWhileLeavingShadowedBodyAndAssets() = fixture { repo, db, _, _ ->
        val history = flat(name(3, "旧章"), "将恢复的内容")
        val original = nested(current.getFileName(), "原章当前内容")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val body = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val title = "新标题"
        val newName = name(0, title)
        val matching = nested(newName, body.text)
        val conflict = flat(newName, "被遮蔽的另一份文本")
        val marker = write(File(original.parentFile, current.getFileName("nr")), "old marker")
        val translation = write(File(folder, "${newName.removeSuffix(".nb")}.en.nb"), "old translation")
        val image = write(File(matching.parentFile, "images/cover.png"), "unchanged image")
        val reviewed = repo.compare(book.bookUrl, source, body.digest, target, title)
        matching.writeText("新缓存已被更新")
        assertEquals(RecoveryProblem.Changed, problem {
            repo.restoreChapter(book.bookUrl, source, body.digest, target, reviewed.version, reviewed.titleChange)
        })
        assertEquals(current.title, db.bookChapterDao.getChapter(book.bookUrl, 0)?.title)
        assertEquals(RecoveryProblem.TitleDestinationConflict, problem {
            repo.compare(book.bookUrl, source, body.digest, target, title)
        })
        matching.writeText(body.text)
        val verified = repo.compare(book.bookUrl, source, body.digest, target, title)
        val changed = repo.restoreChapter(book.bookUrl, source, body.digest, target, verified.version, verified.titleChange)
        assertEquals(title, changed.title)
        assertEquals("被遮蔽的另一份文本", conflict.readText())
        assertEquals("原章当前内容", original.readText())
        assertEquals("old marker", marker.readText())
        assertEquals("old translation", translation.readText())
        assertEquals("unchanged image", image.readText())
        assertEquals(body.text, history.readText())
        assertEquals(body.text, BookHelp.getContent(book, db.bookChapterDao.getChapter(book.bookUrl, 0)!!))
    }

    @Test fun translationStillAppearsInBaseOrphansButInvalidAndTranslatedFilesAreIsolated() = fixture { repo, _, maintenance, _ ->
        val saved = flat(name(3, "原章"), "正常")
        val subject = name(4, "译文章").removeSuffix(".nb")
        val translated = flat("$subject.zh-Hant.nb", "译文")
        val localized = "٠٠٠١٢-${subject.substringAfter('-')}"
        val localizedFlat = flat("$localized.ja.nb", "日语译文")
        val localizedNested = write(File(File(folder, localized), "$localized.iw.nb"), "旧代号译文")
        val unrecognized = flat("$localized.zz.nb", "不能确认语言的历史原文")
        val historic = flat("chapter.2019.final.nb", "另一个正文")
        flat("broken.nb", "first line\n").appendBytes(byteArrayOf(0xc3.toByte(), 0x28))
        val group = maintenance.scanOrphanedChapters().single()
        assertTrue(translated.name in group.entryNames)
        assertTrue(localizedFlat.name in group.entryNames)
        assertTrue(localizedNested.parentFile!!.name in group.entryNames)
        val catalog = repo.scanBook(book.bookUrl)
        assertEquals(setOf(saved.name, historic.name, unrecognized.name), catalog.texts.map { it.id.entry }.toSet())
        assertEquals(1, catalog.unreadable)
    }

    private fun expectedTxtPreface(author: String = "", intro: String = ""): String {
        val context = RuntimeEnvironment.getApplication()
        val title = context.getString(R.string.feature_orphan_recovery_local_name, book.name)
        val authorLine = context.getString(R.string.author_show, author)
        val introLine = context.getString(R.string.intro_show, "\n$intro")
        return "$title\n$authorLine\n$introLine\n\n"
    }

    @Test fun txtExportIncludesDisplayMetadataAndBaseImporterReadsThePreface() = fixture { repo, db, _, _ ->
        val original = book.copy(author = "作者：作者甲", intro = "原简介", customIntro = "<b>自定义简介</b><br>第二段")
        db.bookDao.update(original)
        val firstTitle = "第1章 第一章"
        val secondTitle = "第2章 第二章"
        val firstBody = "第一章正文。".repeat(240)
        val secondBody = "第二章正文。".repeat(240)
        flat(name(1, firstTitle), firstBody)
        flat(name(2, secondTitle), secondBody)
        val plan = repo.inspectExport(book.bookUrl, repo.scanBook(book.bookUrl).texts.mapIndexed { index, item ->
            LocalRecoverySelection(item, listOf(firstTitle, secondTitle)[index], repo.preview(book.bookUrl, item).digest)
        })
        val chapters = "$firstTitle\n$firstBody\n\n$secondTitle\n$secondBody\n\n"
        val directory = Files.createTempDirectory(
            android.os.Environment.getExternalStorageDirectory().toPath(), "txt-metadata-",
        ).toFile()
        startImportSettings()
        try {
            val result = repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, true) { _, _ -> }
            val header = expectedTxtPreface("作者甲", "自定义简介\n　　第二段")
            val exported = directory.resolve(result.fileName)
            assertEquals(header + chapters, exported.readText())
            assertFalse(exported.readText().contains("原简介"))

            // Exercise the ordinary TXT parser with a known enabled rule and chapter spacing
            // that meets its auto-detection threshold. This is not a custom recovery format.
            db.txtTocRuleDao.insert(TxtTocRule(name = "test chapters", chapterRule = "^第[0-9]+章[^\\r\\n]*"))
            val imported = checkNotNull(db.bookDao.getBook(checkNotNull(result.importedBookUrl)))
            val toc = TextFile.getChapterList(imported)
            assertEquals(listOf("前言", firstTitle, secondTitle), toc.map { it.title })
            assertEquals(header, imported.intro)
            assertTrue(TextFile.getContent(imported, toc[1]).contains(firstBody))
            assertTrue(TextFile.getContent(imported, toc[2]).contains(secondBody))

            db.bookDao.update(original.copy(customIntro = null))
            val fallback = repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, false) { _, _ -> }
            assertEquals(expectedTxtPreface("作者甲", "原简介") + chapters, directory.resolve(fallback.fileName).readText())
            db.bookDao.update(original.copy(author = "", intro = null, customIntro = null))
            val empty = repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, false) { _, _ -> }
            assertEquals(expectedTxtPreface() + chapters, directory.resolve(empty.fileName).readText())
        } finally {
            TextFile.clear()
            stopKoin()
            directory.deleteRecursively()
        }
    }

    @Test fun ordinaryTxtIsPublishedInChosenOrderAndKeptWhenImportDisabled() = fixture { repo, db, _, _ ->
        val a = flat("a.nb", "A & <img src=\"https://img.example/a?q=1&x=2\">")
        val b = flat("b.nb", "短")
        val byName = repo.scanBook(book.bookUrl).texts.associateBy { it.id.entry }
        val plan = repo.inspectExport(book.bookUrl, listOf(
            LocalRecoverySelection(byName.getValue(b.name), "先 & <标题>", null),
            LocalRecoverySelection(byName.getValue(a.name), "后", null),
        ))
        assertTrue(plan.containsImages)
        val directory = java.nio.file.Files.createTempDirectory(
            android.os.Environment.getExternalStorageDirectory().toPath(), "review-export-",
        ).toFile()
        try {
            val result = repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, false) { _, _ -> }
            assertNull(result.importedBookUrl)
            assertFalse(result.importAttempted)
            assertEquals(expectedTxtPreface() + "先 & <标题>\n短\n\n后\n${a.readText()}\n\n", directory.resolve(result.fileName).readText())
            assertEquals(1, db.bookDao.allBookCount)
            a.writeText("modified")
            assertEquals(RecoveryProblem.Changed, problem {
                repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, false) { _, _ -> }
            })
            assertEquals(1, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun txtAndEpubRemoveOnlyLeadingDuplicateTitlesWithoutChangingHistoricalCaches() = fixture { repo, _, _, _ ->
        val oldTitle = "第七章 旧标题"
        val historical = nested(name(6, oldTitle), "\uFEFF　$oldTitle \r\n历史正文\r\n末段")
        val plain = flat("plain.nb", "普通正文\n自选标题\n末段")
        val same = flat("same.nb", "相同标题\n相同标题\n正文")
        val uncertain = flat(name(8, "另一章名"), "第八章 开始\n应保留正文")
        val imageBytes = png(0xff0000)
        val imageBody = "<img src=\"${inlinePng(imageBytes)}\">\n图后正文"
        val illustrated = flat(name(9, "插图标题"), "插图标题\n$imageBody")
        val sources = listOf(historical, plain, same, uncertain, illustrated)
        val titles = listOf("第七章 新标题", "自选标题", "相同标题", "第八章 新标题", "插图新标题")
        val expectedBodies = listOf("历史正文\r\n末段", plain.readText(), "相同标题\n正文", uncertain.readText(), imageBody)
        val before = sources.map { it.readBytes() }
        val catalog = repo.scanBook(book.bookUrl).texts.associateBy { it.id.child ?: it.id.entry }
        val selections = sources.zip(titles).map { (file, title) ->
            val item = catalog.getValue(file.name)
            val preview = repo.preview(book.bookUrl, item)
            assertEquals(file.readText(), preview.text)
            LocalRecoverySelection(item, title, preview.digest)
        }
        assertNull(selections[0].source.originalTitle)
        assertNull(selections[3].source.originalTitle)
        assertEquals("第八章 开始", selections[3].source.suggestedTitle)
        assertEquals("插图标题", selections[4].source.originalTitle)
        val plan = repo.inspectExport(book.bookUrl, selections)
        assertTrue(plan.containsImages)
        val directory = Files.createTempDirectory(
            android.os.Environment.getExternalStorageDirectory().toPath(), "export-title-",
        ).toFile()
        try {
            val txt = repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, false) { _, _ -> }
            assertEquals(
                expectedTxtPreface() + titles.zip(expectedBodies).joinToString("") { (title, body) -> "$title\n$body\n\n" },
                directory.resolve(txt.fileName).readText(),
            )
            val epub = repo.export(book.bookUrl, plan, RecoveryExportFormat.Epub, directory.path, false) { _, _ -> }
            assertEquals(0, epub.missingImages)
            ZipFile(directory.resolve(epub.fileName)).use { zip ->
                fun read(path: String) = zip.getInputStream(zip.getEntry(path)).reader().use { it.readText() }
                titles.forEachIndexed { index, title ->
                    val pageText = read("EPUB/chapter-${index + 1}.xhtml")
                    val page = Jsoup.parse(pageText, "", Parser.xmlParser())
                    assertEquals(title, page.select("h1").single().text())
                    val expectedMarkup = if (index == 4) {
                        val href = page.select("body > div img").single().attr("src")
                        assertTrue(imageBytes.contentEquals(zip.getInputStream(zip.getEntry("EPUB/$href")).use { it.readBytes() }))
                        "<img src=\"$href\" alt=\"\"/><br/>图后正文"
                    } else expectedBodies[index].replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br/>")
                    assertTrue(pageText.contains("<body><h1>$title</h1><div>$expectedMarkup</div></body>"))
                }
                val nav = Jsoup.parse(read("EPUB/nav.xhtml"), "", Parser.xmlParser())
                assertEquals(titles, nav.select("nav li a").eachText())
            }
            sources.forEachIndexed { index, file ->
                assertTrue(before[index].contentEquals(file.readBytes()))
                val preview = repo.preview(book.bookUrl, selections[index].source)
                assertEquals(selections[index].previewDigest, preview.digest)
                assertEquals(file.readText(), preview.text)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun rawExportRejectsLinkedDirectoriesAndDirectoryReplacement() = fixture { _, _, _, _ ->
        val primary = android.os.Environment.getExternalStorageDirectory().toPath()
        val root = Files.createTempDirectory(primary, "export-path-").toFile()
        val chosen = root.resolve("chosen").apply { mkdir() }
        val alternate = root.resolve("alternate").apply { mkdir() }
        val unrelated = alternate.resolve("existing.txt").apply { writeText("keep") }
        val linked = root.resolve("linked").toPath()
        val source = root.resolve("source.payload").apply { writeText("original chapters") }
        try {
            Files.createSymbolicLink(linked, alternate.toPath())
            fun publishTo(directory: String, check: () -> Unit = {}) = RecoveryExportFiles.publish(
                source, directory, "recovery", RecoveryExportFormat.Txt, { _, _ -> false }, check,
            )
            assertTrue(runCatching { publishTo(linked.toString()) }.exceptionOrNull() is IOException)
            val displaced = root.resolve("displaced")
            assertTrue(runCatching {
                publishTo(chosen.path) {
                    if (!displaced.exists()) {
                        Files.move(chosen.toPath(), displaced.toPath())
                        Files.createSymbolicLink(chosen.toPath(), alternate.toPath())
                    }
                }
            }.exceptionOrNull() is IOException)
            assertEquals(listOf(unrelated), alternate.listFiles()!!.toList())
            assertEquals("keep", unrelated.readText())
            assertTrue(displaced.listFiles()!!.isEmpty())
        } finally {
            Files.deleteIfExists(linked)
            if (Files.isSymbolicLink(chosen.toPath())) Files.delete(chosen.toPath())
            root.deleteRecursively()
        }
    }

    private fun png(color: Int): ByteArray {
        val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, color)
        return java.io.ByteArrayOutputStream().use {
            javax.imageio.ImageIO.write(image, "png", it)
            it.toByteArray()
        }
    }

    private fun inlinePng(bytes: ByteArray): String =
        "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    @Test fun exportFormatUsesActualSelectedBodiesAndOnlyExcludesKnownCoverAddresses() = fixture { repo, db, _, _ ->
        val cover = "https://example.test/cover.png?size=200&v=1"
        val illustration = "https://example.test/cover.png?size=200&v=2"
        val inline = inlinePng(png(0xff0000))
        val localChapter = chapter(6, "ordinary", "illus")
        val files = listOf(
            "plain.nb" to "纯文字",
            "relative-ref.nb" to "<img src=\"cover.png\">",
            "cover-ref.nb" to "<img src=\"https://example.test/cover.png?size=200&amp;v=1\">",
            "missing.nb" to "<img src=\"$illustration\">",
            "inline-cover.nb" to "<img src=\"$inline\">",
            "body-base64.nb" to "<img src=\"${inlinePng(png(0xabcdef))}\">",
            "rich.nb" to "<usehtml><p>内容<img src='https://example.test/rich.png'></p></usehtml>",
            "bubbles.nb" to "<img src=\"dp:2\"><usehtml><img src='bubble://paragraph?count=2'></usehtml>",
        )
        files.forEach { (name, body) -> flat(name, body) }
        nested(localChapter.getFileName(), "<img src=\"https://example.test/actual.png\">")
        BookHelp.getImage(book, localChapter, "https://example.test/actual.png").apply {
            parentFile!!.mkdirs()
            writeBytes(png(0x0000ff))
        }
        val texts = repo.scanBook(book.bookUrl).texts.associateBy { it.id.child ?: it.id.entry }
        suspend fun hasImages(vararg names: String) = repo.inspectExport(book.bookUrl, names.map {
            LocalRecoverySelection(texts.getValue(it), it, null)
        }).containsImages
        assertFalse(hasImages("plain.nb"))
        db.bookDao.update(book.copy(coverUrl = cover))
        assertFalse(hasImages("plain.nb"))
        assertFalse(hasImages("cover-ref.nb"))
        db.bookDao.update(book.copy(coverUrl = cover, customCoverUrl = inline))
        assertFalse(hasImages("plain.nb"))
        assertFalse(hasImages("inline-cover.nb"))
        db.bookDao.update(book.copy(coverUrl = "cover.png"))
        assertTrue(hasImages("relative-ref.nb"))
        db.bookDao.update(book.copy(coverUrl = inline))
        assertFalse(hasImages("plain.nb"))
        db.bookDao.update(book.copy(coverUrl = cover, customCoverUrl = inline))
        assertTrue(hasImages(localChapter.getFileName()))
        assertTrue(hasImages("missing.nb"))
        assertTrue(hasImages("body-base64.nb"))
        assertTrue(hasImages("rich.nb"))
        assertFalse(hasImages("bubbles.nb"))
        assertFalse(hasImages("plain.nb", "cover-ref.nb", "bubbles.nb"))
        assertTrue(hasImages("plain.nb", "cover-ref.nb", "missing.nb"))
    }

    @Test fun recoveredEpubUsesOnlyLocalCoverBytesAndDeclaresTheExactEmbeddedCover() = fixture { repo, db, _, app ->
        flat("orphan.nb", "保留正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val plan = repo.inspectExport(book.bookUrl, listOf(LocalRecoverySelection(source, "原章", null)))
        val directory = Files.createTempDirectory(
            android.os.Environment.getExternalStorageDirectory().toPath(), "cover-sources-",
        ).toFile()
        val cover = png(0x00bbaa)
        val other = png(0x996633)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, cover.size.toLong())
                exchange.responseBody.use { it.write(cover) }
            }
            start()
        }
        try {
            CoverFileCache.clear()
            val base = "http://127.0.0.1:${server.address.port}"
            val file = directory.resolve("local.png").apply { writeBytes(cover) }
            val custom = directory.resolve("custom.png").apply { writeBytes(other) }
            val uri = Uri.parse("content://cover.fixture/local.png")
            shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { file.inputStream() }
            CoverFileCache.write("$base/old-token", other, book.bookUrl)
            CoverFileCache.write("$base/precise", cover)
            val cases = listOf(
                Triple("ordinary local file", book.copy(coverUrl = file.path), cover),
                Triple("ordinary file URI", book.copy(coverUrl = Uri.fromFile(file).toString()), cover),
                Triple("custom local file", book.copy(coverUrl = file.path, customCoverUrl = custom.path), other),
                Triple("content URI", book.copy(coverUrl = uri.toString()), cover),
                Triple("precise cache", book.copy(coverUrl = "$base/precise"), cover),
                Triple("rotating URL with book alias", book.copy(coverUrl = "$base/new-token"), other),
                Triple("inline", book.copy(coverUrl = inlinePng(cover)), cover),
                Triple("default marker with old alias", book.copy(coverUrl = "$base/precise", customCoverUrl = DefaultCoverPath), null),
                Triple("no selected cover with old alias", book, null),
                Triple("missing custom chosen file", book.copy(coverUrl = "$base/precise", customCoverUrl = directory.resolve("missing.png").path), null),
                Triple("missing custom URL", book.copy(coverUrl = "$base/precise", customCoverUrl = "$base/custom-miss"), null),
            )
            suspend fun exportCover(label: String, original: Book, expected: ByteArray?) {
                db.bookDao.update(original)
                val result = repo.export(book.bookUrl, plan, RecoveryExportFormat.Epub, directory.path, false) { _, _ -> }
                ZipFile(directory.resolve(result.fileName)).use { zip ->
                    val opf = Jsoup.parse(zip.getInputStream(zip.getEntry("EPUB/book.opf")).reader().readText(), "", Parser.xmlParser())
                    val coverItem = opf.select("item[properties=cover-image]").singleOrNull()
                    if (expected == null) {
                        assertNull(label, coverItem)
                        assertNull(label, zip.getEntry("EPUB/cover.png"))
                    } else {
                        assertEquals(label, "cover.png", coverItem?.attr("href"))
                        assertEquals(label, "image/png", coverItem?.attr("media-type"))
                        val bytes = zip.getInputStream(zip.getEntry("EPUB/cover.png")).use { it.readBytes() }
                        assertTrue(label, expected.contentEquals(bytes))
                    }
                }
            }
            cases.forEach { (label, original, expected) -> exportCover(label, original, expected) }
            CoverFileCache.clear()
            exportCover("network miss without an alias", book.copy(coverUrl = "$base/miss"), null)
            assertEquals(0, requests.get())
        } finally {
            CoverFileCache.clear()
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test fun recoveryEligibilityAgreesWithMaintenanceIncludingIllustratedTextAndMixedMedia() = fixture { repo, db, maintenance, _ ->
        flat("orphan.nb", "带插图的在线文字正文<img src='https://example.test/illustration.png'>")
        val cases = listOf(
            Triple(BookType.text, "", true),
            Triple(BookType.text or BookType.updateError, "", true),
            Triple(BookType.image, "", false),
            Triple(BookType.audio, "", false),
            Triple(BookType.video, "", false),
            Triple(BookType.webFile, "", false),
            Triple(BookType.text or BookType.image, "", false),
            Triple(BookType.text or BookType.audio, "", false),
            Triple(BookType.text or BookType.video, "", false),
            Triple(BookType.text or BookType.webFile, "", false),
            Triple(BookType.local or BookType.text, "local.txt", false),
            Triple(BookType.local or BookType.text, "local.epub", false),
        )
        for ((type, filename, supported) in cases) {
            db.bookDao.update(book.copy(
                type = type, originName = filename, origin = if (filename.isEmpty()) book.origin else BookType.localTag,
            ))
            assertEquals("detail type $type $filename", supported, supportsOrphanRecovery(type))
            val group = maintenance.scanOrphanedChapters().singleOrNull()
            if (filename.endsWith(".txt")) assertNull(group)
            else assertEquals(supported, group?.recoverySupported)
            if (supported) assertEquals(1, repo.scanBook(book.bookUrl).texts.size)
            else assertEquals(RecoveryProblem.Unavailable, problem { repo.scanBook(book.bookUrl) })
        }
    }

    @Test fun restoredHistoricalTitleCanReadOriginalImageThroughTheBaseCache() = fixture { repo, db, _, _ ->
        val historical = chapter(current.index, "历史章名", "old-url")
        val url = "https://example.test/image.png?key=a%2Fb&part=1"
        val body = "历史正文<img src=\"$url\">"
        val old = nested(historical.getFileName(), body)
        nested(current.getFileName(), "当前正文")
        val png = png(0xaaccdd)
        val originalImage = BookHelp.getImage(book, historical, url).apply {
            parentFile!!.mkdirs()
            writeBytes(png)
        }
        val source = repo.scanBook(book.bookUrl).texts.single()
        val preview = repo.preview(book.bookUrl, source)
        val target = repo.targets(book.bookUrl, source).single()
        val reviewed = repo.compare(book.bookUrl, source, preview.digest, target, historical.title)
        repo.restoreChapter(book.bookUrl, source, preview.digest, target, reviewed.version, checkNotNull(reviewed.titleChange))
        val restored = checkNotNull(db.bookChapterDao.getChapter(book.bookUrl, current.index))
        assertEquals(current.url, restored.url)
        assertEquals(historical.getFileName(), restored.getFileName())
        assertEquals(body, BookHelp.getContent(book, restored))
        assertEquals(originalImage, BookHelp.getImage(book, restored, url))
        assertTrue(png.contentEquals(BookHelp.openCachedImage(book, restored, url).use { it.readBytes() }))
        assertEquals(body, old.readText())
    }

    @Test fun epubUsesChosenVersionsImagesAndLeavesMissingReferencesOutOfTheManifest() = fixture { repo, db, _, _ ->
        val url = "https://example.test/img.png?id=a%2Fb&x=1"
        val chapterA = chapter(4, "旧版本 A", "not-current-a")
        val chapterB = chapter(4, "旧版本 B", "not-current-b")
        val embedded = android.util.Base64.encodeToString(png(0x00ffff), android.util.Base64.NO_WRAP)
        val text = "普通 <p>字面</p> & 内容\n<usehtml><h2>历史标题</h2><p>保留 &amp; 字" +
            "<a href='https://example.test'>链接</a></p><table><tr><td>表格</td></tr></table>" +
            "<script>隐藏</script><img src=\"$url\"><img src='data:image/png;base64,$embedded'>" +
            "<img src=\"relative/missing.png\"></usehtml>普通结尾"
        nested(chapterA.getFileName(), text)
        nested(chapterB.getFileName(), text)
        val imageA = png(0xff0000)
        val imageB = png(0x0000ff)
        for ((chapter, image) in listOf(chapterA to imageA, chapterB to imageB)) {
            BookHelp.getImage(book, chapter, url).apply { parentFile!!.mkdirs(); writeBytes(image) }
        }
        val directory = java.nio.file.Files.createTempDirectory(
            android.os.Environment.getExternalStorageDirectory().toPath(), "epub-export-",
        ).toFile()
        try {
            val cover = directory.resolve("custom.png").apply { writeBytes(png(0x00ff00)) }
            db.bookDao.update(book.copy(customCoverUrl = cover.path))
            val group = repo.scanBook(book.bookUrl).texts.single()
            val selected = group.copies.single { it.id.child == chapterB.getFileName() }
            val variant = group.copy(id = selected.id, titleHash = selected.titleHash, index = selected.index, modified = selected.modified)
            val plan = repo.inspectExport(book.bookUrl, listOf(LocalRecoverySelection(variant, "<任意 & 章名>", null)))
            assertTrue(plan.containsImages)
            val result = repo.export(book.bookUrl, plan, RecoveryExportFormat.Epub, directory.path, false) { _, _ -> }
            assertEquals(1, result.missingImages)
            val epub = directory.resolve(result.fileName)
            java.util.zip.ZipFile(epub).use { zip ->
                fun content(entry: String) = zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }
                assertTrue(content("EPUB/image-2.png").contentEquals(imageB))
                assertFalse(content("EPUB/image-2.png").contentEquals(imageA))
                assertTrue(content("EPUB/image-3.png").contentEquals(png(0x00ffff)))
                assertTrue(content("EPUB/cover.png").contentEquals(cover.readBytes()))
                assertNull(zip.getEntry("EPUB/image-4.png"))
                val xhtml = content("EPUB/chapter-1.xhtml").decodeToString()
                assertTrue("&lt;任意 &amp; 章名&gt;" in xhtml)
                assertTrue("保留 &amp; 字" in xhtml)
                assertTrue("普通 &lt;p&gt;字面&lt;/p&gt; &amp; 内容" in xhtml)
                assertTrue("<h2>历史标题</h2>" in xhtml)
                assertTrue("<td>表格</td>" in xhtml)
                assertFalse("隐藏" in xhtml)
                assertFalse("relative/missing.png" in xhtml)
            }
            System.getenv("LEGADO_EPUBCHECK_FIXTURES")?.let {
                epub.copyTo(File(it, "recovery-selected-images.epub"), overwrite = true)
            }
        } finally { directory.deleteRecursively() }
    }

    @Config(shadows = [EpubSeekableFilesShadow::class])
    @Test fun multiChapterEpubKeepsSelectedSubjectsAndImportsThroughBaseReadingPaths() = fixture { repo, db, _, _ ->
        val imageUrl = "https://example.test/shared.png?sig=x%2Fy&size=2"
        val absent = "https://example.test/missing.png"
        val a = chapter(11, "历史 A", "a")
        val alternateA = chapter(11, "另一个 A", "alternate-a")
        val b = chapter(12, "历史 B", "b")
        val c = chapter(13, "历史 C", "c")
        val red = png(0xff0000)
        val blue = png(0x0000ff)
        val green = png(0x00ff00)
        val fallback = png(0xffffff)
        val aText = "选定 A 正文<img src=\"$imageUrl\">"
        val bText = "选定 B 正文<img src=\"$imageUrl\">"
        val cText = "<usehtml><p>C 的正文</p><img src='${inlinePng(green)}'><img src='$absent'></usehtml>"
        nested(a.getFileName(), aText)
        nested(alternateA.getFileName(), aText)
        nested(b.getFileName(), bText)
        nested(c.getFileName(), cText)
        for ((chapter, bytes) in listOf(a to red, alternateA to fallback, b to blue)) {
            BookHelp.getImage(book, chapter, imageUrl).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        }
        val groups = repo.scanBook(book.bookUrl).texts.associateBy { it.index }
        val aGroup = groups.getValue(11)
        val chosen = aGroup.copies.single { it.id.child == a.getFileName() }
        assertEquals(2, aGroup.copies.size)
        val aVersion = aGroup.copy(
            id = chosen.id, index = chosen.index, titleHash = chosen.titleHash, originalTitle = chosen.originalTitle,
            suggestedTitle = chosen.suggestedTitle, modified = chosen.modified,
        )
        val titles = listOf("C 重新命名", "A 重新命名", "B 重新命名")
        val sources = listOf(groups.getValue(13), aVersion, groups.getValue(12))
        val plan = repo.inspectExport(book.bookUrl, sources.zip(titles).map { (item, title) ->
            LocalRecoverySelection(item, title, repo.preview(book.bookUrl, item).digest)
        })
        assertTrue(plan.containsImages)
        val directory = Files.createTempDirectory(
            android.os.Environment.getExternalStorageDirectory().toPath(), "multi-chapter-epub-",
        ).toFile()
        val expectedTexts = listOf("C 的正文", "选定 A 正文", "选定 B 正文")
        val expectedImages = listOf(green, red, blue)
        startImportSettings()
        val metricEvents = mutableListOf<Pair<String, Boolean>>()
        val metricListener: (String) -> Unit = { url -> metricEvents.add(url to db.inTransaction()) }
        LocalBookMetricInvalidation.register(metricListener)
        metricEvents.clear() // Discard the upstream bus's pre-subscription backlog.
        try {
            val result = repo.export(book.bookUrl, plan, RecoveryExportFormat.Epub, directory.path, true) { _, _ -> }
            val epub = directory.resolve(result.fileName)
            assertEquals(1, result.missingImages)
            val spine = inspectGeneratedEpub(epub).spine
            assertEquals(listOf("EPUB/chapter-1.xhtml", "EPUB/chapter-2.xhtml", "EPUB/chapter-3.xhtml"), spine)
            val imageNames = ZipFile(epub).use { zip ->
                fun xml(entry: String) = Jsoup.parse(
                    zip.getInputStream(zip.getEntry(entry)).reader().readText(), "", Parser.xmlParser(),
                )
                val nav = xml("EPUB/nav.xhtml").select("li a")
                assertEquals(titles, nav.eachText())
                assertEquals(spine.map { it.removePrefix("EPUB/") }, nav.eachAttr("href"))
                assertEquals(listOf("c1", "c2", "c3"), xml("EPUB/book.opf").select("spine itemref").eachAttr("idref"))
                val names = spine.mapIndexed { index, entry ->
                    val chapter = xml(entry)
                    assertEquals(titles[index], chapter.select("h1").single().text())
                    assertTrue(chapter.select("body").single().text().contains(expectedTexts[index]))
                    assertFalse(chapter.outerHtml().contains(absent))
                    val imageName = chapter.select("img").single().attr("src")
                    assertTrue(expectedImages[index].contentEquals(
                        zip.getInputStream(zip.getEntry("EPUB/$imageName")).use { it.readBytes() },
                    ))
                    if (index == 0) {
                        assertTrue(chapter.select("span").single().text().isNotBlank())
                    } else assertTrue(chapter.select("span").isEmpty())
                    imageName
                }
                assertEquals(3, names.toSet().size)
                assertEquals(names.toSet(), zip.entries().toList().map { it.name.removePrefix("EPUB/") }
                    .filter { it.endsWith(".png") }.toSet())
                names
            }
            val importedUrl = checkNotNull(result.importedBookUrl) { "Base could not import the published EPUB" }
            val imported = checkNotNull(db.bookDao.getBook(importedUrl))
            assertEquals(listOf(imported.bookUrl to true), metricEvents)
            val toc = LocalBook.getChapterList(imported)
            assertEquals(titles, toc.map { it.title })
            for ((index, chapter) in toc.withIndex()) {
                val text = checkNotNull(LocalBook.getContent(imported, chapter))
                assertTrue(text, text.contains(expectedTexts[index]))
                val imageSrc = checkNotNull(io.legado.app.constant.AppPattern.imgPattern.find(text)).groupValues[1]
                assertTrue(imageSrc, imageSrc.endsWith(imageNames[index]))
                val image = checkNotNull(EpubFile.getImage(imported, imageSrc))
                assertTrue(expectedImages[index].contentEquals(image.use { it.readBytes() }))
                assertFalse(text.contains(absent))
            }
            System.getenv("LEGADO_EPUBCHECK_FIXTURES")?.let {
                epub.copyTo(File(it, "recovery-multi-chapter-images.epub"), overwrite = true)
            }
        } finally {
            LocalBookMetricInvalidation.unregister(metricListener)
            EpubFile.clear()
            EpubSeekableFilesShadow.reset()
            stopKoin()
            directory.deleteRecursively()
        }
    }

    @Test fun exportCancellationAndIoFailureDoNotPublishPartialChapters() = fixture { repo, _, _, _ ->
        flat("old.nb", "必须保留")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val plan = repo.inspectExport(book.bookUrl, listOf(LocalRecoverySelection(source, "自选标题", null)))
        val directory = java.nio.file.Files.createTempDirectory(
            android.os.Environment.getExternalStorageDirectory().toPath(), "cancel-export-",
        ).toFile()
        try {
            for (fault in listOf(CancellationException("fixture canceled"), IOException("fixture failed"))) {
                val result = runCatching {
                    repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, true) { _, _ -> throw fault }
                }
                // Coroutine stack-trace recovery can recreate the exception across the IO boundary.
                val thrown = checkNotNull(result.exceptionOrNull())
                assertEquals(fault.javaClass, thrown.javaClass)
                assertEquals(fault.message, thrown.message)
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            }
            val committed = cancelAroundIoReturn {
                repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, false) { _, _ -> }
            }.getOrThrow()
            assertEquals(expectedTxtPreface() + "自选标题\n必须保留\n\n", directory.resolve(committed.fileName).readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun publishingRechecksNewPrivateFlagAndGroupAfterTheExternalFileExists() {
        lateinit var db: AppDatabase
        var duringPublication: (() -> Unit)? = null
        var output: File? = null
        fixture(canOpen = { _, facts ->
            if (output?.listFiles()?.any { it.extension == "txt" } == true) {
                duringPublication?.also { duringPublication = null }?.invoke()
            }
            !facts.isPrivate
        }) { repo, database, _, _ ->
            db = database
            flat("old.nb", "正文")
            val source = repo.scanBook(book.bookUrl).texts.single()
            val plan = repo.inspectExport(book.bookUrl, listOf(LocalRecoverySelection(source, "原标题", null)))
            val directory = Files.createTempDirectory(
                android.os.Environment.getExternalStorageDirectory().toPath(), "privacy-export-",
            ).toFile()
            output = directory
            val unrelated = directory.resolve("unrelated.keep").apply { writeText("keep") }
            db.bookGroupDao.insert(BookGroup(groupId = 4, isPrivate = true))
            try {
                for (changed in listOf(book.copy(isPrivate = true), book.copy(group = 4))) {
                    duringPublication = { db.bookDao.update(changed) }
                    assertEquals(RecoveryProblem.PrivateBook, problem {
                        repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, false) { _, _ -> }
                    })
                    assertNull(duringPublication)
                    assertEquals(listOf(unrelated), directory.listFiles()!!.toList())
                    assertEquals("keep", unrelated.readText())
                    db.bookDao.update(book)
                }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun committedExportRemainsAfterBookshelfInsertFails() = fixture { repo, db, _, _ ->
        flat("old.nb", "正文")
        val source = repo.scanBook(book.bookUrl).texts.single()
        val plan = repo.inspectExport(book.bookUrl, listOf(LocalRecoverySelection(source, "原标题", null)))
        val directory = Files.createTempDirectory(
            android.os.Environment.getExternalStorageDirectory().toPath(), "failed-import-",
        ).toFile()
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_recovered_book BEFORE INSERT ON books
            BEGIN SELECT RAISE(ABORT, 'fixture rejected import'); END
        """.trimIndent())
        startImportSettings()
        try {
            val result = repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, true) { _, _ -> }
            assertTrue(result.importAttempted)
            assertFalse(result.missingDurableRead)
            assertNull(result.importedBookUrl)
            assertEquals(1, db.bookDao.allBookCount)
            val exported = directory.resolve(result.fileName)
            assertEquals(expectedTxtPreface() + "原标题\n正文\n\n", exported.readText())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_recovered_book")
            assertEquals(exported.path, LocalBook.importNewExport(android.net.Uri.fromFile(exported), false, 0).bookUrl)
        } finally {
            stopKoin()
            directory.deleteRecursively()
        }
    }

    @Test fun authorizedGroupExportImportsPrivatelyAndNeverRebindsAnExistingLocalBook() {
        var granted = false
        fixture(canOpen = { _, facts -> !facts.isPrivate || granted }) { repo, db, _, _ ->
            flat("old.nb", "私密原文")
            db.bookGroupDao.insert(BookGroup(groupId = 4, isPrivate = true))
            db.bookDao.update(book.copy(group = 4))
            assertEquals(RecoveryProblem.PrivateBook, problem { repo.scanBook(book.bookUrl) })
            granted = true
            val source = repo.scanBook(book.bookUrl).texts.single()
            val plan = repo.inspectExport(book.bookUrl, listOf(LocalRecoverySelection(source, "原章", null)))
            val directory = java.nio.file.Files.createTempDirectory(
                android.os.Environment.getExternalStorageDirectory().toPath(), "private-export-",
            ).toFile()
            startImportSettings()
            try {
                granted = false
                assertEquals(RecoveryProblem.PrivateBook, problem {
                    repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, true) { _, _ -> }
                })
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                granted = true
                val result = repo.export(book.bookUrl, plan, RecoveryExportFormat.Txt, directory.path, true) { _, _ -> }
                val imported = checkNotNull(db.bookDao.getBook(checkNotNull(result.importedBookUrl)))
                assertEquals(4L, imported.group)
                assertTrue(db.bookDao.privateFacts(imported.bookUrl).isPrivate)
                assertEquals(listOf(book.bookUrl, imported.bookUrl).toSet(), db.bookDao.allBookUrls.toSet())
                val duplicateDir = directory.resolve("other").apply { mkdir() }
                val duplicate = duplicateDir.resolve(result.fileName).apply { writeText("different subset") }
                val before = imported.copy(durChapterIndex = 8)
                db.bookDao.update(before)
                assertTrue(runCatching {
                    LocalBook.importNewExport(android.net.Uri.fromFile(duplicate), false, 0)
                }.isFailure)
                assertEquals(8, db.bookDao.getBook(imported.bookUrl)?.durChapterIndex)
                assertEquals(2, db.bookDao.allBookCount)
            } finally {
                stopKoin()
                directory.deleteRecursively()
            }
        }
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

    private fun startImportSettings() {
        startKoin {
            modules(module {
                single<OtherSettingsGateway> {
                    object : OtherSettingsGateway {
                        override val settings = MutableStateFlow(OtherSettings())
                        override val currentSettings get() = settings.value
                        override suspend fun update(transform: (OtherSettings) -> OtherSettings) {
                            settings.value = transform(settings.value)
                        }
                    }
                }
                single<ReadSettingsGateway> {
                    object : ReadSettingsGateway {
                        override val settings = MutableStateFlow(ReadSettings())
                        override val currentSettings get() = settings.value
                        override suspend fun update(transform: (ReadSettings) -> ReadSettings) {
                            settings.value = transform(settings.value)
                        }
                    }
                }
                single<ImportBookSettingsGateway> {
                    object : ImportBookSettingsGateway {
                        override val settings = MutableStateFlow(ImportBookSettings())
                        override val currentSettings get() = settings.value
                        override suspend fun update(transform: (ImportBookSettings) -> ImportBookSettings) {
                            settings.value = transform(settings.value)
                        }
                    }
                }
            })
        }
    }

    private fun fixture(
        tocCountWords: Boolean = true,
        makeTitleFile: (File) -> TitleCacheFile = { TitleCacheFile(it, TestTitleHandle(it)) },
        canOpen: (String, PrivateBookFacts) -> Boolean = { _, facts -> !facts.isPrivate },
        test: suspend (OrphanCacheRecoveryRepository, AppDatabase, BookCacheMaintenanceRepository, Application) -> Unit,
    ) {
        val app = RuntimeEnvironment.getApplication()
        app.injectAsAppCtx()
        root.deleteRecursively()
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
                runBlocking { test(OrphanCacheRecoveryRepository(db, maintenance, settings, makeTitleFile, canOpen), db, maintenance, app) }
            } finally {
                root.deleteRecursively()
                        TextFile.clear()
            }
        }
    }
}

package io.legado.app.feature.orphanrecovery

import io.legado.app.R
import io.legado.app.domain.gateway.OrphanCacheRecoveryGateway
import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.OrphanRecoveryException
import io.legado.app.domain.model.OrphanText
import io.legado.app.domain.model.OrphanTextCatalog
import io.legado.app.domain.model.OrphanTextComparison
import io.legado.app.domain.model.OrphanTextId
import io.legado.app.domain.model.OrphanTextPreview
import io.legado.app.domain.model.RecoveryDifference
import io.legado.app.domain.model.RecoveryMatch
import io.legado.app.domain.model.RecoveryProblem
import io.legado.app.domain.model.RecoveryTarget
import io.legado.app.domain.model.RecoveryTargetVersion
import io.legado.app.domain.model.RecoveryTitleChange
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class OrphanRecoveryViewModelTest {
    private val first = OrphanText(OrphanTextId("first.nb"), 2, null, null, 40, 1)
    private val second = OrphanText(OrphanTextId("second.nb"), null, null, null, 40, 1)
    private val target = RecoveryTarget(4, "chapter-4", "Title", RecoveryMatch.Manual)

    @Test fun selectionAndManualOrderSurvivePreviewAndDriveBatchRecovery() = scenario { vm, gateway, settle ->
        assertEquals(setOf(first.id, second.id), vm.uiState.value.selected)
        vm.onIntent(OrphanRecoveryIntent.Toggle(first.id))
        vm.onIntent(OrphanRecoveryIntent.Preview(second))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("找回的章名"))
        vm.onIntent(OrphanRecoveryIntent.Back)
        assertEquals(setOf(second.id), vm.uiState.value.selected)
        vm.onIntent(OrphanRecoveryIntent.ToggleAll)
        vm.onIntent(OrphanRecoveryIntent.Move(second.id, -1))
        vm.onIntent(OrphanRecoveryIntent.RecoverLocal(persistentMapOf(first.id to "缓存索引 2")))
        settle()
        assertEquals(listOf(second.id, first.id), gateway.selected.map { it.source.id })
        assertEquals(listOf("找回的章名", "缓存索引 2"), gateway.selected.map { it.title })
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        assertEquals(2, vm.uiState.value.texts.size)
    }

    @Test fun toggleAllInvalidatesPreviousLocalRecoveryInBothDirections() = scenario { vm, gateway, settle ->
        val titles = persistentMapOf(first.id to "First", second.id to "Second")
        vm.onIntent(OrphanRecoveryIntent.RecoverLocal(titles))
        settle()
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.ToggleAll)
        assertTrue(vm.uiState.value.selected.isEmpty())
        assertNull(vm.uiState.value.recoveredBookUrl)

        vm.onIntent(OrphanRecoveryIntent.Toggle(first.id))
        vm.onIntent(OrphanRecoveryIntent.RecoverLocal(titles))
        settle()
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.ToggleAll)
        assertEquals(setOf(first.id, second.id), vm.uiState.value.selected)
        assertNull(vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.RecoverLocal(titles))
        settle()
        assertEquals(listOf(first.id, second.id), gateway.selected.map { it.source.id })
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
    }

    @Test fun editingLocalTitleInvalidatesPreviousResultButUnchangedEditDoesNot() = scenario { vm, gateway, settle ->
        val titles = persistentMapOf(first.id to "First", second.id to "Second")
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("Old title"))
        vm.onIntent(OrphanRecoveryIntent.Back)
        vm.onIntent(OrphanRecoveryIntent.RecoverLocal(titles))
        settle()
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("Old title"))
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.EditTitle("New title"))
        assertNull(vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.Back)
        vm.onIntent(OrphanRecoveryIntent.RecoverLocal(titles))
        settle()
        assertEquals(listOf("New title", "Second"), gateway.selected.map { it.title })
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
    }

    @Test fun replacementRequiresConfirmationAndStaleComparisonReturnsToTargetSelection() = scenario { vm, gateway, settle ->
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        vm.onIntent(OrphanRecoveryIntent.Targets)
        settle()
        vm.onIntent(OrphanRecoveryIntent.Target(target))
        settle()
        vm.onIntent(OrphanRecoveryIntent.Restore)
        settle()
        assertEquals(0, gateway.restoreCalls)
        vm.onIntent(OrphanRecoveryIntent.RequestReplace)
        assertTrue(vm.uiState.value.confirm)
        vm.onIntent(OrphanRecoveryIntent.DismissConfirmation)
        assertFalse(vm.uiState.value.confirm)
        gateway.failRestore = true
        vm.onIntent(OrphanRecoveryIntent.RequestReplace)
        vm.onIntent(OrphanRecoveryIntent.Restore)
        settle()
        assertEquals(1, gateway.restoreCalls)
        assertEquals(RecoveryPane.Targets, vm.uiState.value.pane)
        assertNull(vm.uiState.value.comparison)
        assertEquals(R.string.feature_orphan_recovery_changed, vm.uiState.value.message)
    }

    @Test fun cancellationKeepsSelectionAndPrivacyChangesClearThePreview() = scenario { vm, gateway, settle ->
        gateway.suspendImport = true
        val titles = persistentMapOf(first.id to "First", second.id to "Second")
        vm.onIntent(OrphanRecoveryIntent.RecoverLocal(titles))
        settle()
        assertTrue(vm.uiState.value.busy)
        vm.onIntent(OrphanRecoveryIntent.Cancel)
        settle()
        assertFalse(vm.uiState.value.busy)
        assertEquals(setOf(first.id, second.id), vm.uiState.value.selected)
        assertEquals(R.string.feature_orphan_recovery_cancelled, vm.uiState.value.message)
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        gateway.privateUrls.value = setOf("book")
        settle()
        assertNull(vm.uiState.value.preview)
        assertNull(vm.uiState.value.bookUrl)
        vm.onIntent(OrphanRecoveryIntent.Open("book"))
        assertNull(vm.uiState.value.bookUrl)
    }

    @Test fun repeatedOldIndexShowsWarningAndKeepsBothCandidatesSelected() = scenario { vm, gateway, settle ->
        assertFalse(vm.uiState.value.hasDuplicateOldIndices)
        gateway.catalog = listOf(first, second.copy(index = first.index))
        vm.onIntent(OrphanRecoveryIntent.Reload)
        settle()
        assertTrue(vm.uiState.value.hasDuplicateOldIndices)
        assertEquals(setOf(first.id, second.id), vm.uiState.value.selected)
        assertEquals(2, vm.uiState.value.texts.size)
    }

    @Test fun committedResultsStaySuccessfulEvenWhenTheirJobHasBeenCancelled() = scenario { vm, gateway, settle ->
        gateway.cancelAfterCommit = true
        vm.onIntent(OrphanRecoveryIntent.RecoverLocal(persistentMapOf(first.id to "First", second.id to "Second")))
        settle()
        assertFalse(vm.uiState.value.busy)
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        assertEquals(R.string.feature_orphan_recovery_local_done, vm.uiState.value.message)

        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        vm.onIntent(OrphanRecoveryIntent.Targets)
        settle()
        vm.onIntent(OrphanRecoveryIntent.Target(target))
        settle()
        vm.onIntent(OrphanRecoveryIntent.RequestReplace)
        vm.onIntent(OrphanRecoveryIntent.Restore)
        settle()
        assertEquals(1, gateway.restoreCalls)
        assertFalse(vm.uiState.value.busy)
        assertEquals(R.string.feature_orphan_recovery_restore_done, vm.uiState.value.message)
        assertEquals(target, vm.uiState.value.restoredTarget)
        assertNull(gateway.verifiedTarget)
        vm.onIntent(OrphanRecoveryIntent.OpenRestored)
        settle()
        assertEquals(target, gateway.verifiedTarget)
        assertEquals(target, vm.uiState.value.readerRequest?.target)
    }

    @Test fun latePreviewFromClosedSessionCannotReplaceNewSession() = scenario { vm, gateway, settle ->
        gateway.delayPreview = true
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        vm.onIntent(OrphanRecoveryIntent.Close)
        vm.onIntent(OrphanRecoveryIntent.Open("another-book"))
        settle()
        gateway.pendingPreview!!.resume(OrphanTextPreview("older session", "old"))
        settle()
        assertEquals("another-book", vm.uiState.value.bookUrl)
        assertEquals(RecoveryPane.List, vm.uiState.value.pane)
        assertNull(vm.uiState.value.preview)
    }

    @Test fun titleChangeIsOffByDefaultAndRequiresNewReviewAndConfirmation() = scenario { vm, gateway, settle ->
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        vm.onIntent(OrphanRecoveryIntent.Targets)
        settle()
        vm.onIntent(OrphanRecoveryIntent.Target(target))
        settle()
        assertNull(vm.uiState.value.titleDraft)
        vm.onIntent(OrphanRecoveryIntent.UseRestoredTitle(true))
        vm.onIntent(OrphanRecoveryIntent.ChangeTitleDraft("新标题"))
        vm.onIntent(OrphanRecoveryIntent.Restore)
        settle()
        assertEquals(0, gateway.restoreCalls)
        vm.onIntent(OrphanRecoveryIntent.ReviewTitle)
        settle()
        vm.onIntent(OrphanRecoveryIntent.RequestReplace)
        vm.onIntent(OrphanRecoveryIntent.Restore)
        settle()
        assertEquals(1, gateway.restoreCalls)
        assertEquals(target.copy(title = "新标题"), vm.uiState.value.restoredTarget)
        vm.onIntent(OrphanRecoveryIntent.OpenRestored)
        settle()
        assertEquals(target.copy(title = "新标题"), gateway.verifiedTarget)
    }

    private fun scenario(test: (OrphanRecoveryViewModel, Gateway, () -> Unit) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gateway = Gateway()
            val vm = OrphanRecoveryViewModel(gateway)
            advanceUntilIdle()
            vm.onIntent(OrphanRecoveryIntent.Open("book"))
            advanceUntilIdle()
            test(vm, gateway) { advanceUntilIdle() }
            vm.onIntent(OrphanRecoveryIntent.Close)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private inner class Gateway : OrphanCacheRecoveryGateway {
        val privateUrls = MutableStateFlow(emptySet<String>())
        var catalog = listOf(first, second)
        var selected = listOf<LocalRecoverySelection>()
        var restoreCalls = 0
        var verifiedTarget: RecoveryTarget? = null
        var suspendImport = false
        var delayPreview = false
        var pendingPreview: Continuation<OrphanTextPreview>? = null
        var failRestore = false
        var cancelAfterCommit = false

        override fun privateBookUrls() = privateUrls
        override suspend fun scanBook(bookUrl: String) = OrphanTextCatalog("Book", catalog)
        override suspend fun preview(bookUrl: String, source: OrphanText): OrphanTextPreview =
            if (delayPreview) suspendCoroutine { pendingPreview = it } else OrphanTextPreview("cached", "digest")
        override suspend fun targets(bookUrl: String, source: OrphanText) = listOf(target)
        override suspend fun compare(
            bookUrl: String, source: OrphanText, previewDigest: String, target: RecoveryTarget, newTitle: String?,
        ) = OrphanTextComparison(
            target, RecoveryTargetVersion("current", null), "current", RecoveryDifference.Different, null,
            titleChange = newTitle?.let { RecoveryTitleChange(target.copy(title = it), RecoveryTargetVersion(null, null)) },
        )

        override suspend fun recoverLocal(
            bookUrl: String,
            selections: List<LocalRecoverySelection>,
            onProgress: (Int, Int) -> Unit,
        ): String {
            if (suspendImport) awaitCancellation()
            selected = selections
            if (cancelAfterCommit) currentCoroutineContext().cancel()
            return "local-book"
        }

        override suspend fun readRestoredChapter(bookUrl: String, target: RecoveryTarget, digest: String): String {
            verifiedTarget = target
            return "restored"
        }

        override suspend fun restoreChapter(
            bookUrl: String,
            source: OrphanText,
            previewDigest: String,
            target: RecoveryTarget,
            targetVersion: RecoveryTargetVersion,
            titleChange: RecoveryTitleChange?,
        ): RecoveryTarget {
            restoreCalls++
            if (failRestore) throw OrphanRecoveryException(RecoveryProblem.Changed)
            if (cancelAfterCommit) currentCoroutineContext().cancel()
            return titleChange?.renamedTarget ?: target
        }
    }
}

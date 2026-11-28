package io.legado.app.feature.orphanrecovery

import androidx.compose.ui.state.ToggleableState
import io.legado.app.R
import io.legado.app.domain.gateway.OrphanCacheRecoveryGateway
import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.RecoveryExportPlan
import io.legado.app.domain.model.RecoveryExportFormat
import io.legado.app.domain.model.RecoveryExportResult
import io.legado.app.domain.model.OrphanTextCopy
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.persistentListOf
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
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(persistentMapOf(first.id to "缓存索引 2")))
        settle()
        vm.onIntent(OrphanRecoveryIntent.ExportTo("export-directory"))
        settle()
        assertEquals(listOf(second.id, first.id), gateway.selected.map { it.source.id })
        assertEquals(listOf("找回的章名", "缓存索引 2"), gateway.selected.map { it.title })
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        assertEquals(2, vm.uiState.value.texts.size)
    }

    @Test fun toggleAllInvalidatesPreviousLocalRecoveryInBothDirections() = scenario { vm, gateway, settle ->
        val titles = persistentMapOf(first.id to "First", second.id to "Second")
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        vm.onIntent(OrphanRecoveryIntent.ExportTo("export-directory"))
        settle()
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.ToggleAll)
        assertTrue(vm.uiState.value.selected.isEmpty())
        assertNull(vm.uiState.value.recoveredBookUrl)

        vm.onIntent(OrphanRecoveryIntent.Toggle(first.id))
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        vm.onIntent(OrphanRecoveryIntent.ExportTo("export-directory"))
        settle()
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.ToggleAll)
        assertEquals(setOf(first.id, second.id), vm.uiState.value.selected)
        assertNull(vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        vm.onIntent(OrphanRecoveryIntent.ExportTo("export-directory"))
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
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        vm.onIntent(OrphanRecoveryIntent.ExportTo("export-directory"))
        settle()
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("Old title"))
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.EditTitle("New title"))
        assertNull(vm.uiState.value.recoveredBookUrl)
        vm.onIntent(OrphanRecoveryIntent.Back)
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        vm.onIntent(OrphanRecoveryIntent.ExportTo("export-directory"))
        settle()
        assertEquals(listOf("New title", "Second"), gateway.selected.map { it.title })
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
    }

    @Test fun selectingAnActualVersionKeepsGroupIdentityAndManualFormatSurvivesRescan() = scenario { vm, gateway, settle ->
        val historical = OrphanTextId("historic.nb")
        gateway.catalog = listOf(first.copy(copies = persistentListOf(
            OrphanTextCopy(first.id, first.titleHash, null, null, 1, 2),
            OrphanTextCopy(historical, "historic-hash", "历史章名", null, 1, 12),
        )), second)
        vm.onIntent(OrphanRecoveryIntent.Reload)
        settle()
        vm.onIntent(OrphanRecoveryIntent.ChooseVersion(first.id, historical))
        settle()
        assertEquals(historical, vm.uiState.value.source?.id)
        assertEquals(12, vm.uiState.value.source?.index)
        assertEquals("historic-hash", vm.uiState.value.source?.titleHash)
        vm.onIntent(OrphanRecoveryIntent.Back)
        gateway.hasImages = true
        val titles = persistentMapOf(first.id to "First", second.id to "Second")
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        assertEquals(RecoveryExportFormat.Epub, vm.uiState.value.exportFormat)
        vm.onIntent(OrphanRecoveryIntent.ExportFormat(RecoveryExportFormat.Txt))
        assertTrue(vm.uiState.value.showImageFormatHint)
        vm.onIntent(OrphanRecoveryIntent.DismissExport)
        vm.onIntent(OrphanRecoveryIntent.Reload)
        settle()
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        assertEquals(RecoveryExportFormat.Txt, vm.uiState.value.exportFormat)
        assertEquals(historical, vm.uiState.value.exportPlan?.selections?.first()?.source?.id)
        assertFalse(vm.uiState.value.showImageFormatHint)
    }

    @Test fun reauthenticationRestoresOnlyDraftsForTheSameVerifiedBytes() = scenario { vm, gateway, settle ->
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("Private first title"))
        vm.onIntent(OrphanRecoveryIntent.Back)
        vm.onIntent(OrphanRecoveryIntent.Preview(second))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("Private second title"))
        vm.onIntent(OrphanRecoveryIntent.AccessChanged(false))
        val locked = vm.uiState.value
        assertTrue(locked.accessBlocked)
        assertTrue(locked.editedTitles.isEmpty())
        assertTrue(locked.texts.isEmpty())
        assertTrue(locked.versionChoices.isEmpty())
        assertNull(locked.preview)
        assertNull(locked.source)
        assertNull(locked.exportPlan)
        gateway.digests[second.id] = "other bytes, same size and timestamp"
        vm.onIntent(OrphanRecoveryIntent.AccessChanged(true))
        settle()

        val reopened = vm.uiState.value
        assertEquals(mapOf(first.id to "Private first title"), reopened.editedTitles)
        assertTrue(reopened.selectionReviewRequired)
        val titles = persistentMapOf(first.id to "First", second.id to "Second")
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        assertNull(vm.uiState.value.exportPlan)
        vm.onIntent(OrphanRecoveryIntent.AcknowledgeSelectionChanges)
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        assertEquals(listOf("Private first title", "Second"), vm.uiState.value.exportPlan?.selections?.map { it.title })
    }

    @Test fun chosenVersionAndDraftFollowConcreteFileWhenRepresentativeChanges() = scenario { vm, gateway, settle ->
        val alternate = second.copy(originalTitle = "Alternate", index = 12)
        val variants = persistentListOf(
            OrphanTextCopy(first.id, null, "First copy", null, 1, 2),
            OrphanTextCopy(alternate.id, "alternate", "Alternate", null, 1, 12),
        )
        gateway.catalog = listOf(first.copy(copies = variants, contentDigest = "digest"))
        vm.onIntent(OrphanRecoveryIntent.Reload)
        settle()
        vm.onIntent(OrphanRecoveryIntent.ChooseVersion(first.id, alternate.id))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("Draft specific to the second image subject"))
        vm.onIntent(OrphanRecoveryIntent.AccessChanged(false))
        gateway.catalog = listOf(alternate.copy(copies = variants, contentDigest = "digest"))
        vm.onIntent(OrphanRecoveryIntent.AccessChanged(true))
        settle()

        val current = vm.uiState.value
        assertEquals(setOf(alternate.id), current.selected)
        assertEquals(mapOf(alternate.id to alternate.id), current.versionChoices)
        assertEquals("Draft specific to the second image subject", current.editedTitles[alternate.id])
        assertFalse(current.selectionReviewRequired)
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(persistentMapOf()))
        settle()
        val selected = checkNotNull(vm.uiState.value.exportPlan).selections.single()
        assertEquals(alternate.id, selected.source.id)
        assertEquals(12, selected.source.index)
        assertEquals("Draft specific to the second image subject", selected.title)
    }

    @Test fun removedConcreteVersionNeverFallsBackToAnIdenticalRepresentative() = scenario { vm, gateway, settle ->
        val previous = OrphanTextId("private-history.nb")
        gateway.catalog = listOf(first.copy(originalTitle = "First title", copies = persistentListOf(
            OrphanTextCopy(first.id, null, "First title", null, 1, 2),
            OrphanTextCopy(previous, "old-subject", "Old title", null, 1, 7),
        ), contentDigest = "digest"), second)
        vm.onIntent(OrphanRecoveryIntent.Reload)
        settle()
        vm.onIntent(OrphanRecoveryIntent.ChooseVersion(first.id, previous))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("Draft for another chapter identity"))
        vm.onIntent(OrphanRecoveryIntent.Back)
        gateway.catalog = listOf(first.copy(originalTitle = "First title"), second)
        vm.onIntent(OrphanRecoveryIntent.Reload)
        settle()

        val invalidated = vm.uiState.value
        assertEquals(setOf(first.id), invalidated.invalidVersions)
        assertTrue(first.id in invalidated.selected)
        assertNull(invalidated.chosen(invalidated.texts.first()))
        assertTrue(invalidated.editedTitles.isEmpty())
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        vm.onIntent(OrphanRecoveryIntent.Targets)
        vm.onIntent(OrphanRecoveryIntent.Restore)
        vm.onIntent(OrphanRecoveryIntent.AcknowledgeSelectionChanges)
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(persistentMapOf(second.id to "Second")))
        settle()
        assertNull(vm.uiState.value.preview)
        assertNull(vm.uiState.value.exportPlan)
        assertEquals(0, gateway.restoreCalls)

        vm.onIntent(OrphanRecoveryIntent.ChooseVersion(first.id, first.id))
        settle()
        assertEquals(first.id, vm.uiState.value.source?.id)
        assertTrue(vm.uiState.value.invalidVersions.isEmpty())
        vm.onIntent(OrphanRecoveryIntent.Back)
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(persistentMapOf(second.id to "Second")))
        settle()
        assertEquals("First title", vm.uiState.value.exportPlan?.selections?.first()?.title)
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
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(titles))
        settle()
        vm.onIntent(OrphanRecoveryIntent.ExportTo("export-directory"))
        settle()
        assertTrue(vm.uiState.value.busy)
        vm.onIntent(OrphanRecoveryIntent.Cancel)
        settle()
        assertFalse(vm.uiState.value.busy)
        assertEquals(setOf(first.id, second.id), vm.uiState.value.selected)
        assertEquals(R.string.feature_orphan_recovery_cancelled, vm.uiState.value.message)
        vm.onIntent(OrphanRecoveryIntent.Preview(first))
        settle()
        vm.onIntent(OrphanRecoveryIntent.AccessChanged(false))
        assertNull(vm.uiState.value.preview)
        assertEquals("book", vm.uiState.value.bookUrl)
        assertTrue(vm.uiState.value.accessBlocked)
        vm.onIntent(OrphanRecoveryIntent.ExportTo("export-directory"))
        vm.onIntent(OrphanRecoveryIntent.AccessChanged(true))
        settle()
        assertFalse(vm.uiState.value.accessBlocked)
        assertEquals("export-directory", vm.uiState.value.exportDirectory)
        assertEquals(setOf(first.id, second.id), vm.uiState.value.selected)
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

    @Test fun indexRowsFoldMultipleBodiesAndEachVersionKeepsItsSelectionTitleAndExportOrder() = scenario { vm, gateway, settle ->
        val historical = OrphanTextId("another-copy.nb")
        val a = first.copy(originalTitle = "A", copies = persistentListOf(
            OrphanTextCopy(first.id, null, "A", null, 1, first.index),
            OrphanTextCopy(historical, null, "A 的另一个来源", null, 1, first.index),
        ), contentDigest = "digest")
        val b = second.copy(index = first.index, originalTitle = "B", contentDigest = "another-digest")
        val c = first.copy(id = OrphanTextId("other-index.nb"), index = 5, originalTitle = "C", contentDigest = "digest")
        val unknown = second.copy(id = OrphanTextId("unknown.nb"), originalTitle = "Unknown")
        gateway.catalog = listOf(a, b, c, unknown)
        vm.onIntent(OrphanRecoveryIntent.Reload)
        settle()

        fun rows() = vm.uiState.value.listRows()
        fun group() = rows().filterIsInstance<RecoveryListRow.Index>().single()
        fun selected() = vm.uiState.value.selected
        assertEquals(listOf(a, b), group().versions)
        assertEquals(3, group().copies)
        assertEquals(listOf(c, unknown), rows().filterIsInstance<RecoveryListRow.Version>().map { it.text })
        assertEquals(4, selected().size)
        assertEquals(ToggleableState.On, group().selection(selected()))

        vm.onIntent(OrphanRecoveryIntent.ToggleIndexSelection(2))
        assertEquals(ToggleableState.Off, group().selection(selected()))
        assertEquals(setOf(c.id, unknown.id), selected())
        vm.onIntent(OrphanRecoveryIntent.ToggleIndex(2))
        assertEquals(listOf(a, b, c, unknown), rows().filterIsInstance<RecoveryListRow.Version>().map { it.text })
        vm.onIntent(OrphanRecoveryIntent.Toggle(a.id))
        assertEquals(ToggleableState.Indeterminate, group().selection(selected()))
        vm.onIntent(OrphanRecoveryIntent.ToggleIndexSelection(2))
        assertEquals(ToggleableState.On, group().selection(selected()))
        vm.onIntent(OrphanRecoveryIntent.ChooseVersion(a.id, historical))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("A 各自编辑"))
        vm.onIntent(OrphanRecoveryIntent.Back)
        vm.onIntent(OrphanRecoveryIntent.Preview(b))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("B 各自编辑"))
        vm.onIntent(OrphanRecoveryIntent.Back)
        vm.onIntent(OrphanRecoveryIntent.ToggleReorder)
        vm.onIntent(OrphanRecoveryIntent.Move(b.id, -1))
        assertEquals(listOf(b, a, c, unknown), rows().filterIsInstance<RecoveryListRow.Version>().map { it.text })

        vm.onIntent(OrphanRecoveryIntent.AccessChanged(false))
        assertTrue(rows().isEmpty())
        assertTrue(vm.uiState.value.editedTitles.isEmpty())
        vm.onIntent(OrphanRecoveryIntent.AccessChanged(true))
        settle()
        assertEquals(mapOf(a.id to historical), vm.uiState.value.versionChoices)
        assertEquals(setOf(a.id, b.id, c.id, unknown.id), selected())
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(persistentMapOf()))
        settle()
        val picks = checkNotNull(vm.uiState.value.exportPlan).selections
        assertEquals(listOf(b.id, historical, c.id, unknown.id), picks.map { it.source.id })
        assertEquals(listOf("B 各自编辑", "A 各自编辑", "C", "Unknown"), picks.map { it.title })
    }

    @Test fun identicalContentAtAnotherIndexDoesNotBecomeTheLostExplicitVersion() = scenario { vm, gateway, settle ->
        val copyId = OrphanTextId("exact-selected.nb")
        val original = first.copy(copies = persistentListOf(
            OrphanTextCopy(first.id, null, "Original", null, 1, first.index),
            OrphanTextCopy(copyId, null, "Historical", null, 1, first.index),
        ), contentDigest = "digest")
        val otherIndex = second.copy(index = 12, originalTitle = "Other index", contentDigest = "digest")
        gateway.catalog = listOf(original, otherIndex)
        vm.onIntent(OrphanRecoveryIntent.Reload)
        settle()
        vm.onIntent(OrphanRecoveryIntent.ChooseVersion(original.id, copyId))
        settle()
        vm.onIntent(OrphanRecoveryIntent.EditTitle("Only for the chosen file"))
        vm.onIntent(OrphanRecoveryIntent.Back)
        gateway.catalog = listOf(otherIndex)
        vm.onIntent(OrphanRecoveryIntent.Reload)
        settle()

        assertTrue(vm.uiState.value.selectionReviewRequired)
        assertTrue(vm.uiState.value.editedTitles.isEmpty())
        assertTrue(vm.uiState.value.versionChoices.isEmpty())
        assertEquals(otherIndex, vm.uiState.value.chosen(otherIndex))
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(persistentMapOf()))
        settle()
        assertNull(vm.uiState.value.exportPlan)
        vm.onIntent(OrphanRecoveryIntent.AcknowledgeSelectionChanges)
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(persistentMapOf()))
        settle()
        assertEquals("Other index", vm.uiState.value.exportPlan?.selections?.single()?.title)
    }

    @Test fun committedResultsStaySuccessfulEvenWhenTheirJobHasBeenCancelled() = scenario { vm, gateway, settle ->
        gateway.cancelAfterCommit = true
        vm.onIntent(OrphanRecoveryIntent.PrepareExport(persistentMapOf(first.id to "First", second.id to "Second")))
        settle()
        vm.onIntent(OrphanRecoveryIntent.ExportTo("export-directory"))
        settle()
        assertFalse(vm.uiState.value.busy)
        assertEquals("local-book", vm.uiState.value.recoveredBookUrl)
        assertEquals("test.txt", vm.uiState.value.exportResult?.fileName)

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
        var catalog = listOf(first, second)
        var selected = listOf<LocalRecoverySelection>()
        var restoreCalls = 0
        var verifiedTarget: RecoveryTarget? = null
        var suspendImport = false
        var delayPreview = false
        var pendingPreview: Continuation<OrphanTextPreview>? = null
        var failRestore = false
        var cancelAfterCommit = false

        val digests = mutableMapOf<OrphanTextId, String>()

        override suspend fun scanBook(bookUrl: String) = OrphanTextCatalog("Book", catalog)
        override suspend fun preview(bookUrl: String, source: OrphanText): OrphanTextPreview =
            if (delayPreview) suspendCoroutine { pendingPreview = it }
            else OrphanTextPreview("cached", digests[source.id] ?: "digest")
        override suspend fun targets(bookUrl: String, source: OrphanText) = listOf(target)
        override suspend fun compare(
            bookUrl: String, source: OrphanText, previewDigest: String, target: RecoveryTarget, newTitle: String?,
        ) = OrphanTextComparison(
            target, RecoveryTargetVersion("current", null), "current", RecoveryDifference.Different, null,
            titleChange = newTitle?.let { RecoveryTitleChange(target.copy(title = it), RecoveryTargetVersion(null, null)) },
        )

        var hasImages = false

        override suspend fun inspectExport(bookUrl: String, selections: List<LocalRecoverySelection>): RecoveryExportPlan =
            RecoveryExportPlan(selections.toImmutableList(), hasImages)

        override suspend fun export(
            bookUrl: String, plan: RecoveryExportPlan, format: RecoveryExportFormat,
            directory: String, addToShelf: Boolean, onProgress: (Int, Int) -> Unit,
        ): RecoveryExportResult {
            if (suspendImport) awaitCancellation()
            selected = plan.selections
            if (cancelAfterCommit) currentCoroutineContext().cancel()
            return RecoveryExportResult("test.txt", 0, "local-book", true)
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

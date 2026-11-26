package io.legado.app.feature.orphanrecovery

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.domain.gateway.OrphanCacheRecoveryGateway
import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.OrphanRecoveryException
import io.legado.app.domain.model.OrphanText
import io.legado.app.domain.model.OrphanTextId
import io.legado.app.domain.model.OrphanTextPreview
import io.legado.app.domain.model.RecoveryActivity
import io.legado.app.domain.model.RecoveryDifference
import io.legado.app.domain.model.RecoveryLine
import io.legado.app.domain.model.RecoveryProblem
import io.legado.app.domain.model.RecoveryReaderRequest
import io.legado.app.domain.model.RecoveryTarget
import io.legado.app.domain.model.RecoveryTargetVersion
import io.legado.app.domain.model.RecoveryTitleChange
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal enum class RecoveryPane { List, Preview, Targets, Compare }
internal enum class RecoveryTextTab { Difference, Source, Current }

@Stable
internal data class RecoveryComparison(
    val target: RecoveryTarget,
    val version: RecoveryTargetVersion,
    val currentText: String?,
    val difference: RecoveryDifference,
    val lines: ImmutableList<RecoveryLine>?,
    val hasShadowedCopy: Boolean,
    val titleChange: RecoveryTitleChange?,
)

@Stable
internal data class OrphanRecoveryState(
    val privateBookUrls: ImmutableSet<String>? = null,
    val bookUrl: String? = null,
    val bookName: String = "",
    val pane: RecoveryPane = RecoveryPane.List,
    val loaded: Boolean = false,
    val texts: ImmutableList<OrphanText> = persistentListOf(),
    val hasDuplicateOldIndices: Boolean = false,
    val selected: ImmutableSet<OrphanTextId> = persistentSetOf(),
    val editedTitles: ImmutableMap<OrphanTextId, String> = persistentMapOf(),
    val reorder: Boolean = false,
    val source: OrphanText? = null,
    val preview: OrphanTextPreview? = null,
    val targets: ImmutableList<RecoveryTarget> = persistentListOf(),
    val query: String = "",
    val comparison: RecoveryComparison? = null,
    val titleDraft: String? = null,
    val tab: RecoveryTextTab = RecoveryTextTab.Source,
    val confirm: Boolean = false,
    val busy: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    @param:StringRes val message: Int? = null,
    val recoveredBookUrl: String? = null,
    val restoredTarget: RecoveryTarget? = null,
    val readerRequest: RecoveryReaderRequest? = null,
) {
    fun canRecover(url: String): Boolean = privateBookUrls?.let { url !in it } == true
}

internal sealed interface OrphanRecoveryIntent {
    data class Open(val bookUrl: String) : OrphanRecoveryIntent
    data object Back : OrphanRecoveryIntent
    data object Close : OrphanRecoveryIntent
    data object Cancel : OrphanRecoveryIntent
    data object Reload : OrphanRecoveryIntent
    data object ToggleAll : OrphanRecoveryIntent
    data object ToggleReorder : OrphanRecoveryIntent
    data class Toggle(val id: OrphanTextId) : OrphanRecoveryIntent
    data class Move(val id: OrphanTextId, val delta: Int) : OrphanRecoveryIntent
    data class Preview(val text: OrphanText) : OrphanRecoveryIntent
    data class EditTitle(val title: String) : OrphanRecoveryIntent
    data class RecoverLocal(val provisionalTitles: ImmutableMap<OrphanTextId, String>) : OrphanRecoveryIntent
    data object Targets : OrphanRecoveryIntent
    data class Query(val text: String) : OrphanRecoveryIntent
    data class Target(val target: RecoveryTarget) : OrphanRecoveryIntent
    data class Tab(val tab: RecoveryTextTab) : OrphanRecoveryIntent
    data class UseRestoredTitle(val enabled: Boolean) : OrphanRecoveryIntent
    data class ChangeTitleDraft(val value: String) : OrphanRecoveryIntent
    data object ReviewTitle : OrphanRecoveryIntent
    data object RequestReplace : OrphanRecoveryIntent
    data object DismissConfirmation : OrphanRecoveryIntent
    data object Restore : OrphanRecoveryIntent
    data object OpenRestored : OrphanRecoveryIntent
}

internal class OrphanRecoveryViewModel(private val gateway: OrphanCacheRecoveryGateway) : ViewModel() {
    private val state = MutableStateFlow(OrphanRecoveryState())
    val uiState = state.asStateFlow()
    @Volatile private var job: Job? = null
    private val previewHashes = mutableMapOf<OrphanTextId, String>()

    init {
        viewModelScope.launch {
            gateway.privateBookUrls().catch {
                close()
                state.update { it.copy(privateBookUrls = null) }
            }.collect { urls ->
                if (state.value.bookUrl in urls) close()
                state.update { it.copy(privateBookUrls = urls.toImmutableSet()) }
            }
        }
    }

    fun onIntent(intent: OrphanRecoveryIntent) {
        val current = state.value
        when (intent) {
            OrphanRecoveryIntent.Close -> {
                close()
                return
            }
            OrphanRecoveryIntent.Cancel -> {
                job?.cancel()
                return
            }
            is OrphanRecoveryIntent.Open -> {
                if (current.bookUrl == null && current.privateBookUrls?.contains(intent.bookUrl) != true) {
                    state.update { it.copy(bookUrl = intent.bookUrl) }
                    scan(intent.bookUrl)
                }
                return
            }
            else -> Unit
        }
        val bookUrl = current.bookUrl ?: return
        if (current.busy || current.privateBookUrls?.contains(bookUrl) == true) return
        when (intent) {
            OrphanRecoveryIntent.Back -> back(current)
            OrphanRecoveryIntent.Reload -> scan(bookUrl)
            OrphanRecoveryIntent.ToggleAll -> state.update {
                val selection = if (it.selected.size == it.texts.size) persistentSetOf()
                else it.texts.map { text -> text.id }.toImmutableSet()
                if (selection == it.selected) it
                else it.copy(selected = selection, recoveredBookUrl = null)
            }
            OrphanRecoveryIntent.ToggleReorder -> state.update { it.copy(reorder = !it.reorder) }
            is OrphanRecoveryIntent.Toggle -> state.update {
                val selection = if (intent.id in it.selected) it.selected - intent.id else it.selected + intent.id
                it.copy(selected = selection.toImmutableSet(), recoveredBookUrl = null)
            }
            is OrphanRecoveryIntent.Move -> move(current, intent)
            is OrphanRecoveryIntent.Preview -> perform {
                val preview = gateway.preview(bookUrl, intent.text)
                if (job !== coroutineContext[Job]) return@perform
                previewHashes[intent.text.id] = preview.digest
                state.update { it.copy(pane = RecoveryPane.Preview, source = intent.text, preview = preview, restoredTarget = null) }
            }
            is OrphanRecoveryIntent.EditTitle -> {
                val source = current.source ?: return
                val titles = current.editedTitles + (source.id to intent.title.take(140))
                state.update {
                    if (titles == it.editedTitles) it
                    else it.copy(editedTitles = titles.toImmutableMap(), recoveredBookUrl = null)
                }
            }
            is OrphanRecoveryIntent.RecoverLocal -> recoverLocal(bookUrl, current, intent.provisionalTitles)
            OrphanRecoveryIntent.Targets -> {
                val source = current.source ?: return
                perform {
                    val targets = gateway.targets(bookUrl, source).toImmutableList()
                    if (job !== coroutineContext[Job]) return@perform
                    state.update { it.copy(pane = RecoveryPane.Targets, targets = targets, query = "") }
                }
            }
            is OrphanRecoveryIntent.Query -> state.update { it.copy(query = intent.text) }
            is OrphanRecoveryIntent.Target -> compare(bookUrl, current, intent.target)
            is OrphanRecoveryIntent.Tab -> state.update { it.copy(tab = intent.tab) }
            is OrphanRecoveryIntent.UseRestoredTitle -> {
                val source = current.source ?: return
                val comparison = current.comparison ?: return
                val title = if (intent.enabled) (source.originalTitle ?: source.suggestedTitle).orEmpty() else null
                state.update {
                    it.copy(titleDraft = title, comparison = comparison.copy(titleChange = null), confirm = false, message = null)
                }
            }
            is OrphanRecoveryIntent.ChangeTitleDraft -> {
                val comparison = current.comparison ?: return
                if (current.titleDraft != null) state.update {
                    it.copy(titleDraft = intent.value.take(140), comparison = comparison.copy(titleChange = null), confirm = false)
                }
            }
            OrphanRecoveryIntent.ReviewTitle -> {
                val comparison = current.comparison ?: return
                val newTitle = current.titleDraft?.trim()?.takeIf { it.isNotEmpty() && it != comparison.target.title }
                    ?: return
                compare(bookUrl, current, comparison.target, newTitle)
            }
            OrphanRecoveryIntent.RequestReplace -> {
                val comparison = current.comparison ?: return
                if (comparison.titleChange != null ||
                    current.titleDraft == null && comparison.difference == RecoveryDifference.Different
                ) state.update { it.copy(confirm = true) }
            }
            OrphanRecoveryIntent.DismissConfirmation -> state.update { it.copy(confirm = false) }
            OrphanRecoveryIntent.Restore -> restore(bookUrl, current)
            OrphanRecoveryIntent.OpenRestored -> {
                val target = current.restoredTarget ?: return
                val digest = current.preview?.digest ?: return
                perform {
                    gateway.readRestoredChapter(bookUrl, target, digest)
                    if (job === coroutineContext[Job]) {
                        state.update { it.copy(readerRequest = RecoveryReaderRequest(bookUrl, target, digest)) }
                    }
                }
            }
        }
    }

    private fun scan(bookUrl: String) = perform {
        state.update { it.copy(loaded = false) }
        val catalog = gateway.scanBook(bookUrl)
        if (job !== coroutineContext[Job]) return@perform
        previewHashes.clear()
        val oldIndices = catalog.texts.mapNotNull(OrphanText::index)
        state.update {
            it.copy(
                bookName = catalog.bookName, loaded = true, pane = RecoveryPane.List,
                texts = catalog.texts.toImmutableList(),
                hasDuplicateOldIndices = oldIndices.size != oldIndices.toSet().size,
                selected = catalog.texts.map(OrphanText::id).toImmutableSet(),
                editedTitles = persistentMapOf(), source = null, preview = null,
                recoveredBookUrl = null, restoredTarget = null, readerRequest = null,
            )
        }
    }

    private fun move(current: OrphanRecoveryState, intent: OrphanRecoveryIntent.Move) {
        val from = current.texts.indexOfFirst { it.id == intent.id }
        val to = from + intent.delta
        if (from < 0 || to !in current.texts.indices) return
        val reordered = current.texts.toMutableList().apply { add(to, removeAt(from)) }
        state.update { it.copy(texts = reordered.toImmutableList(), recoveredBookUrl = null) }
    }

    private fun recoverLocal(bookUrl: String, current: OrphanRecoveryState, titles: ImmutableMap<OrphanTextId, String>) {
        val selections = current.texts.filter { it.id in current.selected }.map {
            LocalRecoverySelection(
                it,
                current.editedTitles[it.id]?.trim()?.takeIf(String::isNotBlank) ?: it.originalTitle ?: it.suggestedTitle ?: titles.getValue(it.id),
                previewHashes[it.id],
            )
        }
        if (selections.isEmpty()) return
        perform {
            val task = coroutineContext[Job]
            val result = gateway.recoverLocal(bookUrl, selections) { count, total ->
                state.update { if (job === task) it.copy(progress = count to total) else it }
            }
            if (job === coroutineContext[Job]) {
                state.update { it.copy(recoveredBookUrl = result, message = R.string.feature_orphan_recovery_local_done) }
            }
        }
    }

    private fun compare(
        bookUrl: String,
        current: OrphanRecoveryState,
        target: RecoveryTarget,
        newTitle: String? = null,
    ) {
        val source = current.source ?: return
        val preview = current.preview ?: return
        perform(keepComparisonOnError = newTitle != null) {
            val result = gateway.compare(bookUrl, source, preview.digest, target, newTitle)
            if (job !== coroutineContext[Job]) return@perform
            state.update {
                it.copy(
                    pane = RecoveryPane.Compare,
                    comparison = RecoveryComparison(
                        result.target, result.version, result.currentText, result.difference, result.lines?.toImmutableList(),
                        result.hasShadowedCopy, result.titleChange,
                    ),
                    titleDraft = result.titleChange?.renamedTarget?.title,
                    tab = if (result.difference == RecoveryDifference.Different) RecoveryTextTab.Difference else RecoveryTextTab.Source,
                )
            }
        }
    }

    private fun restore(bookUrl: String, current: OrphanRecoveryState) {
        val source = current.source ?: return
        val preview = current.preview ?: return
        val comparison = current.comparison ?: return
        if (current.titleDraft != null && comparison.titleChange == null) return
        if (comparison.titleChange == null && comparison.difference == RecoveryDifference.Identical) return
        if ((comparison.titleChange != null || comparison.difference == RecoveryDifference.Different) &&
            !current.confirm
        ) return
        state.update { it.copy(confirm = false) }
        perform {
            val target = gateway.restoreChapter(
                bookUrl, source, preview.digest, comparison.target, comparison.version, comparison.titleChange,
            )
            if (job !== coroutineContext[Job]) return@perform
            state.update {
                it.copy(
                    pane = RecoveryPane.Preview, comparison = null, restoredTarget = target, titleDraft = null,
                    message = R.string.feature_orphan_recovery_restore_done,
                )
            }
        }
    }

    private fun back(current: OrphanRecoveryState) {
        when (current.pane) {
            RecoveryPane.List -> close()
            RecoveryPane.Preview -> state.update {
                it.copy(pane = RecoveryPane.List, source = null, preview = null, restoredTarget = null, message = null)
            }
            RecoveryPane.Targets -> state.update { it.copy(pane = RecoveryPane.Preview, targets = persistentListOf(), message = null) }
            RecoveryPane.Compare -> state.update {
                it.copy(pane = RecoveryPane.Targets, comparison = null, confirm = false, message = null)
            }
        }
    }

    private fun close() {
        val running = job
        job = null
        running?.cancel()
        previewHashes.clear()
        state.update { OrphanRecoveryState(privateBookUrls = it.privateBookUrls) }
    }

    private fun perform(keepComparisonOnError: Boolean = false, block: suspend () -> Unit) {
        if (job != null) return
        state.update { it.copy(busy = true, progress = null, message = null) }
        val task = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                if (job === coroutineContext[Job]) state.update {
                    it.copy(message = R.string.feature_orphan_recovery_cancelled)
                }
                throw cancelled
            } catch (error: Exception) {
                if (job === coroutineContext[Job]) state.update {
                    it.copy(
                        message = when ((error as? OrphanRecoveryException)?.problem) {
                            RecoveryProblem.PrivateBook -> R.string.feature_orphan_recovery_private
                            RecoveryProblem.BusyBook -> when ((error as OrphanRecoveryException).activity) {
                                RecoveryActivity.Reader -> R.string.feature_orphan_recovery_busy_reader
                                RecoveryActivity.ReadAloud -> R.string.feature_orphan_recovery_busy_aloud
                                RecoveryActivity.ContentRequest -> R.string.feature_orphan_recovery_busy_request
                                RecoveryActivity.Admission -> R.string.feature_orphan_recovery_busy_admission
                                RecoveryActivity.Waiting -> R.string.feature_orphan_recovery_busy_waiting
                                RecoveryActivity.Running -> R.string.feature_orphan_recovery_busy_running
                                RecoveryActivity.Retrying -> R.string.feature_orphan_recovery_busy_retrying
                                null -> R.string.feature_orphan_recovery_busy_book
                            }
                            RecoveryProblem.Changed -> R.string.feature_orphan_recovery_changed
                            RecoveryProblem.QueryFailed -> R.string.feature_orphan_recovery_query_failed
                            RecoveryProblem.ScanFailed -> R.string.feature_orphan_recovery_scan_failed
                            RecoveryProblem.ResultMissing -> R.string.feature_orphan_recovery_result_missing
                            RecoveryProblem.ResultChanged -> R.string.feature_orphan_recovery_result_changed
                            RecoveryProblem.InvalidTitle -> R.string.feature_orphan_recovery_invalid_title
                            RecoveryProblem.TitleDestinationConflict -> R.string.feature_orphan_recovery_title_path_conflict
                            RecoveryProblem.TitleAssetsConflict -> R.string.feature_orphan_recovery_title_assets_conflict
                            RecoveryProblem.Unavailable -> R.string.feature_orphan_recovery_unavailable
                            else -> R.string.feature_orphan_recovery_error
                        },
                        comparison = if (keepComparisonOnError) it.comparison else null,
                        titleDraft = if (keepComparisonOnError) it.titleDraft else null,
                        pane = if (it.pane == RecoveryPane.Compare && !keepComparisonOnError) RecoveryPane.Targets else it.pane,
                    )
                }
            } finally {
                if (job === coroutineContext[Job]) {
                    job = null
                    state.update { it.copy(busy = false, progress = null) }
                }
            }
        }
        job = task
        task.start()
    }
}

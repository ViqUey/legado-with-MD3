package io.legado.app.feature.orphanrecovery

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.domain.gateway.OrphanCacheRecoveryGateway
import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.RecoveryExportPlan
import io.legado.app.domain.model.RecoveryExportFormat
import io.legado.app.domain.model.RecoveryExportResult
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
    val bookUrl: String? = null,
    val bookName: String = "",
    val pane: RecoveryPane = RecoveryPane.List,
    val loaded: Boolean = false,
    val texts: ImmutableList<OrphanText> = persistentListOf(),
    val unreadable: Int = 0,
    val versionChoices: ImmutableMap<OrphanTextId, OrphanTextId> = persistentMapOf(),
    val invalidVersions: ImmutableSet<OrphanTextId> = persistentSetOf(),
    val selectionReviewRequired: Boolean = false,
    val accessBlocked: Boolean = false,
    val exportPlan: RecoveryExportPlan? = null,
    val exportFormat: RecoveryExportFormat? = null,
    val exportManuallyChosen: Boolean = false,
    val exportAddToShelf: Boolean = true,
    val showImageFormatHint: Boolean = false,
    val exportDirectory: String? = null,
    val exportResult: RecoveryExportResult? = null,
    val hasDuplicateOldIndices: Boolean = false,
    val expandedIndices: ImmutableSet<Int> = persistentSetOf(),
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
    fun chosen(group: OrphanText): OrphanText? {
        if (group.id in invalidVersions) return null
        val id = versionChoices[group.id] ?: return group
        return group.version(id)
    }
}

private fun OrphanText.version(id: OrphanTextId): OrphanText? {
    if (id == this.id) return this
    val copy = copies.firstOrNull { it.id == id } ?: return null
    return copy(
        id = copy.id, titleHash = copy.titleHash, index = copy.index, originalTitle = copy.originalTitle,
        suggestedTitle = copy.suggestedTitle, modified = copy.modified,
    )
}

@Stable
internal sealed interface RecoveryListRow {
    @Stable
    data class Index(val index: Int, val versions: ImmutableList<OrphanText>) : RecoveryListRow {
        val copies: Int get() = versions.sumOf { it.copies.size.coerceAtLeast(1) }

        fun selection(selected: Set<OrphanTextId>): ToggleableState = when (versions.count { it.id in selected }) {
            versions.size -> ToggleableState.On
            0 -> ToggleableState.Off
            else -> ToggleableState.Indeterminate
        }
    }

    @Stable
    data class Version(val text: OrphanText, val position: Int, val nested: Boolean = false) : RecoveryListRow
}

internal fun OrphanRecoveryState.listRows(): List<RecoveryListRow> {
    if (reorder) return texts.mapIndexed { position, text -> RecoveryListRow.Version(text, position) }
    val indexed = texts.withIndex().filter { it.value.index != null }.groupBy { it.value.index }
    return buildList {
        texts.forEachIndexed { position, text ->
            val index = text.index
            val versions = indexed[index]
            if (index == null || versions == null || versions.size == 1) {
                add(RecoveryListRow.Version(text, position))
            } else if (versions.first().index == position) {
                add(RecoveryListRow.Index(index, versions.map { it.value }.toImmutableList()))
                if (index in expandedIndices) {
                    versions.forEach { add(RecoveryListRow.Version(it.value, it.index, nested = true)) }
                }
            }
        }
    }
}

internal sealed interface OrphanRecoveryIntent {
    data class Open(val bookUrl: String) : OrphanRecoveryIntent
    data object Back : OrphanRecoveryIntent
    data object Close : OrphanRecoveryIntent
    data object Cancel : OrphanRecoveryIntent
    data object Reload : OrphanRecoveryIntent
    data object AcknowledgeSelectionChanges : OrphanRecoveryIntent
    data object ToggleAll : OrphanRecoveryIntent
    data object ToggleReorder : OrphanRecoveryIntent
    data class ToggleIndex(val index: Int) : OrphanRecoveryIntent
    data class ToggleIndexSelection(val index: Int) : OrphanRecoveryIntent
    data class Toggle(val id: OrphanTextId) : OrphanRecoveryIntent
    data class Move(val id: OrphanTextId, val delta: Int) : OrphanRecoveryIntent
    data class Preview(val text: OrphanText) : OrphanRecoveryIntent
    data class EditTitle(val title: String) : OrphanRecoveryIntent
    data class PrepareExport(val provisionalTitles: ImmutableMap<OrphanTextId, String>) : OrphanRecoveryIntent
    data class ChooseVersion(val group: OrphanTextId, val version: OrphanTextId) : OrphanRecoveryIntent
    data class AccessChanged(val granted: Boolean) : OrphanRecoveryIntent
    data class ExportFormat(val format: RecoveryExportFormat) : OrphanRecoveryIntent
    data class ExportAddToShelf(val enabled: Boolean) : OrphanRecoveryIntent
    data class ExportTo(val directory: String) : OrphanRecoveryIntent
    data object DismissExport : OrphanRecoveryIntent
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
    private data class GroupSelection(
        val id: OrphanTextId,
        val members: Set<OrphanTextId>,
        val digest: String?,
        val index: Int?,
        val selected: Boolean,
        val unconfirmed: Boolean,
    ) {
        fun matches(group: OrphanText): Boolean =
            group.id in members || group.copies.any { it.id in members } ||
                digest != null && index == group.index && digest == group.contentDigest
    }

    private data class Version(val id: OrphanTextId, val bytes: Long, val modified: Long, val digest: String) {
        fun matches(item: OrphanText) = id == item.id && bytes == item.bytes && modified == item.modified
    }

    private data class TitleDraft(val version: Version, val text: String)

    // Retained only for the open recovery session. Re-authentication revalidates each draft's bytes.
    private var suspendedGroups: List<GroupSelection>? = null
    private var suspendedReviewRequired = false
    private val chosenVersions = mutableMapOf<OrphanTextId, Version>()
    private val drafts = mutableMapOf<OrphanTextId, TitleDraft>()
    private val previewHashes = mutableMapOf<OrphanTextId, String>()

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
                if (current.bookUrl == null) {
                    state.update { it.copy(bookUrl = intent.bookUrl) }
                    scan(intent.bookUrl)
                }
                return
            }
            is OrphanRecoveryIntent.AccessChanged -> {
                val url = current.bookUrl ?: return
                if (!intent.granted) suspendAccess()
                else if (current.accessBlocked) {
                    state.update { it.copy(accessBlocked = false) }
                    scan(url)
                }
                return
            }
            is OrphanRecoveryIntent.ExportTo -> {
                val url = current.bookUrl ?: return
                state.update { it.copy(exportDirectory = intent.directory) }
                if (!current.accessBlocked && !current.busy) export(url, state.value)
                return
            }
            else -> Unit
        }
        val bookUrl = current.bookUrl ?: return
        if (current.busy || current.accessBlocked) return
        when (intent) {
            OrphanRecoveryIntent.Close, OrphanRecoveryIntent.Cancel, is OrphanRecoveryIntent.Open,
            is OrphanRecoveryIntent.AccessChanged, is OrphanRecoveryIntent.ExportTo -> Unit
            OrphanRecoveryIntent.Back -> back(current)
            OrphanRecoveryIntent.Reload -> scan(bookUrl)
            OrphanRecoveryIntent.AcknowledgeSelectionChanges -> state.update { it.copy(selectionReviewRequired = false) }
            OrphanRecoveryIntent.ToggleAll -> state.update {
                val selection = if (it.selected.size == it.texts.size) persistentSetOf()
                else it.texts.map { text -> text.id }.toImmutableSet()
                if (selection == it.selected) it
                else it.copy(selected = selection, recoveredBookUrl = null, exportResult = null)
            }
            is OrphanRecoveryIntent.ToggleIndex -> state.update {
                val expanded = if (intent.index in it.expandedIndices) it.expandedIndices - intent.index
                else it.expandedIndices + intent.index
                it.copy(expandedIndices = expanded.toImmutableSet())
            }
            is OrphanRecoveryIntent.ToggleIndexSelection -> state.update {
                val ids = it.texts.filter { text -> text.index == intent.index }.map(OrphanText::id)
                if (ids.isEmpty()) return@update it
                val selection = if (it.selected.containsAll(ids)) it.selected - ids else it.selected + ids
                it.copy(selected = selection.toImmutableSet(), exportPlan = null, recoveredBookUrl = null, exportResult = null)
            }
            OrphanRecoveryIntent.ToggleReorder -> state.update { it.copy(reorder = !it.reorder) }
            is OrphanRecoveryIntent.Toggle -> state.update {
                val selection = if (intent.id in it.selected) it.selected - intent.id else it.selected + intent.id
                it.copy(selected = selection.toImmutableSet(), recoveredBookUrl = null)
            }
            is OrphanRecoveryIntent.Move -> move(current, intent)
            is OrphanRecoveryIntent.Preview -> {
                val source = current.texts.firstNotNullOfOrNull { current.chosen(it)?.takeIf { item -> item.id == intent.text.id } }
                    ?: return
                openPreview(bookUrl, source)
            }
            is OrphanRecoveryIntent.EditTitle -> {
                val source = current.source ?: return
                val preview = current.preview ?: return
                val title = intent.title.take(140)
                drafts[source.id] = TitleDraft(Version(source.id, source.bytes, source.modified, preview.digest), title)
                val titles = current.editedTitles + (source.id to title)
                state.update {
                    if (titles == it.editedTitles) it
                    else it.copy(editedTitles = titles.toImmutableMap(), recoveredBookUrl = null)
                }
            }
            is OrphanRecoveryIntent.PrepareExport -> prepareExport(bookUrl, current, intent.provisionalTitles)
            is OrphanRecoveryIntent.ChooseVersion -> {
                val group = current.texts.firstOrNull { it.id == intent.group } ?: return
                val source = group.version(intent.version) ?: return
                openPreview(bookUrl, source, group.id)
            }
            is OrphanRecoveryIntent.ExportFormat -> state.update {
                it.copy(
                    exportFormat = intent.format, exportManuallyChosen = true,
                    showImageFormatHint = intent.format == RecoveryExportFormat.Txt &&
                        it.exportFormat == RecoveryExportFormat.Epub && it.exportPlan?.containsImages == true,
                )
            }
            is OrphanRecoveryIntent.ExportAddToShelf -> state.update { it.copy(exportAddToShelf = intent.enabled) }
            OrphanRecoveryIntent.DismissExport -> state.update { it.copy(exportPlan = null, showImageFormatHint = false) }
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

    private fun OrphanText.members(): Set<OrphanTextId> = copies.mapTo(mutableSetOf(id)) { it.id }

    private fun OrphanRecoveryState.groupSelections() = texts.map {
        GroupSelection(it.id, it.members(), it.contentDigest, it.index, it.id in selected, it.id in invalidVersions)
    }

    private fun openPreview(bookUrl: String, source: OrphanText, group: OrphanTextId? = null) = perform {
        val preview = gateway.preview(bookUrl, source)
        if (job !== coroutineContext[Job]) return@perform
        val originalDigest = previewHashes[source.id]
        if (group == null && originalDigest != null && originalDigest != preview.digest) {
            throw OrphanRecoveryException(RecoveryProblem.Changed)
        }
        previewHashes[source.id] = preview.digest
        val version = Version(source.id, source.bytes, source.modified, preview.digest)
        val draftChanged = drafts[source.id]?.let { it.version != version } == true
        if (draftChanged) drafts.remove(source.id)
        if (group != null) chosenVersions[group] = version
        state.update {
            it.copy(
                pane = RecoveryPane.Preview, source = source, preview = preview, restoredTarget = null,
                versionChoices = if (group == null) it.versionChoices else
                    (it.versionChoices + (group to source.id)).toImmutableMap(),
                invalidVersions = if (group == null) it.invalidVersions else (it.invalidVersions - group).toImmutableSet(),
                selectionReviewRequired = it.selectionReviewRequired || draftChanged,
                editedTitles = if (draftChanged) (it.editedTitles - source.id).toImmutableMap() else it.editedTitles,
                recoveredBookUrl = if (group == null) it.recoveredBookUrl else null,
                exportResult = if (group == null) it.exportResult else null,
                exportPlan = null,
            )
        }
    }

    private fun scan(bookUrl: String) = perform {
        val before = state.value
        val resuming = suspendedGroups != null
        val previous = suspendedGroups ?: before.groupSelections()
        state.update { it.copy(loaded = false, exportPlan = null) }
        val catalog = gateway.scanBook(bookUrl)
        if (job !== coroutineContext[Job]) return@perform
        val memberGroups = buildMap {
            catalog.texts.forEach { group -> group.members().forEach { put(it, group) } }
        }
        val digestGroups = catalog.texts.mapNotNull { group -> group.contentDigest?.let { (group.index to it) to group } }.toMap()
        val verified = mutableMapOf<OrphanTextId, String?>()
        suspend fun valid(version: Version): Boolean {
            val source = memberGroups[version.id]?.version(version.id) ?: return false
            if (!version.matches(source)) return false
            if (!verified.containsKey(version.id)) {
                verified[version.id] = try {
                    gateway.preview(bookUrl, source).digest
                } catch (error: OrphanRecoveryException) {
                    if (error.problem != RecoveryProblem.Changed) throw error
                    null
                }
            }
            return verified[version.id] == version.digest
        }

        val invalid = mutableSetOf<OrphanTextId>()
        val remapped = mutableMapOf<OrphanTextId, Version>()
        val ordering = mutableMapOf<OrphanTextId, Int>()
        val selected = mutableSetOf<OrphanTextId>()
        var reviewRequired = suspendedReviewRequired || before.selectionReviewRequired
        previous.forEachIndexed { index, old ->
            val choice = chosenVersions[old.id]
            val remaining = old.members.firstNotNullOfOrNull { memberGroups[it] }
                ?: old.digest?.let { digestGroups[old.index to it] }
            val group = choice?.let { memberGroups[it.id] } ?: memberGroups[old.id] ?: remaining
            if (group == null) {
                if (old.selected && choice != null) reviewRequired = true
                return@forEachIndexed
            }
            if (choice != null) {
                val competing = remapped.putIfAbsent(group.id, choice)
                if (old.unconfirmed || !valid(choice) || competing != null && competing != choice) invalid += group.id
            }
            ordering.putIfAbsent(group.id, index)
            if (old.selected) selected += group.id
        }
        val keptDrafts = drafts.filterValues { valid(it.version) }
        if (keptDrafts.size != drafts.size) reviewRequired = true
        if (job !== coroutineContext[Job]) return@perform
        val explicitGroups = previous.filter { it.id in chosenVersions }
        drafts.keys.retainAll(keptDrafts.keys)
        chosenVersions.clear()
        chosenVersions.putAll(remapped)
        previewHashes.clear()
        (keptDrafts.values.map(TitleDraft::version) + remapped.values).forEach { version ->
            if (verified[version.id] == version.digest) previewHashes[version.id] = version.digest
        }

        val entries = if (resuming) catalog.texts.sortedBy { ordering[it.id] ?: Int.MAX_VALUE } else catalog.texts
        // An ordinary rescan selects all by default. Do not silently select a group split from an
        // explicit choice, and preserve the user's order and selection when restoring access.
        if (!resuming || previous.isEmpty()) {
            for (group in entries) {
                if (group.id in ordering || explicitGroups.none { it.matches(group) }) selected += group.id
            }
        }
        suspendedGroups = null
        suspendedReviewRequired = false
        val oldIndices = catalog.texts.mapNotNull(OrphanText::index)
        state.update {
            it.copy(
                bookName = catalog.bookName, loaded = true, pane = RecoveryPane.List,
                texts = entries.toImmutableList(),
                unreadable = catalog.unreadable,
                hasDuplicateOldIndices = oldIndices.size != oldIndices.toSet().size,
                selected = selected.toImmutableSet(),
                versionChoices = remapped.mapValues { (_, version) -> version.id }.toImmutableMap(),
                invalidVersions = invalid.toImmutableSet(),
                selectionReviewRequired = reviewRequired,
                editedTitles = keptDrafts.mapValues { (_, draft) -> draft.text }.toImmutableMap(),
                source = null, preview = null, targets = persistentListOf(), query = "", comparison = null,
                titleDraft = null, confirm = false, exportResult = null,
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

    private fun prepareExport(bookUrl: String, current: OrphanRecoveryState, titles: ImmutableMap<OrphanTextId, String>) {
        if (current.selectionReviewRequired || current.selected.any { it in current.invalidVersions }) return
        val selections = current.texts.filter { it.id in current.selected }.map { group ->
            val item = current.chosen(group) ?: return
            LocalRecoverySelection(
                item,
                current.editedTitles[item.id]?.trim()?.takeIf(String::isNotBlank)
                    ?: item.originalTitle ?: item.suggestedTitle ?: titles.getValue(group.id),
                previewHashes[item.id],
            )
        }
        if (selections.isEmpty()) return
        perform {
            val plan = gateway.inspectExport(bookUrl, selections)
            if (job === coroutineContext[Job]) state.update {
                it.copy(
                    exportPlan = plan, exportResult = null, showImageFormatHint = false,
                    exportFormat = it.exportFormat.takeIf { _ -> it.exportManuallyChosen }
                        ?: if (plan.containsImages) RecoveryExportFormat.Epub else RecoveryExportFormat.Txt,
                )
            }
        }
    }

    private fun export(bookUrl: String, current: OrphanRecoveryState) {
        val plan = current.exportPlan ?: return
        val format = current.exportFormat ?: return
        val directory = current.exportDirectory ?: return
        perform {
            val task = coroutineContext[Job]
            val result = gateway.export(bookUrl, plan, format, directory, current.exportAddToShelf) { count, total ->
                state.update { if (job === task) it.copy(progress = count to total) else it }
            }
            if (job === task) state.update {
                it.copy(exportPlan = null, exportResult = result, recoveredBookUrl = result.importedBookUrl)
            }
        }
    }

    private fun suspendAccess() {
        val running = job
        job = null
        running?.cancel()
        previewHashes.clear()
        state.update {
            if (!it.accessBlocked) {
                if (it.texts.isNotEmpty()) suspendedGroups = it.groupSelections()
                suspendedReviewRequired = suspendedReviewRequired || it.selectionReviewRequired
            }
            it.copy(
                accessBlocked = true, bookName = "", loaded = false, pane = RecoveryPane.List,
                busy = false, progress = null, texts = persistentListOf(), editedTitles = persistentMapOf(),
                selected = persistentSetOf(), versionChoices = persistentMapOf(), invalidVersions = persistentSetOf(),
                selectionReviewRequired = false, unreadable = 0, hasDuplicateOldIndices = false,
                expandedIndices = persistentSetOf(),
                source = null, preview = null, targets = persistentListOf(), query = "", comparison = null,
                titleDraft = null, confirm = false, readerRequest = null, restoredTarget = null,
                exportPlan = null, exportResult = null, recoveredBookUrl = null, message = null,
            )
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
        suspendedGroups = null
        suspendedReviewRequired = false
        chosenVersions.clear()
        drafts.clear()
        state.value = OrphanRecoveryState()
    }

    override fun onCleared() {
        close()
        super.onCleared()
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
                if (job === coroutineContext[Job] && (error as? OrphanRecoveryException)?.problem == RecoveryProblem.PrivateBook) {
                    suspendAccess()
                }
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

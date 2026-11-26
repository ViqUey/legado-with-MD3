package io.legado.app.feature.orphanrecovery

import android.content.res.Resources
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import io.legado.app.domain.model.RecoveryReaderRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.OrphanText
import io.legado.app.domain.model.RecoveryDifference
import io.legado.app.domain.model.RecoveryLineKind
import io.legado.app.domain.model.RecoveryMatch
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.modalBottomSheet.SheetAction
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.settingItem.SettingItem
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import kotlinx.collections.immutable.toImmutableMap

@Composable
internal fun OrphanRecoveryMenu(enabled: Boolean, onOpen: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val openLabel = stringResource(R.string.feature_orphan_recovery_open)
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(AppIcons.MoreVert, contentDescription = openLabel)
        }
        RoundDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            RoundDropdownMenuItem(text = openLabel, onClick = {
                expanded = false
                onOpen()
            })
        }
    }
}

@Composable
internal fun OrphanRecoverySheet(
    state: OrphanRecoveryState,
    send: (OrphanRecoveryIntent) -> Unit,
    onOpenLocal: (String) -> Unit,
    onOpenRecovered: (RecoveryReaderRequest) -> Unit,
) {
    if (state.bookUrl == null) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val openRecovered by rememberUpdatedState(onOpenRecovered)
    LaunchedEffect(state.readerRequest, lifecycle) {
        val request = state.readerRequest ?: return@LaunchedEffect
        lifecycle.withResumed {
            send(OrphanRecoveryIntent.Close)
            openRecovered(request)
        }
    }
    val back = { send(OrphanRecoveryIntent.Back) }
    val title = when (state.pane) {
        RecoveryPane.List -> state.bookName.ifBlank { stringResource(R.string.feature_orphan_recovery_open) }
        RecoveryPane.Preview -> stringResource(R.string.feature_orphan_recovery_preview)
        RecoveryPane.Targets -> stringResource(R.string.feature_orphan_recovery_choose_target)
        RecoveryPane.Compare -> stringResource(R.string.feature_orphan_recovery_compare)
    }
    AppModalBottomSheet(
        show = true,
        onDismissRequest = { send(OrphanRecoveryIntent.Close) },
        title = title,
        animateContentSize = false,
        windowPresentation = true,
        sheetGesturesEnabled = state.pane == RecoveryPane.List && !state.busy,
        startSheetAction = if (state.pane != RecoveryPane.List) {
            SheetAction(
                text = stringResource(R.string.back),
                onClick = back,
                enabled = !state.busy,
            )
        } else null,
    ) {
        BackHandler(enabled = !state.confirm && (state.pane != RecoveryPane.List || state.busy)) {
            if (state.busy) send(OrphanRecoveryIntent.Cancel) else back()
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.84f)) {
            if (state.busy) {
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppCircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    AppText(
                        text = state.progress?.let { (count, total) ->
                            stringResource(R.string.feature_orphan_recovery_progress, count, total)
                        } ?: stringResource(R.string.feature_orphan_recovery_loading),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { send(OrphanRecoveryIntent.Cancel) }) {
                        AppText(stringResource(R.string.cancel))
                    }
                }
            }
            state.message?.let { message ->
                SettingItem(title = stringResource(message), onClick = null)
            }
            key(state.pane) {
                when (state.pane) {
                    RecoveryPane.List -> RecoveryLibrary(state, send, onOpenLocal)
                    RecoveryPane.Preview -> SourcePreview(state, send)
                    RecoveryPane.Targets -> TargetPicker(state, send)
                    RecoveryPane.Compare -> Comparison(state, send)
                }
            }
        }
    }

    val comparison = state.comparison
    val titleOnly = comparison?.difference == RecoveryDifference.Identical
    val confirmationLabel = when {
        comparison?.titleChange == null -> R.string.feature_orphan_recovery_replace
        titleOnly -> R.string.feature_orphan_recovery_restore_title_only
        else -> R.string.feature_orphan_recovery_restore_title
    }
    AppAlertDialog(
        show = state.confirm && comparison != null,
        onDismissRequest = { send(OrphanRecoveryIntent.DismissConfirmation) },
        title = stringResource(confirmationLabel),
        text = comparison?.let {
            val renamed = it.titleChange?.renamedTarget
            if (renamed != null) {
                val message = if (titleOnly) R.string.feature_orphan_recovery_title_only_confirmation
                    else R.string.feature_orphan_recovery_title_confirmation
                stringResource(message, it.target.title, renamed.title)
            } else {
                stringResource(R.string.feature_orphan_recovery_replace_warning, it.target.title)
            }
        },
        confirmText = stringResource(confirmationLabel),
        dismissText = stringResource(R.string.cancel),
        onConfirm = { send(OrphanRecoveryIntent.Restore) },
        onDismiss = { send(OrphanRecoveryIntent.DismissConfirmation) },
    )
}

@Composable
private fun RecoveryLibrary(
    state: OrphanRecoveryState,
    send: (OrphanRecoveryIntent) -> Unit,
    onOpenLocal: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    if (!state.loaded) {
        if (!state.busy) {
            TextButton(onClick = { send(OrphanRecoveryIntent.Reload) }) { AppText(stringResource(R.string.retry)) }
        }
        return
    }
    if (state.texts.isEmpty()) {
        SettingItem(title = stringResource(R.string.feature_orphan_recovery_empty), onClick = null)
        return
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val checked = state.selected.size == state.texts.size
            Checkbox(
                checked = checked,
                onCheckedChange = { send(OrphanRecoveryIntent.ToggleAll) },
                enabled = !state.busy,
            )
            TextButton(onClick = { send(OrphanRecoveryIntent.ToggleAll) }, enabled = !state.busy) {
                AppText(stringResource(if (checked) R.string.feature_orphan_recovery_deselect_all else R.string.select_all))
            }
            AppText(
                stringResource(R.string.feature_orphan_recovery_selection_count, state.selected.size, state.texts.size),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { send(OrphanRecoveryIntent.ToggleReorder) }, enabled = !state.busy && state.texts.size > 1) {
                AppText(stringResource(if (state.reorder) R.string.feature_orphan_recovery_end_reorder else R.string.feature_orphan_recovery_reorder))
            }
            TextButton(onClick = { send(OrphanRecoveryIntent.Reload) }, enabled = !state.busy) {
                AppText(stringResource(R.string.feature_orphan_recovery_rescan))
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (state.hasDuplicateOldIndices) {
                item {
                    SettingItem(
                        title = stringResource(R.string.feature_orphan_recovery_duplicate_indices),
                        onClick = null,
                    )
                }
            }
            itemsIndexed(state.texts, key = { _, item -> item.id.relativePath }) { position, item ->
                val display = state.editedTitles[item.id]?.takeIf(String::isNotBlank)
                    ?: item.originalTitle ?: item.suggestedTitle ?: fallbackTitle(resources, item, position)
                RecoveryRow(
                    item, display, position, state, send,
                    description = listOfNotNull(
                        if (item.originalTitle == null) stringResource(R.string.feature_orphan_recovery_title_unknown) else null,
                        if (item.copies.isNotEmpty()) stringResource(R.string.feature_orphan_recovery_same_copies, item.copies.size) else null,
                        indexLabel(item),
                        stringResource(if (item.id.child == null) R.string.feature_orphan_recovery_flat else R.string.feature_orphan_recovery_nested),
                        Formatter.formatShortFileSize(context, item.bytes),
                    ).joinToString(" · "),
                )
            }
        }
        val recovered = state.recoveredBookUrl
        Button(
            onClick = {
                if (recovered != null) {
                    send(OrphanRecoveryIntent.Close)
                    onOpenLocal(recovered)
                } else {
                    val provisional = state.texts.mapIndexed { i, item -> item.id to fallbackTitle(resources, item, i) }.toMap()
                    send(OrphanRecoveryIntent.RecoverLocal(provisional.toImmutableMap()))
                }
            },
            enabled = !state.busy && (recovered != null || state.selected.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            AppText(
                if (recovered == null) stringResource(R.string.feature_orphan_recovery_recover_local, state.selected.size)
                else stringResource(R.string.feature_orphan_recovery_open_local),
            )
        }
        AppText(
            stringResource(R.string.feature_orphan_recovery_keep_source),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = LegadoTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RecoveryRow(
    item: OrphanText,
    display: String,
    position: Int,
    state: OrphanRecoveryState,
    send: (OrphanRecoveryIntent) -> Unit,
    description: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val checkLabel = stringResource(R.string.feature_orphan_recovery_select_item, display)
        Checkbox(
            checked = item.id in state.selected,
            enabled = !state.busy,
            onCheckedChange = { send(OrphanRecoveryIntent.Toggle(item.id)) },
            modifier = Modifier.semantics { contentDescription = checkLabel },
        )
        SettingItem(
            modifier = Modifier.weight(1f),
            title = display,
            description = description,
            enabled = !state.busy,
            onClick = { send(OrphanRecoveryIntent.Preview(item)) },
        )
        if (state.reorder) {
            val up = stringResource(R.string.feature_orphan_recovery_move_up, display)
            val down = stringResource(R.string.feature_orphan_recovery_move_down, display)
            IconButton(onClick = { send(OrphanRecoveryIntent.Move(item.id, -1)) }, enabled = !state.busy && position > 0) {
                Icon(Icons.Default.ArrowUpward, up)
            }
            IconButton(
                onClick = { send(OrphanRecoveryIntent.Move(item.id, 1)) },
                enabled = !state.busy && position < state.texts.lastIndex,
            ) {
                Icon(Icons.Default.ArrowDownward, down)
            }
        }
    }
}

@Composable
private fun SourcePreview(state: OrphanRecoveryState, send: (OrphanRecoveryIntent) -> Unit) {
    val item = state.source ?: return
    val preview = state.preview ?: return
    val context = LocalContext.current
    val resources = LocalResources.current
    val displayed = item.originalTitle ?: item.suggestedTitle ?: fallbackTitle(resources, item, state.texts.indexOf(item))
    Column {
        SettingItem(
            title = displayed,
            description = buildString {
                append(indexLabel(item))
                append(" · ")
                append(Formatter.formatShortFileSize(context, item.bytes))
                append('\n')
                append(item.id.relativePath)
                if (item.copies.isNotEmpty()) {
                    append("\n" + resources.getString(R.string.feature_orphan_recovery_same_copies, item.copies.size))
                }
                when {
                    item.hasConflictingTitles -> append("\n" + resources.getString(
                        R.string.feature_orphan_recovery_conflicting_titles,
                        item.copies.mapNotNull { it.originalTitle }.distinct().joinToString(" / "),
                    ))
                    item.originalTitle != null -> append("\n" + resources.getString(R.string.feature_orphan_recovery_title_verified))
                    item.suggestedTitle != null -> append("\n" + resources.getString(R.string.feature_orphan_recovery_title_suspected))
                }
            },
            onClick = null,
        )
        AppTextField(
            value = state.editedTitles[item.id] ?: displayed,
            onValueChange = { send(OrphanRecoveryIntent.EditTitle(it)) },
            label = stringResource(R.string.feature_orphan_recovery_local_title),
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        FullText(preview.text, Modifier.weight(1f))
        val restored = state.restoredTarget
        if (restored != null) {
            SettingItem(
                title = stringResource(R.string.feature_orphan_recovery_target_restored, restored.title),
                onClick = null,
            )
            TextButton(onClick = { send(OrphanRecoveryIntent.Targets) }, enabled = !state.busy) {
                AppText(stringResource(R.string.feature_orphan_recovery_other_target))
            }
        }
        Button(
            onClick = {
                send(if (restored == null) OrphanRecoveryIntent.Targets else OrphanRecoveryIntent.OpenRestored)
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            AppText(stringResource(if (restored == null) R.string.feature_orphan_recovery_restore_to_book
                else R.string.feature_orphan_recovery_view_restored))
        }
    }
}

@Composable
private fun TargetPicker(state: OrphanRecoveryState, send: (OrphanRecoveryIntent) -> Unit) {
    if (state.targets.isEmpty()) {
        SettingItem(title = stringResource(R.string.feature_orphan_recovery_no_targets), onClick = null)
        return
    }
    Column {
        AppTextField(
            value = state.query,
            onValueChange = { send(OrphanRecoveryIntent.Query(it)) },
            label = stringResource(R.string.search),
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        val targets = remember(state.targets, state.query) {
            val query = state.query.trim()
            state.targets.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) || it.index.toString() == query }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (targets.isEmpty()) item {
                SettingItem(title = stringResource(R.string.feature_orphan_recovery_no_search_results), onClick = null)
            }
            items(targets, key = { it.url }) { target ->
                val match = when (target.match) {
                    RecoveryMatch.TitleHash -> R.string.feature_orphan_recovery_match_hash
                    RecoveryMatch.Index -> R.string.feature_orphan_recovery_match_index
                    RecoveryMatch.Manual -> R.string.feature_orphan_recovery_match_manual
                }
                SettingItem(
                    title = target.title,
                    description = stringResource(match),
                    enabled = !state.busy,
                    onClick = { send(OrphanRecoveryIntent.Target(target)) },
                )
            }
        }
    }
}

@Composable
private fun Comparison(state: OrphanRecoveryState, send: (OrphanRecoveryIntent) -> Unit) {
    val compare = state.comparison ?: return
    val source = state.preview ?: return
    Column {
        SettingItem(
            title = compare.target.title,
            description = if (compare.hasShadowedCopy) {
                stringResource(R.string.feature_orphan_recovery_shadowed_copy)
            } else {
                when (compare.difference) {
                    RecoveryDifference.Missing -> stringResource(R.string.feature_orphan_recovery_no_current)
                    RecoveryDifference.Identical -> stringResource(R.string.feature_orphan_recovery_identical)
                    RecoveryDifference.Different -> stringResource(R.string.feature_orphan_recovery_has_current)
                }
            },
            onClick = null,
        )
        if (compare.difference == RecoveryDifference.Different) {
            AppTabRow(
                tabTitles = listOf(
                    stringResource(R.string.feature_orphan_recovery_diff),
                    stringResource(R.string.feature_orphan_recovery_source),
                    stringResource(R.string.feature_orphan_recovery_current),
                ),
                selectedTabIndex = state.tab.ordinal,
                onTabSelected = { send(OrphanRecoveryIntent.Tab(RecoveryTextTab.entries[it])) },
                isScrollable = false,
            )
        }
        key(state.tab) {
            when (state.tab) {
                RecoveryTextTab.Difference -> {
                    if (compare.lines == null) {
                        SettingItem(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.feature_orphan_recovery_diff_large),
                            onClick = null,
                        )
                    } else {
                        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            items(compare.lines) { line ->
                                val (marker, color) = when (line.kind) {
                                    RecoveryLineKind.Same -> "  " to LegadoTheme.colorScheme.onSurface
                                    RecoveryLineKind.Removed -> "− " to LegadoTheme.colorScheme.error
                                    RecoveryLineKind.Added -> "+ " to LegadoTheme.colorScheme.primary
                                }
                                AppText(marker + line.text, color = color, style = LegadoTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                RecoveryTextTab.Source -> FullText(source.text, Modifier.weight(1f))
                RecoveryTextTab.Current -> FullText(compare.currentText.orEmpty(), Modifier.weight(1f))
            }
        }
        val titleDraft = state.titleDraft
        val selected = state.source
        val titleOnly = compare.difference == RecoveryDifference.Identical
        val toggleLabel = stringResource(
            if (titleOnly) R.string.feature_orphan_recovery_restore_title_only
            else R.string.feature_orphan_recovery_restore_title,
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = titleDraft != null,
                onCheckedChange = { send(OrphanRecoveryIntent.UseRestoredTitle(it)) },
                enabled = !state.busy,
                modifier = Modifier.semantics {
                    contentDescription = toggleLabel
                },
            )
            AppText(toggleLabel)
        }
        if (titleDraft != null) {
            val basis = when {
                !selected?.originalTitle.isNullOrBlank() && titleDraft == selected?.originalTitle ->
                    R.string.feature_orphan_recovery_title_verified
                !selected?.suggestedTitle.isNullOrBlank() && titleDraft == selected?.suggestedTitle ->
                    R.string.feature_orphan_recovery_title_suspected
                else -> R.string.feature_orphan_recovery_title_manual
            }
            AppText(
                stringResource(basis),
                modifier = Modifier.padding(horizontal = 12.dp),
                style = LegadoTheme.typography.bodySmall,
            )
            AppTextField(
                value = titleDraft,
                onValueChange = { send(OrphanRecoveryIntent.ChangeTitleDraft(it)) },
                label = stringResource(R.string.feature_orphan_recovery_new_title),
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            if (compare.titleChange != null) {
                SettingItem(
                    title = stringResource(
                        R.string.feature_orphan_recovery_reviewed_title, compare.titleChange.renamedTarget.title,
                    ),
                    onClick = null,
                )
            }
        }
        if (titleDraft != null && compare.titleChange == null) {
            Button(
                onClick = { send(OrphanRecoveryIntent.ReviewTitle) },
                enabled = !state.busy && titleDraft.isNotBlank() && titleDraft.trim() != compare.target.title,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) { AppText(stringResource(R.string.feature_orphan_recovery_review_title)) }
        } else if (compare.titleChange != null || compare.difference != RecoveryDifference.Identical) {
            val replace = compare.titleChange != null || compare.difference == RecoveryDifference.Different
            Button(
                onClick = { send(if (replace) OrphanRecoveryIntent.RequestReplace else OrphanRecoveryIntent.Restore) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                val label = when {
                    titleOnly -> R.string.feature_orphan_recovery_restore_title_only
                    compare.titleChange != null -> R.string.feature_orphan_recovery_restore_title
                    replace -> R.string.feature_orphan_recovery_replace
                    else -> R.string.feature_orphan_recovery_restore_to_book
                }
                AppText(stringResource(label))
            }
        }
    }
}

@Composable
private fun FullText(text: String, modifier: Modifier = Modifier) {
    val ranges = remember(text) {
        buildList {
            var start = 0
            while (start < text.length) {
                var end = minOf(start + 4096, text.length)
                if (end < text.length) {
                    val breakAt = (end - 1 downTo start + 2048).firstOrNull { text[it] == '\n' }
                    if (breakAt != null) {
                        add(start until breakAt)
                        start = breakAt + 1
                        continue
                    }
                    if (text[end - 1].isHighSurrogate()) end--
                }
                add(start until end)
                start = end
            }
        }
    }
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            items(ranges) { range ->
                AppText(text.substring(range), style = LegadoTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun indexLabel(item: OrphanText): String =
    item.index?.let { stringResource(R.string.feature_orphan_recovery_raw_index, it) }
        ?: stringResource(R.string.feature_orphan_recovery_order_unknown)

private fun fallbackTitle(resources: Resources, item: OrphanText, position: Int): String =
    item.index?.let { resources.getString(R.string.feature_orphan_recovery_index_title, it) }
        ?: resources.getString(R.string.feature_orphan_recovery_unknown_title, position + 1)

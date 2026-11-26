package io.legado.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

/** Both names are single path segments. A null child denotes the old flat layout. */
@Immutable
data class OrphanTextId(val entry: String, val child: String? = null) {
    val relativePath: String get() = child?.let { "$entry/$it" } ?: entry
}

@Immutable
data class OrphanText(
    val id: OrphanTextId,
    val index: Int?,
    val titleHash: String?,
    val originalTitle: String?,
    val bytes: Long,
    val modified: Long,
    val suggestedTitle: String? = null,
    val copies: ImmutableList<OrphanTextCopy> = persistentListOf(),
    val contentDigest: String? = null,
) {
    val hasConflictingTitles: Boolean
        get() = copies.mapNotNull(OrphanTextCopy::originalTitle).distinct().size > 1
}

@Immutable
data class OrphanTextCopy(
    val id: OrphanTextId,
    val titleHash: String?,
    val originalTitle: String?,
    val suggestedTitle: String?,
    val modified: Long,
)

data class OrphanTextCatalog(val bookName: String, val texts: List<OrphanText>)

@Immutable
data class OrphanTextPreview(val text: String, val digest: String)

@Immutable
data class LocalRecoverySelection(val source: OrphanText, val title: String, val previewDigest: String?)

enum class RecoveryMatch { TitleHash, Index, Manual }

@Immutable
@Serializable
data class RecoveryTarget(val index: Int, val url: String, val title: String, val match: RecoveryMatch)

enum class RecoveryDifference { Missing, Identical, Different }

enum class RecoveryLineKind { Same, Removed, Added }

@Immutable
data class RecoveryLine(val kind: RecoveryLineKind, val text: String)

/** Both cache locations are part of the confirmation, even when only one is shown. */
@Immutable
data class RecoveryTargetVersion(val current: String?, val legacy: String?)

@Immutable
data class RecoveryTitleChange(val renamedTarget: RecoveryTarget, val destinationVersion: RecoveryTargetVersion)

data class OrphanTextComparison(
    val target: RecoveryTarget,
    val version: RecoveryTargetVersion,
    val currentText: String?,
    val difference: RecoveryDifference,
    val lines: List<RecoveryLine>?,
    val hasShadowedCopy: Boolean = false,
    val titleChange: RecoveryTitleChange? = null,
)

@Immutable
data class RecoveryReaderRequest(val bookUrl: String, val target: RecoveryTarget, val digest: String)

enum class RecoveryProblem {
    Unavailable, PrivateBook, BusyBook, Changed, QueryFailed, ScanFailed, ResultMissing, ResultChanged,
    InvalidTitle, TitleDestinationConflict, TitleAssetsConflict,
}

enum class RecoveryActivity { Reader, ReadAloud, ContentRequest, Admission, Waiting, Running, Retrying }

class OrphanRecoveryException(
    val problem: RecoveryProblem,
    val activity: RecoveryActivity? = null,
) : IllegalStateException(problem.name)

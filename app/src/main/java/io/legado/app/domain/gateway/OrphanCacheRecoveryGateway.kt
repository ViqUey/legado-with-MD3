package io.legado.app.domain.gateway

import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.OrphanText
import io.legado.app.domain.model.OrphanTextCatalog
import io.legado.app.domain.model.OrphanTextComparison
import io.legado.app.domain.model.OrphanTextPreview
import io.legado.app.domain.model.RecoveryTarget
import io.legado.app.domain.model.RecoveryTargetVersion
import io.legado.app.domain.model.RecoveryTitleChange
import kotlinx.coroutines.flow.Flow

interface OrphanCacheRecoveryGateway {
    fun privateBookUrls(): Flow<Set<String>>
    suspend fun scanBook(bookUrl: String): OrphanTextCatalog
    suspend fun preview(bookUrl: String, source: OrphanText): OrphanTextPreview
    suspend fun targets(bookUrl: String, source: OrphanText): List<RecoveryTarget>
    suspend fun compare(
        bookUrl: String,
        source: OrphanText,
        previewDigest: String,
        target: RecoveryTarget,
        newTitle: String? = null,
    ): OrphanTextComparison

    suspend fun recoverLocal(
        bookUrl: String,
        selections: List<LocalRecoverySelection>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): String

    suspend fun readRestoredChapter(bookUrl: String, target: RecoveryTarget, digest: String): String

    suspend fun restoreChapter(
        bookUrl: String,
        source: OrphanText,
        previewDigest: String,
        target: RecoveryTarget,
        targetVersion: RecoveryTargetVersion,
        titleChange: RecoveryTitleChange? = null,
    ): RecoveryTarget
}

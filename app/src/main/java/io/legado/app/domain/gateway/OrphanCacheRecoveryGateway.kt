package io.legado.app.domain.gateway

import io.legado.app.domain.model.LocalRecoverySelection
import io.legado.app.domain.model.RecoveryExportPlan
import io.legado.app.domain.model.RecoveryExportFormat
import io.legado.app.domain.model.RecoveryExportResult
import io.legado.app.domain.model.OrphanText
import io.legado.app.domain.model.OrphanTextCatalog
import io.legado.app.domain.model.OrphanTextComparison
import io.legado.app.domain.model.OrphanTextPreview
import io.legado.app.domain.model.RecoveryTarget
import io.legado.app.domain.model.RecoveryTargetVersion
import io.legado.app.domain.model.RecoveryTitleChange

interface OrphanCacheRecoveryGateway {
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

    suspend fun inspectExport(bookUrl: String, selections: List<LocalRecoverySelection>): RecoveryExportPlan

    suspend fun export(
        bookUrl: String,
        plan: RecoveryExportPlan,
        format: RecoveryExportFormat,
        directory: String,
        addToShelf: Boolean,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): RecoveryExportResult

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

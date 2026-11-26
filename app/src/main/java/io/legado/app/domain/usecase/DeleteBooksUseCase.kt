package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.BookSourceCallbackGateway
import io.legado.app.domain.gateway.LocalBookGateway
import io.legado.app.domain.repository.BookDomainRepository
import io.legado.app.domain.model.RecoveredSourceDeletionException

class DeleteBooksUseCase(
    private val bookRepository: BookDomainRepository,
    private val localBookGateway: LocalBookGateway,
    private val bookSourceCallbackGateway: BookSourceCallbackGateway
) {

    data class Result(val deletedBookUrls: Set<String>, val failedRecoveredSources: Int)

    suspend fun execute(bookUrls: Set<String>, deleteOriginal: Boolean): Result {
        if (bookUrls.isEmpty()) return Result(emptySet(), 0)
        val books = bookRepository.getDeletableBooks(bookUrls)
        val deleted = mutableSetOf<String>()
        var failedSources = 0
        books.forEach { book ->
            if (book.isLocal) {
                try {
                    localBookGateway.deleteBook(book.bookUrl, deleteOriginal)
                } catch (_: RecoveredSourceDeletionException) {
                    failedSources++
                    return@forEach
                }
            } else {
                bookSourceCallbackGateway.onDeleteFromShelf(book.bookUrl)
            }
            bookRepository.deleteChaptersByBook(book.bookUrl)
            deleted += book.bookUrl
        }
        if (deleted.isNotEmpty()) bookRepository.deleteBooks(deleted)
        return Result(deleted, failedSources)
    }
}

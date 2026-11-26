package io.legado.app.data.repository

import io.legado.app.domain.model.OrphanText
import io.legado.app.domain.model.RecoveryLine
import io.legado.app.domain.model.RecoveryLineKind
import io.legado.app.domain.model.RecoveryMatch
import io.legado.app.domain.model.RecoveryTarget

internal object OrphanRecoveryRules {
    // String.format("%05d") uses the current locale and has no upper width limit.
    private val chapterName = Regex("""(\p{Nd}{5,})-([a-fA-F0-9]{16})""")

    fun parseName(subject: String): Pair<Int?, String?> {
        val match = chapterName.matchEntire(subject) ?: return null to null
        val (index, hash) = match.destructured
        return index.map { Character.digit(it, 10) }.joinToString("").toIntOrNull() to hash.lowercase()
    }

    val sourceOrder = compareBy<OrphanText> { it.index == null }
        .thenBy { it.index }
        .thenBy { it.titleHash }
        .thenBy { it.id.relativePath }

    fun match(source: OrphanText, index: Int, hash: String): RecoveryMatch = when {
        source.titleHash == hash -> RecoveryMatch.TitleHash
        source.index == index -> RecoveryMatch.Index
        else -> RecoveryMatch.Manual
    }

    fun orderedTargets(source: OrphanText, targets: List<RecoveryTarget>): List<RecoveryTarget> =
        targets.sortedWith(compareBy<RecoveryTarget> { it.match }
            .thenBy {
                if (it.match == RecoveryMatch.TitleHash && source.index != null) {
                    kotlin.math.abs(it.index.toLong() - source.index)
                } else 0L
            }.thenBy { it.index })

    /** A bounded line LCS. On larger inputs the UI still offers both complete previews. */
    fun diff(old: String, current: String): List<RecoveryLine>? {
        if (old.length.toLong() + current.length > 96_000) return null
        val a = current.split('\n')
        val b = old.split('\n')
        if (a.size > 400 || b.size > 400) return null

        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lengths[i][j] = if (a[i] == b[j]) 1 + lengths[i + 1][j + 1]
                else maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
        return buildList {
            var i = 0
            var j = 0
            while (i < a.size || j < b.size) {
                when {
                    i < a.size && j < b.size && a[i] == b[j] -> {
                        add(RecoveryLine(RecoveryLineKind.Same, a[i++]))
                        j++
                    }
                    j == b.size || i < a.size && lengths[i + 1][j] >= lengths[i][j + 1] ->
                        add(RecoveryLine(RecoveryLineKind.Removed, a[i++]))
                    else -> add(RecoveryLine(RecoveryLineKind.Added, b[j++]))
                }
            }
        }
    }
}

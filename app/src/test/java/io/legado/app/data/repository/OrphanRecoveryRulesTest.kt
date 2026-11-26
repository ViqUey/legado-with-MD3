package io.legado.app.data.repository

import io.legado.app.domain.model.RecoveryLine
import io.legado.app.domain.model.RecoveryLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrphanRecoveryRulesTest {
    @Test fun fileIndexMayBeLongAndUseLocaleDigits() {
        val hash = "f0abcdef01234567"
        assertEquals(100001 to hash, OrphanRecoveryRules.parseName("100001-$hash"))
        assertEquals(12 to hash, OrphanRecoveryRules.parseName("٠٠٠١٢-$hash"))
        assertEquals(null to null, OrphanRecoveryRules.parseName("historical"))
    }

    @Test fun lineComparisonPreservesWhitespaceAndTerminators() {
        val lines = OrphanRecoveryRules.diff("same\nnew \n", "same\nold\n")
        assertEquals(listOf(
            RecoveryLine(RecoveryLineKind.Same, "same"),
            RecoveryLine(RecoveryLineKind.Removed, "old"),
            RecoveryLine(RecoveryLineKind.Added, "new "),
            RecoveryLine(RecoveryLineKind.Same, ""),
        ), lines)
    }

    @Test fun largeComparisonFallsBackToCompletePreviews() {
        assertNull(OrphanRecoveryRules.diff("章".repeat(100_000), "current"))
        assertNull(OrphanRecoveryRules.diff("line\n".repeat(500), "current"))
    }
}

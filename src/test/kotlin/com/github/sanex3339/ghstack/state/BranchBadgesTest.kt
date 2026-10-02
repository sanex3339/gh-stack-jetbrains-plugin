package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.model.ChecksState
import com.github.sanex3339.ghstack.model.MergeBlocker
import com.github.sanex3339.ghstack.model.PrDetails
import com.github.sanex3339.ghstack.model.PrState
import com.github.sanex3339.ghstack.model.PrUi
import com.github.sanex3339.ghstack.model.ReviewState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BranchBadgesTest {
    private fun open(details: PrDetails?, blocker: MergeBlocker? = null, blockedBy: String? = null) =
        BranchUi("api", false, BranchStatus.OPEN, PrUi(7, null, PrState.OPEN), details, blocker, blockedBy)

    private val clean = PrDetails(7, isDraft = false, review = ReviewState.NONE, checks = ChecksState.PASSING, conflicting = false)

    @Test
    fun `open pull requests show their readiness`() {
        assertEquals(listOf(Badge("open", Tone.NEUTRAL)), BranchBadges.of(open(null)))
        assertEquals(listOf(Badge("ready", Tone.POSITIVE)), BranchBadges.of(open(clean)))
        assertEquals(listOf(Badge("approved", Tone.POSITIVE)), BranchBadges.of(open(clean.copy(review = ReviewState.APPROVED))))
        assertEquals(listOf(Badge("draft", Tone.NEUTRAL)), BranchBadges.of(open(clean.copy(isDraft = true), MergeBlocker.DRAFT)))
        assertEquals(listOf(Badge("checks failing", Tone.NEGATIVE)), BranchBadges.of(open(clean, MergeBlocker.CHECKS_FAILING)))
        assertEquals(listOf(Badge("review required", Tone.WARNING)), BranchBadges.of(open(clean, MergeBlocker.REVIEW_REQUIRED)))
    }

    @Test
    fun `blocked layers say what blocks them`() {
        assertEquals(
            listOf(Badge("approved", Tone.POSITIVE), Badge("blocked by #5", Tone.WARNING)),
            BranchBadges.of(open(clean.copy(review = ReviewState.APPROVED), blockedBy = "#5")),
        )
    }

    @Test
    fun `other statuses keep their labels`() {
        assertEquals(listOf(Badge("merged", Tone.NEUTRAL)), BranchBadges.of(BranchUi("m", false, BranchStatus.MERGED, null)))
        assertEquals(listOf(Badge("needs rebase", Tone.WARNING)), BranchBadges.of(BranchUi("m", false, BranchStatus.NEEDS_REBASE, null)))
    }
}

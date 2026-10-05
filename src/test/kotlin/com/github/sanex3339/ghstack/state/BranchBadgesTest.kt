package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.model.CheckOutcome
import com.github.sanex3339.ghstack.model.CheckRunInfo
import com.github.sanex3339.ghstack.model.ChecksState
import com.github.sanex3339.ghstack.model.MergeBlocker
import com.github.sanex3339.ghstack.model.PrDetails
import com.github.sanex3339.ghstack.model.PrState
import com.github.sanex3339.ghstack.model.PrUi
import com.github.sanex3339.ghstack.model.ReviewState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BranchBadgesTest {
    private fun open(details: PrDetails?, blocker: MergeBlocker? = null, blockedBy: String? = null) =
        BranchUi("api", false, BranchStatus.OPEN, PrUi(7, null, PrState.OPEN), details, blocker, blockedBy)

    private val clean = PrDetails(7, isDraft = false, review = ReviewState.NONE, checks = ChecksState.PASSING, conflicting = false)

    @Test
    fun `open pull requests show their readiness`() {
        fun texts(branch: BranchUi) = BranchBadges.of(branch).map { it.text to it.tone }
        assertEquals(listOf("open" to Tone.NEUTRAL), texts(open(null)))
        assertEquals(listOf("ready" to Tone.POSITIVE), texts(open(clean)))
        assertEquals(listOf("approved" to Tone.POSITIVE), texts(open(clean.copy(review = ReviewState.APPROVED))))
        assertEquals(listOf("draft" to Tone.NEUTRAL), texts(open(clean.copy(isDraft = true), MergeBlocker.DRAFT)))
        assertEquals(listOf("checks failing" to Tone.NEGATIVE), texts(open(clean.copy(checks = ChecksState.FAILING), MergeBlocker.CHECKS_FAILING)))
        assertEquals(listOf("review required" to Tone.WARNING), texts(open(clean.copy(review = ReviewState.REVIEW_REQUIRED), MergeBlocker.REVIEW_REQUIRED)))
    }

    @Test
    fun `review and checks are shown side by side`() {
        val approvedRunning = open(clean.copy(review = ReviewState.APPROVED, checks = ChecksState.PENDING), MergeBlocker.CHECKS_PENDING)
        val badges = BranchBadges.of(approvedRunning)
        assertEquals(listOf("approved", "checks running"), badges.map { it.text })
        assertEquals(listOf("✓ approved", "● checks"), badges.map { it.compact })
        assertEquals(listOf(Tone.POSITIVE, Tone.WARNING), badges.map { it.tone })

        val needsWork = open(clean.copy(review = ReviewState.CHANGES_REQUESTED, checks = ChecksState.FAILING, conflicting = true), MergeBlocker.CONFLICTS)
        assertEquals(listOf("conflicts", "changes requested", "checks failing"), BranchBadges.of(needsWork).map { it.text })

        val draft = open(clean.copy(isDraft = true, review = ReviewState.REVIEW_REQUIRED, checks = ChecksState.PENDING), MergeBlocker.DRAFT)
        assertEquals(listOf("draft", "checks running"), BranchBadges.of(draft).map { it.text }, "a draft can't be reviewed yet")

        val stack = com.github.sanex3339.ghstack.model.StackUi(null, 1, "main", listOf(approvedRunning), isCurrent = true)
        assertTrue(BranchBadges.tooltip(approvedRunning, stack).contains("Status: approved, checks running"))
    }

    @Test
    fun `blocked layers say what blocks them, compactly in the tree, and link to the blocker`() {
        val blocker = BranchUi("auth", false, BranchStatus.OPEN, PrUi(5, null, PrState.OPEN), blocker = MergeBlocker.DRAFT)
        val blocked = open(clean.copy(review = ReviewState.APPROVED), blockedBy = "#5")
        val stack = com.github.sanex3339.ghstack.model.StackUi(null, 1, "main", listOf(blocker, blocked), isCurrent = true)
        val badges = BranchBadges.of(blocked, stack)
        assertEquals(listOf("approved", "blocked by #5"), badges.map { it.text })
        assertEquals("⛔ #5", badges[1].compact)
        assertEquals(BadgeLink.Branch("auth"), badges[1].link)
        assertTrue(BranchBadges.tooltip(blocked, stack).contains("Blocked by #5 (draft)"))
    }

    @Test
    fun `failing checks link to the checks page and are named in the tooltip`() {
        val runs = listOf("build", "test").map { CheckRunInfo(it, "CI", "pull_request", CheckOutcome.FAILING, url = null, required = false) }
        val failing = open(clean.copy(checks = ChecksState.FAILING, checkRuns = runs, title = "Add <api>"), MergeBlocker.CHECKS_FAILING)
            .copy(pr = PrUi(7, "https://github.com/o/r/pull/7", PrState.OPEN))
        val stack = com.github.sanex3339.ghstack.model.StackUi(null, 1, "main", listOf(failing), isCurrent = true)
        val badge = BranchBadges.of(failing, stack).single()
        assertEquals("✗ checks", badge.compact)
        assertEquals(BadgeLink.Checks("https://github.com/o/r/pull/7/checks"), badge.link)
        val tooltip = BranchBadges.tooltip(failing, stack)
        assertTrue(tooltip.contains("<b>Add &lt;api&gt;</b>"), tooltip)
        assertTrue(tooltip.contains("✗ failing: build, test"), tooltip)
    }

    @Test
    fun `other statuses keep their labels`() {
        assertEquals(listOf("merged"), BranchBadges.of(BranchUi("m", false, BranchStatus.MERGED, null)).map { it.text })
        assertEquals(listOf("needs rebase"), BranchBadges.of(BranchUi("m", false, BranchStatus.NEEDS_REBASE, null)).map { it.text })
    }
}

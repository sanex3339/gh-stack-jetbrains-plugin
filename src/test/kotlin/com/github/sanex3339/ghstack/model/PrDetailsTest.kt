package com.github.sanex3339.ghstack.model

import com.github.sanex3339.ghstack.model.BranchStatus.MERGED
import com.github.sanex3339.ghstack.model.BranchStatus.NEEDS_REBASE
import com.github.sanex3339.ghstack.model.BranchStatus.NOT_SUBMITTED
import com.github.sanex3339.ghstack.model.BranchStatus.OPEN
import com.github.sanex3339.ghstack.testutil.Fixtures
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrDetailsTest {
    private val details = PrDetailsQuery.parse(Fixtures.text("pr-details.json"))

    @Test
    fun `builds one aliased query for all pull requests`() {
        val query = PrDetailsQuery.build(RepoCoordinates("github.com", "octo", "app"), listOf(101, 102, 101))
        assertTrue(query.startsWith("""query { repository(owner: "octo", name: "app") {"""), query)
        assertEquals(2, Regex("""pullRequest\(number: """).findAll(query).count())
        assertTrue("pr102: pullRequest(number: 102)" in query)
        assertTrue("statusCheckRollup { state contexts(first: 100)" in query)
        assertTrue("number title isDraft" in query)
    }

    @Test
    fun `parses draft, review, checks and conflicts`() {
        assertEquals(PrDetails(101, isDraft = true, review = ReviewState.NONE, checks = ChecksState.PASSING, conflicting = false, title = "Add auth"), details[101])
        assertEquals(PrDetails(102, isDraft = false, review = ReviewState.REVIEW_REQUIRED, checks = ChecksState.PENDING, conflicting = false), details[102])
        val ui = details.getValue(103)
        assertEquals(
            PrDetails(103, isDraft = false, review = ReviewState.APPROVED, checks = ChecksState.FAILING, conflicting = true, title = "Add UI"),
            ui.copy(checkRuns = emptyList()),
        )
        assertEquals(listOf("build", "ci/legacy"), ui.failingChecks)
        assertEquals(listOf("e2e"), ui.pendingChecks)
        assertEquals(ChecksState.NONE, details[104]!!.checks)
        assertEquals(setOf(101, 102, 103, 104), details.keys)
        assertEquals(emptyMap<Int, PrDetails>(), PrDetailsQuery.parse("not json"))
        assertEquals(emptyMap<Int, PrDetails>(), PrDetailsQuery.parse("""{"errors":[{"message":"Bad credentials"}]}"""))
    }

    @Test
    fun `blockers follow GitHub's order`() {
        assertEquals(MergeBlocker.DRAFT, MergeReadiness.detailsBlocker(details.getValue(101)))
        assertEquals(MergeBlocker.REVIEW_REQUIRED, MergeReadiness.detailsBlocker(details.getValue(102)))
        assertEquals(MergeBlocker.CONFLICTS, MergeReadiness.detailsBlocker(details.getValue(103)))
        assertNull(MergeReadiness.detailsBlocker(details.getValue(104)))
    }

    @Test
    fun `layers above the lowest blocker are blocked downstack`() {
        // models=#100 merged, auth=#101 draft, api=#102 review required, ui=#103 approved but conflicting
        val annotated = MergeReadiness.annotate(
            stack("main", "models" to MERGED, "auth" to OPEN, "api" to OPEN, "ui" to OPEN, current = "ui"),
            details,
        )
        val byName = annotated.branches.associateBy { it.name }
        assertNull(byName.getValue("models").blockedBy)
        assertNull(byName.getValue("auth").blockedBy, "nothing below the lowest blocker")
        assertEquals(MergeBlocker.DRAFT, byName.getValue("auth").blocker)
        assertEquals("#101", byName.getValue("api").blockedBy)
        assertEquals("#101", byName.getValue("ui").blockedBy)
        assertEquals(listOf(101), annotated.draftPrNumbers)
    }

    @Test
    fun `local-only problems block without GitHub details`() {
        val annotated = MergeReadiness.annotate(
            stack("main", "auth" to OPEN, "new" to NOT_SUBMITTED, "api" to NEEDS_REBASE, "ui" to OPEN, current = "ui"),
            emptyMap(),
        )
        val byName = annotated.branches.associateBy { it.name }
        assertNull(byName.getValue("auth").blocker, "open PR without details is unknown, not blocked")
        assertEquals(MergeBlocker.NOT_SUBMITTED, byName.getValue("new").blocker)
        assertEquals("new", byName.getValue("api").blockedBy)
        assertEquals("new", byName.getValue("ui").blockedBy)
    }
}

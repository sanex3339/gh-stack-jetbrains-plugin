package com.github.sanex3339.ghstack.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

enum class ChecksState { PASSING, FAILING, PENDING, NONE }

enum class ReviewState { APPROVED, CHANGES_REQUESTED, REVIEW_REQUIRED, NONE }

/** What GitHub knows about an open pull request's mergeability. */
data class PrDetails(
    val number: Int,
    val isDraft: Boolean,
    val review: ReviewState,
    val checks: ChecksState,
    val conflicting: Boolean,
    val title: String = "",
    val failingChecks: List<String> = emptyList(),
    val pendingChecks: List<String> = emptyList(),
)

/** One GraphQL request for all of a stack's pull requests (`gh api graphql -f query=…`). */
object PrDetailsQuery {
    fun build(repo: RepoCoordinates, numbers: List<Int>): String = buildString {
        append("query { repository(owner: ").append(JsonPrimitive(repo.owner)).append(", name: ").append(JsonPrimitive(repo.name)).append(") {")
        numbers.distinct().forEach { number ->
            append(" pr").append(number).append(": pullRequest(number: ").append(number).append(") {")
            append(" number title isDraft reviewDecision mergeable")
            append(" commits(last: 1) { nodes { commit { statusCheckRollup { state contexts(first: 100) { nodes {")
            append(" __typename ... on CheckRun { name conclusion status } ... on StatusContext { context state } } } } } } } }")
        }
        append(" } }")
    }

    /** PR number → details; PRs GitHub couldn't resolve are left out. Bad JSON gives an empty map. */
    fun parse(json: String): Map<Int, PrDetails> {
        val repository = try {
            (Json.parseToJsonElement(json) as? JsonObject)?.obj("data")?.obj("repository")
        } catch (e: IllegalArgumentException) {
            null
        } ?: return emptyMap()
        return repository.values.mapNotNull { (it as? JsonObject)?.toDetails() }.associateBy { it.number }
    }

    private fun JsonObject.toDetails(): PrDetails? {
        val number = (this["number"] as? JsonPrimitive)?.intOrNull ?: return null
        val statusRollup = ((obj("commits")?.get("nodes") as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.obj("commit")?.obj("statusCheckRollup")
        val rollup = statusRollup?.string("state")
        val contexts = (statusRollup?.obj("contexts")?.get("nodes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return PrDetails(
            number = number,
            isDraft = (this["isDraft"] as? JsonPrimitive)?.booleanOrNull == true,
            review = when (string("reviewDecision")) {
                "APPROVED" -> ReviewState.APPROVED
                "CHANGES_REQUESTED" -> ReviewState.CHANGES_REQUESTED
                "REVIEW_REQUIRED" -> ReviewState.REVIEW_REQUIRED
                else -> ReviewState.NONE
            },
            checks = when (rollup) {
                "SUCCESS" -> ChecksState.PASSING
                "FAILURE", "ERROR" -> ChecksState.FAILING
                "PENDING", "EXPECTED" -> ChecksState.PENDING
                else -> ChecksState.NONE
            },
            conflicting = string("mergeable") == "CONFLICTING",
            title = string("title").orEmpty(),
            failingChecks = contexts.filter { it.isFailingCheck() }.map { it.checkName() },
            pendingChecks = contexts.filter { it.isPendingCheck() }.map { it.checkName() },
        )
    }

    private fun JsonObject.checkName(): String = string("name") ?: string("context") ?: "check"

    private fun JsonObject.isFailingCheck(): Boolean =
        string("conclusion") in FAILING_CONCLUSIONS || string("state") in setOf("FAILURE", "ERROR")

    private fun JsonObject.isPendingCheck(): Boolean =
        (string("__typename") == "CheckRun" && string("status") != "COMPLETED") || string("state") in setOf("PENDING", "EXPECTED")

    private val FAILING_CONCLUSIONS = setOf("FAILURE", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE")

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
}

/** Why a layer can't merge by itself, most fundamental first. */
enum class MergeBlocker(val label: String) {
    NOT_SUBMITTED("not submitted"),
    NEEDS_REBASE("needs rebase"),
    DRAFT("draft"),
    CONFLICTS("conflicts"),
    CHECKS_FAILING("checks failing"),
    CHANGES_REQUESTED("changes requested"),
    REVIEW_REQUIRED("review required"),
    CHECKS_PENDING("checks running"),
}

/**
 * A stack merges all-or-nothing from the bottom, so a layer is "blocked downstack" when any unmerged
 * layer below it has a blocker — the same rule GitHub's stack view uses.
 */
object MergeReadiness {
    fun blocker(branch: BranchUi): MergeBlocker? = when (branch.status) {
        BranchStatus.MERGED, BranchStatus.QUEUED -> null
        BranchStatus.NOT_SUBMITTED -> MergeBlocker.NOT_SUBMITTED
        BranchStatus.NEEDS_REBASE -> MergeBlocker.NEEDS_REBASE
        BranchStatus.OPEN -> branch.details?.let(::detailsBlocker)
    }

    fun detailsBlocker(details: PrDetails): MergeBlocker? = when {
        details.isDraft -> MergeBlocker.DRAFT
        details.conflicting -> MergeBlocker.CONFLICTS
        details.checks == ChecksState.FAILING -> MergeBlocker.CHECKS_FAILING
        details.review == ReviewState.CHANGES_REQUESTED -> MergeBlocker.CHANGES_REQUESTED
        details.review == ReviewState.REVIEW_REQUIRED -> MergeBlocker.REVIEW_REQUIRED
        details.checks == ChecksState.PENDING -> MergeBlocker.CHECKS_PENDING
        else -> null
    }

    /** Attaches PR details, each layer's own blocker, and the lowest blocking layer below it ("#123" or a branch name). */
    fun annotate(stack: StackUi, details: Map<Int, PrDetails>): StackUi {
        var lowestBlocker: String? = null
        val branches = stack.branches.map { branch ->
            val withDetails = branch.copy(details = branch.pr?.number?.let(details::get))
            val own = blocker(withDetails)
            val settled = branch.status == BranchStatus.MERGED || branch.status == BranchStatus.QUEUED
            val annotated = withDetails.copy(blocker = own, blockedBy = if (settled) null else lowestBlocker)
            if (own != null && lowestBlocker == null) lowestBlocker = branch.pr?.let { "#${it.number}" } ?: branch.name
            annotated
        }
        return stack.copy(branches = branches)
    }
}

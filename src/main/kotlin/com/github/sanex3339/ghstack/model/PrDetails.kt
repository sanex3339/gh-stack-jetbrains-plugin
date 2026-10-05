package com.github.sanex3339.ghstack.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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

/**
 * One GraphQL request for all of a stack's pull requests (`gh api graphql -f query=…`), plus one request per
 * further page of checks for pull requests with more than 100.
 *
 * The checks state is worked out from the current runs rather than taken from GitHub's `statusCheckRollup.state`:
 * that summary counts runs a newer run replaced and cancelled runs, so it says FAILURE while the pull request page
 * shows no failing check. Like the page, a cancelled check only counts when it's required.
 */
object PrDetailsQuery {
    private const val PAGE_SIZE = 100
    private const val MAX_EXTRA_PAGES = 10

    private fun contextFields(number: Int) = "pageInfo { hasNextPage endCursor } nodes { __typename" +
        " ... on CheckRun { databaseId name conclusion status completedAt isRequired(pullRequestNumber: $number)" +
        " checkSuite { workflowRun { databaseId event workflow { name } } } }" +
        " ... on StatusContext { context state createdAt isRequired(pullRequestNumber: $number) } }"

    fun build(repo: RepoCoordinates, numbers: List<Int>): String = buildString {
        append(repositoryHeader(repo))
        numbers.distinct().forEach { number ->
            append(" pr").append(number).append(": pullRequest(number: ").append(number).append(") {")
            append(" number title isDraft reviewDecision mergeable")
            append(" commits(last: 1) { nodes { commit { statusCheckRollup { state contexts(first: ").append(PAGE_SIZE).append(") { ")
            append(contextFields(number)).append(" } } } } } }")
        }
        append(" } }")
    }

    /** The next page of one pull request's checks. */
    fun buildMoreChecks(repo: RepoCoordinates, number: Int, after: String): String = buildString {
        append(repositoryHeader(repo))
        append(" pullRequest(number: ").append(number).append(") {")
        append(" commits(last: 1) { nodes { commit { statusCheckRollup { contexts(first: ").append(PAGE_SIZE)
        append(", after: ").append(JsonPrimitive(after)).append(") { ")
        append(contextFields(number)).append(" } } } } } } } }")
    }

    /**
     * PR number → details, reading further pages of checks through [graphql] (a query in, the response or `null`
     * out). `null` when GitHub didn't answer the first request.
     */
    fun fetch(repo: RepoCoordinates, numbers: List<Int>, graphql: (String) -> String?): Map<Int, PrDetails>? {
        val prs = parseRaw(graphql(build(repo, numbers)) ?: return null)
        prs.forEach { pr ->
            var pages = 0
            while (pages++ < MAX_EXTRA_PAGES) {
                val cursor = pr.nextCursor ?: break
                val page = graphql(buildMoreChecks(repo, pr.number, cursor))
                    ?.let { parseJson(it)?.obj("data")?.obj("repository")?.obj("pullRequest")?.contextsPage() }
                    ?: break
                pr.contexts += page.nodes
                pr.nextCursor = page.nextCursor
            }
        }
        return prs.associate { it.number to it.toDetails() }
    }

    /** PR number → details from one response; PRs GitHub couldn't resolve are left out. Bad JSON gives an empty map. */
    fun parse(json: String): Map<Int, PrDetails> = parseRaw(json).associate { it.number to it.toDetails() }

    private class RawPr(val json: JsonObject, val number: Int, val rollup: String?, val contexts: MutableList<JsonObject>, var nextCursor: String?)

    private class ContextsPage(val nodes: List<JsonObject>, val nextCursor: String?)

    private fun parseRaw(json: String): List<RawPr> {
        val repository = parseJson(json)?.obj("data")?.obj("repository") ?: return emptyList()
        return repository.values.mapNotNull { node ->
            val pr = node as? JsonObject ?: return@mapNotNull null
            val number = (pr["number"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val page = pr.contextsPage()
            RawPr(pr, number, pr.rollup()?.string("state"), page?.nodes.orEmpty().toMutableList(), page?.nextCursor)
        }
    }

    private fun parseJson(json: String): JsonObject? = try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun JsonObject.rollup(): JsonObject? =
        ((obj("commits")?.get("nodes") as? JsonArray)?.firstOrNull() as? JsonObject)?.obj("commit")?.obj("statusCheckRollup")

    private fun JsonObject.contextsPage(): ContextsPage? {
        val connection = rollup()?.obj("contexts") ?: return null
        val pageInfo = connection.obj("pageInfo")
        val hasNext = (pageInfo?.get("hasNextPage") as? JsonPrimitive)?.booleanOrNull == true
        return ContextsPage(
            nodes = (connection["nodes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject },
            nextCursor = pageInfo?.string("endCursor")?.takeIf { hasNext },
        )
    }

    private fun RawPr.toDetails(): PrDetails {
        val latest = latestRuns(contexts)
        val failing = latest.filter { it.isFailingCheck() }
        val pending = latest.filter { it.isPendingCheck() }
        // Without every page the newest runs aren't known, so GitHub's own summary is the better guess.
        val complete = nextCursor == null
        val checks = when {
            !complete || latest.isEmpty() -> rollupChecks(rollup)
            failing.isNotEmpty() -> ChecksState.FAILING
            pending.isNotEmpty() || rollup == "EXPECTED" -> ChecksState.PENDING
            else -> ChecksState.PASSING
        }
        return PrDetails(
            number = number,
            isDraft = (json["isDraft"] as? JsonPrimitive)?.booleanOrNull == true,
            review = when (json.string("reviewDecision")) {
                "APPROVED" -> ReviewState.APPROVED
                "CHANGES_REQUESTED" -> ReviewState.CHANGES_REQUESTED
                "REVIEW_REQUIRED" -> ReviewState.REVIEW_REQUIRED
                else -> ReviewState.NONE
            },
            checks = checks,
            conflicting = json.string("mergeable") == "CONFLICTING",
            title = json.string("title").orEmpty(),
            failingChecks = failing.map { it.checkName() }.distinct(),
            pendingChecks = pending.map { it.checkName() }.distinct(),
        )
    }

    private fun rollupChecks(state: String?): ChecksState = when (state) {
        "SUCCESS" -> ChecksState.PASSING
        "FAILURE", "ERROR" -> ChecksState.FAILING
        "PENDING", "EXPECTED" -> ChecksState.PENDING
        else -> ChecksState.NONE
    }

    /**
     * The checks that count, in the order GitHub listed them. A check is a job name within one workflow and
     * triggering event; of its runs, the one that completed last counts, and one still going is newer than any
     * finished one. When a workflow ran more than once for the commit, its cancelled checks don't count: a concurrency
     * group cancelled them in favour of another run, which may be older (a newer run cancelled before it started) or
     * not have reached that job yet (result jobs waiting on the tests).
     */
    private fun latestRuns(contexts: List<JsonObject>): List<JsonObject> {
        val liveRuns = HashMap<List<String?>, MutableSet<Long>>()
        contexts.filter { it.string("conclusion") != "CANCELLED" }.forEach { context ->
            val run = context.workflowRunId() ?: return@forEach
            liveRuns.getOrPut(context.workflowKey()) { mutableSetOf() } += run
        }
        fun replaced(context: JsonObject): Boolean {
            if (context.string("conclusion") != "CANCELLED") return false
            val run = context.workflowRunId() ?: return false
            return liveRuns[context.workflowKey()].orEmpty().any { it != run }
        }
        val newest = LinkedHashMap<List<String?>, JsonObject>()
        contexts.filterNot(::replaced).forEach { context ->
            val key = context.checkKey()
            val current = newest[key]
            if (current == null || context.isNewerThan(current)) newest[key] = context
        }
        return newest.values.toList()
    }

    private fun JsonObject.checkKey(): List<String?> = listOf(string("__typename")) + workflowKey() + checkName()

    private fun JsonObject.workflowKey(): List<String?> {
        val run = obj("checkSuite")?.obj("workflowRun")
        return listOf(run?.obj("workflow")?.string("name"), run?.string("event"))
    }

    private fun JsonObject.workflowRunId(): Long? =
        (obj("checkSuite")?.obj("workflowRun")?.get("databaseId") as? JsonPrimitive)?.longOrNull

    private fun JsonObject.isNewerThan(other: JsonObject): Boolean {
        val done = finishedAt()
        val otherDone = other.finishedAt()
        if (done != otherDone) return done == null || (otherDone != null && done > otherDone)
        val id = (this["databaseId"] as? JsonPrimitive)?.longOrNull ?: 0
        val otherId = (other["databaseId"] as? JsonPrimitive)?.longOrNull ?: 0
        return id > otherId
    }

    /** ISO-8601 UTC, so it compares as text; `null` while a check run is still going. */
    private fun JsonObject.finishedAt(): String? = string("completedAt") ?: string("createdAt")

    private fun JsonObject.checkName(): String = string("name") ?: string("context") ?: "check"

    private fun JsonObject.isFailingCheck(): Boolean = when (string("conclusion")) {
        in FAILING_CONCLUSIONS -> true
        "CANCELLED" -> (this["isRequired"] as? JsonPrimitive)?.booleanOrNull == true
        else -> string("state") in setOf("FAILURE", "ERROR")
    }

    private fun JsonObject.isPendingCheck(): Boolean =
        (string("__typename") == "CheckRun" && string("status") != "COMPLETED") || string("state") in setOf("PENDING", "EXPECTED")

    private val FAILING_CONCLUSIONS = setOf("FAILURE", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE")

    private fun repositoryHeader(repo: RepoCoordinates) =
        "query { repository(owner: ${JsonPrimitive(repo.owner)}, name: ${JsonPrimitive(repo.name)}) {"

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

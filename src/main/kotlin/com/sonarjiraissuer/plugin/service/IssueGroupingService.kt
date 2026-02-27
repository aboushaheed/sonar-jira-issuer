package com.sonarjiraissuer.plugin.service

import com.sonarjiraissuer.plugin.api.dto.SonarIssue
import com.sonarjiraissuer.plugin.model.IssueGroup
import com.sonarjiraissuer.plugin.util.EffortParser

/**
 * Groups a flat list of [SonarIssue] objects into batches suitable for Jira ticket creation.
 *
 * Two entry points are provided:
 * - [group]         — batch a homogeneous list (already filtered by type).
 * - [groupByType]   — partition by issue type then batch each partition separately.
 */
object IssueGroupingService {

    /**
     * Split [issues] into consecutive batches of at most [batchSize] items.
     *
     * @param issues        Source issues (should all belong to [issueTypeName]).
     * @param batchSize     Maximum issues per batch. Must be > 0.
     * @param issueTypeName Label used when constructing [IssueGroup] metadata.
     * @return              Ordered list of [IssueGroup], one per batch. Empty if [issues] is empty.
     * @throws IllegalArgumentException if [batchSize] ≤ 0.
     */
    fun group(
        issues: List<SonarIssue>,
        batchSize: Int,
        issueTypeName: String
    ): List<IssueGroup> {
        require(batchSize > 0) { "batchSize must be a positive integer, got $batchSize" }
        if (issues.isEmpty()) return emptyList()

        val chunks       = issues.chunked(batchSize)
        val totalBatches = chunks.size

        return chunks.mapIndexed { index, batch ->
            val totalEffortMinutes = batch.sumOf { EffortParser.parseToMinutes(it.effectiveEffort()) }
            val storyPoints        = StoryPointCalculator.calculate(totalEffortMinutes)

            IssueGroup(
                batchIndex         = index + 1,
                totalBatches       = totalBatches,
                issueType          = issueTypeName,
                issues             = batch,
                totalEffortMinutes = totalEffortMinutes,
                storyPoints        = storyPoints
            )
        }
    }

    /**
     * Partition [issues] by their [SonarIssue.type], then batch each partition independently.
     *
     * @return Map from issue-type string to its list of [IssueGroup] batches.
     *         The ordering within each type list is stable and 1-indexed.
     */
    fun groupByType(issues: List<SonarIssue>, batchSize: Int): Map<String, List<IssueGroup>> {
        return issues
            .groupBy { it.type }
            .mapValues { (type, typeIssues) -> group(typeIssues, batchSize, type) }
    }
}

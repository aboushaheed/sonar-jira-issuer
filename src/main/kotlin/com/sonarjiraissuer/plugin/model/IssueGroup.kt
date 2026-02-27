package com.sonarjiraissuer.plugin.model

import com.sonarjiraissuer.plugin.api.dto.SonarIssue

/**
 * A batch of SonarQube issues to be represented as a single Jira ticket.
 *
 * @property batchIndex     1-based index of this batch within its type group.
 * @property totalBatches   Total number of batches for this issue type.
 * @property issueType      SonarQube type string (e.g. "CODE_SMELL").
 * @property issues         Issues contained in this batch.
 * @property totalEffortMinutes  Sum of all issue efforts, in minutes.
 * @property storyPoints    Story points derived from total effort (1 SP = 480 min).
 */
data class IssueGroup(
    val batchIndex: Int,
    val totalBatches: Int,
    val issueType: String,
    val issues: List<SonarIssue>,
    val totalEffortMinutes: Long,
    val storyPoints: Double
)

package com.sonarjiraissuer.plugin.api.dto

/**
 * Top-level response from `/api/issues/search`.
 *
 * The API returns both a legacy flat `total`/`p`/`ps` structure and a `paging` sub-object.
 * [totalCount], [currentPage], and [pageSize] normalise access to both formats.
 */
data class SonarIssuesResponse(
    val total: Int = 0,
    val p: Int = 1,
    val ps: Int = 100,
    val paging: Paging? = null,
    val issues: List<SonarIssue> = emptyList()
) {
    data class Paging(
        val pageIndex: Int = 1,
        val pageSize: Int = 100,
        val total: Int = 0
    )

    fun totalCount(): Int  = paging?.total ?: total
    fun currentPage(): Int = paging?.pageIndex ?: p
    fun pageSize(): Int    = paging?.pageSize ?: ps
    fun hasMorePages(): Boolean = currentPage() * pageSize() < totalCount()
}

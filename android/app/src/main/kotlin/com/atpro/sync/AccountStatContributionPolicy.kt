package com.atpro.sync

/** Result of merging the independently versioned account status fields. */
data class AccountStatusValue(
    val status: String,
    val checkpoint: Boolean,
    val updatedAt: Long,
)

sealed interface AccountStatusMergeResult {
    data class Resolved(val value: AccountStatusValue) : AccountStatusMergeResult
    data object Conflict : AccountStatusMergeResult
}

/**
 * Pure multi-installation account merge rules.
 *
 * Status/checkpoint disagreement is always unresolved across snapshots: device wall clocks
 * are not causal ordering. Matching values may retain the maximum timestamp as metadata.
 * Cumulative counters merge by component-wise MAX for the same installation key,
 * never by summing two snapshots of the same installation.
 */
object AccountStatContributionPolicy {
    data class Totals(
        val sessionsCount: Long,
        val totalLikes: Long,
        val totalFollows: Long,
        val totalVideos: Long,
        val updatedAt: Long,
    )

    fun mergeStatus(
        local: AccountStatusValue,
        remote: AccountStatusValue,
    ): AccountStatusMergeResult =
        if (local.status == remote.status && local.checkpoint == remote.checkpoint) {
            AccountStatusMergeResult.Resolved(
                local.copy(updatedAt = maxOf(local.updatedAt, remote.updatedAt))
            )
        } else {
            AccountStatusMergeResult.Conflict
        }

    fun mergeContributions(
        local: List<BackupAccountStatContribution>,
        remote: List<BackupAccountStatContribution>,
    ): List<BackupAccountStatContribution> {
        val merged = LinkedHashMap<Pair<String, String>, BackupAccountStatContribution>()
        (local + remote).forEach { incoming ->
            val key = incoming.accountId to incoming.installationId
            val existing = merged[key]
            merged[key] = if (existing == null) incoming else BackupAccountStatContribution(
                accountId = incoming.accountId,
                installationId = incoming.installationId,
                sessionsCount = maxOf(existing.sessionsCount, incoming.sessionsCount),
                totalLikes = maxOf(existing.totalLikes, incoming.totalLikes),
                totalFollows = maxOf(existing.totalFollows, incoming.totalFollows),
                totalVideos = maxOf(existing.totalVideos, incoming.totalVideos),
                updatedAt = maxOf(existing.updatedAt, incoming.updatedAt),
            )
        }
        return merged.values.sortedWith(compareBy({ it.accountId }, { it.installationId }))
    }

    fun aggregate(contributions: List<BackupAccountStatContribution>): Totals {
        var sessions = 0L
        var likes = 0L
        var follows = 0L
        var videos = 0L
        var updatedAt = 0L
        contributions.forEach {
            sessions = saturatingAdd(sessions, it.sessionsCount)
            likes = saturatingAdd(likes, it.totalLikes)
            follows = saturatingAdd(follows, it.totalFollows)
            videos = saturatingAdd(videos, it.totalVideos)
            updatedAt = maxOf(updatedAt, it.updatedAt)
        }
        return Totals(sessions, likes, follows, videos, updatedAt)
    }

    fun toDisplayInt(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (right <= 0L) return left
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}

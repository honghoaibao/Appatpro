package com.atpro.sync

/**
 * Cloud keeps closed-session history long-term. A device keeps only the latest 90 days;
 * pending session UPSERTs are temporarily retained locally until the server confirms them.
 *
 * The cutoff intentionally uses startedAt, matching the existing Room cleanup contract.
 * Timestamps are used only for local retention and never for cross-device merging.
 */
object BackupSessionRetentionPolicy {
    const val RETENTION_DAYS = 90L
    const val RETENTION_WINDOW_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1_000L

    fun cutoff(nowEpochMillis: Long): Long =
        nowEpochMillis - RETENTION_WINDOW_MILLIS

    fun shouldKeepLocally(
        startedAtEpochMillis: Long,
        nowEpochMillis: Long,
    ): Boolean = startedAtEpochMillis >= cutoff(nowEpochMillis)

    fun retainLocally(
        sessions: List<BackupClosedSession>,
        nowEpochMillis: Long,
    ): List<BackupClosedSession> {
        val cutoff = cutoff(nowEpochMillis)
        return sessions.filter { it.startedAt >= cutoff }
    }
}

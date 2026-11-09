package com.atpro.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atpro.db.entity.AccountStatContributionEntity

@Dao
interface AccountStatContributionDao {
    /** Insert a local baseline once; never overwrite an existing installation contribution. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureBaseline(contribution: AccountStatContributionEntity): Long

    @Query("SELECT * FROM account_stat_contributions WHERE accountId = :accountId ORDER BY installationId ASC")
    suspend fun getForAccount(accountId: String): List<AccountStatContributionEntity>

    @Query("SELECT * FROM account_stat_contributions ORDER BY accountId ASC, installationId ASC")
    suspend fun getAll(): List<AccountStatContributionEntity>

    /**
     * Monotonic, saturating increments. Inputs must be non-negative and are validated by
     * LocalRepository before entering this transaction.
     */
    @Query("""
        UPDATE account_stat_contributions SET
            sessionsCount = CASE
                WHEN sessionsCount > 9223372036854775806 THEN 9223372036854775807
                ELSE sessionsCount + 1
            END,
            totalLikes = CASE
                WHEN totalLikes > 9223372036854775807 - :likes THEN 9223372036854775807
                ELSE totalLikes + :likes
            END,
            totalFollows = CASE
                WHEN totalFollows > 9223372036854775807 - :follows THEN 9223372036854775807
                ELSE totalFollows + :follows
            END,
            totalVideos = CASE
                WHEN totalVideos > 9223372036854775807 - :videos THEN 9223372036854775807
                ELSE totalVideos + :videos
            END,
            updatedAt = MAX(updatedAt, :updatedAt)
        WHERE accountId = :accountId AND installationId = :installationId
    """)
    suspend fun addSessionContribution(
        accountId: String,
        installationId: String,
        likes: Long,
        follows: Long,
        videos: Long,
        updatedAt: Long,
    ): Int
}

package com.atpro.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Monotonic cumulative contribution made by one installation for one account.
 * Values are Long so the per-installation history does not inherit the UI's Int ceiling.
 */
@Entity(
    tableName = "account_stat_contributions",
    primaryKeys = ["accountId", "installationId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["username"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["accountId"])],
)
data class AccountStatContributionEntity(
    val accountId: String,
    val installationId: String,
    val sessionsCount: Long = 0L,
    val totalLikes: Long = 0L,
    val totalFollows: Long = 0L,
    val totalVideos: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
)

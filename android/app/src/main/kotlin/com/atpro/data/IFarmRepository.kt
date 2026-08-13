package com.atpro.data

import com.atpro.db.entity.AccountEntity

/**
 * IFarmRepository — data operations cần bởi AutomationEngine.
 *
 * Tách AutomationEngine khỏi Room/Context để unit test được với MockK.
 *
 * Production: LocalRepository implements IFarmRepository
 * Unit tests:  MockK mock
 */
interface IFarmRepository {
    suspend fun loadFarmConfig(): FarmConfig
    suspend fun getAccounts(): List<AccountEntity>
    suspend fun addAccount(username: String)
    suspend fun startSession(accountId: String): Long
    suspend fun closeSession(
        sessionId: Long,
        accountId: String,
        likes:     Int,
        follows:   Int,
        videos:    Int,
        comments:  Int = 0,
    )
    suspend fun setCheckpoint(username: String, isCheckpoint: Boolean)
    suspend fun log(message: String, level: String = "INFO", accountId: String? = null)
}

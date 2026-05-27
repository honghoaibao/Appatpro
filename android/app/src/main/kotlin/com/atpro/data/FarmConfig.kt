package com.atpro.data

/**
 * FarmConfig — cấu hình farm, load từ Room DB qua LocalRepository.loadFarmConfig()
 */
data class FarmConfig(
    val minutesPerAccount:          Int     = 5,
    val videoWatchTimeMin:          Double  = 3.0,
    val videoWatchTimeMax:          Double  = 8.0,
    val likeRate:                   Float   = 0.30f,
    val followRate:                 Float   = 0.15f,
    val enableRestBetweenAccounts:  Boolean = false,
    val restDurationMinutes:        Int     = 2,
    val maxBackAttempts:            Int     = 5,
    val skipLive:                   Boolean = true,
    val enableVerifyAccount:        Boolean = true,
    val delayAfterLike:             Double  = 0.5,
    val delayAfterFollow:           Double  = 1.0,
    val delayAfterSwitchClick:      Double  = 3.0,
)

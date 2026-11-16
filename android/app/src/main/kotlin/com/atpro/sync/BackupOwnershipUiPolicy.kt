package com.atpro.sync

/**
 * Prevents stale ownership state from leaking into Settings while Auth owner resolution
 * is in flight. A status belongs to the currently resolved Auth identity only.
 */
object BackupOwnershipUiPolicy {
    fun resolve(
        authUserId: String?,
        resolvedAuthUserId: String?,
        actualState: BackupOwnershipState,
    ): BackupOwnershipState {
        val activeOwner = BackupMetadataRules.normalizeAuthUserId(authUserId)
            ?: return if (authUserId.isNullOrBlank()) {
                BackupOwnershipState.SignedOut
            } else {
                BackupOwnershipState.InvalidAuthIdentity
            }
        val resolvedOwner = BackupMetadataRules.normalizeAuthUserId(resolvedAuthUserId)
        return if (resolvedOwner != activeOwner) {
            BackupOwnershipState.Checking
        } else {
            actualState
        }
    }
}

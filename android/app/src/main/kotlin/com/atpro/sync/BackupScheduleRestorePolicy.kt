package com.atpro.sync

/**
 * Restore policy for scheduled automation across installations.
 *
 * A schedule already present on this installation is user-owned local state: preserve its
 * enabled flag whether or not it was present in the staged snapshot. A new cloud schedule is
 * imported disabled. A durable tombstone always wins and requires cancellation of its work.
 */
internal enum class BackupScheduleRestoreAction {
    PRESERVE_LOCAL,
    IMPORT_DISABLED,
    REMOVE_LOCAL,
    NO_OP,
}

internal object BackupScheduleRestorePolicy {
    fun decide(
        hasLocalSchedule: Boolean,
        hasIncomingSchedule: Boolean,
        deletedByTombstone: Boolean,
    ): BackupScheduleRestoreAction = when {
        deletedByTombstone -> BackupScheduleRestoreAction.REMOVE_LOCAL
        hasLocalSchedule -> BackupScheduleRestoreAction.PRESERVE_LOCAL
        hasIncomingSchedule -> BackupScheduleRestoreAction.IMPORT_DISABLED
        else -> BackupScheduleRestoreAction.NO_OP
    }
}

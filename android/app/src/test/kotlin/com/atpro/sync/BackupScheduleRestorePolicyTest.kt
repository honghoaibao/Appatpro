package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupScheduleRestorePolicyTest {
    @Test
    fun existingLocalScheduleKeepsItsLocalActivationChoiceWhenCloudHasSameDefinition() {
        assertEquals(
            BackupScheduleRestoreAction.PRESERVE_LOCAL,
            BackupScheduleRestorePolicy.decide(
                hasLocalSchedule = true,
                hasIncomingSchedule = true,
                deletedByTombstone = false,
            ),
        )
    }

    @Test
    fun localScheduleCreatedAfterSnapshotCaptureIsPreserved() {
        assertEquals(
            BackupScheduleRestoreAction.PRESERVE_LOCAL,
            BackupScheduleRestorePolicy.decide(
                hasLocalSchedule = true,
                hasIncomingSchedule = false,
                deletedByTombstone = false,
            ),
        )
    }

    @Test
    fun scheduleImportedFromAnotherInstallationIsDisabled() {
        assertEquals(
            BackupScheduleRestoreAction.IMPORT_DISABLED,
            BackupScheduleRestorePolicy.decide(
                hasLocalSchedule = false,
                hasIncomingSchedule = true,
                deletedByTombstone = false,
            ),
        )
    }

    @Test
    fun tombstoneWinsOverLocalAndIncomingScheduleState() {
        assertEquals(
            BackupScheduleRestoreAction.REMOVE_LOCAL,
            BackupScheduleRestorePolicy.decide(
                hasLocalSchedule = true,
                hasIncomingSchedule = true,
                deletedByTombstone = true,
            ),
        )
    }

    @Test
    fun absentScheduleWithoutTombstoneIsANoOp() {
        assertEquals(
            BackupScheduleRestoreAction.NO_OP,
            BackupScheduleRestorePolicy.decide(
                hasLocalSchedule = false,
                hasIncomingSchedule = false,
                deletedByTombstone = false,
            ),
        )
    }
}

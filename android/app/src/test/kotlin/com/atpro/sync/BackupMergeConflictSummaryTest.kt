package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeConflictSummaryTest {
    @Test
    fun formatsStableDatasetEntityAndConflictLabelsWithoutValues() {
        val result = BackupMergeConflictSummary.format(
            listOf(
                BackupMergeConflict(
                    dataset = BackupTombstoneDataset.ACCOUNTS,
                    entityKey = "sample_user",
                    kind = BackupMergeConflictKind.ACCOUNT_STATUS,
                ),
                BackupMergeConflict(
                    dataset = BackupTombstoneDataset.SAFE_CONFIGS,
                    entityKey = "like_rate",
                    kind = BackupMergeConflictKind.CONFIG_VALUE,
                ),
            )
        )

        assertEquals(
            "Tài khoản [sample_user]: trạng thái tài khoản khác nhau; Cấu hình [like_rate]: giá trị cấu hình khác nhau",
            result,
        )
        assertTrue(result!!.length <= BackupMergeConflictSummary.MAX_SUMMARY_LENGTH)
    }

    @Test
    fun removesControlCharactersAndBoundsEntityKeys() {
        val rawKey = "schedule\n" + "x".repeat(100)
        val result = BackupMergeConflictSummary.format(
            listOf(
                BackupMergeConflict(
                    dataset = BackupTombstoneDataset.SCHEDULES,
                    entityKey = rawKey,
                    kind = BackupMergeConflictKind.SCHEDULE_DEFINITION,
                )
            )
        )

        assertTrue(result!!.contains("Lịch tự động"))
        assertTrue(result.contains("schedule" + "x".repeat(BackupMergeConflictSummary.MAX_KEY_LENGTH - 8)))
        assertTrue(result.length <= BackupMergeConflictSummary.MAX_SUMMARY_LENGTH)
        assertTrue(!result.contains("\n"))
    }

    @Test
    fun limitsRenderedItemsAndReportsHowManyRemain() {
        val conflicts = (1..5).map {
            BackupMergeConflict(
                dataset = BackupTombstoneDataset.ACCOUNTS,
                entityKey = "user_$it",
                kind = BackupMergeConflictKind.ACCOUNT_STATUS,
            )
        }

        val result = BackupMergeConflictSummary.format(conflicts)

        assertEquals(3, result!!.split("; ").size)
        assertTrue(result.contains("(+2 mục khác)"))
        assertTrue(result.length <= BackupMergeConflictSummary.MAX_SUMMARY_LENGTH)
    }

    @Test
    fun emptyConflictSetHasNoDiagnostic() {
        assertNull(BackupMergeConflictSummary.format(emptyList()))
    }

    @Test
    fun unknownDatasetDoesNotEchoAnUntrustedDatasetName() {
        val result = BackupMergeConflictSummary.format(
            listOf(
                BackupMergeConflict(
                    dataset = "unexpected-secret-looking-dataset",
                    entityKey = "entity",
                    kind = BackupMergeConflictKind.DATASET_LIMIT_EXCEEDED,
                )
            )
        )
        assertTrue(result!!.startsWith("Dữ liệu [entity]"))
        assertTrue(!result.contains("unexpected-secret-looking-dataset"))
    }
}

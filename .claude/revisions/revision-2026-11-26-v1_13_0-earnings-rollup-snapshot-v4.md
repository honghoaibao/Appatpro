# Revision v1.13.0 — Nén thu nhập cũ thành dòng tổng, snapshot v4 (2026-11-26)

Nhánh: `v1.13.0-earnings-rollup` (từ `v1.12.7-popup-cookie-withdraw`). **Chưa chạy Gradle/test được trong sandbox — cần CI.**

## Vì sao
Người dùng muốn giữ lịch sử thu nhập quá mốc 25.000 dòng: khi gần giới hạn thì tạo bản tổng hợp đầy đủ rồi xoá các dòng cũ; 60 ngày gần nhất giữ dạng thô (khớp biểu đồ). Xem ADR-0029.

## Thay đổi
- `data/EarningsRollup.kt` (mới, thuần): `EarningRollup`, `EarningsRollupPolicy` (ngày UTC, id tất định, nén, luật phủ, gộp).
- `data/EarningsStore.kt`: dòng `v3|…|origin`; file `earnings.rollup`, `earnings.origin`; `compactIfNeeded`; `mergeFromRestore(entries, rollups)`; `readSnapshot().rollups`; `clear()` xoá cả dòng tổng.
- `data/EarningsStats.kt`: `summarizeEarnings(..., rollups)`; `ui/stats/StatsViewModel.kt` đọc một lần cả hai.
- `sync/BackupSnapshot.kt`: format 4; `BackupSnapshotV3`, `BackupEarningLegacy`; `earningRollups`, `BackupEarning.origin`; `SUPPORTED_WIRE_VERSIONS`.
- `sync/BackupCodec.kt` (nhánh đọc v3), `BackupSnapshotValidator.kt` (validateV3 + kiểm tra dòng tổng), `BackupMergeEngine.kt` (gộp dòng tổng, loại dòng thô bị phủ, origin không tính là xung đột), `BackupSnapshotBuilder.kt`, `BackupRestoreCoordinator.kt`, `BackupRestoreJournalCodec.kt`, `BackupCasCoordinator.kt`, `SupabaseBackupRemoteStore.kt`, `SyncWorker.kt` (nén trước khi chụp snapshot).
- Test mới: `EarningsRollupPolicyTest`, `EarningsStatsRollupTest`, `BackupSnapshotV3CompatibilityTest`; bổ sung `BackupMergeEngineTest`, `BackupSnapshotValidatorTest`; `BackupSnapshotV2CompatibilityTest` đổi "phiên bản tương lai" từ 4 → 5.
- Docs: ADR-0029, `cloud_sync_architecture.md` (mục 4).

## Tác động tới thiết bị
App < v1.13.0 không đọc được snapshot v4 → cập nhật tất cả thiết bị. Server không cần migration.

## Rollback
Revert nhánh. Snapshot v4 đã tải lên đám mây sẽ không đọc được bởi bản cũ; nếu cần quay lại hãy giữ bản v1.13.x trên mọi máy hoặc xoá bản sao lưu trên đám mây rồi để máy chủ lực đẩy lại.

## Rủi ro / việc sau
Không bump `versionName`/`versionCode` (vẫn v1.12.6 trong build.gradle) — để chủ app quyết định khi phát hành. Dòng cũ không có `origin` không nén được. Cần nén theo tháng khi số dòng tổng lớn. Chưa có test cho `EarningsStore` (phụ thuộc Android `AtomicFile`) — nên thêm test thiết bị/Robolectric.

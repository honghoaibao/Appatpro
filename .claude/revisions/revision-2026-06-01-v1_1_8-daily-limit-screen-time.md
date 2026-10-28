# Revision — v1.1.8 (2026-06-01) — Daily Screen Time Limit

> Chuyển nguyên văn từ mục Changelog của README.md (2026-10-26) để README gọn lại; nội dung không đổi.

**Bug fix — Daily Screen Time Limit (hình chụp)**

| # | File | Thay đổi |
|---|------|----------|
| 1 | `NodeTraverser.kt` | Thêm `PopupType.DAILY_LIMIT`; tách nhánh 4-EditText: kiểm tra `DAILY_LIMIT_SIGNALS` trước khi kết luận `VERIFY_1234` |
| 2 | `NodeTraverser.kt` | Thêm `detectDailyLimitScreen()`, `findReturnToTikTokButton()` |
| 3 | `PopupHandler.kt` | Thêm `handleDailyLimit()`: gõ passcode "1234" → click "Quay lại TikTok" |
| 4 | `PopupHandler.kt` | Thêm Tier 2 keyword fallback cho daily limit |
| 5 | `AutomationEngine.kt` | Main loop step [2c]: phát hiện + xử lý daily limit trước `isLostWithRetry()` |
| 6 | `AutomationEngine.kt` | `recoverToFeed()` Tier 0a: daily limit trước Tier 0b (wellbeing) |
| 7 | `AutomationEngine.kt` | Cache `getRootNode()` 1 lần/iteration → giảm IPC call từ ~6 xuống ~2 |

**Optimizations**

| # | File | Thay đổi |
|---|------|----------|
| 8 | `FarmForegroundService.kt` | Heartbeat 10s → 15s — giảm wake count, tiết kiệm pin |
| 9 | `OverlayFarmMonitor.kt` | `update()` dirty-check: skip `handler.post` khi không có gì thay đổi |
| 10 | `OverlayFarmMonitor.kt` | `addLog()` dedup: bỏ qua message trùng dòng cuối; lọc thêm `WDG:`, `RECOVER:` noise |

**Root cause của bug:** Màn hình Daily Limit có 4 `EditText` → `detectPopup()` nhận diện là `VERIFY_1234` → `handle1234()` gõ "1234" nhưng không tìm được nút "Confirm" (không tồn tại) → `pressBack()` → đóng TikTok.

**Fix:** Phân biệt hai màn hình bằng context text trước khi kiểm tra số EditText. Daily Limit luôn chứa "quay lại tiktok"/"nhập mật mã"/... — các từ này không xuất hiện trên VERIFY_1234.

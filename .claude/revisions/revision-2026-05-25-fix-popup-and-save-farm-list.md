# Revision: BUG-FARM-005 + BUG-FARM-006

_Date: 2026-05-25 | Session: 15_

## Bugs fixed

### BUG-FARM-005 — Popup stuck / stats frozen during farm

**Symptom**: Popup TikTok xuất hiện nhưng không bị dismiss, overlay stats đóng băng.

**Root cause**: Trong `farmOneAccount()`, thứ tự kiểm tra sai:
1. `isLost()` được gọi TRƯỚC `popup.handleIfPresent()`
2. Popup full-screen khiến `hasNavBar() = false` → `isLost() = true`
3. `recoverToFeed()` kill TikTok thay vì dismiss popup
4. TikTok relaunch → popup xuất hiện lại → loop vô tận
5. Ngoài ra: result của `popup.handleIfPresent()` bị ignore → code tiếp tục delay/like/swipe trong khi popup còn trên màn hình

**Fix** (`AutomationEngine.kt`):
- Move `popup.handleIfPresent()` TRƯỚC `isLost()` check
- Lưu result vào `val popupResult`, nếu `handled = true` → `continue` để re-evaluate state
- `isLost()` chỉ chạy sau khi đã chắc chắn không có popup

**Files changed**: `AutomationEngine.kt` (lines ~162-169)

---

### BUG-FARM-006 — SELECTED_LIST không check id / không lưu account vào DB

**Symptom**: Farm theo danh sách nhập tay không thực hiện check id và không lưu account vào DB.

**Root cause**: `autoSaveAccounts()` chỉ chạy khi `parseAccountList()` tìm được `@username` nodes trong switch popup. Khi TikTok hiển thị display name thay `@username`, hoặc chỉ 1 account logged in → `parseAccountList()` trả empty → không gì được lưu.

**Fix** (`AutomationEngine.kt`):
- Sau khi switch thành công + `waitFeedLoad()` OK: gọi `verifyAndSaveCurrentAccount(expected)`
- Hàm mới này: tìm profile tab → click → đọc `@username` thật từ profile (NodeTraverser.getCurrentAccountId)
- Auto-save `actualId` vào DB qua `autoSaveAccounts()`
- Log mismatch nếu `actualId != expected`
- Fallback: nếu profile tab null hoặc username null → save `expected` để đảm bảo tối thiểu account được ghi nhận

**Files changed**: `AutomationEngine.kt` (thêm `verifyAndSaveCurrentAccount()`, sửa `switchToAccountViaSettings()`)

---

## Test cases

| Case | Expected |
|------|----------|
| Popup overlay (Follow friends) xuất hiện | Popup được dismiss, loop `continue`, farm tiếp tục từ đầu iteration |
| Popup full-screen xuất hiện | Popup được dismiss (không bị kill TikTok), stats không tích sai |
| SELECTED_LIST, switch thành công | Profile tab được click, @username đọc ra, saved vào DB |
| SELECTED_LIST, profile tab không tìm thấy | expected name được saved vào DB (fallback) |
| SELECTED_LIST, @username mismatch | Log warning ⚠️, actual id được saved |

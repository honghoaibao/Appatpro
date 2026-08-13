# Revision: Fix 3 bugs — pressBack, overlay popup, signing conflict

**Date:** 2026-05-27  
**Session:** Bug fix session

---

## Bug 1 — App kẹt "Chuẩn bị xem video" + back liên tục

**Root cause:**  
`detectCurrentAccount()` và `verifyAndSaveCurrentAccount()` đều click sang Profile tab
rồi gọi `host.pressBack()` để về feed. Trong TikTok, các tab chính (Home/Profile)
**không push vào Android back stack**. `pressBack()` tại màn hình chính = thoát app.
Hậu quả: TikTok đóng, `farmOneAccount()` loop thấy `isLost()` = true liên tục,
`recoverToFeed()` gọi `pressBack()` nhiều lần → "back liên tục".

**Fix:**  
- Thêm `NodeTraverser.findHomeTab()` tìm tab Home/Feed bằng resource-id + text.  
- Thêm `AutomationEngine.navigateToFeedTab()` click Home tab, fallback relaunch nếu không tìm thấy.  
- Thay thế `host.pressBack(); delay(800)` bằng `navigateToFeedTab()` trong cả 2 hàm.

**Files changed:**  
- `NodeTraverser.kt` — thêm `findHomeTab()`  
- `AutomationEngine.kt` — thêm `navigateToFeedTab()`, cập nhật `detectCurrentAccount()` và `verifyAndSaveCurrentAccount()`

---

## Bug 2 — Popup không hiển thị trạng thái cho đến sau khi xác định acc

**Root cause:**  
`StartupStatusDialog` là Compose Dialog render trong AT Pro Activity.
Sau khi `host.launchTikTok()` được gọi (Phase 1), TikTok lên foreground,
AT Pro Activity xuống background — Dialog không nhìn thấy được.
Popup chỉ hiện khi AT Pro lên lại foreground, mà điều đó xảy ra khi
Bug 1 làm TikTok bị thoát (do pressBack), đúng lúc đang xác nhận acc.

**Fix:**  
- Thêm `OverlayFarmMonitor.setStartupStatus(msg)` — cập nhật `tvAction` trên system overlay (`TYPE_APPLICATION_OVERLAY` hiển thị được trên mọi app).  
- `AutomationEngine.setStatus()` gọi thêm `OverlayFarmMonitor.setStartupStatus(msg)` để trạng thái hiện ngay trên overlay khi TikTok đang ở foreground.

**Files changed:**  
- `OverlayFarmMonitor.kt` — thêm `setStartupStatus()`  
- `AutomationEngine.kt` — cập nhật `setStatus()`

---

## Bug 3 — Xung đột gói khi cập nhật APK cùng version

**Root cause:**  
`build.gradle` fallback về `signingConfigs.debug` khi thiếu `key.properties`.
`~/.android/debug.keystore` là machine-specific — build trên máy A và máy B
cho ra APK ký bởi 2 key khác nhau. Android reject update vì signature mismatch.
Ngoài ra `versionCode 4` không tăng giữa các patch.

**Fix:**  
- `versionCode` tăng lên 5.  
- Thêm `generate_keystore.sh` — script tạo release keystore nhất quán một lần.  
- Comment trong `build.gradle` hướng dẫn setup `key.properties` rõ ràng hơn.  
- Xác nhận `android/key.properties` và `android/**/*.jks` đã có trong `.gitignore`.

**Files changed:**  
- `android/app/build.gradle` — versionCode 5, comment cải thiện  
- `generate_keystore.sh` — file mới  

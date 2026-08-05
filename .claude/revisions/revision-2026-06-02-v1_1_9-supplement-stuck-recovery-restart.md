# Revision — v1.1.9 bổ sung (2026-06-02) — Stuck Recovery + App Restart

## Tóm tắt
Patch bổ sung cho v1.1.9 (không thay đổi versionName/versionCode). Ba thay đổi:

1. **Watchdog kích hoạt smart recovery** thay vì chỉ log khi không thấy video mới.
2. **recoverFromStuck()** — hàm mới: back × maxBackAttempts → popup check → feed check → kill+relaunch.
3. **Restart the app** trước/sau nghỉ — kill TikTok trước khi nghỉ, mở lại sau khi hết thời gian nghỉ.

---

## 1. Watchdog — từ "chỉ log" → "kích hoạt flag"

### Trước
```kotlin
log("WDG: Watchdog: không có video mới trong ${stuckSecs}s — engine có thể bị kẹt")
```

### Sau
```kotlin
log("WDG: Không có video mới trong ${stuckSecs}s — kích hoạt smart recovery")
watchdogStuckFlag = true
watchdogLastTick = System.currentTimeMillis()  // reset để không trigger lại ngay
```

**Thêm volatile field:**
```kotlin
@Volatile private var watchdogStuckFlag = false
```

`resetWatchdog()` cũng được cập nhật để clear flag.

---

## 2. farmOneAccount — Step [3b] Watchdog Stuck Recovery

Thêm giữa step [3] Lost detection và step [4] Update overlay:

```kotlin
// ── [3b] Watchdog stuck recovery ─────────────────────────────
if (watchdogStuckFlag) {
    watchdogStuckFlag = false
    log("WDG: Thực hiện smart recovery (không có video mới quá lâu)")
    val recovered = recoverFromStuck()
    if (!recovered) {
        log("ERR: recoverFromStuck() thất bại — kết thúc sớm @$account")
        break
    }
    resetWatchdog()
    continue
}
```

---

## 3. recoverFromStuck() — hàm mới

```
Quy trình:
  1. pressBack × config.maxBackAttempts (kiểm tra feed sau mỗi lần)
  2. popup.handleIfPresent() — xử lý popup đang chặn
  3. Kiểm tra isOnFeedTab lần cuối
  4. Nếu vẫn chưa về feed → killTikTok() + launchTikTok() + waitFeedLoad()
```

---

## 4. REST phase — Restart the app

### Trước
```
[REST bắt đầu] → đếm ngược → setStatus("")
```

### Sau
```
[REST bắt đầu]
→ killTikTok()                    ← Đóng TikTok hoàn toàn
→ đếm ngược (countdown)
→ launchTikTok()                  ← Mở lại TikTok
→ waitFeedLoad() / recoverToFeed() ← Chờ feed ổn định
→ tiếp tục farm acc tiếp theo
```

---

## Files thay đổi

| File | Loại | Thay đổi |
|---|---|---|
| `AutomationEngine.kt` | Sửa | watchdogStuckFlag; watchdog set flag; step [3b]; recoverFromStuck(); REST kill+relaunch |

---

## Risks / Notes
- `recoverFromStuck()` dùng `config.maxBackAttempts` — cùng setting với `recoverToFeed()` tier 1.
- Nếu app bị lock (Wellbeing, Daily Limit), `popup.handleIfPresent()` ở bước 2 sẽ xử lý.
- Khi REST relaunch TikTok, feed của acc tiếp theo chưa được switch → `farmOneAccount()` sẽ gọi `switchToAccount()` ngay sau đó (idx > 0) → OK.
- `waitFeedLoad()` sau relaunch timeout 18s; nếu thất bại → `recoverToFeed()` làm fallback.

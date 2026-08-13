# Revision — v1.1.9 bổ sung (2026-06-02)

## Tóm tắt
Hoàn thiện 2 tính năng còn dở từ v1.1.9 ban đầu:
1. **Skip Ads** — AutomationEngine giờ tôn trọng `config.skipAds` (trước đó luôn skip bất kể cài đặt).
2. **Golike TikTok jobs UI** — GolikeTikTokAccountRow được viết lại hoàn chỉnh với tracking trạng thái,
   nút Mở link, nút Xong (gọi API complete), loading spinner, checkmark hoàn thành.

---

## 1. Skip Ads — kết nối config với AutomationEngine

### Vấn đề
`FarmConfig.skipAds` đã có từ v1.1.9, switch UI lưu/load đúng, nhưng trong
`AutomationEngine.farmVideoLoop()` dòng skip ad không kiểm tra flag này:
```kotlin
// Cũ — bỏ qua quảng cáo BẤT KỂ config.skipAds
if (NodeTraverser.detectAd(root)) { ... }
```

### Fix
```kotlin
// Mới — tôn trọng cài đặt user
if (config.skipAds && NodeTraverser.detectAd(root)) { ... }
```

**File:** `AutomationEngine.kt` — dòng ~798 trong `farmVideoLoop()`

---

## 2. Golike TikTok Jobs — hoàn thiện UI và tracking

### GolikeViewModel.kt

**Thêm vào GolikeUiState:**
| Field | Kiểu | Ý nghĩa |
|---|---|---|
| `completingJobs` | `Set<Int>` | Job IDs đang gửi API → hiện spinner |
| `completedJobs` | `Set<Int>` | Job IDs đã hoàn thành session → hiện checkmark |

**Update `completeJob()`:**
- Set `completingJobs + jobId` trước khi call API
- Sau khi API trả về: nếu `success == true` → thêm vào `completedJobs`
- Xóa khỏi `completingJobs` sau dù thành công hay thất bại
- Tự động `loadJobsForAccount()` để refresh list

### ConfigScreen.kt — GolikeTikTokJobsCard
- Thêm loading spinner `CircularProgressIndicator` khi `state.isLoadingAccounts == true`
- Bỏ guard `.take(3)` trong vòng lặp accounts → hiển thị tất cả jobs

### ConfigScreen.kt — GolikeTikTokAccountRow (viết lại hoàn toàn)

**Header:**
- Giữ avatar box + tên + username
- Thêm `+X🪙` coin badge (tổng coin của tất cả jobs account này)
- Badge `Y/Z xong` thay cho badge `Z job` khi đã có job hoàn thành
- Badge màu Green khi tất cả đã xong

**Mỗi job row:**
- Icon theo loại (`like` → Favorite, `follow` → PersonAdd, `share` → Share, `comment` → Comment, `view` → Visibility)
- Type badge + link rút gọn + coin
- **Trạng thái pending:** nút `OpenInNew` (mở TikTok link) + nút `Xong` (gọi `completeJob()`)
- **Trạng thái loading:** `CircularProgressIndicator` size 16dp thay nút
- **Trạng thái done:** `CheckCircle` icon màu Green + row nền xanh nhạt
- Row nền `BgDeeper` bình thường → `Green.copy(alpha=0.07f)` khi done

**Import thêm:**
- `android.content.Intent`
- `android.net.Uri`

---

## Files thay đổi

| File | Loại | Thay đổi |
|---|---|---|
| `AutomationEngine.kt` | Sửa | Thêm `config.skipAds &&` guard |
| `GolikeViewModel.kt` | Sửa | `completingJobs`, `completedJobs` trong state; `completeJob()` tracking |
| `ConfigScreen.kt` | Sửa | `GolikeTikTokAccountRow` viết lại; `GolikeTikTokJobsCard` thêm loading spinner |

---

## Rollback
```bash
git diff HEAD -- \
  android/app/src/main/kotlin/com/atpro/automation/AutomationEngine.kt \
  android/app/src/main/kotlin/com/atpro/ui/golike/GolikeViewModel.kt \
  android/app/src/main/kotlin/com/atpro/ui/config/ConfigScreen.kt
```

---

## Risks / TODO
- `completeJob()` không retry khi API fail — user cần bấm Xong lại thủ công nếu network lỗi.
- `completedJobs` là in-memory (không persist) — reset khi đóng app → acceptable cho session tracking.
- Automation deep integration (AutomationEngine tự nhận và làm job Golike) vẫn ở backlog.

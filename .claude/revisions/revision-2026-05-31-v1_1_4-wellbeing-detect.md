# Revision — v1.1.4 | 2026-05-31

## Tổng quan

Bản vá tập trung vào 3 vấn đề phát hiện màn hình TikTok:
1. App bị stuck khi TikTok hiển thị màn hình Wellbeing (nghỉ ngơi).
2. Nhận dạng Live stream còn bỏ sót trên nhiều phiên bản APK TikTok VN.
3. Nhận dạng quảng cáo trong feed chưa bắt được các CTA phổ biến.

---

## Thay đổi chi tiết

### 1. [NEW] Xử lý màn hình Wellbeing TikTok — NodeTraverser + AutomationEngine

**Vấn đề:** TikTok hiển thị màn hình toàn phần "Nghỉ ngơi" (ví dụ: "Hít vào",
"Quay lại ngay bây giờ") sau khi xem video quá lâu. Màn hình này:
- Che phủ hoàn toàn feed → `isOnFeedTab()` trả `false` → app bị lạc
- `pressBack()` KHÔNG hoạt động trên màn hình này → `recoverToFeed()` thất bại
- App kẹt vô hạn tới khi Tier 3 kill+relaunch TikTok

**Fix:**
- `NodeTraverser.detectWellbeingScreen()` — phát hiện màn hình 2 tầng
  - Tier 1: tìm nút "Quay lại ngay bây giờ" / "Return now" / "Tiếp tục xem"
  - Tier 2: quét text đặc trưng wellbeing ("khám phá các công cụ chăm sóc sức khỏe", v.v.)
- `NodeTraverser.findReturnFromWellbeingButton()` — tìm nút trở về
- `AutomationEngine` step **[2b]** — check wellbeing sau popup, trước lost detection
- `AutomationEngine.recoverToFeed()` **Tier 0** — click wellbeing button trước khi pressBack

**Files thay đổi:**
- `NodeTraverser.kt` — 2 hàm mới
- `AutomationEngine.kt` — step [2b] + Tier 0 trong recoverToFeed

---

### 2. [FIX] Cải thiện detectLive — NodeTraverser

**Vấn đề:** TikTok APK VN dùng resource-id dạng full path
(`com.zhiliaoapp.musically:id/live_room_container`) — exact-match list bỏ sót.

**Fix:**
- Thêm **Tier 1b**: quét toàn bộ node tree qua `viewIdResourceName.contains("/live")`
  → bắt được mọi biến thể APK mà không cần biết trước tên ID
- Bổ sung ID list: `live_stream`, `live_badge`, `live_comment`, `broadcast`,
  `live_panel`, `live_info`, `live_title`
- Thêm strict signal: `"đang live"` (variant ngắn phổ biến)
- Thêm soft signal: `"tặng tim"` (heart gift trong live VN)

---

### 3. [FIX] Cải thiện detectAd — NodeTraverser

**Vấn đề:** Quảng cáo TikTok VN render badge "Quảng cáo" qua canvas/image
thay vì text node → Tier 2 exact-text miss; resource-id short-name cũng bỏ sót.

**Fix:**
- Thêm **Tier 1b**: quét `viewIdResourceName.contains("/ad_")` trên toàn bộ node tree
- Thêm **Tier 3** soft CTA signals (>= 2 signal): "Tìm hiểu thêm", "Cài đặt ngay",
  "Mua ngay", "Đặt hàng ngay", "Đăng ký ngay", "Shop now", v.v.
  → bắt được ads không có badge text nhưng có nút CTA đặc trưng

---

### 4. Version bump

| Field | Cũ | Mới |
|---|---|---|
| `versionName` | `v1.1.3` | `v1.1.4` |
| `versionCode` | `13` | `14` |
| CI default `VER` | `1.1.3` | `1.1.4` |

**Files thay đổi:**
- `android/app/build.gradle`
- `.github/workflows/build.yml`

---

## Hướng dẫn người dùng

### Màn hình "Nghỉ ngơi" TikTok đã được xử lý tự động
Từ v1.1.4, khi TikTok hiển thị màn hình bài tập thở / nhắc nghỉ ngơi,
app sẽ **tự động nhấn "Quay lại ngay bây giờ"** và tiếp tục farm mà không cần can thiệp.
Log overlay sẽ hiển thị: `WELLBEING: Màn hình nghỉ ngơi TikTok — click 'Quay lại ngay bây giờ'`

### Nhận dạng Live và Quảng cáo tốt hơn
- Live stream giờ được bắt qua quét toàn bộ resource-id — không cần biết trước tên ID chính xác
- Quảng cáo có CTA như "Tìm hiểu thêm", "Cài đặt ngay" giờ được nhận dạng dù không có badge text

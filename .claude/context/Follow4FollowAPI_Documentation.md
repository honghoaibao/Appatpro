# Follow4Follow — tài liệu tích hợp (v1.12.0 · API reference v2.7 · f4f.py v2.8)

Tài liệu này là **bản đọc nhanh cho việc tích hợp trong app** (đăng nhập → liên kết acc → lấy job → báo hoàn thành/bỏ qua).
Nguồn đầy đủ & chính xác từng byte nằm cạnh đây, trong `follow4follow/`:

| File | Vai trò |
|------|---------|
| `follow4follow/API_reference_v2.txt` | Tham chiếu 82 endpoint — **bản v2.7** (audit 28/10 + DevTools 29/10: response THẬT của `complete-job`) — **nguồn sự thật** về schema |
| `follow4follow/f4f.py` | Công cụ CLI Python đã field-test với server thật — **bản tham chiếu hành vi** (như `golike.py` trước đây). Khi app và `f4f.py` khác nhau, đối chiếu `f4f.py` |

Code Kotlin tương ứng: `android/app/src/main/kotlin/com/atpro/tasks/f4f/` (xem mục 8).

> **Phạm vi app dùng:** phía **kiếm coin** (earn-coin) cho TikTok Follow/Like (mục 3–8) và **ví** (mục 9: tổng quan, xếp hạng, rút tiền, lịch sử, giới thiệu, thông báo). Phía mua đơn (SMM `api/v2`, `order`, `deposit`, chuyển coin) app KHÔNG dùng.

---

## 1. Cơ bản

| Mục | Giá trị |
|-----|---------|
| Base URL | `https://api.follow4follow.net` (web: `https://follow4follow.net`) |
| Định dạng | JSON (`Content-Type: application/json` cho POST) |
| Xác thực | `Authorization: Bearer <access_token>` (lấy từ login) |
| Ký request | `x-ts` + `x-sign` trên **mọi** request, kể cả login (mục 2) |
| Header giả lập web | `Origin: https://follow4follow.net`, `Referer: https://follow4follow.net/`, `User-Agent` Chrome Android |

### Phong bì (envelope) response

```json
{ "status_code": 200, "message": "…", "errors": null, "data": { } }
```

- `status_code` trong JSON **có thể khác** mã HTTP (đã gặp HTTP 200 + `status_code: 400`) → coi là lỗi nếu **một trong hai** ≥ 400.
- Ngoại lệ: `skip-job`/`report-job` có thể trả `success: true` → web coi thành công khi `status_code == 200 || success`.
- `data` không phải lúc nào cũng là object (có thể mảng/null) → đọc theo schema từng endpoint, không giả định.
- Số có thể là chuỗi (`reward_amount: "26"`, `coin_received: "26"`, `balance: "1250 coin"`) → đọc "khoan dung" (xem `F4fJson.kt`).
- Lỗi validation (HTTP 422): `errors: { field: ["thông báo", …] }`.

---

## 2. Ký request — `x-ts` / `x-sign`

```
x-ts   = Unix timestamp (giây)
x-sign = hex( HMAC-SHA256( key = "follow4follow_key", msg = "{ts}:{METHOD}:{path}" ) )
```

- `METHOD` viết HOA; `path` là đường dẫn **không có query** (`/user/earn-coin`, không phải `/user/earn-coin?type=like`).
- Chữ ký **không** gồm body.
- Server từ chối nếu `x-ts` lệch giờ thật quá ~60 giây → client học độ lệch từ header `Date` của response và thử lại 1 lần (`F4fApiClient`).
- Khoá là hằng số công khai trong bundle JS của web, không phải bí mật người dùng; trong code vẫn đi qua `StringEncryptor.F4F_SIGN_KEY` cho đồng bộ với ràng buộc dự án.

**Vector kiểm thử** (có trong `F4fSignerTest`): `ts=1793087078`, `POST`, `/user/auth/login` →
`589b0d565bd8ce3db463535bbac382f55096350eb8de0df605dbcb0e84b8028e`.

Nếu site đổi bundle và khoá đổi → sửa mảng mã hoá `F4F_SIGN_KEY` (cách tìm khoá: `API_reference_v2.txt` mục 6).

---

## 3. Đăng nhập

```
POST /user/auth/login
{ "username": "…", "password": "…" }          ← KHÔNG có recaptcha
```

Thành công → `data.access_token` (chuẩn; có thêm bản sao ở `data.user.access_token`) + `data.user` (`username`, `balance`, `balance_pending`, `level`, `must_change_password`…).

| Việc | Endpoint | Ghi chú |
|------|----------|---------|
| Kiểm tra token / lấy số dư | `GET /user/auth/me` | 401 → token hết hạn |
| Đăng xuất | `POST /user/auth/logout` | app gọi best-effort rồi xoá token cục bộ |

**Trong app (`F4fAuthManager`):**
- Login gửi **không kèm Bearer cũ** (token hết hạn có thể làm server từ chối).
- Chỉ lưu `access_token` (qua `StringEncryptor` — `F4fTokenCodec`), `username`, số dư (SharedPreferences riêng `f4f_session`, MODE_PRIVATE). **Không lưu mật khẩu.**
- Token hết hạn giữa chừng: mọi lời gọi nhận 401 → provider gọi `sessionExpired()` → UI về "Chưa đăng nhập", engine dừng task với `isAuthError`.
- Mở Cài đặt → Nhiệm vụ: thẻ `F4fAccountCard` gọi `GET /user/auth/me` để xác thực lại token + cập nhật số dư.

---

## 4. Tài khoản TikTok đã liên kết

> **v1.12.0 (theo `f4f.py` v2.8 — build web mới):** nguồn danh sách acc là **`quick-switch`** (đúng thứ web dùng); `account/list` chỉ còn là dự phòng.

```
GET /user/account/quick-switch?type=tiktok
```

```json
{ "accounts": [ { "id": 8589, "name": "hoaibao.2009", "uid": "6983264181085832219", "avatar": "…" } ] }
```

- App nhận cả 3 dạng `data.accounts` · `data.data` · `data` là mảng (server đang chuyển tiếp build); chỉ giữ phần tử có `id`.
- `id` = **account_id** dùng trong mọi lời gọi sau; `name` = @username TikTok (khớp với acc trên thiết bị); `uid` = id số.
- Rỗng/lỗi (không phải 401) → dự phòng `GET /user/account/list?type=tiktok&page=N` (phân trang phẳng, tối đa 20 trang, **không** gửi `search`):

```json
{ "data": [ { "id": 8589, "uid": "6983264181085832219", "name": "hoaibao.2009",
              "video": 3, "status": "Hoạt động", "country": "vn" } ],
  "per_page": 10, "total_page": 2, "current_page": 1, "total": 12 }
```

- App → `TaskAccount(id, username = name, providerData = {uid})`.
- Việc **liên kết** acc TikTok với Follow4Follow làm trên web follow4follow.net (app chưa có luồng thêm acc).

### Acc đang kích hoạt (active) — ĐỔI ACC LÀ PHÍA CLIENT

Server giữ **một** acc active cho mỗi nền tảng; job trả về là của acc đó. Điểm khác quan trọng của build web mới (đo bằng `f4f.py` v2.7/v2.8):

- Web **không POST** để đổi acc. Đổi acc = gọi lại `earn-coin` kèm **`uid=<tên acc>` + `refresh=1`**; server đổi `accountActive` theo `uid`.
- Web **không bao giờ gọi `earn-coin` khi chưa biết acc** — fetch "trần" (không `uid`) bị server trả `Không tìm thấy dữ liệu`. App cũ (≤ v1.11.0) làm đúng điều này ở lần lấy đầu rồi POST `quick-switch` — chính là lỗi "đổi acc không được".
- `POST /user/account/quick-switch` (cách cũ) trả `Không tìm thấy dữ liệu` trên build mới; chỉ còn dùng làm một bước dự phòng (S3 dưới đây).
- `POST /user/account/active-acc` `{ "account_id": 8589, "type": "tiktok" }` là cách đổi acc **tường minh** mới (bước S1).

### Thang đổi acc S1–S5 (`Follow4FollowProvider.forceActivate`, mirror `_switch_force` của `f4f.py`)

Chạy khi fetch kèm `uid` không đưa `accountActive` về đúng acc. Dừng ngay khi khớp; dừng sớm khi 401/mất mạng.

| Bước | Hành động |
|------|-----------|
| S1 | `POST active-acc {account_id,type}` → `GET earn-coin?uid=<tên>&refresh=1` |
| S2 | `GET earn-coin?uid=<uid số>&refresh=1` (chỉ khi `uid` số ≠ tên) |
| S3 | `POST quick-switch` (cách cũ) → `GET earn-coin?uid=<tên>&refresh=1` |
| S4 | `GET earn-coin?uid=<tên>&refresh=1` lần nữa |
| S5 | `GET earn-coin?refresh=1` (trơn — chấp nhận "sự thật của server" nhưng **chỉ dùng nếu đúng acc**) |

Hết thang vẫn không khớp → lỗi có hướng xử lý: server đang giữ acc khác (`@tên`) hoặc `accountActive = null` (xoá rồi thêm lại acc trên follow4follow.net). Mỗi bước phát một dòng *trace* (không chứa token) → nhật ký thấy dưới tag `F4F` (chế độ "Chi tiết").

---

## 5. Lấy job & làm job (luồng chính)

```
        listAccounts ── GET /user/account/quick-switch (dự phòng: account/list)
              │
   mỗi acc ───┤
              ▼
   ┌── GET /user/earn-coin?platform=tiktok&type=follow|like&uid=<tên>[&refresh=1] ──┐
   │      accountActive.id == acc cần làm ?                                          │
   │        không → thang đổi acc S1–S5 (mục 4)                                      │
   │      jobs rỗng → thử 1 lần fetch trơn (V2) — chỉ nhận nếu vẫn đúng acc          │
   │      account_daily_usage.reached ? → dừng acc này                               │
   └──────────────────────────────┬──────────────────────────────────────────────────┘
                                  ▼  (mỗi job)
            [LIKE] POST /user/earn-coin/tiktok-like/mark-start
                   mở link bằng deep link → làm Like/Follow thật trên TikTok
                   ≥5 giây kể từ lúc mở link
                                  ▼
        POST /user/earn-coin/complete-job   ──(lỗi nghiệp vụ)──▶ POST /user/earn-coin/skip-job
                                  └─ "Không tìm thấy dữ liệu" = job đã bị xoá/hết → bỏ job, KHÔNG skip
```

### 5.1 Lấy job

```
GET /user/earn-coin?platform=tiktok&type=follow|like&uid=<tên acc>[&refresh=1]
```

- **Luôn** gửi `uid` = `name` (fallback `uid`) của acc cần làm — như web. Lần lấy **đầu của phiên** không gửi `refresh`; mọi lần sau (lấy lại, hoặc đổi sang acc khác) gửi `refresh=1`.
- Server báo thao tác nhanh (429): chờ `retry_after` (tối đa 30 giây, tại chỗ) rồi thử lại **một** lần; vẫn 429 → trả `cooldownSecs` cho engine chờ.
- Response:

```json
{ "accountActive": { "id": 8589, "uid": "…", "name": "hoaibao.2009", "avatar": "…" },
  "jobs": [ { "id": 55070, "link": "https://www.tiktok.com/@kim_truong.28", "type_job": "follow",
              "object_id": "kim_truong.28", "original_link": "…", "quantity": 100, "run": 1,
              "remains": 99, "reward_amount": "26" } ],
  "account_daily_usage": { "done": 3, "limit": 50, "remaining": 47, "reached": false } }
```

- ⚠ **`accountActive = null` hoặc khác acc cần làm → KHÔNG được dùng `jobs`, KHÔNG complete/skip** (gửi `account_id = null` làm server lỗi 500 "Có lỗi hệ thống"; jobs của acc khác báo hoàn thành sẽ sai acc). Provider chạy thang S1–S5; vẫn không khớp → báo lỗi rõ ràng.
- `account_daily_usage.reached = true` hoặc `remaining ≤ 0` → hết lượt hôm nay → provider trả "không có job" kèm lý do (engine ghi log + thông báo "hết lượt hôm nay").
- Provider giữ hàng đợi job theo (acc, loại), dùng lại không gọi mạng; hàng đợi quá **5 phút** thì bỏ (job có thể bị thu hồi).
- Chế độ **BOTH**: provider lấy riêng `follow` và `like`, xoay vòng để xen kẽ.

### 5.2 Báo bắt đầu (chỉ job LIKE)

```
POST /user/earn-coin/tiktok-like/mark-start
{ "job_id": 55155, "account_id": 8589 }
```

Gọi **ngay trước khi mở link**, fire-and-forget (lỗi bỏ qua). Server ghi mốc để đối chiếu khi complete. Engine gọi qua `TaskProvider.onJobOpening()`.

### 5.3 Hoàn thành

```
POST /user/earn-coin/complete-job
{ "job_id": 55070, "account_id": 8589, "type": "tiktok", "type_job": "follow" }
```

- **Đủ 4 field**, `job_id`/`account_id` là **số** (thiếu `account_id`/`type` → 500). Không gửi `uid`.
- Web đếm ngược **5 giây** sau khi mở link mới cho bấm Hoàn thành → provider đợi đủ 5s kể từ `onJobOpening` (`MIN_DWELL_MS`).
- Thành công (response THẬT, v2.7): `message: "Bạn đã nhận được 26 xu thành công"`, `data = { coin_received, current_balance, balance_pending, account_daily_usage:{done,limit,remaining} }` — số đều là **số nguyên**. App lấy `coin_received` (số coin server thực ghi nhận) làm phần thưởng trả về engine (thiếu thì dùng reward lúc hiển thị job), cập nhật số dư trên UI từ `current_balance`/`balance_pending`, và coi acc hết lượt khi `remaining ≤ 0`.
- (Các field `job_today`/`coin_pending_today` trong tài liệu cũ KHÔNG có trong response thật.)
- Coin thường ở trạng thái **"Chờ duyệt"** (`balance_pending`) rồi mới thành số dư.

### 5.4 Bỏ qua

```
POST /user/earn-coin/skip-job
{ "job_id": 55070, "account_id": 8589, "uid": "<accountActive.uid>", "object_id": "kim_truong.28", "type": "tiktok" }
```

(`report-job` cùng shape + `message` — app chưa dùng.) Engine skip job khi **complete thất bại vì lỗi nghiệp vụ** để nhả job, tránh bị giao lại.

---

## 6. Lỗi, giới hạn tốc độ & gợi ý

| Tình huống | Nhận biết | App xử lý |
|------------|-----------|-----------|
| Token hết hạn | HTTP/`status_code` 401, hoặc message "cần đăng nhập" | `TaskResult.Error(isAuthError = true)`, xoá phiên, dừng task |
| Thao tác quá nhanh | HTTP 429, `data.retry_after` (hoặc `retry_after` gốc) giây, "Bạn thao tác hơi nhanh" | `cooldownSecs` (kẹp 3–300s); engine chờ rồi **báo lại complete đúng 1 lần** |
| Lệch đồng hồ | 4xx kèm `Date` header lệch >30s | client tự hiệu chỉnh `x-ts`, thử lại 1 lần |
| Mất mạng | `IOException` | `Error` thường (không phải lỗi xác thực) |
| Body không phải JSON | proxy/Cloudflare trả HTML | `Error` "phản hồi không phải JSON" |

Message nghiệp vụ hay gặp (`F4fHints`, hiển thị kèm gợi ý trong log):

| Message chứa | Ý nghĩa |
|--------------|---------|
| "chưa làm job này" / "chưa thực hiện follow|like" | Follow/Like **thật** chưa được TikTok ghi nhận khi báo complete |
| "đã được thực hiện" | Job đã làm trước đó |
| "hết thời gian" | Job hết hạn |
| "hết quota" | Hết lượt trong ngày → ngừng lấy job cho acc |
| "không tìm thấy dữ liệu" | Job bị xoá/hết |
| "có lỗi hệ thống" | Lỗi server (hoặc gửi thiếu field) |

---

## 7. Điều chưa biết / không làm

- App **chưa** có luồng thêm/liên kết acc TikTok vào Follow4Follow — làm trên web.
- `report-job` đã verify bundle nhưng chưa test riêng và chưa nối vào engine.
- **Token hết hạn giữa phiên (đo 5/11 bằng `f4f.py` v2.8: chết sau vài giờ).** `f4f.py` có tuỳ chọn **ghi nhớ mật khẩu** (base64, `chmod 600`) để tự đăng nhập lại khi 401. **App KHÔNG làm vậy** — `constraints.md`: *không bao giờ lưu mật khẩu*. Thay vào đó: 401 giữa phiên → engine dừng cả lượt (không chuyển acc vô ích) + thông báo "Follow4Follow cần đăng nhập lại". Nếu muốn tự phục hồi thì cần quyết định bảo mật mới (ADR) — chưa làm.
- Chưa nối thang đổi acc với trạng thái `quickswitch_active_<type>` mà web nhớ trong localStorage (`f4f.py` v2.7 lưu ở `~/.f4f/state.json`); app luôn truyền `uid` nên không cần.
- Provider chỉ thử **một** lần fetch trơn (V2) khi jobs rỗng; `f4f.py` thử thêm V3 (`refresh=1` trơn) — bỏ để không tăng số lời gọi mỗi lần hết job.
- Số job/ngày (`limit`) và mức thưởng do server quyết định, có thể đổi theo cấp (`level`).

---

## 8. Ánh xạ sang code (`com.atpro.tasks`)

| File | Trách nhiệm |
|------|-------------|
| `TaskProvider.kt` | Interface engine dùng: `listAccounts`, `nextJob(account, kinds)`, `onJobOpening`, `complete`, `skip`, `noJobReason`, `setTraceListener` + `TaskResult` (`isAuthError`, `cooldownSecs`, `jobGone`) |
| `TaskAccountSelector.kt` | **v1.12.0** — chọn tài khoản nhận nhiệm vụ: acc trên máy ∩ acc đã liên kết ∩ (chọn tay). Thuần Kotlin, có test |
| `f4f/F4fSigner.kt` | Ký `x-ts`/`x-sign` (thuần, có test vector) |
| `f4f/F4fHttp.kt` | `F4fTransport` (tách để test) + `UrlConnectionTransport` (HttpURLConnection, không thêm thư viện) |
| `f4f/F4fApiClient.kt` | Gắn header, bóc envelope, hiệu chỉnh đồng hồ, không bao giờ ném exception |
| `f4f/Follow4FollowProvider.kt` | Luồng mục 4–5 (**theo `f4f.py` v2.8**): danh sách acc `quick-switch`, lấy job luôn kèm `uid`, thang đổi acc S1–S5, hàng đợi job, mark-start, complete (đợi 5s), skip, quota, 429 chờ-thử-lại, `jobGone`, trace → `TaskResult` |
| `f4f/F4fAuth.kt` | `F4fAuthManager` (login/refresh/logout + `StateFlow`), lưu phiên (`F4fTokenCodec`), singleton `F4fAuth` dùng chung giữa UI và `TikTokAccessibilityService` |
| `f4f/F4fHints.kt`, `F4fJson.kt` | Gợi ý lỗi; đọc JSON khoan dung |
| `f4f/F4fService.kt`, `f4f/F4fModels.kt` | **Ví (v1.7.0):** `F4fService` (dashboard, bảng xếp hạng, rút tiền, lịch sử, giới thiệu, thông báo) + model + 5 kênh rút (`F4fWithdrawRequest`) |
| `ui/f4f/*` | `F4fHubScreen` (6 tab), `F4fWithdrawPanel`, `F4fHubViewModel`, thành phần dùng chung |
| `ui/tasks/F4fAccountCard.kt` | `F4fLoginDialog` + `F4fAccountCard` (Cài đặt → Nhiệm vụ); hàng "Nhiệm vụ kiếm tiền" ở tab Dịch vụ mở hộp thoại đăng nhập nếu chưa đăng nhập |

Engine (`AutomationEngine`, mục Task mode): `listAccounts` → [`TaskAccountSelector.select`](acc trên máy ∩ liên kết ∩ chọn tay) → với mỗi acc: `nextJob(account, kinds)` → `onJobOpening` → mở link → làm → `complete` (429 thì chờ + báo lại 1 lần; `jobGone` thì bỏ job; lỗi nghiệp vụ khác thì `skip`) → nuôi acc ngắn giữa các job. 401 ở bất kỳ bước nào → dừng cả lượt + thông báo.

### Test

`android/app/src/test/kotlin/com/atpro/tasks/f4f/` — `F4fSignerTest`, `F4fApiClientTest`, `Follow4FollowProviderTest` (v1.12.0: viết lại theo giao thức v2.8 + test thang đổi acc, 429, job gone, quick-switch), `F4fAuthManagerTest`, `F4fTokenCodecTest`, `F4fServiceTest` (+ `FakeF4fTransport`): chạy hoàn toàn offline bằng transport giả (response mẫu lấy từ `API_reference_v2.txt`). Bộ chọn tài khoản: `tasks/TaskAccountSelectorTest`.

### Bảo mật

- Không ghi log token/mật khẩu/`x-sign`.
- Token nằm trong SharedPreferences riêng của app (MODE_PRIVATE), đã che bằng `StringEncryptor` theo `constraints.md` — đây là che chuỗi (XOR), **không phải mã hoá mạnh**; chưa dùng Keystore (dự án chưa có `androidx.security-crypto`). Nếu cần nâng mức bảo vệ → ADR mới.

---

## 9. Ví Follow4Follow (v1.7.0)

Truy cập: tab **Dịch vụ → thẻ "Ví Follow4Follow"** (lối tắt Xếp hạng / Rút tiền / Giới thiệu trong thẻ) hoặc tab **Cài đặt → Công cụ → Ví Follow4Follow**. Màn hình `F4fHubScreen` có 6 tab; mọi dữ liệu qua `F4fService` (không ném exception; 401 → về chưa đăng nhập, 429 → cooldown).

| Tab | Endpoint | Ghi chú |
|-----|----------|---------|
| Tổng quan | `GET /user/dashboard` · `/user/earn-coin/statistical?type=tiktok` · `/user/job-rewards` · `/user/rate` | Số dư, chờ duyệt, cấp, coin hôm nay, job còn lại, tỷ giá |
| Xếp hạng | `GET /user/leaderboard?limit=20` | Tham số là `limit` (**không** phải page). Đọc cả key HOA/thường; thiếu `rank` thì đánh số theo thứ tự; `my_rank` tuỳ chọn |
| Rút tiền | `GET /user/withdraw/{info,statistical,list}` · `POST /user/withdraw/{bank,zalopay,paypal,crypto,card}` | Xem 9.1 |
| Lịch sử | `GET /user/earn-coin/list?type=tiktok&action=follow|like&page=N` | `action` **bắt buộc** (thiếu thì server trả rỗng) |
| Giới thiệu | `GET /user/referral/summary` · `POST /user/referral/claim-all` | Mã, link, F1/F2, hoa hồng; nhận hoa hồng đang chờ |
| Thông báo | `GET /user/notifications` | Danh sách + số chưa đọc (chưa có "đánh dấu đã đọc" — endpoint chưa xác minh) |

### 9.1 Rút tiền — body từng kênh

| Kênh | Endpoint | Body |
|------|----------|------|
| Ngân hàng | `/user/withdraw/bank` | `{bank_name, account_name, account_number, amount}` |
| ZaloPay | `/user/withdraw/zalopay` | **cùng shape ngân hàng**: `{bank_name:"ZaloPay", account_name, account_number = số điện thoại, amount}` |
| PayPal | `/user/withdraw/paypal` | `{email, amount}` |
| Crypto | `/user/withdraw/crypto` | `{network (bep20|trc20|erc20), wallet_address, amount}` |
| Thẻ cào | `/user/withdraw/card` | `{telco (Viettel|MobiFone|VinaPhone), amount}` |

`amount` là **số nguyên** (coin). `GET /user/withdraw/info` chỉ dùng để GỢI Ý danh sách ngân hàng/mạng/nhà mạng; schema chưa xác minh nên đọc khoan dung, không đọc được thì dùng danh sách mặc định.

**An toàn (rút tiền không hoàn tác):** kiểm tra dữ liệu cục bộ (`F4fWithdrawRequest.validate`) → hộp thoại xác nhận liệt kê mọi trường → sau xác nhận ô số coin bị xoá (muốn rút tiếp phải nhập lại) → ViewModel khoá khi đang gửi → **không có thử lại tự động ở bất kỳ tầng nào**; lỗi mạng khi gửi hiện nhắc "kiểm tra Lịch sử rút tiền trước khi gửi lại".

**Chưa xác minh:** giá trị `bank_name` chính xác cho ZaloPay (app gửi `"ZaloPay"`; nếu server từ chối, thông báo 422 hiện nguyên văn); hạn mức tối thiểu/tối đa từng kênh; endpoint đánh dấu thông báo đã đọc; `/jobs` (nhận thưởng hằng ngày) và `transaction/list` — app chưa dùng vì chưa có schema xác minh.

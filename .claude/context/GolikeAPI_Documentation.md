# 📘 Golike API – Tài liệu tích hợp chi tiết

> **Nguồn:** Reverse-engineered từ `GolikeApi.smali` + toàn bộ DTO trong package `com.autogolike.mobile.core.network.dto`  
> **Base URL:** `https://golike.io/`  
> **Base URL cũ (deprecated):** `https://golike.net/` — đã đổi domain từ v1.1.9  
> **Content-Type:** `application/json`  
> **Auth:** Bearer Token trong header `Authorization: Bearer <token>`

> ⚠️ **v1.1.9 — Breaking change:** Các field `coin`, `hold_coin`, `pending_coin` đổi kiểu từ `Int` → `Double` (API có thể trả về số thập phân, ví dụ `250.5`). Xem `GolikeModels.kt`.

---

## Mục lục

1. [Xác thực (Authentication)](#1-xác-thực)
2. [Thông tin người dùng](#2-thông-tin-người-dùng)
3. [Facebook Jobs](#3-facebook-jobs)
4. [TikTok Jobs](#4-tiktok-jobs)
5. [Instagram Jobs](#5-instagram-jobs)
6. [LinkedIn Jobs](#6-linkedin-jobs)
7. [Snapchat Jobs](#7-snapchat-jobs)
8. [Threads Jobs](#8-threads-jobs)
9. [Thống kê](#9-thống-kê)
10. [Data Models tổng hợp](#10-data-models-tổng-hợp)
11. [Flow tích hợp đề xuất](#11-flow-tích-hợp-đề-xuất)

---

## 1. Xác thực

### 1.1 Login

Đăng nhập để lấy token. Không yêu cầu Authorization header.

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/auto/login` |
| **Auth** | Không cần |

**Request Body:**
```json
{
  "username": "your_username",
  "password": "your_password"
}
```

| Field | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `username` | string | ✅ | Tên đăng nhập tài khoản Golike |
| `password` | string | ✅ | Mật khẩu tài khoản |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Login successful",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "data": {
    "id": 12345,
    "username": "your_username",
    "name": "Nguyễn Văn A",
    "email": "email@example.com",
    "role": "user",
    "coin": 5000
  }
}
```

| Field | Type | Mô tả |
|---|---|---|
| `status` | int | HTTP-like status code (200 = OK) |
| `success` | boolean | Kết quả đăng nhập |
| `message` | string | Thông báo từ server |
| `token` | string | **JWT token** – lưu lại để dùng cho tất cả request tiếp theo |
| `data.id` | int | ID nội bộ của user |
| `data.username` | string | Tên đăng nhập |
| `data.name` | string | Tên hiển thị |
| `data.email` | string | Email |
| `data.role` | string | Vai trò (user / admin...) |
| `data.coin` | **double** | Số coin hiện tại (có thể thập phân — v1.1.9) |

> ⚠️ **Lưu ý tích hợp:** Sau khi login thành công, lưu `token` vào secure storage (EncryptedSharedPreferences / Keystore). Gắn vào header mọi request tiếp theo: `Authorization: Bearer <token>`

---

## 2. Thông tin người dùng

### 2.1 Lấy thông tin user hiện tại

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/users/me` |
| **Auth** | ✅ Bearer Token |

**Request Headers:**
```
Authorization: Bearer <token>
```

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "OK",
  "data": {
    "id": 12345,
    "username": "your_username",
    "name": "Nguyễn Văn A",
    "email": "email@example.com",
    "role": "user",
    "coin": 5000,
    "total_notifications_unread": 3,
    "user_rank": {
      "rank": 5,
      "rank_name": "Gold"
    }
  }
}
```

| Field | Type | Mô tả |
|---|---|---|
| `data.id` | int | User ID |
| `data.username` | string | Tên đăng nhập |
| `data.name` | string | Tên đầy đủ |
| `data.email` | string | Email |
| `data.role` | string | Vai trò |
| `data.coin` | **double** | Số coin hiện tại (có thể thập phân — v1.1.9) |
| `data.total_notifications_unread` | int | Số thông báo chưa đọc |
| `data.user_rank.rank` | int | Hạng số |
| `data.user_rank.rank_name` | string | Tên hạng (Bronze/Silver/Gold...) |

---

## 3. Facebook Jobs

### 3.1 Lấy danh sách tài khoản Facebook

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/fb-account` |
| **Auth** | ✅ Bearer Token |

**Query Parameters:**

| Param | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `limit` | int | ✅ | Số lượng tài khoản muốn lấy (ví dụ: 50) |

**Request ví dụ:**
```
GET https://golike.net/api/fb-account?limit=50
Authorization: Bearer <token>
```

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 1,
      "fb_id": "100012345678",
      "fb_name": "Nguyễn Văn A",
      "username": "nguyenvana",
      "status": 1,
      "is_banned": false
    }
  ]
}
```

| Field | Type | Mô tả |
|---|---|---|
| `data[].id` | int | ID nội bộ trong DB |
| `data[].fb_id` | string | Facebook UID |
| `data[].fb_name` | string | Tên hiển thị Facebook |
| `data[].username` | string | Username FB |
| `data[].status` | int | Trạng thái (1=active) |
| `data[].is_banned` | boolean | Tài khoản có bị ban không |

---

### 3.2 Lấy danh sách Facebook Jobs

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/advertising/publishers/_private/get-jobs` |
| **Auth** | ✅ Bearer Token |

**Query Parameters:**

| Param | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `fb_id` | string | ✅ | Facebook UID lấy từ `FbAccountDto.fb_id` |

**Request ví dụ:**
```
GET https://golike.net/api/advertising/publishers/_private/get-jobs?fb_id=100012345678
Authorization: Bearer <token>
```

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 9001,
      "orders_id": 501,
      "object_id": "123456789_987654321",
      "link": "https://www.facebook.com/photo/?fbid=987654321",
      "type": "like",
      "description": "Like bài viết về sản phẩm mới",
      "comment_id": null,
      "quantity": 100,
      "remains": 45,
      "count_is_run": 55,
      "fix_coin_job": 5,
      "time_expired": 1717200000,
      "time_expired_format": "2024-06-01 12:00:00"
    }
  ]
}
```

| Field | Type | Mô tả |
|---|---|---|
| `data[].id` | int | Job ID – **dùng để complete job** |
| `data[].orders_id` | int | Order ID gốc |
| `data[].object_id` | string | ID đối tượng Facebook (post/photo) |
| `data[].link` | string | URL cần tương tác |
| `data[].type` | string | Loại job: `like`, `follow`, `share`, `comment`... |
| `data[].description` | string | Mô tả job |
| `data[].comment_id` | string/null | ID comment mẫu (nếu type=comment) |
| `data[].quantity` | int | Số lượng cần chạy |
| `data[].remains` | int | Số lượng còn lại |
| `data[].count_is_run` | int | Số lượng đã chạy |
| `data[].fix_coin_job` | int | Coin thưởng mỗi job |
| `data[].time_expired` | long | Unix timestamp hết hạn |
| `data[].time_expired_format` | string | Ngày hết hạn định dạng đọc được |

---

### 3.3 Hoàn thành Facebook Job

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/advertising/publishers/_private/complete-jobs` |
| **Auth** | ✅ Bearer Token |

**Request Body:**
```json
{
  "job_id": 9001,
  "uid": "100012345678",
  "success": true,
  "message": "Đã like thành công",
  "comment_id": null
}
```

| Field | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `job_id` | int | ✅ | ID job lấy từ `JobDto.id` |
| `uid` | string | ✅ | Facebook UID của tài khoản thực hiện |
| `success` | boolean | ✅ | `true` nếu thực hiện thành công |
| `message` | string | ✅ | Thông báo kết quả (có thể để trống hoặc mô tả) |
| `comment_id` | string/null | ✅ | ID comment nếu type=comment, ngược lại `null` |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Job completed successfully"
}
```

| Field | Type | Mô tả |
|---|---|---|
| `status` | int | Mã trạng thái |
| `success` | boolean | Kết quả |
| `message` | string | Thông báo từ server |

---

## 4. TikTok Jobs

### 4.1 Lấy danh sách tài khoản TikTok

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/tiktok-account` |
| **Auth** | ✅ Bearer Token |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 1,
      "unique_id": "user_abc123",
      "unique_username": "@user_abc123",
      "nickname": "Tên hiển thị TikTok",
      "avatar_thumb": "https://p16-sign.tiktokcdn.com/...",
      "status": 1,
      "status_text": "Active",
      "is_banned": false
    }
  ]
}
```

| Field | Type | Mô tả |
|---|---|---|
| `data[].id` | int | ID nội bộ |
| `data[].unique_id` | string | TikTok unique ID (không có @) |
| `data[].unique_username` | string | Username đầy đủ (có @) – **dùng cho get-jobs & complete-jobs** |
| `data[].nickname` | string | Tên hiển thị |
| `data[].avatar_thumb` | string | URL ảnh đại diện |
| `data[].status` | int | Trạng thái |
| `data[].status_text` | string | Mô tả trạng thái |
| `data[].is_banned` | boolean | Bị ban không |

---

### 4.2 Lấy danh sách TikTok Jobs

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/advertising/publishers/tiktok/_private/get-jobs` |
| **Auth** | ✅ Bearer Token |

**Query Parameters:**

| Param | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `unique_username` | string | ✅ | Username TikTok (lấy từ `TiktokAccountDto.unique_username`) |

**Request ví dụ:**
```
GET https://golike.net/api/advertising/publishers/tiktok/_private/get-jobs?unique_username=@user_abc123
Authorization: Bearer <token>
```

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "OK",
  "data": [
    {
      "job_id": 7001,
      "link": "https://www.tiktok.com/@creator/video/7123456789",
      "type": "like",
      "fix_coin": 3
    }
  ]
}
```

> **Lưu ý:** TikTok sử dụng `SocialJobDto` cho data items, cấu trúc gọn hơn `JobDto`.

| Field | Type | Mô tả |
|---|---|---|
| `data[].job_id` | int | Job ID – **dùng để complete** |
| `data[].link` | string | URL video/profile cần tương tác |
| `data[].type` | string | Loại job: `like`, `follow`, `comment`, `view`... |
| `data[].fix_coin` | int | Coin thưởng |

---

### 4.3 Hoàn thành TikTok Job

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/advertising/publishers/tiktok/_private/complete-jobs` |
| **Auth** | ✅ Bearer Token |

**Request Body:**
```json
{
  "job_id": 7001,
  "unique_username": "@user_abc123",
  "success": true
}
```

| Field | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `job_id` | int | ✅ | Job ID từ get-jobs |
| `unique_username` | string | ✅ | Username TikTok của tài khoản thực hiện |
| `success` | boolean | ✅ | Kết quả thực hiện |

**Response:** Giống `CompleteJobResponse` – xem [mục 3.3](#33-hoàn-thành-facebook-job).

---

## 5. Instagram Jobs

### 5.1 Lấy danh sách tài khoản Instagram

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/instagram-account` |
| **Auth** | ✅ Bearer Token |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 1,
      "instagram_id": "1234567890",
      "instagram_username": "username_ig",
      "instagram_full_name": "Tên đầy đủ",
      "profile_pic_url": "https://instagram.com/...",
      "status": 1,
      "status_text": "Active",
      "is_banned": false
    }
  ]
}
```

| Field | Type | Mô tả |
|---|---|---|
| `data[].instagram_id` | string | Instagram UID |
| `data[].instagram_username` | string | Username – **dùng cho get-jobs & complete-jobs** |
| `data[].instagram_full_name` | string | Tên đầy đủ |
| `data[].profile_pic_url` | string | URL avatar |
| `data[].status` | int | Trạng thái |
| `data[].status_text` | string | Mô tả trạng thái |
| `data[].is_banned` | boolean | Bị ban không |

---

### 5.2 Lấy danh sách Instagram Jobs

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/advertising/publishers/instagram/_private/get-jobs` |
| **Auth** | ✅ Bearer Token |

**Query Parameters:**

| Param | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `instagram_username` | string | ✅ | Username Instagram |

**Response:** Cấu trúc giống TikTok – `data[]` là mảng `SocialJobDto`.

---

### 5.3 Hoàn thành Instagram Job

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/advertising/publishers/instagram/_private/complete-jobs` |
| **Auth** | ✅ Bearer Token |

**Request Body:**
```json
{
  "job_id": 8001,
  "instagram_username": "username_ig",
  "success": true
}
```

| Field | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `job_id` | int | ✅ | Job ID |
| `instagram_username` | string | ✅ | Username Instagram thực hiện |
| `success` | boolean | ✅ | Kết quả |

---

## 6. LinkedIn Jobs

### 6.1 Lấy danh sách tài khoản LinkedIn

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/linkedin-account` |
| **Auth** | ✅ Bearer Token |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 1,
      "linkedin_username": "nguyenvana",
      "linkedin_full_name": "Nguyen Van A",
      "profile_pic_url": "https://media.linkedin.com/...",
      "status": 1,
      "status_text": "Active",
      "is_banned": false
    }
  ]
}
```

---

### 6.2 Lấy danh sách LinkedIn Jobs

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/advertising/publishers/linkedin/_private/get-jobs` |
| **Auth** | ✅ Bearer Token |

**Query Parameters:**

| Param | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `linkedin_username` | string | ✅ | LinkedIn username |

---

### 6.3 Hoàn thành LinkedIn Job

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/advertising/publishers/linkedin/_private/complete-jobs` |
| **Auth** | ✅ Bearer Token |

**Request Body:**
```json
{
  "job_id": 6001,
  "linkedin_username": "nguyenvana",
  "success": true
}
```

---

## 7. Snapchat Jobs

Snapchat có thêm 2 endpoint quản lý tài khoản: **verify** và **remove**.

### 7.1 Lấy danh sách tài khoản Snapchat

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/snapchat-account` |
| **Auth** | ✅ Bearer Token |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 1,
      "snap_username": "snapuser123",
      "username": "snapuser123",
      "name": "Snap User",
      "nickname": "Snap",
      "status": 1
    }
  ]
}
```

| Field | Type | Mô tả |
|---|---|---|
| `data[].snap_username` | string | Snap username – **dùng cho jobs & complete** |
| `data[].name` | string | Tên thật |
| `data[].nickname` | string | Nickname |
| `data[].status` | int | Trạng thái |

---

### 7.2 Lấy danh sách Snapchat Jobs

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/advertising/publishers/snapchat/_private/get-jobs` |
| **Auth** | ✅ Bearer Token |

**Query Parameters:**

| Param | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `snap_username` | string | ✅ | Snap username |
| `type` | string | ✅ | Loại job (ví dụ: `"follow"`, `"view"`) |

> ⚠️ **Snapchat là platform duy nhất** có thêm query param `type` trong get-jobs.

---

### 7.3 Hoàn thành Snapchat Job

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/advertising/publishers/snapchat/_private/complete-jobs` |
| **Auth** | ✅ Bearer Token |

**Request Body:**
```json
{
  "job_id": 5001,
  "snap_username": "snapuser123",
  "success": true
}
```

---

### 7.4 Xác minh tài khoản Snapchat

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/snapchat-account/verify-account` |
| **Auth** | ✅ Bearer Token |

**Request Body:**
```json
{
  "username": "snapuser123"
}
```

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Account verified successfully"
}
```

---

### 7.5 Xóa tài khoản Snapchat

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/snapchat-account/remove` |
| **Auth** | ✅ Bearer Token |

**Request Body:**
```json
{
  "id": 1
}
```

| Field | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `id` | int | ✅ | ID nội bộ của Snapchat account (`SnapAccountDto.id`) |

**Response:** Giống `SnapSimpleResponse`.

---

## 8. Threads Jobs

### 8.1 Lấy danh sách tài khoản Threads

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/threads-account` |
| **Auth** | ✅ Bearer Token |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 1,
      "uid": "tt_uid_12345",
      "threads_username": "threads_user",
      "name": "Tên hiển thị",
      "profile_image_url": "https://threads.net/...",
      "status": 1,
      "status_text": "Active",
      "is_banned": false
    }
  ]
}
```

| Field | Type | Mô tả |
|---|---|---|
| `data[].uid` | string | UID nội bộ Threads |
| `data[].threads_username` | string | Username – **dùng cho jobs & complete** |
| `data[].name` | string | Tên hiển thị |
| `data[].profile_image_url` | string | URL avatar |

---

### 8.2 Lấy danh sách Threads Jobs

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/advertising/publishers/threads/_private/get-jobs` |
| **Auth** | ✅ Bearer Token |

**Query Parameters:**

| Param | Type | Bắt buộc | Mô tả |
|---|---|---|---|
| `threads_username` | string | ✅ | Threads username |

---

### 8.3 Hoàn thành Threads Job

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/advertising/publishers/threads/_private/complete-jobs` |
| **Auth** | ✅ Bearer Token |

**Request Body:**
```json
{
  "job_id": 4001,
  "threads_username": "threads_user",
  "success": true
}
```

---

## 9. Thống kê

### 9.1 Lấy báo cáo thống kê

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `GET` |
| **Endpoint** | `api/statistics/report` |
| **Auth** | ✅ Bearer Token |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "current_coin": 5000,
  "facebook": {
    "hold_coin": 200,
    "pending_coin": 50
  },
  "tiktok": {
    "hold_coin": 150,
    "pending_coin": 30
  },
  "instagram": { "hold_coin": 0, "pending_coin": 0 },
  "linkedin": { "hold_coin": 0, "pending_coin": 0 },
  "snapchat": { "hold_coin": 0, "pending_coin": 0 },
  "threads": { "hold_coin": 0, "pending_coin": 0 },
  "youtube": { "hold_coin": 0, "pending_coin": 0 },
  "twitter": { "hold_coin": 0, "pending_coin": 0 },
  "bluesky": { "hold_coin": 0, "pending_coin": 0 },
  "pinterest": { "hold_coin": 0, "pending_coin": 0 },
  "tumblr": { "hold_coin": 0, "pending_coin": 0 },
  "soundcloud": { "hold_coin": 0, "pending_coin": 0 },
  "traffic": { "hold_coin": 0, "pending_coin": 0 },
  "review": { "hold_coin": 0, "pending_coin": 0 },
  "shopee": { "hold_coin": 0, "pending_coin": 0 },
  "lazada": { "hold_coin": 0, "pending_coin": 0 }
}
```

| Field | Type | Mô tả |
|---|---|---|
| `current_coin` | **double** | Tổng coin hiện tại của user |
| `<platform>.hold_coin` | **double** | Coin đang giữ (chưa quyết toán) |
| `<platform>.pending_coin` | **double** | Coin đang chờ duyệt |

**Danh sách platform keys:** `facebook`, `tiktok`, `instagram`, `linkedin`, `snapchat`, `threads`, `youtube`, `twitter`, `bluesky`, `pinterest`, `tumblr`, `soundcloud`, `traffic`, `review`, `shopee`, `lazada`

---

## 10. Data Models tổng hợp

### CompleteJobResponse (dùng chung cho FB, Instagram, LinkedIn, Snapchat, Threads, TikTok)

```json
{
  "status": 200,
  "success": true,
  "message": "Job completed successfully"
}
```

### SocialJobDto (TikTok, Instagram, LinkedIn, Snapchat, Threads)

```json
{
  "job_id": 7001,
  "link": "https://...",
  "type": "like",
  "fix_coin": 3
}
```

### JobDto (Facebook – đầy đủ nhất)

```json
{
  "id": 9001,
  "orders_id": 501,
  "object_id": "123456789_987654321",
  "link": "https://www.facebook.com/...",
  "type": "like",
  "description": "...",
  "comment_id": null,
  "quantity": 100,
  "remains": 45,
  "count_is_run": 55,
  "fix_coin_job": 5,
  "time_expired": 1717200000,
  "time_expired_format": "2024-06-01 12:00:00"
}
```

### PlatformStatsDto

```json
{
  "hold_coin": 200,
  "pending_coin": 50
}
```

### UserRankDto

```json
{
  "rank": 5,
  "rank_name": "Gold"
}
```

---

## 11. Flow tích hợp đề xuất

### Flow chuẩn cho mỗi platform

```
1. Login → lấy token
       ↓
2. GET api/users/me → kiểm tra coin, rank
       ↓
3. GET api/<platform>-account → lấy danh sách tài khoản
       ↓
4. Với mỗi tài khoản:
   GET api/advertising/publishers/<platform>/_private/get-jobs
                                    → lấy jobs
       ↓
5. Thực hiện tương tác trên app (accessibility / automation)
       ↓
6. POST api/advertising/publishers/<platform>/_private/complete-jobs
   → gửi kết quả { job_id, <platform>_username/uid/fb_id, success }
       ↓
7. Kiểm tra response.success == true → coin được cộng
```

### Mapping Platform → Query Param → Complete Param

| Platform | Get Jobs Query Param | Complete Jobs Body Param |
|---|---|---|
| Facebook | `fb_id` | `uid` |
| TikTok | `unique_username` | `unique_username` |
| Instagram | `instagram_username` | `instagram_username` |
| LinkedIn | `linkedin_username` | `linkedin_username` |
| Snapchat | `snap_username` + `type` | `snap_username` |
| Threads | `threads_username` | `threads_username` |

### Headers cần thiết cho mọi request (trừ login)

```http
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json
```

### Error Handling

| `success` | `status` | Ý nghĩa | Xử lý |
|---|---|---|---|
| `true` | 200 | Thành công | Xử lý data |
| `false` | 401 | Token hết hạn / sai | Re-login |
| `false` | 403 | Không đủ quyền | Kiểm tra role |
| `false` | 404 | Không có jobs | Bỏ qua, thử lại sau |
| `false` | 500 | Lỗi server | Retry với backoff |

---

*Tài liệu được tổng hợp từ: `GolikeApi.smali` + `autogolike/mobile/core/network/dto/*.smali`*  
*Phiên bản: 1.1 – Cập nhật: 2026-06-04 (v1.1.9: domain → golike.io, coin → Double)*

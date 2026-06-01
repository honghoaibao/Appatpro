# 📘 Golike API – Tài liệu tích hợp chi tiết

> **Nguồn:** Reverse-engineered từ `GolikeApi.smali` + toàn bộ DTO trong package `com.autogolike.mobile.core.network.dto`  
> **Base URL:** `https://golike.net/`  
> **Content-Type:** `application/json`  
> **Auth:** Bearer Token trong header `Authorization: Bearer <token>`

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

| Thuộc tính | Giá trị |
|---|---|
| **Method** | `POST` |
| **Endpoint** | `api/auto/login` |
| **Auth** | Không cần |

**Request Body:**
```json
{ "username": "your_username", "password": "your_password" }
```

**Response:**
```json
{
  "status": 200, "success": true, "message": "Login successful",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "data": { "id": 12345, "username": "your_username", "name": "Nguyễn Văn A",
            "email": "email@example.com", "role": "user", "coin": 5000 }
}
```

> ⚠️ Sau khi login thành công, lưu `token` vào secure storage. Gắn vào header: `Authorization: Bearer <token>`

---

## 2. Thông tin người dùng

### 2.1 GET `api/users/me`

**Response:**
```json
{
  "status": 200, "success": true,
  "data": {
    "id": 12345, "username": "your_username", "name": "Nguyễn Văn A",
    "coin": 5000, "total_notifications_unread": 3,
    "user_rank": { "rank": 5, "rank_name": "Gold" }
  }
}
```

---

## 3. Facebook Jobs

### 3.1 GET `api/fb-account?limit=50`
### 3.2 GET `api/advertising/publishers/_private/get-jobs?fb_id=<fb_id>`
### 3.3 POST `api/advertising/publishers/_private/complete-jobs`
```json
{ "job_id": 9001, "uid": "100012345678", "success": true, "message": "...", "comment_id": null }
```

---

## 4. TikTok Jobs

### 4.1 GET `api/tiktok-account`

**Response data[]:**
```json
{
  "id": 1, "unique_id": "user_abc123", "unique_username": "@user_abc123",
  "nickname": "Tên hiển thị TikTok", "avatar_thumb": "https://...",
  "status": 1, "status_text": "Active", "is_banned": false
}
```

> `unique_username` (có @) — **dùng cho get-jobs & complete-jobs**

### 4.2 GET `api/advertising/publishers/tiktok/_private/get-jobs?unique_username=@user_abc123`

**Response data[]:**
```json
{ "job_id": 7001, "link": "https://www.tiktok.com/@user/video/123", "type": "like", "fix_coin": 3 }
```

| `type` | Hành động |
|---|---|
| `like` | Thích video |
| `follow` | Follow tác giả |
| `comment` | Bình luận |
| `share` | Chia sẻ |
| `view` | Xem video |

### 4.3 POST `api/advertising/publishers/tiktok/_private/complete-jobs`
```json
{ "job_id": 7001, "unique_username": "@user_abc123", "success": true }
```

---

## 5. Instagram Jobs

### 5.1 GET `api/instagram-account`
### 5.2 GET `api/advertising/publishers/instagram/_private/get-jobs?instagram_username=<username>`
### 5.3 POST `api/advertising/publishers/instagram/_private/complete-jobs`
```json
{ "job_id": 2001, "instagram_username": "instauser", "success": true }
```

---

## 6. LinkedIn Jobs

### 6.1 GET `api/linkedin-account`
### 6.2 GET `api/advertising/publishers/linkedin/_private/get-jobs?linkedin_username=<username>`
### 6.3 POST `api/advertising/publishers/linkedin/_private/complete-jobs`
```json
{ "job_id": 3001, "linkedin_username": "linkedinuser", "success": true }
```

---

## 7. Snapchat Jobs

### 7.1 GET `api/snapchat-account`
### 7.2 GET `api/advertising/publishers/snapchat/_private/get-jobs?snap_username=<>&type=<type>`
> Snapchat là platform **duy nhất** có thêm `type` trong get-jobs.
### 7.3 POST `api/advertising/publishers/snapchat/_private/complete-jobs`
```json
{ "job_id": 5001, "snap_username": "snapuser123", "success": true }
```

---

## 8. Threads Jobs

### 8.1 GET `api/threads-account`
### 8.2 GET `api/advertising/publishers/threads/_private/get-jobs?threads_username=<username>`
### 8.3 POST `api/advertising/publishers/threads/_private/complete-jobs`
```json
{ "job_id": 4001, "threads_username": "threads_user", "success": true }
```

---

## 9. Thống kê

### 9.1 GET `api/statistics/report`

**Response:**
```json
{
  "status": 200, "success": true,
  "current_coin": 5000,
  "tiktok": { "hold_coin": 150, "pending_coin": 30 },
  "facebook": { "hold_coin": 200, "pending_coin": 50 },
  "instagram": { "hold_coin": 0, "pending_coin": 0 },
  ...
}
```

Platform keys: `facebook`, `tiktok`, `instagram`, `linkedin`, `snapchat`, `threads`, `youtube`, `twitter`, `bluesky`, `pinterest`, `tumblr`, `soundcloud`, `traffic`, `review`, `shopee`, `lazada`

---

## 10. Data Models tổng hợp

### SocialJobDto (TikTok, Instagram, LinkedIn, Snapchat, Threads)
```json
{ "job_id": 7001, "link": "https://...", "type": "like", "fix_coin": 3 }
```

### JobDto (Facebook – đầy đủ nhất)
```json
{
  "id": 9001, "orders_id": 501, "object_id": "123_987", "link": "https://...",
  "type": "like", "description": "...", "comment_id": null,
  "quantity": 100, "remains": 45, "count_is_run": 55,
  "fix_coin_job": 5, "time_expired": 1717200000, "time_expired_format": "2024-06-01 12:00:00"
}
```

---

## 11. Flow tích hợp đề xuất

```
1. POST api/auto/login          → lấy token
2. GET  api/users/me            → kiểm tra coin, rank
3. GET  api/tiktok-account      → danh sách TikTok accounts
4. Với mỗi account:
   GET api/advertising/publishers/tiktok/_private/get-jobs?unique_username=@...
5. Thực hiện tương tác trên TikTok (accessibility/automation)
6. POST api/advertising/publishers/tiktok/_private/complete-jobs
   → { job_id, unique_username, success: true }
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

### Error Handling

| `success` | `status` | Ý nghĩa | Xử lý |
|---|---|---|---|
| `true` | 200 | Thành công | Xử lý data |
| `false` | 401 | Token hết hạn / sai | Re-login |
| `false` | 403 | Không đủ quyền | Kiểm tra role |
| `false` | 404 | Không có jobs | Bỏ qua, thử lại sau |
| `false` | 500 | Lỗi server | Retry với backoff |

---

*Nguồn: `GolikeApi.smali` + `autogolike/mobile/core/network/dto/*.smali`*  
*Phiên bản: 1.0 – Ngày đưa vào .claude: 2026-06-01*

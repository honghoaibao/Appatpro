# 📡 Tài liệu API — Golike Server
> Base URL: `https://golike.io` *(xác nhận bằng network capture)*  
> Auth: `Authorization: Bearer {token}` — trừ endpoint login  
> Content-Type: `application/json`

---

## Mục lục

1. [Xác thực](#xác-thực)
2. [Thông tin người dùng](#thông-tin-người-dùng)
3. [Facebook](#facebook)
4. [TikTok](#tiktok)
5. [Instagram](#instagram)
6. [LinkedIn](#linkedin)
7. [Threads](#threads)
8. [Snapchat](#snapchat)
9. [Tổng hợp endpoints](#tổng-hợp)

---

## Xác thực

### POST `api/auto/login`
Không cần auth.

**Request:**
```json
{
  "username": "your_username",
  "password": "your_password"
}
```

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Đăng nhập thành công",
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "data": {
    "id": 12345,
    "username": "your_username",
    "name": "Nguyen Van A",
    "email": "user@gmail.com",
    "role": "user",
    "coin": 250.5
  }
}
```

> ⚠️ Lưu lại `token` — dùng trong header `Authorization: Bearer {token}` cho tất cả request bên dưới.

---

## Thông tin người dùng

### GET `api/users/me`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": {
    "id": 12345,
    "name": "Nguyen Van A",
    "username": "your_username",
    "email": "user@gmail.com",
    "coin": 250.5,
    "total_notifications_unread": 2,
    "user_rank": {
      "rank_name": "Gold",
      "rank": 3
    }
  }
}
```

---

### GET `api/statistics/report`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "current_coin": 250.5,
  "facebook":   { "pending_coin": 5.0, "hold_coin": 2.0 },
  "tiktok":     { "pending_coin": 3.0, "hold_coin": 0.0 },
  "instagram":  { "pending_coin": 1.0, "hold_coin": 0.0 },
  "threads":    { "pending_coin": 0.0, "hold_coin": 0.0 },
  "linkedin":   { "pending_coin": 0.0, "hold_coin": 0.0 },
  "snapchat":   { "pending_coin": 0.0, "hold_coin": 0.0 },
  "youtube":    { "pending_coin": 0.0, "hold_coin": 0.0 },
  "twitter":    { "pending_coin": 0.0, "hold_coin": 0.0 },
  "shopee":     { "pending_coin": 0.0, "hold_coin": 0.0 },
  "lazada":     { "pending_coin": 0.0, "hold_coin": 0.0 },
  "traffic":    { "pending_coin": 0.0, "hold_coin": 0.0 },
  "review":     { "pending_coin": 0.0, "hold_coin": 0.0 },
  "pinterest":  { "pending_coin": 0.0, "hold_coin": 0.0 },
  "bluesky":    { "pending_coin": 0.0, "hold_coin": 0.0 },
  "tumblr":     { "pending_coin": 0.0, "hold_coin": 0.0 },
  "soundcloud": { "pending_coin": 0.0, "hold_coin": 0.0 }
}
```

---

## Facebook

### GET `api/fb-account`
**Query:** `?limit={N}`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": {
    "data": [
      {
        "id": 1,
        "fb_id": "100009876543210",
        "fb_name": "Nguyen Van A",
        "username": "nguyen.van.a",
        "status": 1,
        "is_banned": false
      }
    ]
  }
}
```

| Field | Type | Mô tả |
|-------|------|-------|
| `fb_id` | string | Facebook UID — dùng cho get-jobs |
| `status` | int | `1` = active |
| `is_banned` | bool | Bị ban trên FB |

---

### GET `api/advertising/publishers/_private/get-jobs`
**Query:** `?fb_id={fb_id}`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": 998877,
      "object_id": "664432109876543",
      "type": "like",
      "link": "https://www.facebook.com/photo/?fbid=664432109876543",
      "count_is_run": 45,
      "quantity": 100,
      "time_expired": 1748995200,
      "time_expired_format": "2025-06-04 00:00:00",
      "description": "Like bài viết trang X",
      "orders_id": 5566,
      "remains": 55,
      "fix_coin_job": 2,
      "comment_id": ""
    }
  ]
}
```

| Field | Type | Mô tả |
|-------|------|-------|
| `id` | int | **Job ID** — dùng khi complete |
| `object_id` | string | **FB Object ID** — dùng để like qua Graph API |
| `type` | string | `"like"`, `"comment"`, ... |
| `remains` | int | Slot còn lại |
| `fix_coin_job` | int | Coin thưởng |
| `comment_id` | string | Nội dung comment nếu type=comment |

---

### POST `api/advertising/publishers/_private/complete-jobs`

**Request:**
```json
{
  "job_id": 998877,
  "uid": "100009876543210",
  "success": true,
  "comment_id": "",
  "message": ""
}
```

| Field | Type | Mô tả |
|-------|------|-------|
| `job_id` | int | `data[].id` từ get-jobs |
| `uid` | string | `fb_id` của tài khoản thực hiện |
| `success` | bool | `true` = thành công |
| `comment_id` | string | ID comment nếu là job comment, để `""` với like |
| `message` | string | Thường để `""` |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Hoàn thành job thành công"
}
```

---

## TikTok

### GET `api/tiktok-account`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": 10,
      "unique_username": "@tiktok_user",
      "nickname": "TikTok User",
      "avatar_thumb": "https://cdn.tiktok.com/avatar/...",
      "unique_id": "tiktok_user_123",
      "status": 1,
      "is_banned": false,
      "status_text": "Active"
    }
  ]
}
```

| Field | Type | Mô tả |
|-------|------|-------|
| `unique_username` | string | **TikTok username** — dùng cho get-jobs |
| `unique_id` | string | TikTok unique ID |

---

### GET `api/advertising/publishers/tiktok/_private/get-jobs`
**Query:** `?unique_username={username}`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "job_id": 77001,
      "fix_coin": 3,
      "link": "https://www.tiktok.com/@user/video/7123456789",
      "type": "like"
    }
  ]
}
```

---

### POST `api/advertising/publishers/tiktok/_private/complete-jobs`

**Request:**
```json
{
  "job_id": 77001,
  "unique_username": "@tiktok_user",
  "success": true
}
```

**Response:**
```json
{ "status": 200, "success": true, "message": "OK" }
```

---

## Instagram

### GET `api/instagram-account`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": 20,
      "instagram_username": "ig_user",
      "instagram_full_name": "IG User Full Name",
      "instagram_id": "987654321",
      "profile_pic_url": "https://cdn.instagram.com/pic.jpg",
      "status": 1,
      "is_banned": false,
      "status_text": "Active"
    }
  ]
}
```

| Field | Type | Mô tả |
|-------|------|-------|
| `instagram_username` | string | **Username** — dùng cho get-jobs |
| `instagram_id` | string | Instagram UID |

---

### GET `api/advertising/publishers/instagram/_private/get-jobs`
**Query:** `?instagram_username={username}`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "job_id": 88001,
      "fix_coin": 2,
      "link": "https://www.instagram.com/p/CxYzAbCdEf/",
      "type": "like"
    }
  ]
}
```

---

### POST `api/advertising/publishers/instagram/_private/complete-jobs`

**Request:**
```json
{
  "job_id": 88001,
  "instagram_username": "ig_user",
  "success": true
}
```

**Response:**
```json
{ "status": 200, "success": true, "message": "OK" }
```

---

## LinkedIn

### GET `api/linkedin-account`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": 30,
      "username": "li-username",
      "name": "LinkedIn User",
      "link": "https://www.linkedin.com/in/li-username/",
      "profile_image_url": "https://media.licdn.com/pic.jpg",
      "status": 1,
      "is_banned": false,
      "status_text": "Active"
    }
  ]
}
```

| Field | Type | Mô tả |
|-------|------|-------|
| `username` | string | **LinkedIn username** — dùng cho get-jobs |

---

### GET `api/advertising/publishers/linkedin/_private/get-jobs`
**Query:** `?linkedin_username={username}`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "job_id": 66001,
      "fix_coin": 4,
      "link": "https://www.linkedin.com/posts/activity-123456",
      "type": "like"
    }
  ]
}
```

---

### POST `api/advertising/publishers/linkedin/_private/complete-jobs`

**Request:**
```json
{
  "job_id": 66001,
  "linkedin_username": "li-username",
  "success": true
}
```

**Response:**
```json
{ "status": 200, "success": true, "message": "OK" }
```

---

## Threads

### GET `api/threads-account`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": 40,
      "threads_username": "threads_user",
      "name": "Threads User",
      "uid": "threads_uid_123",
      "profile_image_url": "https://cdn.threads.net/pic.jpg",
      "status": 1,
      "is_banned": false,
      "status_text": "Active"
    }
  ]
}
```

| Field | Type | Mô tả |
|-------|------|-------|
| `threads_username` | string | **Username** — dùng cho get-jobs |
| `uid` | string | Threads UID |

---

### GET `api/advertising/publishers/threads/_private/get-jobs`
**Query:** `?threads_username={username}`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "job_id": 55001,
      "fix_coin": 2,
      "link": "https://www.threads.net/@user/post/AbCdEfGh",
      "type": "like"
    }
  ]
}
```

---

### POST `api/advertising/publishers/threads/_private/complete-jobs`

**Request:**
```json
{
  "job_id": 55001,
  "threads_username": "threads_user",
  "success": true
}
```

**Response:**
```json
{ "status": 200, "success": true, "message": "OK" }
```

---

## Snapchat

### POST `api/snapchat-account/verify-account`
Xác thực trước khi thêm tài khoản.

**Request:**
```json
{ "username": "snap_user" }
```

**Response:**
```json
{ "status": 200, "success": true, "message": "Tài khoản hợp lệ" }
```

---

### POST `api/snapchat-account/remove`

**Request:**
```json
{ "id": 50 }
```

**Response:**
```json
{ "status": 200, "success": true, "message": "Đã xoá" }
```

---

### GET `api/snapchat-account`

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": 50,
      "username": "golike_internal_username",
      "snap_username": "snap_user",
      "name": "Snap User",
      "nickname": "Snap Nick",
      "status": 1
    }
  ]
}
```

| Field | Type | Mô tả |
|-------|------|-------|
| `snap_username` | string | **Snap username** — dùng cho get-jobs |

---

### GET `api/advertising/publishers/snapchat/_private/get-jobs`
**Query:** `?snap_username={username}&type={type}`

| Query param | Mô tả |
|-------------|-------|
| `snap_username` | Snap username |
| `type` | Loại job, vd: `"subscribe"`, `"view"` |

**Response:**
```json
{
  "status": 200,
  "success": true,
  "message": "Success",
  "data": [
    {
      "job_id": 44001,
      "fix_coin": 3,
      "link": "https://www.snapchat.com/add/snap_target",
      "type": "subscribe"
    }
  ]
}
```

---

### POST `api/advertising/publishers/snapchat/_private/complete-jobs`

**Request:**
```json
{
  "job_id": 44001,
  "snap_username": "snap_user",
  "success": true
}
```

**Response:**
```json
{ "status": 200, "success": true, "message": "OK" }
```

---

## Tổng hợp

| # | Method | Endpoint | Mô tả |
|---|--------|----------|-------|
| 1 | POST | `api/auto/login` | Đăng nhập |
| 2 | GET | `api/users/me` | Thông tin user |
| 3 | GET | `api/statistics/report` | Thống kê coin |
| 4 | GET | `api/fb-account` | DS tài khoản FB |
| 5 | GET | `api/advertising/publishers/_private/get-jobs` | Job FB |
| 6 | POST | `api/advertising/publishers/_private/complete-jobs` | Hoàn thành job FB |
| 7 | GET | `api/tiktok-account` | DS tài khoản TikTok |
| 8 | GET | `api/advertising/publishers/tiktok/_private/get-jobs` | Job TikTok |
| 9 | POST | `api/advertising/publishers/tiktok/_private/complete-jobs` | Hoàn thành job TikTok |
| 10 | GET | `api/instagram-account` | DS tài khoản Instagram |
| 11 | GET | `api/advertising/publishers/instagram/_private/get-jobs` | Job Instagram |
| 12 | POST | `api/advertising/publishers/instagram/_private/complete-jobs` | Hoàn thành job Instagram |
| 13 | GET | `api/linkedin-account` | DS tài khoản LinkedIn |
| 14 | GET | `api/advertising/publishers/linkedin/_private/get-jobs` | Job LinkedIn |
| 15 | POST | `api/advertising/publishers/linkedin/_private/complete-jobs` | Hoàn thành job LinkedIn |
| 16 | GET | `api/threads-account` | DS tài khoản Threads |
| 17 | GET | `api/advertising/publishers/threads/_private/get-jobs` | Job Threads |
| 18 | POST | `api/advertising/publishers/threads/_private/complete-jobs` | Hoàn thành job Threads |
| 19 | POST | `api/snapchat-account/verify-account` | Xác thực Snapchat |
| 20 | POST | `api/snapchat-account/remove` | Xoá tài khoản Snapchat |
| 21 | GET | `api/snapchat-account` | DS tài khoản Snapchat |
| 22 | GET | `api/advertising/publishers/snapchat/_private/get-jobs` | Job Snapchat |
| 23 | POST | `api/advertising/publishers/snapchat/_private/complete-jobs` | Hoàn thành job Snapchat |

---

### Cấu trúc Job — 2 loại

**Facebook** (`JobDto` — chi tiết):

| Field | Type | Ý nghĩa |
|-------|------|---------|
| `id` | int | Job ID |
| `object_id` | string | FB Object ID để like |
| `type` | string | Loại job |
| `remains` | int | Slot còn |
| `fix_coin_job` | int | Coin thưởng |
| `time_expired` | int | Unix timestamp hết hạn |
| `comment_id` | string | Nội dung comment |

**Các nền tảng còn lại** (`SocialJobDto` — gọn):

| Field | Type | Ý nghĩa |
|-------|------|---------|
| `job_id` | int | Job ID |
| `fix_coin` | int | Coin thưởng |
| `link` | string | URL bài viết |
| `type` | string | Loại job |


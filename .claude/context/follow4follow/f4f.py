#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""f4f.py — Single-file CLI for follow4follow.net (v2.0).

Workflow:
  1. Login  (POST /user/auth/login)
  2. Pick endpoint: nhóm → endpoint (menu 2 cấp, tìm kiếm /từ-khóa, ✓ = đã verify)
     · FIELD_GUIDE hiện trước khi điền form  · lỗi nghiệp vụ + gợi ý xử lý
     · 429 tự đếm ngược retry_after  · endpoint chỉ-admin → đề nghị ẩn khỏi menu
  3. Fill body / path-params / query interactively when needed
  4. Tool signs request (X-Ts + X-Sign, HMAC-SHA-256, secret='follow4follow_key')
     and sends it automatically.
  5. Show response: status bar + smart view + message panel.
  6. On error → "Chi tiết lỗi" panel with full HTTP + JSON + errors object.
  7. Clear màn hình xoá SẠCH cả scrollback (\033[2J + \033[3J + \033[H).
  8. Complete job xong → bảng Số dư đủ 4 nhóm (v2.3, DevTools 29/10):
     coin_received · current_balance · balance_pending · hạn mức hôm nay
     (done/limit/remaining + bar) — hạn mức tự cập nhật lên header phiên.
  9. Trong vòng lặp job ấn A để ĐỔI TÀI KHOẢN TikTok làm jobs giữa phiên
     (v2.7 — KIỂU BUILD WEB MỚI, KHÔNG POST): GET /user/account/quick-switch
     → chọn acc → fetch lại jobs refresh=1 + uid=<acc.name||uid> → server đổi
     accountActive THEO uid + đồng bộ hạn mức theo server-truth; danh sách
     job thay TOÀN BỘ theo acc mới (không trộn jobs acc cũ).
 10. v2.5 — 429 tự đếm ngược retry_after + thử lại ở mọi bước (lấy jobs,
     quick-switch); hết jobs/chạm hạn mức → menu A/F/T/Q tại chỗ; fix crash
     `return False` (thiếu tuple) trong _do_job_action.
 11. v2.6 — FIX "Lỗi không tìm thấy dữ liệu" (báo cáo 4/11 tối; ảnh DevTools
     18:25 chứng minh web vẫn nhận đủ jobs cho acc hoaibao.2009):
     • Site deploy BUILD WEB MỚI (bundle CEACzOZS.js / Da1G0J4j.js — chunk
       EarnCoinsTiktokView): trang web TRUYỀN uid=<acc.name||uid> vào MỖI lần
       GET /user/earn-coin khi biết acc active (chụp thật 18:25:
       ?platform=tiktok&type=follow&refresh=1&uid=hoaibao.2009 → 200 + jobs).
       v2.5 bỏ hẳn uid (dựa trên ảnh buổi sáng) → server không tự đoán được
       acc → trả lỗi "Không tìm thấy dữ liệu".
     • fetch_jobs mirror 100% build mới: uid = acc.name || acc.uid, refresh=1
       chỉ khi refetch. Thêm THANG DỰ PHÒNG: V1 web-chuẩn → V2 plain →
       V3 refresh=1 plain; lỗi/rỗng thì tự thử phương án kế.
     • Đổi acc / kích hoạt acc xong → fetch refresh=1 + uid=<acc mới> (web
       sl(): Ra(true, e, true) — đúng nhịp ảnh DevTools).
     • Job card đọc cả `remain` (schema build 4/11) lẫn `remains` (schema cũ).
     • Complete/Skip trả "Không tìm thấy dữ liệu" (job đã xoá/hết) → TỰ sang
       job kế, không hỏi lại (web cũng gạch job lỗi khỏi danh sách).
 12. v2.7 — FIX TIẾP "Vẫn lỗi / server không cho đổi acc" (4/11 tối, sau v2.6):
     Đọc lại BUILD WEB MỚI NHẤT (đọc trực tiếp follow4follow.net/_nuxt/:
     chunk BxxhXs6I.js QuickSwitch + Da1G0J4j.js EarnCoinsTiktokView):
     • Web ĐÃ BỎ HẲN POST đổi acc (quick-switch / active-acc không còn được
       gọi). Đổi acc = thao tác CLIENT-SIDE: GET quick-switch list → chọn acc
       → lưu localStorage['quickswitch_active_<type>'] → fetch earn-coin
       refresh=1 & uid=<name||uid>. SERVER NHẬN ACC TỪ uid PARAM.
       → Tool cũ POST /user/account/quick-switch → server build mới trả
       "Không tìm thấy dữ liệu" = ĐÚNG lỗi user báo (đổi acc không được).
     • Web KHÔNG BAO GIỜ gọi earn-coin khi chưa biết acc (view: if(acc) fetch
       uid… else jobs=[]) → tool gọi earn-coin TRẦN (không uid) cũng bị
       "Không tìm thấy dữ liệu" = lỗi "không nhận được nhiệm vụ".
     • v2.7: mở workflow → GET quick-switch list (data.accounts) → acc đã nhớ
       (state file ≈ localStorage) / auto nếu 1 acc / hỏi nếu nhiều → LUÔN
       fetch kèm uid. Đổi acc (A) = chọn acc KHÔNG POST → fetch refresh=1+
       uid. Nếu server vẫn trả acc khác acc vừa chọn → thử POST cũ 1 lần
       (quick-switch → active-acc) rồi theo server-truth.

 13. v2.8 — FIX CỐT "Vẫn còn lỗi không tìm thấy dữ liệu khi đổi acc" (5/11):
     Chẩn đoán TRỰC TIẾP trên API thật (ký HMAC y hệt tool): thuật toán x-sign
     KHỚP 100% chữ ký web thật (ảnh DevTools 4/11 18:25 — so bit); token chết
     trả 401 "Phiên đăng nhập không hợp lệ" ở MỌI endpoint (khác lỗi user
     thấy) → "Không tìm thấy dữ liệu" là message SERVER trả riêng cho bước
     đổi acc. Đọc lại build web mới nhất 5/11 (_nuxt: DM6Yw4R9 / BxxhXs6I /
     Da1G0J4j / DYEqVcGR / CEACzOZS): flow đổi acc web KHÔNG ĐỔI (uid param,
     không POST), service layer vẫn export activateAccount + quickSwitch.
     • THANG ĐỔI ACC MỚI (_switch_force) — khi fetch uid không hiệu lực:
       S1 POST active-acc → S2 fetch uid=<số> → S3 POST quick-switch (cũ) →
       S4 fetch uid=<name> lại → S5 fetch trần; mỗi bước in 1 dòng trace
       (HTTP + message server) để chụp màn hình chẩn đoán; 429 tự chờ.
     • Fetch jobs LỖI khi vừa chọn acc / accountActive=null → TỰ chạy thang
       với acc đang nhắm TRƯỚC khi bỏ cuộc / hỏi lại user.
     • PHIÊN HẾT HẠN TỰ HỒI PHỤC: server cho phiên ngắn (token ảnh 4/11 chết
       sau ~5 giờ — xác thực bằng call thật). Đăng nhập có tùy chọn "ghi nhớ
       mật khẩu" (base64 + chmod 600, ở máy user) → MỌI request gặp 401 tự
       đăng nhập lại 1 lần rồi GỬI LẠI request đó trong suốt; mở tool cũng
       tự đăng nhập lại. Đăng xuất (mục 4) quên cả mật khẩu.
     • Hết thang vẫn fail → panel chỉ rõ 3 nguyên nhân + cách xử lý: (1) phiên
       chết → mục 1; (2) server giữ acc khác → thoát mở lại workflow;
       (3) acc hỏng dữ liệu phía server → web Account: xóa + thêm lại acc.

Use API_reference_v2.txt for the full endpoint+body reference.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import random
import re
import sys
import time
import webbrowser
from dataclasses import dataclass
from datetime import datetime
from getpass import getpass
from pathlib import Path
from typing import Any

import requests
from rich import box
from rich.console import Console
from rich.live import Live
from rich.panel import Panel
from rich.prompt import Confirm, IntPrompt, Prompt
from rich.rule import Rule
from rich.syntax import Syntax
from rich.table import Table
from rich.text import Text

VERSION = "2.8"
console = Console()

# Hiệu ứng động: tắt nếu không phải TTY hoặc F4F_NO_ANIM=1
ANIM = sys.stdout.isatty() and os.environ.get("F4F_NO_ANIM", "") != "1"
# F4F_FAST=1 → sau khi hoàn thành job KHÔNG dừng hỏi Enter (chạy nhanh liên tục)
FAST = os.environ.get("F4F_FAST", "") == "1"

# Metadata request GẦN NHẤT — để panel lỗi chứng minh headers luôn được gắn
_LAST_REQ: dict = {}

STATE_DIR = Path.home() / ".f4f"
SESSION_PATH = STATE_DIR / "session.json"
# v2.7 — mirror localStorage['quickswitch_active_<type>'] của web (QuickSwitch
# chunk BxxhXs6I.js): nhớ acc đã chọn để lần sau tự dùng lại không cần hỏi.
STATE_PATH = STATE_DIR / "state.json"
# v2.8 — ghi nhớ đăng nhập (OPT-IN, base64 + chmod 600) để TỰ đăng nhập lại khi
# server trả 401 giữa phiên (đo thực tế 5/11: token chết sau vài giờ → mọi
# call báo lỗi, tool cũ không tự phục hồi được).
CREDS_PATH = STATE_DIR / "cred.json"
BASE_URL = "https://api.follow4follow.net"
SECRET: bytes = b"follow4follow_key"   # Pm trong bundle Bfp0_r72.js
SECRET_HINTS = ("token", "password", "api_key", "secret")

# ───────────────────────── Signing (embedded) ─────────────────────────
@dataclass(frozen=True)
class Signed:
    x_ts: int
    x_sign: str
    def headers(self) -> dict[str, str]:
        return {"x-ts": str(self.x_ts), "x-sign": self.x_sign}

def sign_request(method: str, path: str, *, ts: int | None = None) -> Signed:
    """HMAC-SHA-256(key='follow4follow_key', msg=f'{ts}:{METHOD.upper()}:{path_no_query}')."""
    if ts is None:
        ts = int(time.time())
    path_only = path.split("?", 1)[0]
    msg = f"{ts}:{method.upper()}:{path_only}".encode("utf-8")
    return Signed(
        x_ts=ts,
        x_sign=hmac.new(SECRET, msg, hashlib.sha256).hexdigest(),
    )

# ───────────────────────── Endpoint catalogue (81 entries) ─────────────────────────
# (label, path, method, query_params_template, body_template, category)
# Endpoint POST gửi dạng multipart/form-data thay vì JSON:
MULTIPART_PATHS = {"/user/support/tickets"}
ENDPOINTS: list[tuple[str, str, str, dict | None, dict | None, str]] = [
    # ── Auth (11)
    ("Đăng nhập",              "/user/auth/login",                  "POST", None,
        {"username": "<USERNAME>", "password": "<PASSWORD>"}, "Auth"),  # [VERIFIED call-site]
    ("Đăng ký",                "/user/auth/register",               "POST", None,
        {"username": "<USERNAME>", "password": "<PASSWORD>",
         "password_confirmation": "<PASSWORD_AGAIN>",
         "g_recaptcha_response": "<RECAPTCHA_V3_TOKEN>",
         "ref_code": "<REF_CODE>?"}, "Auth"),
    ("Đăng xuất (server)",    "/user/auth/logout",                 "POST", None, None, "Auth"),
    ("Hồ sơ tôi",              "/user/auth/me",                     "GET",  None, None, "Auth"),
    ("Đổi mật khẩu",           "/user/auth/change-password",        "POST", None,
        {"current_password": "<CURRENT>",
         "password": "<NEW>",
         "password_confirmation": "<NEW_AGAIN>"}, "Auth"),  # [VERIFIED — KHÔNG phải new_password]
    ("Quên mật khẩu",          "/user/auth/forgot-password",        "POST", None,
        {"email": "<EMAIL>"}, "Auth"),
    ("Reset mật khẩu",         "/user/auth/reset-password",         "POST", None,
        {"email": "<EMAIL>", "token": "<RESET_TOKEN>",
         "password": "<NEW>", "password_confirmation": "<NEW_AGAIN>"}, "Auth"),
    ("Cập nhật hồ sơ",         "/user/auth/update-profile",         "POST", None,
        {"name": "<NAME>?", "avatar": "<URL_OR_BASE64>?", "email": "<EMAIL>?"}, "Auth"),
    ("Sinh SMM api key",      "/user/auth/generate-smm-api-key",   "POST", None, None, "Auth"),
    ("Accept terms",          "/user/auth/accept-terms",            "POST", None, None, "Auth"),
    ("Login Google token",    "/user/auth/google/token",            "POST", None,
        {"token": "<GOOGLE_ID_TOKEN>", "ref_code": "<REF_CODE>?"}, "Auth"),
    # ── Profile / Account (3)
    ("Profile (GET)",          "/user/profile",                     "GET",  None, None, "Account"),
    ("Profile (PUT)",          "/user/profile",                     "PUT",  None,
        {"name": "<NAME>?", "avatar": "<URL>?"}, "Account"),
    ("Số dư",                  "/user/balance",                     "GET",  None, None, "Account"),
    # ── Dashboard / Feature flags (2)
    ("Dashboard",              "/user/dashboard",                   "GET",  None, None, "Account"),
    ("Feature flags",          "/user/feature-flags",                "GET",  None, None, "Account"),
    # ── Leaderboard (1)
    ("Bảng xếp hạng",          "/user/leaderboard",                 "GET",  {"limit": 20}, None, "Leaderboard"),
    # ── Accounts (8)
    # v2.1 FIELD-TESTED (DevTools 27/10): web CHỈ gửi type + page — KHÔNG search
    ("Danh sách tài khoản",    "/user/account/list",                "GET",
        {"type": "tiktok", "page": 1}, None, "Accounts"),
    ("Tài khoản active",       "/user/account/active",             "GET",  {"type": "tiktok"}, None, "Accounts"),
    ("Kích hoạt tài khoản",    "/user/account/active-acc",          "POST", None,
        {"account_id": "<ACCOUNT_ID>", "type": "tiktok"}, "Accounts"),
    ("Thêm tài khoản",         "/user/account/add",                 "POST", None,
        {"type": "tiktok", "link": "<PROFILE_LINK_OR_USERNAME>",
         "g_recaptcha_response": "<RECAPTCHA_V3_TOKEN>"}, "Accounts"),  # [VERIFIED call-site]
    ("Thêm hàng loạt",         "/user/account/add-bulk",            "POST", None,
        {"type": "tiktok", "links": ["<URL1>", "<URL2>"]}, "Accounts"),
    ("Convert link",           "/user/convert-link",                "POST", None,
        {"link": "<URL>", "type": "tiktok?"}, "Accounts"),
    ("Quick-switch list",      "/user/account/quick-switch",        "GET",  {"type": "tiktok"}, None, "Accounts"),
    ("Quick-switch (POST)",   "/user/account/quick-switch",         "POST", None,
        {"account_id": "<ACCOUNT_ID>", "type": "tiktok"}, "Accounts"),
    # ── Earn-coin (8)
    ("Lấy jobs earn-coin",     "/user/earn-coin",                   "GET",
        {"platform": "tiktok", "type": "follow", "refresh": 1, "uid": "<UID>?"}, None, "Earn"),
    # v2.1 FIELD-TESTED (DevTools 27/10): action BẮT BUỘC — web luôn gửi
    # ?action=follow hoặc ?action=like; thiếu action → server trả total 0 (rỗng)
    ("Lịch sử earn-coin",     "/user/earn-coin/list",               "GET",
        {"type": "tiktok", "page": 1, "action": "<action:follow|like>"}, None, "Earn"),
    ("Thống kê earn-coin",    "/user/earn-coin/statistical",        "GET",  {"type": "tiktok"}, None, "Earn"),
    ("Complete job",          "/user/earn-coin/complete-job",       "POST", None,
        {"job_id": "<JOB_ID>", "account_id": "<ACCOUNT_ID>",
         "type": "tiktok", "type_job": "follow"}, "Earn"),
    ("Skip job",               "/user/earn-coin/skip-job",          "POST", None,
        {"job_id": "<JOB_ID>", "account_id": "<ACCOUNT_ID>", "uid": "<UID>",
         "object_id": "<OBJECT_ID>", "type": "tiktok"}, "Earn"),
    ("Report job",             "/user/earn-coin/report-job",        "POST", None,
        {"job_id": "<JOB_ID>", "account_id": "<ACCOUNT_ID>", "uid": "<UID>",
         "object_id": "<OBJECT_ID>", "type": "tiktok", "message": "<REASON>?"}, "Earn"),
    ("Tiktok-like mark-start", "/user/earn-coin/tiktok-like/mark-start", "POST", None,
        {"job_id": "<JOB_ID>", "account_id": "<ACCOUNT_ID>"}, "Earn"),
    ("Job rewards",            "/user/job-rewards",                 "GET",  None, None, "Earn"),
    # ── Jobs (3)
    ("Danh sách jobs",         "/jobs",                              "GET",  None, None, "Jobs"),  # [VERIFIED 28/10 — cur_Cs2oLB4-.js getJobs(o={}) params generic; build hiện tại không trang nào gọi (dead code) nhưng server vẫn nhận]
    ("Claim job",              "/jobs/{id}/claim",                   "POST", None, None, "Jobs"),  # [VERIFIED 28/10 — claimJob(o)=>t.post(path) KHÔNG body]
    ("Claim all jobs",         "/jobs/claim-all",                    "POST", None, None, "Jobs"),  # [VERIFIED 28/10 — claimAllJobs() KHÔNG body]
    # ── /accounts/ public social (4)
    ("Linked accounts",        "/accounts/{type}",                   "GET",  None, None, "Social"),  # [VERIFIED 28/10 — getLinkedAccounts(o='tiktok'); dead-code: trang /account/* dùng /user/account/list thay thế]
    ("Add social account",     "/accounts/{type}",                   "POST", None,
        {"cookie_or_token": "<COOKIE_OR_TOKEN>"}, "Social"),  # [LƯU Ý 28/10 — web client addAccount(o,a) dead-code, body thật KHÔNG xác định được; giữ body cũ]
    ("Remove social account",  "/accounts/{type}/{id}",              "DELETE", None, None, "Social"),  # [VERIFIED 28/10 — removeAccount DELETE không body]
    ("Refresh social token",   "/accounts/{type}/{id}/refresh",      "POST", None, None, "Social"),  # [VERIFIED 28/10 — refreshAccountToken POST không body]
    # ── Transaction / Deposit (2)
    ("Lịch sử giao dịch",     "/user/transaction/list",            "GET",
        {"page": 1, "per_page": 20, "search": "<SEARCH>?"}, None, "Finance"),
    ("Deposit info",           "/user/deposit/info",                "GET",
        {"month": "<YYYYMM>?"}, None, "Finance"),
    # ── Withdraw (8)
    ("Withdraw info",          "/user/withdraw/info",                "GET",  None, None, "Withdraw"),
    ("Withdraw list",          "/user/withdraw/list",                "GET",
        {"page": 1, "per_page": 10, "search": "<SEARCH>?"}, None, "Withdraw"),
    ("Withdraw statistical",   "/user/withdraw/statistical",         "GET",  None, None, "Withdraw"),
    ("Rút BANK",               "/user/withdraw/bank",                "POST", None,
        {"bank_name": "<BANK_NAME>", "account_name": "<NAME>",
         "account_number": "<ACCT_NUM>", "amount": 1000}, "Withdraw"),
    ("Rút ZaloPay",            "/user/withdraw/zalopay",             "POST", None,
        {"bank_name": "<BANK_NAME>", "account_number": "<ZALOPAY_PHONE>",
         "account_name": "<NAME>", "amount": 1000}, "Withdraw"),  # [VERIFIED — cùng shape bank]
    ("Rút PayPal",             "/user/withdraw/paypal",              "POST", None,
        {"email": "<PAYPAL_EMAIL>", "amount": 1000}, "Withdraw"),  # [VERIFIED — field `email`]
    ("Rút Crypto",             "/user/withdraw/crypto",              "POST", None,
        {"network": "<network>", "wallet_address": "<WALLET_ADDR>", "amount": 1000}, "Withdraw"),  # [VERIFIED — field `wallet_address`; network → menu bep20|trc20|erc20]
    ("Rút thẻ cào",            "/user/withdraw/card",                "POST", None,
        {"telco": "<telco>", "amount": 1000}, "Withdraw"),  # [telco → menu Viettel|MobiFone|VinaPhone]
    # ── Transfer (4)
    ("Transfer Coin",          "/user/transfer/coin",                "POST", None,
        {"username": "<USER_NICK>", "amount": 1000000},
        "Transfer"),  # [!] WEB ĐÃ TẮT: route /transfer có middleware 'disable-transfer', trang rỗng — API vẫn tồn tại nhưng server có thể từ chối
    ("Transfer list",          "/user/transfer/list",                "GET",
        {"page": 1, "per_page": 20, "search": "<SEARCH>?"}, None, "Transfer"),
    ("Transfer fee",           "/user/transfer/fee",                 "GET",  None, None, "Transfer"),
    ("Transfer statistical",   "/user/transfer/statistical",         "GET",  None, None, "Transfer"),
    # ── Order (3) — web LUÔN gửi type (C2mIQ7fi.js: getList/getServices/getStatistical)
    ("Order list",             "/user/order/list",                  "GET",
        {"type": "tiktok", "search": "", "per_page": 20, "page": 1,
         "action": "<ACTION>?"}, None, "Order"),  # [VERIFIED params]
    ("Order services",         "/user/order/services",               "GET",
        {"type": "tiktok", "action": "<ACTION>?"}, None, "Order"),  # [VERIFIED params]
    ("Order statistical",      "/user/order/statistical",            "GET",
        {"type": "tiktok", "action": "<ACTION>?"}, None, "Order"),  # [VERIFIED params]
    # ── Referral (4) — B0fYbuUu.js: f1/f2 gửi {page,per_page}; commissions thêm status?
    ("Referral summary",       "/user/referral/summary",             "GET",  None, None, "Referral"),  # [VERIFIED]
    ("Referral F1",            "/user/referral/f1",                  "GET",
        {"page": 1, "per_page": 20}, None, "Referral"),  # [VERIFIED params]
    ("Referral F2",            "/user/referral/f2",                  "GET",
        {"page": 1, "per_page": 20}, None, "Referral"),  # [VERIFIED params]
    ("Referral commissions",   "/user/referral/commissions",         "GET",
        {"page": 1, "per_page": 20, "status": "<STATUS>?"}, None, "Referral"),  # [VERIFIED params]
    # ── Support (2) — GET gửi cả status (Bfp0_r72.js)
    ("List support tickets",   "/user/support/tickets",              "GET",
        {"page": 1, "per_page": 10, "search": "", "status": "<STATUS>?"},
        None, "Support"),  # [VERIFIED params]
    # ── Order (10) — list/services/statistical đã có ở trên
    ("Mua order (boost)",      "/user/order/buy",                    "POST", None,
        {"service": "<SERVICE_ID>", "link": "<LINK>",
         "original_link": "<ORIGINAL_LINK?>", "object_id": "<OBJECT_ID?>",
         "quantity": 50, "note": "",
         "reaction": "<LIKE,LOVE,...>?"}, "Order"),  # [VERIFIED call-site 28/10 — reaction CHỈ gửi khi service type reaction (FB): web join(','), order page DraUqZY4/DaSuhmBY.js]
    ("Order detail",           "/user/order/{id}",                  "GET",  None, None, "Order"),
    ("Cancel preview",         "/user/order/cancel-preview",         "GET",  {"order_id": "<ORDER_ID>"}, None, "Order"),
    ("Hủy order",              "/user/order/cancel",                 "POST", None,
        {"order_id": "<ORDER_ID>"}, "Order"),  # [VERIFIED]
    ("Refill order",           "/user/order/refill",                 "POST", None,
        {"order_id": "<ORDER_ID>"}, "Order"),  # [VERIFIED]
    ("Refill history",         "/user/order/refill-history",         "GET",  {"order_id": "<ORDER_ID>"}, None, "Order"),
    ("Refill list",            "/user/order/refill-list",            "GET",
        {"page": 1, "per_page": 20}, None, "Order"),
    # ── Referral bổ sung (2)
    ("Claim commission",       "/user/referral/claim",               "POST", None,
        {"commission_id": "<COMMISSION_ID>"}, "Referral"),  # [VERIFIED]
    ("Claim all commissions",  "/user/referral/claim-all",           "POST", None, None, "Referral"),  # [VERIFIED]
    # ── Support multipart (đã sửa body — gửi dạng FormData)
    ("Tạo support ticket",     "/user/support/tickets",              "POST", None,
        {"category": "<withdraw|deposit|account|other>",
         "title": "<TITLE>", "order_info": "<ORDER_INFO?>",
         "description": "<DESCRIPTION>"}, "Support"),  # [VERIFIED — multipart/form-data, images[] tối đa 5]
    # ── Notifications / Menus / Blogs / Promotions / Rate
    ("Thông báo",              "/user/notifications",                "GET",  None, None, "Content"),  # [VERIFIED — BEnDMXnR.js getNotifications() không params]
    ("Notifications root",     "/notifications",                     "GET",  None, None, "Content"),  # [VERIFIED 28/10 — cur_Cs2oLB4-.js getNotifications(o={}) params generic; demo shape {data:[],unread}]
    ("Đánh dấu đã đọc",        "/notifications/{id}/read",           "POST", None, None, "Content"),  # [VERIFIED 28/10 — markNotificationRead POST KHÔNG body]
    ("Menu",                   "/user/menus",                        "GET",  None, None, "Content"),
    ("Blogs",                  "/user/blogs",                        "GET",  {"limit": 4}, None, "Content"),  # [VERIFIED]
    ("Blog categories",        "/user/blogs/categories",             "GET",  None, None, "Content"),  # [VERIFIED]
    ("Blog popular",           "/user/blogs/popular",                "GET",  {"limit": 5}, None, "Content"),  # [VERIFIED params — web mặc định limit=5]
    ("Blog detail",            "/user/blogs/{id}",                   "GET",  None, None, "Content"),  # [VERIFIED — ISufPJEP.js getDetail]
    ("Signup bonus promo",     "/user/promotions/signup-bonus",      "GET",  None, None, "Content"),  # [VERIFIED 28/10 — endpoint /user/promotions standalone KHÔNG có thật: whitelist demo chỉ dùng startsWith('/user/promotions') khớp prefix signup-bonus]
    ("Tỉ giá",                 "/user/rate",                          "GET",  None, None, "Content"),
]

# ───────────────────────── Helpers ─────────────────────────
def mask(key: str, value: Any) -> Any:
    if isinstance(value, str) and value and any(h in key.lower() for h in SECRET_HINTS):
        return "••••" + value[-4:] if len(value) > 8 else "••••"
    return value

def flatten(d: dict, prefix: str = ""):
    for k, v in d.items():
        key = f"{prefix}{k}"
        if isinstance(v, dict) and v:
            yield from flatten(v, key + ".")
        else:
            yield key, v

def fmt_value(v: Any) -> Text:
    if v is None:
        return Text("null", style="dim italic")
    if isinstance(v, bool):
        return Text(str(v).lower(), style="green" if v else "red")
    if isinstance(v, (int, float)):
        return Text(str(v), style="bright_cyan")
    if isinstance(v, (list, dict)):
        s = json.dumps(v, ensure_ascii=False)
        return Text(s if len(s) < 80 else s[:77] + "…", style="yellow")
    return Text(str(v))

def status_style(code: int) -> str:
    return "green" if code < 300 else "yellow" if code < 400 else "red"

# ───────────────────────── Hiệu ứng động (v1.5) ─────────────────────────
def _sleep(d: float) -> None:
    if ANIM:
        time.sleep(d)

def type_out(msg: str, style: str = "bold bright_cyan", delay: float = 0.006) -> None:
    """Chữ động — đánh từng ký tự (typing effect)."""
    if not ANIM:
        console.print(Text(msg, style=style))
        return
    t = Text()
    live = Live(console=console, refresh_per_second=60, transient=True)
    live.start()
    try:
        for ch in msg:
            t.append(ch, style=style)
            live.update(t)
            _sleep(delay)
    finally:
        live.stop()
    console.print(t)

def countdown(seconds: int, label: str = "Chờ") -> None:
    """Đếm ngược động với thanh tiến trình (mirror web)."""
    if seconds <= 0:
        return
    if not ANIM:
        time.sleep(seconds)
        return
    total = seconds
    with Live(console=console, refresh_per_second=8, transient=True) as live:
        for s in range(seconds, 0, -1):
            bar = Text()
            bar.append(f" ⏳ {label} {s:2d}s ", style="bold black on yellow")
            bar.append(" " + "▓" * min(s, 24) + "░" * max(0, min(total, 24) - min(s, 24)))
            live.update(bar)
            time.sleep(1)

def coin_count_up(amount, label: str = "+Coin") -> None:
    """Hiệu ứng đếm tăng dần số coin nhận được."""
    try:
        amt = int(float(amount))
    except (TypeError, ValueError):
        amt = None
    if amt is None:
        console.print(f"  [bold bright_green]✔ {label}[/bold bright_green]")
        return
    if not ANIM:
        console.print(f"  [bold bright_green]✔ {label}: +{amt} coin[/bold bright_green]")
        return
    with Live(console=console, refresh_per_second=24, transient=True) as live:
        for i in range(1, 13):
            v = int(amt * i / 12)
            live.update(Text(f"  ◈ +{v} coin", style="bold bright_yellow"))
            _sleep(0.045)
    console.print(f"  [bold bright_green]✔ {label}: +{amt} coin[/bold bright_green]")

# ── Hiệu ứng v2.4 ──────────────────────────────────────────
_GRAD_STYLES = ["bright_cyan", "cyan", "bright_blue", "bright_magenta",
                "magenta", "bright_magenta", "bright_blue", "cyan"]

def gradient(text: str, styles: list[str] | None = None) -> Text:
    """v2.4 — chữ gradient: mỗi ký tự một màu, lặp vòng (cyan→magenta)."""
    styles = styles or _GRAD_STYLES
    t = Text()
    for i, ch in enumerate(text):
        t.append(ch, style=styles[i % len(styles)])
    return t

_BURST_CHARS = "✦✧◈✶✷❖✸"
_BURST_COLS = ["bright_yellow", "bright_cyan", "bright_magenta",
               "bright_green", "bright_white"]

def coin_burst() -> None:
    """v2.4 — pháo giấy ký tự sau khi +Coin (~0.4s, transient — không để lại rác).
    Tắt cùng ANIM (F4F_NO_ANIM=1)."""
    if not ANIM:
        return
    with Live(console=console, refresh_per_second=24, transient=True) as live:
        for _ in range(6):
            t = Text("  ")
            for _ in range(18):
                t.append(random.choice(_BURST_CHARS),
                         style=random.choice(_BURST_COLS))
            live.update(t)
            _sleep(0.06)

def error_pulse(msg: str = "") -> None:
    """v2.4 — nhịp đỏ 3 frame BẢO LỖI trước khi vẽ panel lỗi (subtle, ~0.3s)."""
    if not ANIM:
        return
    m = str(msg)[:40]
    with Live(console=console, refresh_per_second=20, transient=True) as live:
        for n in (28, 12, 34):
            t = Text()
            t.append(" ✘ LỖI ", style="bold black on red")
            t.append("▂" * n, style="red")
            if m:
                t.append(f" {m} ", style="bold red")
            live.update(t)
            _sleep(0.09)

def _usage_text(u: dict, width: int = 10) -> Text:
    """'▓░░░░░░░░░ 1/150 (còn 149)' — quota ngày từ account_daily_usage.
    DevTools 29/10: complete-job trả kèm {done, limit, remaining} — dùng cho
    cả panel Số dư sau Complete lẫn progress bar ở header phiên."""
    done, limit, rem = u.get("done"), u.get("limit"), u.get("remaining")
    try:
        d_, l_ = int(done or 0), int(limit or 0)
        filled = max(0, min(width, round(d_ * width / l_))) if l_ else 0
        t = Text()
        t.append("▓" * filled + "░" * (width - filled), style="bright_yellow")
        t.append(f" {d_}/{l_}", style="bold bright_yellow")
        if rem is not None:
            t.append(f" (còn {rem})", style="dim")
        return t
    except (TypeError, ValueError):
        t = Text(f"{done}/{limit}")
        if rem is not None:
            t.append(f" (còn {rem})", style="dim")
        return t

def flash(ok: bool, text: str) -> None:
    """Thanh trạng thái nổi: ✔ thành công / ✘ lỗi."""
    style = "bold black on green" if ok else "bold black on red"
    icon = "✔" if ok else "✘"
    console.print(Text(f" {icon} {text} ", style=style))

def req_badge() -> str:
    """Dòng dim chứng minh headers luôn gắn kèm: x-ts · x-sign · bearer."""
    m = _LAST_REQ.get("method", "?")
    p = _LAST_REQ.get("path", "?")
    auth = "✓" if _LAST_REQ.get("auth") else "✗ CHƯA ĐĂNG NHẬP!"
    return (f"[dim]→ {m} {p}  ·  x-ts ✓  ·  x-sign ✓  ·  "
            f"Authorization Bearer {auth}[/dim]")

def clear_screen() -> None:
    """v2.0 — xoá SẠCH toàn bộ màn hình KỂ CẢ phần scrollback (lịch sử cuộn).
    \033[2J xoá vùng nhìn thấy · \033[3J xoá scrollback (xterm/Windows Terminal/
    iTerm2/Termux/GNOME) · \033[H đưa con trỏ về góc trên. rich console.clear()
    giữ lại làm fallback cho Windows legacy (VT đã bật trong main)."""
    if not sys.stdout.isatty():
        return
    try:
        sys.stdout.write("\033[2J\033[3J\033[H")
        sys.stdout.flush()
    except OSError:
        pass
    console.clear()

def _pause_simple() -> None:
    """Dừng 1 nhịp cho người dùng đọc — Enter để tiếp tục."""
    if sys.stdin.isatty():
        try:
            console.input("[dim]Enter › [/dim]")
        except (EOFError, KeyboardInterrupt):
            console.print()

# ───────────────────────── Client ─────────────────────────
class Client:
    def __init__(self) -> None:
        STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.http = requests.Session()
        self.timeout = 30
        self.token: str | None = None
        self.profile: dict | None = None
        self.history: list[dict] = []
        self._load()

    def _load(self) -> None:
        try:
            v = json.loads(SESSION_PATH.read_text("utf-8"))
            self.token = v.get("access_token")
        except (OSError, ValueError):
            self.token = None

    def save(self, token: str) -> None:
        SESSION_PATH.write_text(json.dumps({"access_token": token}), "utf-8")
        try:
            os.chmod(SESSION_PATH, 0o600)
        except OSError:
            pass
        self.token = token

    def logout(self) -> None:
        self.token = None
        self.profile = None
        SESSION_PATH.unlink(missing_ok=True)
        self.clear_cred()          # v2.8 — đăng xuất = quên cả mật khẩu đã nhớ

    # ── v2.8 — tự hồi phục phiên: ghi nhớ mật khẩu (OPT-IN) + relogin ──
    def save_cred(self, username: str, password: str) -> None:
        """Lưu cặp đăng nhập (base64 — chỉ tránh đọc trần, KHÔNG phải mã hóa
        an toàn; file chmod 600 trong máy user). Dùng để TỰ đăng nhập lại khi
        server trả 401 giữa phiên (phiên server ngắn — token chết sau vài
        giờ, đo thực tế 5/11)."""
        try:
            CREDS_PATH.write_text(json.dumps({
                "u": base64.b64encode(username.encode("utf-8")).decode("ascii"),
                "p": base64.b64encode(password.encode("utf-8")).decode("ascii"),
            }), "utf-8")
            os.chmod(CREDS_PATH, 0o600)
        except OSError:
            pass

    def load_cred(self) -> tuple[str, str] | None:
        try:
            v = json.loads(CREDS_PATH.read_text("utf-8"))
            return (base64.b64decode(v["u"]).decode("utf-8"),
                    base64.b64decode(v["p"]).decode("utf-8"))
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def has_cred(self) -> bool:
        return CREDS_PATH.exists()

    def clear_cred(self) -> None:
        try:
            CREDS_PATH.unlink(missing_ok=True)
        except OSError:
            pass

    def relogin(self, silent: bool = False) -> bool:
        """v2.8 — đăng nhập lại bằng mật khẩu đã nhớ → token mới. True nếu OK.
        Gọi nội bộ từ request() khi gặp 401, và từ main() khi mở tool."""
        cred = self.load_cred()
        if not cred:
            return False
        try:
            code, data, _ = self.request("POST", "/user/auth/login",
                                         body={"username": cred[0],
                                               "password": cred[1]},
                                         _retried=True)   # chặn vòng lặp 401
        except requests.RequestException:
            return False
        root = data.get("data") if isinstance(data, dict) else None
        token = None
        if isinstance(root, dict):
            token = root.get("access_token") \
                or (root.get("user") or {}).get("access_token")
        if code < 300 and token:
            self.save(str(token))
            self.refresh_profile()
            if silent:
                console.print("[dim]↻ phiên hết hạn — đã tự đăng nhập lại.[/dim]")
            else:
                flash(True, f"Đã tự đăng nhập lại: "
                      f"{self.profile.get('username', '')}"
                      if self.profile else "Đã tự đăng nhập lại.")
            return True
        if not silent:
            console.print("[yellow]Đăng nhập lại thất bại — mật khẩu đã nhớ có "
                          "thể đã đổi; chọn mục 1 để đăng nhập tay.[/yellow]")
        return False

    def request(self, method: str, path: str, params=None, body=None,
                form: bool = False, _retried: bool = False):
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Origin": "https://follow4follow.net",
            "Referer": "https://follow4follow.net/",
            "User-Agent": (
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
            ),
        }
        if form:
            headers.pop("Content-Type", None)   # requests tự set boundary multipart
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        # Auto-sign EVERY request (mirror interceptor trong BEnDMXnR.js):
        #   Authorization: Bearer <token>  +  x-ts  +  x-sign
        #   x-sign = HMAC-SHA256('follow4follow_key', f'{ts}:{METHOD}:{path_no_query}')
        signed = sign_request(method, path)
        headers["x-ts"] = str(signed.x_ts)
        headers["x-sign"] = signed.x_sign
        _LAST_REQ.clear()
        _LAST_REQ.update({
            "method": method.upper(), "path": path,
            "x_ts": signed.x_ts, "x_sign": signed.x_sign,
            "auth": bool(self.token),
            "auth_last4": self.token[-4:] if self.token else "",
        })

        t0 = time.perf_counter()
        try:
            r = self.http.request(method, BASE_URL + path, params=params,
                                   json=None if form else body,
                                   data=body if form else None,
                                   headers=headers, timeout=self.timeout)
        except requests.RequestException as e:
            raise
        ms = int((time.perf_counter() - t0) * 1000)
        try:
            data: Any = r.json()
        except ValueError:
            data = {"raw_text": r.text[:3000]}
        self.history.append({
            "t": datetime.now().strftime("%H:%M:%S"),
            "m": method, "p": path,
            "http": r.status_code, "ms": ms,
        })
        self.history = self.history[-20:]
        # v2.8 — phiên chết (401) + có mật khẩu đã nhớ → TỰ đăng nhập lại 1 lần
        # rồi GỬI LẠI đúng request này (trong suốt với mọi bước của tool:
        # earn-coin, quick-switch, complete-job…). Không áp dụng cho chính
        # endpoint login để không lặp vô hạn.
        api = data.get("status_code") if isinstance(data, dict) else None
        if (r.status_code == 401 or (isinstance(api, int) and api == 401)) \
                and not _retried and self.has_cred() \
                and not path.startswith("/user/auth/login"):
            if self.relogin(silent=True):
                return self.request(method, path, params=params, body=body,
                                    form=form, _retried=True)
        return r.status_code, data, ms

    def login(self, username: str, password: str) -> tuple[int, Any, int]:
        return self.request("POST", "/user/auth/login",
                            body={"username": username, "password": password})

    def refresh_profile(self) -> None:
        if not self.token:
            self.profile = None
            return
        try:
            code, data, _ = self.request("GET", "/user/auth/me")
            if code < 300 and isinstance(data, dict) and isinstance(data.get("data"), dict):
                self.profile = data["data"]
            else:
                self.profile = None
        except requests.RequestException:
            self.profile = None

# ───────────────────────── Rendering ─────────────────────────
# Banner ASCII v2.0 — 3 dòng, vừa màn hình 60 cột (Termux), tô gradient từng dòng
_BANNER_ART = [
    ("███████╗███████╗███████╗", "bold bright_cyan"),
    ("██╔════╝██╔════╝╚══███╔╝", "bold cyan"),
    ("█████╗  █████╗    ███╔╝ ", "bold bright_blue"),
    ("██╔══╝  ██╔══╝   ███╔╝  ", "bold blue"),
    ("██║     ███████╗███████╗", "bold magenta"),
    ("╚═╝     ╚══════╝╚══════╝", "bold bright_magenta"),
]

def _banner_reveal(c: Client) -> None:
    """Animation khởi động: banner hiện từng dòng (chỉ lần đầu — nhanh, ~0.4s)."""
    if not ANIM:
        console.print(banner(c))
        return
    art = Text()
    for line, style in _BANNER_ART:
        art.append(line + "\n", style=style)
    live = Live(console=console, refresh_per_second=30, transient=True)
    live.start()
    try:
        shown = Text()
        for i in range(0, len(_BANNER_ART)):
            line, style = _BANNER_ART[i]
            shown.append(line + "\n", style=style)
            live.update(shown)
            _sleep(0.06)
    finally:
        live.stop()
    console.print(art)
    console.print(banner(c))

def banner(c: Client) -> Panel:
    """Header phiên — v2.4: gradient title + chip trạng thái + user + giờ + coin."""
    title = Text()
    title.append(" ")
    title.append(gradient("FOLLOW4FOLLOW"))
    title.append(" CLI", style="bold white")
    title.append(f" v{VERSION}", style="bold bright_yellow")
    state = Text(" ● ĐÃ ĐĂNG NHẬP", style="bold green") if c.token \
        else Text(" ● CHƯA ĐĂNG NHẬP", style="bold yellow")
    narrow = console.width < 70
    grid = Table.grid(expand=True)
    grid.add_column(justify="left")
    grid.add_column(justify="right")
    grid.add_row(title, state)
    right = Text()
    if not narrow:
        right.append(BASE_URL.replace("https://", ""), style="dim")
        right.append("   " + datetime.now().strftime("%d/%m/%Y %H:%M"), style="dim")
    else:
        right.append(datetime.now().strftime("%d/%m %H:%M"), style="dim")
    if c.profile:
        u = c.profile.get("username", "?")
        right.append(f"   ·   {u}", style="bold white")
    # v2.4 — chip số dư nếu có hồ sơ
    if c.profile and c.profile.get("balance") is not None:
        state.append("   ")
        state.append(f" ◈ {c.profile.get('balance')} coin ",
                     style="bold black on bright_yellow")
    grid.add_row(right, Text(""))
    tag = Text()
    tag.append("◆ tự ký mọi request: " if not narrow else "◆ tự ký: ", style="dim italic")
    tag.append("x-ts", style="cyan")
    tag.append(" · ", style="dim")
    tag.append("x-sign", style="cyan")
    if not narrow:
        tag.append(" · ", style="dim")
        tag.append("Bearer", style="cyan")
    grid.add_row(tag, Text(""))
    return Panel(grid, box=box.HEAVY, border_style="bright_blue", padding=(0, 1))

def _menu_rows() -> list[tuple[str, str]]:
    return [
        ("1", "Đăng nhập"),
        ("2", f"Gọi endpoint ({len(ENDPOINTS)} — chọn nhóm / tìm kiếm)"),
        ("3", "🎯 Earn-coin workflow (chạy job tự động)"),
        ("4", "Đăng xuất"),
        ("R", "Làm mới hồ sơ"),
        ("0", "Thoát"),
    ]

def menu_panel() -> Panel:
    """v2.0 — phím tắt dạng 'chip' màu nền nổi, dễ nhìn hơn hẳn kiểu [1]."""
    t = Table(box=None, show_header=False, padding=(0, 1), expand=True)
    t.add_column(width=5, justify="center")
    t.add_column()
    for k, v in _menu_rows():
        t.add_row(Text(f" {k} ", style="bold black on bright_cyan"), v)
    return Panel(t, title="Menu", border_style="bright_blue", box=box.ROUNDED)

def profile_panel(c: Client) -> Panel:
    if not c.profile:
        msg = "Chưa có dữ liệu. Đăng nhập (mục 1) hoặc R để làm mới."
        return Panel(msg, title="Tài khoản", border_style="magenta", box=box.ROUNDED, padding=(1, 2))
    p = c.profile
    lvl = p.get("level") or {}
    t = Table.grid(padding=(0, 2))
    t.add_column(style="dim"); t.add_column()
    t.add_row("User",     Text(str(p.get("username", "?")), style="bold white"))
    t.add_row("Trạng thái",Text(str(p.get("status", "?")),
                style="green" if p.get("status") == "Active" else "yellow"))
    t.add_row("Cấp độ",   f"{lvl.get('name', '?')} · {lvl.get('description', '')}")
    t.add_row("Số dư",    Text(str(p.get("balance", "?")), style="bold bright_yellow"))
    t.add_row("Đang chờ", Text(str(p.get("balance_pending", "?")), style="yellow"))
    return Panel(t, title="Tài khoản", border_style="magenta", box=box.ROUNDED, padding=(1, 2))

def home(c: Client, animated: bool = False) -> None:
    clear_screen()
    if animated and ANIM:
        _banner_reveal(c)
    else:
        console.print(banner(c))
    if console.width >= 100:
        grid = Table.grid(expand=True, padding=(0, 1))
        grid.add_column(ratio=1); grid.add_column(ratio=1)
        grid.add_row(menu_panel(), profile_panel(c))
        console.print(grid)
    else:
        console.print(menu_panel())
        console.print(profile_panel(c))

# ───────────────────────── Smart view + error detail (v1.6) ─────────────────────────
# Key hay chứa DANH SÁCH trong response — tool tự nhận diện để render bảng
_LIST_KEYS = ("data", "rows", "items", "list", "records", "results", "jobs")
# Key meta của envelope phân trang (Laravel style)
_PAG_KEYS = ("per_page", "total_page", "last_page", "current_page", "total",
             "from", "to")

def _split_list_payload(payload: Any):
    """Trả (rows, meta) nếu payload chứa danh sách (trực tiếp hoặc lồng 1 cấp),
    else (None, None). meta = các field còn lại nằm cạnh danh sách."""
    if isinstance(payload, list):
        return payload, None
    if isinstance(payload, dict):
        for k in _LIST_KEYS:
            v = payload.get(k)
            if isinstance(v, list):
                meta = {kk: vv for kk, vv in payload.items() if kk != k}
                return v, (meta or None)
    return None, None

def _meta_summary(meta: dict | None, short: bool = False) -> str:
    """'trang 1/1 · tổng 0 · 10/trang' từ meta phân trang (bỏ key lạ).
    short=True → bỏ 'M/trang' cho màn hình hẹp."""
    if not meta:
        return ""
    pag = {k: v for k, v in meta.items() if k in _PAG_KEYS}
    parts: list[str] = []
    if "current_page" in pag:
        last = pag.get("total_page", pag.get("last_page", "?"))
        parts.append(f"trang {pag['current_page']}/{last}")
    if "total" in pag:
        parts.append(f"tổng {pag['total']}")
    if "per_page" in pag and not short:
        parts.append(f"{pag['per_page']}/trang")
    return " · ".join(parts)

# Cột QUAN TRỌNG được ưu tiên hiển thị trước khi cắt theo chiều rộng màn hình
# v2.1: thêm job_id / link_job (lịch sử earn-coin) + create_time (tài khoản liên kết)
_COL_PRIORITY = ("id", "job_id", "name", "username", "uid", "coin_received",
                 "reward_amount", "amount", "coin", "type_job", "status_label",
                 "status", "balance", "current_balance", "total", "created_at",
                 "date", "create_time", "link_job", "link", "message")

def _ordered_cols(cols: list[str]) -> list[str]:
    # status_label (chữ, vd 'Chờ duyệt') đã đủ nghĩa → bỏ status (số) cho gọn bảng
    if "status_label" in cols:
        cols = [c for c in cols if c != "status"]
    pri = [c for p in _COL_PRIORITY for c in cols if c == p]
    rest = [c for c in cols if c not in pri]
    return pri + rest

def _render_kv(payload: dict, title: str) -> None:
    """Bảng Trường/Giá trị (flatten dict lồng)."""
    t = Table(box=box.SIMPLE_HEAD, header_style="bold bright_cyan", expand=True)
    t.add_column("Trường", style="dim", overflow="fold")
    t.add_column("Giá trị", overflow="fold")
    items = list(flatten(payload))
    for k, v in items[:40]:
        t.add_row(k, fmt_value(mask(k, v)))
    head = title if len(items) <= 40 else \
        f"{title} · 40/{len(items)} trường (j = xem JSON đầy đủ)"
    console.print(Panel(t, title=head, border_style="green", box=box.ROUNDED))

def _render_list(rows: list, title: str, meta: dict | None) -> None:
    """Danh sách → BẢNG; rỗng → thông báo gọn; meta → dòng phân trang.
    Field còn lại cạnh danh sách (accountActive, usage…) → bảng phụ."""
    summ = _meta_summary(meta, short=(console.width < 70))
    extra = {k: v for k, v in (meta or {}).items() if k not in _PAG_KEYS}
    if not rows:
        line = Text("∅ Không có dữ liệu", style="bold yellow")
        if summ:
            line.append(f"   —  {summ}", style="dim")
        console.print(Panel(line, title=title, border_style="yellow",
                            box=box.ROUNDED, padding=(0, 2)))
    elif all(isinstance(x, dict) for x in rows):
        cols: list[str] = []
        for r in rows[:8]:
            for k in r.keys():
                if k not in cols:
                    cols.append(k)
        cols = _ordered_cols(cols)[: (6 if console.width >= 100 else 4)]
        t = Table(box=box.SIMPLE_HEAD, header_style="bold bright_cyan", expand=True)
        for col in cols:
            t.add_column(col, overflow="fold")
        for r in rows[:30]:
            t.add_row(*[fmt_value(mask(c, r.get(c))) for c in cols])
        head = f"{title} · {len(rows)} mục"
        if summ:
            head += f" · {summ}"
        if len(rows) > 30:
            head += f"  (+{len(rows) - 30} dòng — j = xem tất cả)"
        console.print(Panel(t, title=head, border_style="green", box=box.ROUNDED))
    else:
        t = Table(box=box.SIMPLE_HEAD, header_style="bold bright_cyan", expand=True)
        t.add_column("Giá trị", overflow="fold")
        for x in rows[:30]:
            t.add_row(fmt_value(mask(title, x)))
        console.print(Panel(t, title=f"{title} · {len(rows)} mục",
                            border_style="green", box=box.ROUNDED))
    if extra:
        _render_kv(extra, f"{title} — thông tin kèm")

# ───────────────────────── Gợi ý toàn cục v2.0 ─────────────────────────
# Áp dụng cho MỌI endpoint (không chỉ earn) — message nghiệp vụ + gợi ý xử lý.
# Pattern khớp lowercase trong `message` của server.
GENERAL_HINTS: list[tuple[str, str]] = [
    ("unauthenticated",
     "Token hết hạn hoặc chưa đăng nhập — chọn [bold]1[/bold] để đăng nhập lại."),
    ("chưa đăng nhập",
     "Token hết hạn hoặc chưa đăng nhập — chọn [bold]1[/bold] để đăng nhập lại."),
    ("chưa xác thực",
     "Token hết hạn hoặc chưa đăng nhập — chọn [bold]1[/bold] để đăng nhập lại."),
    ("hết hạn",
     "Token/mã đã hết hạn — đăng nhập lại (mục [bold]1[/bold]) hoặc tạo mã mới."),
    ("thao tác hơi nhanh",
     "Rate-limit — chờ vài giây rồi thử lại (tool tự đếm ngược khi gặp 429)."),
    ("too many",
     "Rate-limit — chờ vài giây rồi thử lại."),
    ("không đủ",
     "Số dư không đủ — kiếm coin ở mục [bold]3[/bold] (Earn) hoặc nạp thêm."),
    ("captcha",
     "Chức năng này cần token reCAPTCHA v3 — lấy từ web: DevTools → Network → "
     "gửi form tương tự → copy trường g_recaptcha_response."),
    ("recaptcha",
     "Chức năng này cần token reCAPTCHA v3 — lấy từ web: DevTools → Network → "
     "gửi form tương tự → copy trường g_recaptcha_response."),
    ("đã tồn tại",
     "Dữ liệu trùng — kiểm tra lại username/link/id đã dùng chưa."),
    ("already exist",
     "Dữ liệu trùng — kiểm tra lại username/link/id đã dùng chưa."),
    ("không tìm thấy",
     "Không tìm thấy dữ liệu — id sai hoặc đã bị xoá; lấy id mới từ danh sách."),
    ("not found",
     "Không tìm thấy dữ liệu — id sai hoặc đã bị xoá; lấy id mới từ danh sách."),
    ("forbidden",
     "Không có quyền — endpoint này có thể chỉ dành cho admin/role cao hơn."),
    ("không có quyền",
     "Không có quyền — endpoint này có thể chỉ dành cho admin/role cao hơn."),
    ("permission",
     "Không có quyền — endpoint này có thể chỉ dành cho admin/role cao hơn."),
    ("admin",
     "Endpoint chỉ dành cho admin — tài khoản thường không gọi được."),
    ("tham quan",
     "Web đang ở chế độ tham quan (demo) — đăng ký/đăng nhập tài khoản thật."),
    ("điều khoản",
     "Cần xác nhận điều khoản — chạy mục 2 → Auth → 'Accept terms'."),
    ("terms",
     "Cần xác nhận điều khoản — chạy mục 2 → Auth → 'Accept terms'."),
    ("chưa kích hoạt",
     "Cần kích hoạt tài khoản trước — mục 2 → Accounts → 'Kích hoạt tài khoản'."),
    ("mật khẩu không đúng",
     "Sai mật khẩu — kiểm tra lại (tool không lưu mật khẩu)."),
    ("hết quota",
     "Đạt giới hạn hôm nay — quay lại sau hoặc đổi loại nhiệm vụ."),
    ("giới hạn",
     "Đạt giới hạn — xem message cụ thể của server."),
    ("disable",
     "Chức năng đang bị web khoá (vd chuyển coin) — server sẽ từ chối."),
]

# Hướng dẫn điền form theo endpoint — hiện TRƯỚC khi nhập để điền đúng lần đầu.
FIELD_GUIDE: dict[str, list[str]] = {
    "/user/order/buy": [
        "service ← lấy id từ 'Order services' (nhóm Order, đã gửi ?type=)",
        "quantity phải nằm trong min/max của service đó",
        "original_link / object_id: để trống nếu không có",
    ],
    "/user/order/{id}": ["id ← lấy từ 'Order list'"],
    "/user/order/cancel": ["order_id ← lấy từ 'Order list'"],
    "/user/order/refill": ["order_id ← lấy từ 'Order list' (đơn đã hoàn thành bị tụt)"],
    "/user/order/cancel-preview": ["order_id ← lấy từ 'Order list' (query param)"],
    "/user/order/refill-history": ["order_id ← lấy từ 'Order list'"],
    "/user/referral/claim": ["commission_id ← lấy từ 'Referral commissions'"],
    "/notifications/{id}/read": ["id ← lấy từ 'Thông báo'"],
    # v2.1 — 2 endpoint vừa FIELD-TESTED qua DevTools (27/10)
    "/user/earn-coin/list": [
        "action BẮT BUỘC (web luôn gửi): follow = lịch sử job follow · like = lịch sử job like",
        "type=tiktok · page tăng dần để xem trang kế (mỗi trang 10 dòng)",
        "status_label 'Chờ duyệt' = coin đang chờ duyệt, CHƯA vào số dư khả dụng",
    ],
    "/user/account/list": [
        "web CHỈ gửi type + page (không search) — tool mirror đúng như vậy",
        "id = account_id dùng cho active-acc / quick-switch / complete / skip",
        "uid = UID TikTok (dùng cho skip/report); 'video' = số video tài khoản đã nạp",
    ],
    "/user/blogs/{id}": ["id ← lấy từ 'Blogs'"],
    "/user/deposit/info": ["month định dạng YYYYMM, vd 202610 (để trống = tháng này)"],
    "/user/withdraw/bank": [
        "Xem 'Withdraw info' để biết hạn mức tối thiểu/tối đa từng kênh",
        "account_number = số tài khoản ngân hàng",
    ],
    "/user/withdraw/zalopay": [
        "account_number = SỐ ĐIỆN THOẠI liên kết ZaloPay",
        "Xem 'Withdraw info' để biết hạn mức",
    ],
    "/user/withdraw/paypal": ["email = email tài khoản PayPal nhận tiền"],
    "/user/withdraw/crypto": [
        "network: bep20 | trc20 | erc20 — chọn đúng mạng ví",
        "wallet_address = địa chỉ ví nhận (sai mạng = mất tiền!)",
    ],
    "/user/withdraw/card": ["telco: Viettel | MobiFone | VinaPhone"],
    "/user/account/add": [
        "link: URL hồ sơ TikTok/Facebook, vd https://www.tiktok.com/@user",
        "g_recaptcha_response: token reCAPTCHA v3 — lấy từ web (DevTools → Network)",
    ],
    "/user/account/add-bulk": ["links: mỗi phần tử 1 URL hồ sơ"],
    "/user/account/active-acc": ["account_id ← lấy từ 'Danh sách tài khoản'",
                                 "⚠ web build mới 4/11 KHÔNG còn gọi (legacy)"],
    "/user/account/quick-switch": ["account_id ← lấy từ 'Quick-switch list'",
                                    "⚠ POST legacy — web mới chỉ GET list, đổi acc qua uid earn-coin"],
    "/user/auth/register": [
        "g_recaptcha_response: token reCAPTCHA v3 từ web",
        "ref_code: mã người giới thiệu (tuỳ chọn)",
    ],
    "/user/auth/reset-password": ["token: mã gửi vào email sau 'Quên mật khẩu'"],
    "/user/auth/google/token": ["token = Google ID Token từ web login Google"],
    "/user/transfer/coin": [
        "⚠ WEB ĐÃ TẮT chuyển coin (trang /transfer bị khoá) — server có thể từ chối",
        "username = tên đăng nhập người nhận",
    ],
    "/user/earn-coin/report-job": ["message: mô tả ngắn gọn, vd 'Link không mở được'"],
    "/user/promotions/signup-bonus": ["GET — thưởng chỉ nhận 1 lần sau đăng ký"],
}

# Trường nhập có tập giá trị cố định → Prompt choices thay vì gõ tay
_FIELD_CHOICES: dict[str, list[str]] = {
    "type": ["tiktok", "facebook"],
    "platform": ["tiktok", "facebook"],
    "telco": ["Viettel", "MobiFone", "VinaPhone"],
    "network": ["bep20", "trc20", "erc20"],
    "category": ["withdraw", "deposit", "account", "other"],
}

def _hint_for(msg: Any, api: Any = None, code: int = 0) -> str | None:
    """Tìm gợi ý xử lý cho message nghiệp vụ của server (GENERAL_HINTS)."""
    if not msg:
        return None
    m = str(msg).lower()
    for pat, hint in GENERAL_HINTS:
        if pat in m:
            return hint
    return None

def _retry_after(data: Any) -> int:
    """Đọc retry_after từ response 429 (đứng ở gốc hoặc trong data)."""
    if not isinstance(data, dict):
        return 3
    dd = data.get("data")
    for cand in (data.get("retry_after"),
                 dd.get("retry_after") if isinstance(dd, dict) else None):
        if isinstance(cand, (int, float)) and cand > 0:
            return int(cand)
    return 3

def _message_panel(name: str, code: int, api: Any, msg: Any, is_error: bool) -> None:
    """Panel message nghiệp vụ cho MỌI endpoint — user không bỏ sót lý do."""
    if not msg:
        return
    lines = [f"[bold]{msg}[/bold]"]
    hint = _hint_for(msg, api, code)
    if hint:
        lines += ["", "[bold bright_cyan]Gợi ý:[/bold bright_cyan] " + hint]
    border = "green" if not is_error else ("yellow" if code < 500 else "red")
    title = f"{name} · {'LỖI' if is_error else 'THÔNG BÁO'}"
    console.print(Panel("\n".join(lines), title=title, border_style=border,
                        box=box.ROUNDED, padding=(1, 2)))

def smart_view(title: str, data: Any) -> None:
    """v1.6 — nhận diện envelope: phân trang → bảng + meta 'trang 1/1 · tổng 0';
    dict → bảng Trường/Giá trị; rỗng → 'Không có dữ liệu'. KHÔNG dump thô nữa."""
    payload = data.get("data") if isinstance(data, dict) and "data" in data else data
    rows, meta = _split_list_payload(payload)
    if rows is not None:
        _render_list(rows, title, meta)
    elif isinstance(payload, dict) and payload:
        _render_kv(payload, title)
    else:
        console.print(Panel(fmt_value(payload), title=title,
                            border_style="green", box=box.ROUNDED))

def raw_json(data: Any) -> None:
    def scrub(o):
        if isinstance(o, dict):
            return {k: mask(k, scrub(v)) for k, v in o.items()}
        if isinstance(o, list):
            return [scrub(x) for x in o]
        return o
    s = json.dumps(scrub(data), ensure_ascii=False, indent=2)[:12000]
    console.print(Panel(Syntax(s, "json", theme="monokai", word_wrap=True),
                        title="JSON thô (đã che token)", border_style="bright_blue",
                        box=box.ROUNDED))

def error_detail(code: int, data: Any, ms: int, path: str, method: str) -> None:
    """Show full error details when HTTP>=400 OR JSON status_code>=400."""
    api = data.get("status_code") if isinstance(data, dict) else None
    msg = data.get("message") if isinstance(data, dict) else None
    errs = data.get("errors") if isinstance(data, dict) else None

    body = []
    body.append(f"[bold]Request[/bold]   {method} {path}")
    body.append(f"[bold]HTTP[/bold]      {code}")
    if api is not None:
        body.append(f"[bold]JSON code[/bold] {api}")
    body.append(f"[bold]Thời gian[/bold] {ms} ms")
    if msg:
        body.append(f"[bold]Message[/bold]   {msg}")
    # Chứng minh headers luôn được gắn — trả lời trực tiếp nghi vấn
    # "thiếu hai khoá X và bearer":
    if _LAST_REQ:
        body.append("")
        body.append("[bold]Headers đã gửi (tool tự gắn trên MỌI request)[/bold]")
        body.append(f"  • x-ts          : {_LAST_REQ.get('x_ts')}")
        body.append(f"  • x-sign        : {str(_LAST_REQ.get('x_sign'))[:32]}…")
        if _LAST_REQ.get("auth"):
            body.append(f"  • Authorization : Bearer ••••{_LAST_REQ.get('auth_last4')}")
        else:
            body.append("  • Authorization : [red]KHÔNG CÓ TOKEN — đăng nhập lại (mục 1)![/red]")
        body.append("  • Content-Type  : application/json")
        body.append("[dim]  (x-sign = HMAC-SHA256 'follow4follow_key', msg = '{ts}:{METHOD}:{path}' — không gồm body)")
    if isinstance(errs, dict) and errs:
        body.append("")
        body.append("[bold]Errors object:[/bold]")
        for k, v in errs.items():
            if isinstance(v, list):
                body.append(f"  • {k}: {', '.join(map(str, v))}")
            else:
                body.append(f"  • {k}: {v}")
    elif isinstance(errs, str) and errs:
        body.append("")
        body.append(f"[bold]Errors[/bold]   {errs}")
    body.append("")
    body.append("[dim]Nguyên nhân thường gặp:[/dim]")
    body.append("[dim]• Token hết hạn → đăng nhập lại (mục 1).[/dim]")
    body.append("[dim]• Sai field trong body → so sánh với API_reference_v2.txt.[/dim]")
    body.append("[dim]• Endpoint yêu cầu role cao hơn (vd chưa accept-terms).[/dim]")
    body.append("[dim]• Rate-limit / server-side validation khác bundle suy đoán.[/dim]")
    console.print(Panel("\n".join(body), title="Chi tiết lỗi",
                        border_style="red", box=box.ROUNDED, padding=(1, 2)))

def _pause_raw(data: Any, rerun: bool = False) -> str:
    """Dừng 1 nhịp sau kết quả: Enter = tiếp tục · j = JSON thô · (e = chạy lại).
    (bản cũ hỏi 'Xem JSON thô? [y/n]' mỗi lần — rất phiền, đã bỏ.)
    Trả về phím user bấm ('' nếu Enter)."""
    if not sys.stdin.isatty():
        console.print("[dim](chạy không tương tác — bỏ qua xem JSON thô)[/dim]")
        return ""
    prompt = "\n[dim]Enter = tiếp tục  ·  [bold bright_cyan]j[/bold bright_cyan] = JSON thô"
    if rerun:
        prompt += "  ·  [bold bright_cyan]e[/bold bright_cyan] = chạy lại endpoint này"
    prompt += "  ›  [/dim]"
    try:
        ans = console.input(prompt).strip().lower()
    except (EOFError, KeyboardInterrupt):
        console.print()
        return ""
    if ans == "j":
        raw_json(data)
        _pause_simple()
    return ans

def is_error_resp(code: int, data: Any) -> bool:
    """Quyết định lỗi từ HTTP code + JSON status_code + errors object (+ cờ success)."""
    if isinstance(data, dict) and data.get("success") is True:
        return False
    api = data.get("status_code") if isinstance(data, dict) else None
    if code >= 400:
        return True
    if isinstance(api, int) and api >= 400:
        return True
    if isinstance(api, str) and api.isdigit() and int(api) >= 400:
        return True
    if isinstance(data, dict) and isinstance(data.get("errors"), dict) and data["errors"]:
        return True
    return False

def show_result(title: str, code: int, data: Any, ms: int,
                path: str = "", method: str = "", rerun: bool = False) -> str:
    """v2.0 — MỌI endpoint được đối xử như flow earn:
    status bar → message nghiệp vụ + gợi ý → smart view → chi tiết lỗi kỹ thuật
    → 429 tự đếm ngược retry_after (như web).
    rerun=True → nhắc thêm 'e = chạy lại'. Trả về phím user bấm."""
    api = data.get("status_code") if isinstance(data, dict) else None
    msg = data.get("message") if isinstance(data, dict) else None
    err = is_error_resp(code, data)

    # 429 rate-limit → đếm ngược động cho MỌI endpoint (mirror web)
    if code == 429 or (isinstance(api, int) and api == 429) \
            or (isinstance(api, str) and api == "429"):
        retry = _retry_after(data)
        _message_panel(title, code, api,
                       msg or "Bạn thao tác hơi nhanh, chờ vài giây nhé.", True)
        countdown(retry, "Thử lại sau")
        return _pause_raw(data, rerun=rerun)

    bar = Text()
    bar.append(f" HTTP {code} ", style=f"bold black on {status_style(code)}")
    if api is not None:
        bar.append(f" JSON {api} ", style=f"bold black on {status_style(int(api)) if str(api).isdigit() else 'white'}")
    bar.append(f"  {ms} ms", style="dim")
    if msg:
        bar.append(f"  {msg}")
    console.print(bar)

    # Message nghiệp vụ + gợi ý — TRƯỚC smart view để không bị lạc giữa bảng
    _message_panel(title, code, api, msg, err)
    smart_view(title, data)
    if err and isinstance(data, dict):
        error_detail(code, data, ms, path, method)
    return _pause_raw(data, rerun=rerun)

# ───────────────────────── Interactive body/path/query filler ─────────────────────────
_SKIP = object()

def _fill_path(path: str) -> str:
    matches = re.findall(r"\{([A-Za-z_]\w*)\}", path)
    out = path
    for m in matches:
        val = Prompt.ask(f"  path param '{m}'", default="tiktok" if m == "type" else "")
        if val:
            out = out.replace("{" + m + "}", val, 1)
    return out

def _walk_template(node):
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            filled = _walk_template(v)
            if filled is not _SKIP:
                out[k] = filled
        return out
    if isinstance(node, list):
        return [_walk_template(x) for x in node if _walk_template(x) is not _SKIP]
    if isinstance(node, str) and node.startswith("<"):
        # v2.1 FIX NGHIÊM TRỌNG: dấu '?' tuỳ chọn nằm NGOÀI ngoặc ("<UID>?" —
        # dạng catalogue đang dùng) từng bị bỏ qua vì chỉ check node.endswith(">")
        # → chuỗi "<UID>?" được gửi NGUYÊN VĂN lên server (action/search/uid…)
        # → server lọc sai → danh sách rỗng. Chuẩn hoá cả 2 dạng:
        # "<UID>?" (? ngoài) và "<UID?>" (? trong).
        s = node
        optional = False
        while s.endswith("?"):
            optional = True
            s = s[:-1]
        if not (s.startswith("<") and s.endswith(">") and len(s) >= 3):
            return node                       # không phải placeholder — giữ nguyên
        tok = s[1:-1]
        if tok.endswith("?"):                 # dạng cũ <UID?> — vẫn hỗ trợ
            optional = True
            tok = tok[:-1]
        # v2.1: <field:opt1|opt2|...> → menu chọn giá trị có sẵn (không gõ tay).
        # vd lịch sử earn-coin: <action:follow|like> — web LUÔN gửi action nên
        # KHÔNG được để tuỳ chọn (thiếu action → server trả danh sách rỗng).
        if ":" in tok:
            label_c, choices_s = tok.split(":", 1)
            choices = [c.strip() for c in choices_s.split("|") if c.strip()]
            if choices:
                val = Prompt.ask(f"  {label_c}", choices=choices,
                                 default=choices[0])
                if val.isdigit():
                    return int(val)
                return val
        label = tok
        default_val = ""
        if label in ("type", "platform"): default_val = "tiktok"
        elif label == "limit": default_val = "20"
        elif label == "amount" and "Coin" in label: default_val = "1000000"
        elif label == "amount": default_val = "1000"
        # v2.0: trường có tập giá trị cố định → menu chọn (không gõ tay, không sai chính tả)
        if label.lower() in _FIELD_CHOICES:
            choices = _FIELD_CHOICES[label.lower()]
            return Prompt.ask(f"  {label}", choices=choices,
                              default=choices[0])
        if optional:
            val = Prompt.ask(f"  {label} (tuỳ chọn, Enter = bỏ qua)", default="")
            if not val.strip():
                return _SKIP
        else:
            val = Prompt.ask(f"  {label}", default=default_val)
        if val.isdigit():
            return int(val)
        try:
            f = float(val)
            return int(f) if f.is_integer() else f
        except ValueError:
            return val
    return node

def fill_template(template: dict | None) -> dict | None:
    if template is None:
        raw = Prompt.ask("Body JSON (tuỳ chọn, để trống nếu không có)", default="")
        if not raw.strip():
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError as e:
            console.print(f"[red]JSON lỗi: {e}[/red]")
            return None
    return _walk_template(template)

# ───────────────────────── Actions ─────────────────────────
def do_login(c: Client) -> None:
    console.print(Rule("Đăng nhập", style="bright_blue"))
    username = Prompt.ask("Username").strip()
    password = getpass("Mật khẩu (ẩn): ")
    if not username or not password:
        console.print("[yellow]Thiếu username hoặc mật khẩu.[/yellow]")
        return
    try:
        with console.status("[bright_cyan]Đang đăng nhập…[/bright_cyan]", spinner="dots"):
            code, data, ms = c.login(username, password)
        show_result("Kết quả đăng nhập", code, data, ms,
                    path="/user/auth/login", method="POST")
        root = data.get("data") if isinstance(data, dict) else None
        token = None
        if isinstance(root, dict):
            token = root.get("access_token") or (root.get("user") or {}).get("access_token")
        if code < 300 and token:
            c.save(str(token))
            c.refresh_profile()
            flash(True, f"Đã lưu token (quyền 600) — xin chào {c.profile.get('username','')}!"
                  if c.profile else "Đã lưu token (quyền 600).")
            # v2.8 — tùy chọn ghi nhớ mật khẩu: server cho phiên NGẮN (token chết
            # sau vài giờ — đo 5/11) → lần sau 401 tool tự đăng nhập lại và gửi
            # lại request trong suốt, hết cảnh 'đang làm job tự nhiên lỗi'.
            if Confirm.ask(
                    "Ghi nhớ mật khẩu để tool TỰ đăng nhập lại khi phiên hết hạn? "
                    "(lưu trong máy bạn, chmod 600 — khuyến nghị yes)",
                    default=True):
                c.save_cred(username, password)
                flash(True, "Đã ghi nhớ — hết phiên tool tự xử lý, không phải hỏi lại.")
    except requests.RequestException as e:
        console.print(Panel(str(e), title="Lỗi kết nối", border_style="red"))
    finally:
        password = ""

# ───────────────────────── Explorer v1.6 — menu 2 cấp + tìm kiếm ─────────────────────────
METHOD_STYLE = {"GET": "bold black on green", "POST": "bold black on bright_yellow",
                "PUT": "bold black on bright_cyan", "DELETE": "bold black on bright_red",
                "PATCH": "bold black on bright_magenta"}
# v2.0 — icon nhận diện nhanh từng nhóm trong màn chọn
_CAT_ICONS = {
    "Auth": "⚿", "Account": "◉", "Leaderboard": "☰", "Accounts": "◎",
    "Earn": "◆", "Jobs": "✦", "Social": "◇", "Finance": "¤",
    "Withdraw": "↥", "Transfer": "⇄", "Order": "▦", "Referral": "⑃",
    "Support": "☎", "Content": "❖",
}

# Body đã đối chiếu call-site JS thật (audit v1.4→v2.0 + field-test) → đánh dấu ✓
# v2.0: đã verify thêm TOÀN BỘ GET params (order/referral/blogs/support/notifications)
VERIFIED_PATHS = {
    # Auth
    "/user/auth/login", "/user/auth/register", "/user/auth/forgot-password",
    "/user/auth/change-password", "/user/auth/update-profile",
    "/user/auth/logout", "/user/auth/me", "/user/auth/generate-smm-api-key",
    "/user/auth/accept-terms", "/user/auth/google/token",
    # Account / dashboard
    "/user/profile", "/user/balance", "/user/dashboard", "/user/feature-flags",
    "/user/account/add", "/user/account/add-bulk", "/user/account/active-acc",
    "/user/account/quick-switch", "/user/convert-link",
    # Earn
    "/user/earn-coin", "/user/earn-coin/list", "/user/earn-coin/statistical",
    "/user/earn-coin/complete-job", "/user/earn-coin/skip-job",
    "/user/earn-coin/report-job", "/user/earn-coin/tiktok-like/mark-start",
    "/user/job-rewards",
    # Jobs + social
    "/jobs", "/jobs/{id}/claim", "/jobs/claim-all",
    "/accounts/{type}", "/accounts/{type}/{id}", "/accounts/{type}/{id}/refresh",
    # Finance / withdraw / transfer
    "/user/transaction/list", "/user/deposit/info",
    "/user/withdraw/info", "/user/withdraw/list", "/user/withdraw/statistical",
    "/user/withdraw/bank", "/user/withdraw/zalopay", "/user/withdraw/paypal",
    "/user/withdraw/crypto", "/user/withdraw/card",
    "/user/transfer/list", "/user/transfer/fee", "/user/transfer/statistical",
    # Order
    "/user/order/list", "/user/order/services", "/user/order/statistical",
    "/user/order/{id}", "/user/order/buy", "/user/order/cancel",
    "/user/order/cancel-preview", "/user/order/refill",
    "/user/order/refill-history", "/user/order/refill-list",
    # Referral
    "/user/referral/summary", "/user/referral/f1", "/user/referral/f2",
    "/user/referral/commissions", "/user/referral/claim", "/user/referral/claim-all",
    # Support / content
    "/user/support/tickets", "/user/promotions/signup-bonus",
    "/user/notifications", "/notifications", "/notifications/{id}/read",
    "/user/menus", "/user/blogs", "/user/blogs/categories",
    "/user/blogs/popular", "/user/blogs/{id}", "/user/rate",
}

# v2.0 — endpoint bị ẨN khỏi menu trong phiên này (index trong ENDPOINTS):
# tự động khi server trả 403/admin, hoặc user chủ động chọn ẩn.
_HIDDEN_EPS: set[int] = set()

def _cats_ordered() -> list[tuple[str, int]]:
    """Danh sách nhóm theo thứ tự khai báo + số endpoint mỗi nhóm (loại đã ẩn)."""
    out: list[str] = []
    for i, (*_r, cat) in enumerate(ENDPOINTS):
        if i in _HIDDEN_EPS:
            continue
        if cat not in out:
            out.append(cat)
    return [(cat, sum(1 for j, e in enumerate(ENDPOINTS)
                     if e[5] == cat and j not in _HIDDEN_EPS)) for cat in out]

def _filter_eps(cat: str | None = None, kw: str | None = None) -> list[tuple]:
    """Lọc endpoint theo nhóm và/hoặc từ khoá (khớp tên + path, không phân biệt hoa thường).
    BỎ QUA các endpoint đã bị ẩn (chỉ admin / user tự ẩn)."""
    res = []
    for i, e in enumerate(ENDPOINTS):
        if i in _HIDDEN_EPS:
            continue
        name, path, method, params, body_tpl, cat_ = e
        if cat and cat_ != cat:
            continue
        if kw and kw.lower() not in f"{name} {path}".lower():
            continue
        res.append(e)
    return res

def _print_endpoints(items: list[tuple], subtitle: str) -> None:
    """v2.0 — danh sách endpoint: method dạng VIÊN màu nền (GET xanh lá, POST vàng…),
    ✓ = body/params đã đối chiếu call-site JS. Màn hẹp (<88 cột) → path xuống dòng."""
    console.print(Rule(f"{subtitle} — {len(items)} endpoint", style="bright_blue"))
    t = Table(box=box.SIMPLE_HEAD, header_style="bold bright_cyan", expand=True,
              padding=(0, 1))
    if console.width < 88:
        t.add_column("#", justify="right", width=3, style="bold bright_cyan")
        t.add_column("Request", overflow="fold")
        for i, (name, path, method, *_r) in enumerate(items, 1):
            tx = Text()
            tx.append(f" {method:<6} ", style=METHOD_STYLE.get(method, "white"))
            tx.append(" ")
            if path in VERIFIED_PATHS:
                tx.append("✓ ", style="bold bright_green")
            tx.append(name, style="bold")
            tx.append("\n     " + path, style="dim")
            t.add_row(str(i), tx)
    else:
        t.add_column("#", justify="right", width=4, style="bold bright_cyan")
        t.add_column("Method", width=9)
        t.add_column("Chức năng", overflow="fold")
        t.add_column("Endpoint", style="dim", overflow="fold")
        for i, (name, path, method, *_r) in enumerate(items, 1):
            mark = "[bright_green]✓[/bright_green] " if path in VERIFIED_PATHS else "  "
            t.add_row(str(i), Text(f" {method:<6} ", style=METHOD_STYLE.get(method, "white")),
                      mark + name, path)
    console.print(t)
    console.print("[dim]✓ = body/params đã đối chiếu call-site JS thật "
                  "(chi tiết trong API_reference_v2.txt)  ·  e = chạy lại sau kết quả[/dim]")

def _category_screen() -> str | None:
    """Màn hình chọn NHÓM — v2.0 grid 3 cột kèm icon nhận diện nhanh.
    Trả về: 'ALL' · 'KW:<từ khóa>' · '<Tên nhóm>' · None (về menu)."""
    console.print(Rule("Chọn nhóm request", style="bright_blue"))
    cats = _cats_ordered()
    cells = [f"[bold bright_cyan]{i:>2}[/] [cyan]{_CAT_ICONS.get(cat, '·')}[/] "
             f"[bold]{cat}[/] [dim]·{n}[/]"
             for i, (cat, n) in enumerate(cats, 1)]
    cells += ["[bold bright_cyan] a[/] [bold]Tất cả[/]",
              "[bold bright_cyan] /[/] [bold]Tìm kiếm[/]",
              "[bold bright_cyan] 0[/] [dim]Về menu[/]"]
    g = Table.grid(padding=(0, 2))
    for _ in range(3):
        g.add_column()
    for i in range(0, len(cells), 3):
        row = cells[i:i + 3] + [""] * (3 - len(cells[i:i + 3]))
        g.add_row(*row)
    console.print(Panel(g, border_style="bright_blue", box=box.ROUNDED, padding=(0, 1)))
    sel = Prompt.ask("Chọn nhóm  (số · a · /từ khóa · 0 = về menu)").strip()
    if sel in ("0", "q", "Q"):
        return None
    if sel.lower() == "a":
        return "ALL"
    if sel.startswith("/"):
        return "KW:" + sel[1:].strip()
    if sel.isdigit() and 1 <= int(sel) <= len(cats):
        return cats[int(sel) - 1][0]
    return "INVALID"

def _run_endpoint(c: Client, ep: tuple) -> None:
    """Điền params/body → GỬI → hiện kết quả trên màn hình SẠCH (clear + banner).
    v2.0: FIELD_GUIDE hiện trước khi điền · 403 admin → đề nghị ẩn khỏi menu
    · 'e = chạy lại' → LẶP (không đệ quy — an toàn khi chạy lại nhiều lần)."""
    name0, path0, method, params0, body_tpl0, _cat = ep
    while True:
        name, path, params, body_tpl = name0, path0, params0, body_tpl0
        clear_screen()
        console.print(banner(c))
        console.print(Rule(f"{method} {path}", style="bright_magenta"))
        # v2.0 — hướng dẫn điền form (từ audit call-site JS)
        guide = FIELD_GUIDE.get(path) or FIELD_GUIDE.get(path.rsplit("/", 1)[0])
        if guide:
            g = Text()
            g.append("Điền sao cho đúng:\n", style="bold bright_cyan")
            for line in guide:
                g.append("  • ", style="dim")
                g.append(line + "\n")
            console.print(Panel(g, border_style="cyan", box=box.ROUNDED, padding=(0, 2)))
        if "{" in path:
            console.print("[dim]Điền path params:[/dim]")
            path = _fill_path(path)
        if params:
            console.print("[dim]Điền query params:[/dim]")
            params = _walk_template(params) or None
        body = None
        form = path in MULTIPART_PATHS   # support ticket → multipart/form-data
        if method in ("POST", "PUT", "PATCH"):
            if body_tpl is not None:
                console.print("[dim]Điền body:[/dim]"
                              + ("  [magenta](multipart/form-data)[/magenta]" if form else ""))
            else:
                console.print("[dim]Không có template — điền raw JSON (Enter = bỏ qua):[/dim]")
            body = fill_template(body_tpl)
        # Màn hình kết quả sạch — không chồng chất dưới phần điền form
        clear_screen()
        console.print(banner(c))
        console.print(f"[bold]{method}[/bold] [green]{path}[/green]")
        ans = ""
        try:
            with console.status("[bright_cyan]Đang gửi…[/bright_cyan]", spinner="dots"):
                code, data, ms = c.request(method, path, params=params,
                                           body=body, form=form)
            console.print(req_badge())
            # MỘT prompt duy nhất sau kết quả: Enter · j = JSON thô · e = chạy lại
            ans = show_result(name, code, data, ms, path=path, method=method,
                              rerun=True)
            # v2.0 — endpoint chỉ admin/403 → đề nghị ẩn khỏi menu phiên này
            api = data.get("status_code") if isinstance(data, dict) else None
            api_n = int(api) if isinstance(api, (int, str)) and str(api).isdigit() else 0
            mlow = str(data.get("message") or "").lower() if isinstance(data, dict) else ""
            admin_like = api_n == 403 or code == 403 or "admin" in mlow \
                or "không có quyền" in mlow or "forbidden" in mlow
            if admin_like:
                try:
                    idx = next(i for i, e in enumerate(ENDPOINTS) if e is ep)
                except StopIteration:
                    idx = None
                if idx is not None and Confirm.ask(
                        "Ẩn endpoint này khỏi menu (phiên này)?", default=True):
                    _HIDDEN_EPS.add(idx)
                    flash(True, f"Đã ẩn '{name}' — sẽ không hiện lại trong phiên này.")
        except requests.RequestException as e:
            console.print(Panel(str(e), title="Lỗi request", border_style="red"))
            _pause_simple()
        if ans != "e":
            return

def explorer(c: Client) -> None:
    """v1.6 — menu 2 cấp: NHÓM → endpoint (hết khó nhìn như bảng 81 dòng cũ).
    Hỗ trợ: a = tất cả · /từ khóa = tìm kiếm · r = đổi nhóm · 0 = về menu chính."""
    if not c.token:
        console.print("[yellow]Cần đăng nhập trước (mục 1).[/yellow]")
        return
    while True:
        clear_screen()
        console.print(banner(c))
        pick = _category_screen()
        if pick is None:
            return
        if pick == "INVALID":
            flash(False, "Lựa chọn không hợp lệ — nhập số nhóm, 'a', hoặc '/từ khóa'.")
            _pause_simple()
            continue
        if pick == "ALL":
            items, subtitle = list(ENDPOINTS), "Tất cả"
        elif pick.startswith("KW:"):
            kw = pick[3:]
            items = _filter_eps(kw=kw)
            subtitle = f"Tìm '{kw}'"
        else:
            items = _filter_eps(cat=pick)
            subtitle = pick
        if not items:
            flash(False, "Không có endpoint nào khớp.")
            _pause_simple()
            continue
        while True:
            clear_screen()
            console.print(banner(c))
            _print_endpoints(items, subtitle)
            p = Prompt.ask("Chọn #  (r = đổi nhóm/tìm · 0 = về menu)").strip()
            if p in ("0", "q", "Q"):
                return
            if p.lower() == "r":
                break
            if not (p.isdigit() and 1 <= int(p) <= len(items)):
                flash(False, "Số không hợp lệ.")
                continue
            _run_endpoint(c, items[int(p) - 1])

# ───────────────────────── Earn-coin workflow (interactive job runner) ─────────────────────────
PLATFORMS = {"1": "tiktok", "2": "facebook"}
JOB_TYPES = {"1": "follow", "2": "like"}

# ───────── Đổi tài khoản (v2.7 — KIỂU BUILD WEB MỚI 4/11 tối: KHÔNG POST) ─────────

def _load_quick_state(platform: str) -> Any:
    """v2.7 — đọc id acc đã chọn lần trước (mirror localStorage[
    'quickswitch_active_<type>'] của web — QuickSwitch BxxhXs6I.js)."""
    try:
        v = json.loads(STATE_PATH.read_text("utf-8"))
        return v.get(f"quickswitch_active_{platform}") if isinstance(v, dict) else None
    except (OSError, ValueError):
        return None


def _save_quick_state(platform: str, acc_id: Any) -> None:
    """v2.7 — ghi nhớ acc vừa chọn (≈ localStorage của web). Lỗi đĩa → bỏ qua."""
    try:
        STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
        try:
            v = json.loads(STATE_PATH.read_text("utf-8"))
        except (OSError, ValueError):
            v = {}
        if not isinstance(v, dict):
            v = {}
        v[f"quickswitch_active_{platform}"] = acc_id
        STATE_PATH.write_text(json.dumps(v, ensure_ascii=False), "utf-8")
        try:
            os.chmod(STATE_PATH, 0o600)
        except OSError:
            pass
    except OSError:
        pass


def _get_switch_accounts(c: Client, platform: str) -> tuple[list, str | None]:
    """v2.7 — Lấy danh sách tài khoản để ĐỔI, đúng build web mới.
    Web (chunk BxxhXs6I.js — QuickSwitch Be()): GET /user/account/quick-switch
    ?type=<platform> → data.accounts[] = {id, name, uid, avatar}.
    Chấp nhận nhiều response-shape (server đang chuyển tiếp):
      data.accounts (mới) · data.data · data.data.data · data là list.
    Lỗi/thiếu → dự phòng GET /user/account/list {type, page} (schema cũ
    data.data — trang quản lý tài khoản vẫn dùng).
    Trả (danh sách acc có `id`, nguồn) — ([], None) nếu mọi nguồn fail."""
    accounts: list = []
    source: str | None = None
    # 1) Quick-switch list — chuẩn build web mới
    try:
        with console.status(
                f"[bright_cyan]Đang tải danh sách tài khoản {platform}…[/bright_cyan]",
                spinner="dots"):
            code, data, _ = c.request("GET", "/user/account/quick-switch",
                                      params={"type": platform})
    except requests.RequestException as e:
        console.print(Panel(str(e), title="Lỗi request", border_style="red"))
    else:
        if code < 400 and isinstance(data, dict):
            api = data.get("status_code")
            if not (isinstance(api, int) and api >= 400):
                d = data.get("data")
                cand: Any = []
                if isinstance(d, dict):
                    cand = d.get("accounts") or d.get("data") or []
                elif isinstance(d, list):
                    cand = d
                if isinstance(cand, list):
                    accounts = [a for a in cand if isinstance(a, dict) and a.get("id")]
                if accounts:
                    source = "quick-switch"
    # 2) Dự phòng: account/list schema cũ
    if not accounts:
        try:
            with console.status(
                    f"[bright_cyan]Đang tải danh sách tài khoản {platform} "
                    "(account/list)…[/bright_cyan]", spinner="dots"):
                code, data, _ = c.request("GET", "/user/account/list",
                                          params={"type": platform, "page": 1})
        except requests.RequestException as e:
            console.print(Panel(str(e), title="Lỗi request", border_style="red"))
        else:
            if code < 400 and isinstance(data, dict):
                api = data.get("status_code")
                if not (isinstance(api, int) and api >= 400):
                    root = data.get("data") or {}
                    cand = root.get("data") if isinstance(root, dict) else None
                    if isinstance(cand, list):
                        accounts = [a for a in cand
                                    if isinstance(a, dict) and a.get("id")]
                    if accounts:
                        source = "account/list"
    if not accounts:
        # v2.8 — phân biệt 'chưa liên kết acc' với 'phiên chết' (401) để user
        # không tưởng mình mất acc.
        console.print("[dim]Mẹo v2.8: nếu bất kỳ bước nào báo 'Phiên đăng nhập "
                      "không hợp lệ' → phiên đã chết, chọn mục 1 đăng nhập lại "
                      "(có ghi nhớ mật khẩu thì tool tự đăng nhập lại từ lần "
                      "sau). Nếu danh sách trống thật → vào web mục Account để "
                      "liên kết tài khoản.[/dim]")
    return accounts, source


def _choose_account(c: Client, platform: str, current_acc: dict | None = None,
                    *, title: str | None = None,
                    accounts: list | None = None) -> dict | None:
    """v2.7 — ĐỔI / CHỌN tài khoản làm nhiệm vụ KIỂU BUILD WEB MỚI (4/11 tối):
    KHÔNG POST endpoint đổi acc nào cả.
    Web: dropdown QuickSwitch → chọn acc → localStorage['quickswitch_active_
    <type>'] = id → emit change(acc) → view fetch GET /user/earn-coin?
    …&refresh=1&uid=<acc.name||acc.uid> → SERVER đổi accountActive THEO uid.
    (POST /user/account/quick-switch cũ không còn được web gọi — server build
    mới trả "Không tìm thấy dữ liệu" → đúng lỗi user báo 4/11 tối.)
    Tool: tải danh sách (quick-switch GET → fallback account/list) → bảng
    chọn ★ đang dùng → GHI NHỚ lựa chọn (state file ≈ localStorage) → trả
    acc cho caller fetch refresh=1 + uid; accountActive trong response là
    server-truth."""
    if accounts is None:
        accounts, _src = _get_switch_accounts(c, platform)
    if not accounts:
        console.print(Panel(
            f"Không tải được danh sách tài khoản {platform} (hoặc bạn chưa "
            f"liên kết acc nào).\nThêm tài khoản: mục 2 → Accounts → "
            f"'Thêm tài khoản'.",
            title="Không có tài khoản", border_style="yellow", box=box.ROUNDED))
        return None
    cur_id = current_acc.get("id") if isinstance(current_acc, dict) else None
    t = Table(title=title or f"Chọn tài khoản {platform} ({len(accounts)} mục)"
                            f" — ★ = đang dùng",
              box=box.ROUNDED, header_style="bold bright_cyan", expand=True)
    t.add_column("#", justify="right", width=4)
    t.add_column("")
    t.add_column("ID")
    t.add_column("UID / Username")
    t.add_column("Name")
    t.add_column("Trạng thái")
    for i, acc in enumerate(accounts, 1):
        uid = acc.get("uid") or acc.get("username") or acc.get("object_id") or "?"
        name = acc.get("name") or acc.get("full_name") or acc.get("nickname") or ""
        status = acc.get("status_label") or acc.get("status") or ""
        active = (acc.get("id") == cur_id)
        mark = Text("★ ĐANG DÙNG", style="bold black on bright_green") if active \
            else Text("")
        t.add_row(str(i), mark, str(acc.get("id", "?")), str(uid)[:40],
                  str(name)[:30], str(status),
                  style="bold" if active else None)
    console.print(t)
    pick = IntPrompt.ask("Chọn # tài khoản (0 = quay lại)", default=0)
    if not 1 <= pick <= len(accounts):
        return None
    chosen = accounts[pick - 1]
    if chosen.get("id") == cur_id:
        console.print("[yellow]Đó chính là tài khoản đang dùng — giữ nguyên.[/yellow]")
        return None
    _save_quick_state(platform, chosen.get("id"))   # mirror localStorage web
    nm = chosen.get("name") or chosen.get("uid") or f"#{chosen.get('id')}"
    flash(True, f"Đã chọn {nm} — tải nhiệm vụ của tài khoản này…")
    return chosen


def _resolve_session_account(c: Client, platform: str) -> dict | None:
    """v2.7 — Xác định tài khoản làm việc lúc MỞ workflow, mirror web:
    GET quick-switch list (QuickSwitch mount khi mở trang) → acc đã NHỚ từ
    lần trước (localStorage ≈ state file) → nếu chỉ có 1 acc thì dùng luôn
    → nhiều acc chưa nhớ thì hỏi. Trả None nếu không có acc nào."""
    accounts, _source = _get_switch_accounts(c, platform)
    if not accounts:
        return None
    rid = _load_quick_state(platform)
    if rid is not None:
        for a in accounts:
            if str(a.get("id")) == str(rid):
                return a
    if len(accounts) == 1:
        _save_quick_state(platform, accounts[0].get("id"))
        return accounts[0]
    return _choose_account(c, platform, None,
                           title=f"Chọn tài khoản {platform} để bắt đầu",
                           accounts=accounts)


def _switch_force(c: Client, platform: str, jtype: str,
                  acc: dict | None) -> dict | None:
    """v2.8 — ĐÓNG CỌ đổi acc: fetch uid không hiệu lực (server trả acc khác /
    accountActive null / "Không tìm thấy dữ liệu") → thử LẦN LƯỢT mọi đường
    server còn hỗ trợ, mỗi bước in 1 dòng trace dim (chụp màn hình chẩn đoán
    được — hết cảnh 'vẫn lỗi' mà không biết server chặn ở đâu):
      S1 POST /user/account/active-acc {account_id,type} — endpoint KÍCH HOẠT
         vẫn được export trong bundle web mới (DYEqVcGR.js activateAccount)
      S2 GET /user/earn-coin refresh=1 & uid=<uid số> — phòng server tra theo số
      S3 POST /user/account/quick-switch {account_id,type} — endpoint cũ, build
         mới web không gọi (server có thể trả "Không tìm thấy dữ liệu" = gỡ)
      S4 GET /user/earn-coin refresh=1 & uid=<name> — web-chuẩn, thử lại sau POST
      S5 GET /user/earn-coin refresh=1 (trần) — chấp nhận server-truth
    Sau mỗi POST → fetch lại để ĐỐI CHIẾU accountActive.id == acc.id. Trả
    payload `data` (dict) KHỚP acc nếu có, else payload hợp lệ cuối; None nếu
    mọi đường đều lỗi request."""
    if not isinstance(acc, dict) or acc.get("id") is None:
        return None
    acc_id = acc.get("id")
    name = acc.get("name") or acc.get("uid")
    num = acc.get("uid") or acc.get("object_id")
    matched: dict | None = None
    last_valid: dict | None = None

    def _show(tag: str, code: int, api: Any, msg: Any, ok: bool) -> None:
        extra = f" · {msg}" if msg else ""
        console.print(f"[dim]· {tag}: HTTP {code}"
                      + (f" · json {api}" if isinstance(api, int) and api != 200 else "")
                      + (extra if not ok else "") + "[/dim]")

    def _pl(tag: str, params: dict) -> dict | None:
        nonlocal matched, last_valid
        for attempt in (1, 2):
            try:
                with console.status(f"[bright_cyan]đổi acc · {tag}…[/bright_cyan]",
                                    spinner="dots"):
                    code, data, _ = c.request("GET", "/user/earn-coin",
                                              params=params)
            except requests.RequestException as e:
                console.print(f"[dim]· {tag}: lỗi mạng — {e}[/dim]")
                return None
            api = data.get("status_code") if isinstance(data, dict) else None
            msg = data.get("message") if isinstance(data, dict) else None
            if code == 429 or (isinstance(api, int) and api == 429):
                retry = _retry_after(data)
                console.print(f"[yellow]⏳ 429 hơi nhanh — chờ {retry}s rồi "
                              "thử lại…[/yellow]")
                countdown(retry, "Rate-limit")
                continue
            ok = code < 400 and not (isinstance(api, int) and api >= 400)
            if ok and isinstance(data, dict):
                pl = data.get("data") if isinstance(data.get("data"), dict) else {}
                na = pl.get("accountActive")
                na_nm = (na or {}).get("name") or (na or {}).get("uid") \
                    if isinstance(na, dict) else "null"
                n_jobs = len(pl.get("jobs") or []) if isinstance(pl, dict) else 0
                console.print(f"[dim]· {tag}: HTTP {code} · active={na_nm} "
                              f"· {n_jobs} jobs[/dim]")
                if isinstance(na, dict) and na.get("id"):
                    last_valid = pl
                    if str(na.get("id")) == str(acc_id):
                        matched = pl
                return pl
            _show(tag, code, api, msg, ok)
            return None
        return None

    def _post(tag: str, path: str) -> bool:
        for attempt in (1, 2):
            try:
                with console.status(f"[bright_cyan]đổi acc · {tag}…[/bright_cyan]",
                                    spinner="dots"):
                    code, data, _ = c.request("POST", path,
                                              body={"account_id": acc_id,
                                                    "type": platform})
            except requests.RequestException:
                console.print(f"[dim]· {tag}: lỗi mạng[/dim]")
                return False
            api = data.get("status_code") if isinstance(data, dict) else None
            msg = data.get("message") if isinstance(data, dict) else None
            if code == 429 or (isinstance(api, int) and api == 429):
                retry = _retry_after(data)
                countdown(retry, "Rate-limit")
                continue
            ok = code < 400 and not (isinstance(api, int) and api >= 400)
            _show(tag, code, api, msg or ("OK" if ok else None), ok)
            return ok
        return False

    console.print(Rule("Thang đổi acc v2.8", style="dim"))
    if _post("S1 active-acc", "/user/account/active-acc"):
        _pl("S1 fetch uid", {"platform": platform, "type": jtype,
                             "refresh": 1, "uid": name})
    if matched is None and num and str(num) != str(name):
        _pl("S2 uid số", {"platform": platform, "type": jtype,
                          "refresh": 1, "uid": num})
    if matched is None:
        _post("S3 quick-switch (cũ)", "/user/account/quick-switch")
        _pl("S3 fetch uid", {"platform": platform, "type": jtype,
                             "refresh": 1, "uid": name})
    if matched is None:
        _pl("S4 fetch uid lại", {"platform": platform, "type": jtype,
                                 "refresh": 1, "uid": name})
    if matched is None:
        _pl("S5 fetch trần", {"platform": platform, "type": jtype,
                              "refresh": 1})
    if matched is not None:
        console.print(f"[dim]· kết quả: KHỚP acc #{acc_id} ({name})[/dim]")
        return matched
    if last_valid is not None:
        console.print("[dim]· kết quả: server giữ acc khác — trả server-truth[/dim]")
        return last_valid
    return None


def _render_job_card(job: dict, idx: int, total: int) -> Panel:
    """v2.4 — job card: chip tiêu đề (JOB x/y · FOLLOW · +coin), progress 14 ô + %."""
    head = Text()
    head.append(f" JOB {idx}/{total} ", style="bold black on bright_magenta")
    head.append("  ")
    head.append(str(job.get("type_job", "?")).upper(),
                style="bold black on bright_yellow")
    head.append("  ")
    head.append(f" +{job.get('reward_amount', '?')} coin ",
                style="bold black on bright_green")
    body = Table.grid(padding=(0, 1))
    body.add_column(style="dim", width=12)
    body.add_column()
    body.add_row("Job ID",    Text(str(job.get("id", "?")), style="bold white"))
    body.add_row("Đối tượng", Text("@" + str(job.get("object_id", ""))[:48],
                                   style="bold bright_cyan"))
    body.add_row("Link",     Text(str(job.get("link", "")), style="cyan"))
    if job.get("original_link"):
        body.add_row("Original", Text(str(job.get("original_link", ""))[:60], style="dim"))
    qty = job.get("quantity"); run = job.get("run")
    rem = job.get("remain", job.get("remains"))   # v2.6 — build 4/11 trả `remain`
    if isinstance(qty, (int, float)) and isinstance(run, (int, float)) and qty:
        pct = min(100, round(run * 100 / qty))
        W = 14
        filled = max(0, min(W, round(pct * W / 100)))
        prog = Text()
        prog.append("▓" * filled, style="bright_green")
        prog.append("░" * (W - filled), style="dim")
        prog.append(f" {run}/{qty} ({pct}%)", style="bold white")
        if rem is not None:
            prog.append(f" · còn {rem}", style="dim")
        body.add_row("Tiến độ", prog)
    return Panel(body, title=head,
                 border_style="bright_magenta", box=box.ROUNDED, padding=(1, 1))

# Common earn-coin server error patterns → friendly hints shown in panel
JOB_ERROR_HINTS = [
    ("chưa làm job này",
     "Bạn cần [bold]mở link và follow/like thật[/bold] trên TikTok/Facebook trước, "
     "sau đó quay lại ấn [bold]C[/bold] (Hoàn thành)."),
    ("chưa thực hiện follow",
     "Bạn cần [bold]mở link và follow thật[/bold] tài khoản trên TikTok, "
     "sau đó quay lại ấn [bold]C[/bold] (Hoàn thành)."),
    ("chưa thực hiện like",
     "Bạn cần [bold]mở link và like thật[/bold] bài viết trên TikTok/Facebook, "
     "sau đó quay lại ấn [bold]C[/bold] (Hoàn thành)."),
    ("đã được thực hiện",
     "Job này đã được thực hiện trước đó. Thử [bold]R[/bold] (Report) hoặc [bold]N[/bold] (Next)."),
    ("hết thời gian",
     "Job đã hết thời gian làm. Thử [bold]R[/bold] (Report) hoặc [bold]N[/bold] (Next)."),
    ("hết quota",
     "Bạn đã đạt limit job hôm nay — quay lại sau."),
    ("không tìm thấy dữ liệu",
     "Job có thể đã bị xóa/hết. Thử [bold]N[/bold] (Next) để sang job khác."),
    ("có lỗi hệ thống",
     "Lỗi server-side, không phải do bạn. Thử lại, nếu vẫn lỗi thì [bold]N[/bold] để qua job khác."),
    ("vui lòng thử lại",
     "Server tạm lỗi. Thử lại lần nữa — nếu vẫn lỗi thì [bold]N[/bold]."),
]

def _show_server_message(label: str, job_id: Any, code: int, api: Any,
                         msg: str | None, ms: int, is_error: bool) -> None:
    """Show a PROMINENT panel with the server message + actionable hint.
    Called for every job action so the user can't miss the actual reason."""
    # v2.4 — nhịp đỏ báo lỗi TRƯỚC khi vẽ panel (subtle, tự tắt cùng ANIM)
    if is_error:
        error_pulse(msg or "")
    # Status bar (compact, always)
    bar = Text()
    bar.append(f" HTTP {code} ", style=f"bold black on {status_style(code)}")
    if api is not None:
        bar.append(f" JSON {api} ",
                   style=f"bold black on {status_style(int(api)) if str(api).isdigit() else 'white'}")
    bar.append(f"  {ms} ms", style="dim")
    console.print(bar)

    # Prominent message panel (always show if msg non-empty)
    body_lines = []
    if msg:
        body_lines.append(f"[bold]{msg}[/bold]")
    else:
        body_lines.append("[dim](server không trả message)[/dim]")

    # Attach hint if we recognize the error pattern
    hint = None
    if is_error and msg:
        m = msg.lower()
        for pattern, h in JOB_ERROR_HINTS:
            if pattern in m:
                hint = h
                break
    if hint:
        body_lines.append("")
        body_lines.append("[bold bright_cyan]💡 Gợi ý:[/bold bright_cyan] " + hint)

    border = "green" if not is_error else ("yellow" if code < 400 or (isinstance(api,int) and api<500) else "red")
    title = f"{label} job #{job_id}  ·  {'THÀNH CÔNG' if not is_error else 'LỖI'}"
    console.print(Panel("\n".join(body_lines), title=title,
                        border_style=border, box=box.ROUNDED, padding=(1, 2)))

def _do_job_action(c: Client, job: dict, action: str,
                   platform: str = "tiktok", acc: dict | None = None,
                   opened: set | None = None,
                   sess: dict | None = None) -> tuple[bool, str]:
    """Execute the chosen action on a job.
    Returns (advance: bool, note: str) — note hiện lại ở header phiên sau khi clear
    (fix v1.6: kết quả +coin không còn biến mất sau 0.5s).

    v2.3 (DevTools 29/10) — Complete success trả:
      data: {coin_received: int, current_balance: int, balance_pending: int,
             account_daily_usage: {done, limit, remaining}}
      → bảng "Số dư" hiển thị đủ 4 nhóm + sess["du"] ghi đè hạn mức
        để progress bar ở header phiên cập nhật NGAY (không cần refetch jobs).

    v1.5 — body mirror web 100% (bundle DBkPpj9p.js + Cs2oLB4-.js):
      complete : {job_id, account_id, type, type_job}   (web KHÔNG gửi uid)
      skip     : {job_id, account_id, uid, object_id, type}
      report   : {..., type, message}  (message, KHÔNG phải reason)
      object_id: job.object_id || job.id  (web: e.username='@'+object_id → replace('@',''))
      open like: mark-start {job_id, account_id} TRƯỚC khi mở link
    Guard v1.5: thiếu account_id → CHẶN (web cũng chặn) — đây chính là
    nguyên nhân 500 "Có lỗi hệ thống" của bản cũ."""
    job_id = job.get("id")
    if not job_id:
        console.print("[red]Job không có ID — bỏ qua.[/red]")
        return True, "job thiếu id — đã bỏ qua"
    acc_id  = acc.get("id")  if isinstance(acc, dict) else None
    acc_uid = acc.get("uid") if isinstance(acc, dict) else None
    # web: object_id = username?.replace("@","") || e.id — chính là job.object_id
    obj_id  = job.get("object_id") or job_id

    # Gate: web chỉ cho Hoàn thành SAU KHI mở link (đếm ngược 5s trên UI)
    if action == "c" and opened is not None and job_id not in opened:
        console.print("[yellow]⚠ Bạn chưa mở link job này trong phiên — "
                      "web yêu cầu mở link trước khi Hoàn thành.[/yellow]")
        if Confirm.ask("Mở link ngay bây giờ?", default=True):
            return _do_job_action(c, job, "o", platform, acc, opened, sess)
        if not Confirm.ask("Vẫn Hoàn thành mà không mở link? (không khuyến khích)",
                           default=False):
            return False, "chưa mở link — nên mở link (O) rồi mới Complete"

    if action == "o":
        link = job.get("link") or job.get("original_link") or ""
        if not link:
            console.print("[yellow]Job không có link để mở.[/yellow]")
            return False, "job không có link để mở"
        # Job LIKE → server cần mark-start TRƯỚC khi mở link (web làm y hệt,
        # fire-and-forget: .catch(()=>{}))
        if str(job.get("type_job", "")).lower() == "like" and acc_id:
            try:
                c.request("POST", "/user/earn-coin/tiktok-like/mark-start",
                          body={"job_id": job_id, "account_id": acc_id})
            except requests.RequestException:
                pass
        console.print(f"[cyan]Mở:[/cyan] {link}")
        try:
            webbrowser.open(link, new=2)
        except Exception:
            console.print(f"[dim](không mở được tự động — copy link bằng tay)[/dim]")
        if isinstance(opened, set):
            opened.add(job_id)
        console.print("[dim]Web đếm ngược [bold]5 giây[/bold] sau khi mở link — "
                      "follow/like xong trên app, đợi ít nhất 5s rồi ấn [bold]C[/bold].[/dim]")
        return False, "đã mở link — follow/like THẬT rồi quay lại ấn C"

    # Guard cứng: web không bao giờ gửi complete/skip/report khi thiếu tài khoản
    # active — gửi thiếu account_id → server exception → 500 "Có lỗi hệ thống".
    if acc_id is None:
        console.print(Panel(
            "Không có [bold]account_id[/bold] (chưa kích hoạt tài khoản).\n"
            "Web cũng CHẶN hoàn thành/skip/report trong trạng thái này — gửi thiếu "
            "field thì server trả 500 \"Có lỗi hệ thống\".",
            title="Chưa có tài khoản active", border_style="yellow", box=box.ROUNDED))
        skip = Confirm.ask("Bỏ qua job này?", default=True)
        return skip, "chưa có tài khoản active — KHÔNG gửi request"

    if action == "c":
        path = "/user/earn-coin/complete-job"
        body = {"job_id": job_id, "account_id": acc_id,
                "type": platform, "type_job": job.get("type_job") or "follow"}
        label = "Complete"
    elif action == "s":
        path = "/user/earn-coin/skip-job"
        body = {"job_id": job_id, "account_id": acc_id, "uid": acc_uid,
                "object_id": obj_id, "type": platform}
        label = "Skip"
    elif action == "r":
        reason = Prompt.ask("Lý do báo lỗi", default="")
        path = "/user/earn-coin/report-job"
        body = {"job_id": job_id, "account_id": acc_id, "uid": acc_uid,
                "object_id": obj_id, "type": platform, "message": reason}
        label = "Report"
    else:
        return False, "hành động không rõ"
    try:
        with console.status(f"[bright_cyan]{label} job #{job_id}…[/bright_cyan]", spinner="dots"):
            code, data, ms = c.request("POST", path, body=body)
    except requests.RequestException as e:
        console.print(Panel(str(e), title="Lỗi request", border_style="red"))
        return False, f"lỗi kết nối — {label} job #{job_id} chưa gửi được"
    console.print(req_badge())
    api = data.get("status_code") if isinstance(data, dict) else None
    msg = data.get("message") if isinstance(data, dict) else None
    # Web nhận cả hai: `status_code===200` HOẶC cờ `success` (skip/report)
    succ = isinstance(data, dict) and data.get("success") is True
    is_error = (code >= 400 or (isinstance(api, int) and api >= 400) or
                (isinstance(api, str) and api.isdigit() and int(api) >= 400)) and not succ

    # 429 rate-limit → đếm ngược động như web ("Bạn thao tác hơi nhanh…")
    if code == 429 or (isinstance(api, int) and api == 429):
        retry = 3
        if isinstance(data, dict):
            dd = data.get("data")
            for cand in (data.get("retry_after"),
                         dd.get("retry_after") if isinstance(dd, dict) else None):
                if isinstance(cand, (int, float)) and cand > 0:
                    retry = int(cand)
                    break
        _show_server_message(label, job_id, code, api,
                             msg or "Bạn thao tác hơi nhanh, chờ vài giây nhé.", ms, True)
        countdown(retry, "Thử lại sau")
        return False, f"⏳ 429 rate-limit — chờ {retry}s rồi thử lại"

    # Prominent panel always — user cannot miss server's actual message
    _show_server_message(label, job_id, code, api, msg, ms, is_error)

    # Complete thành công → hiệu ứng +Coin + bảng Số dư đủ 4 nhóm (v2.3,
    # DevTools 29/10): coin_received · current_balance · balance_pending ·
    # account_daily_usage {done, limit, remaining}.
    got = reward = None
    if not is_error and action == "c" and isinstance(data, dict) \
            and isinstance(data.get("data"), dict):
        d = data["data"]
        got = d.get("coin_received")            # số xu THẬT server ghi nhận
        reward = got if got is not None else (job.get("reward_amount")
                                              or d.get("reward"))
        if reward:
            coin_count_up(reward, label=f"+Coin job #{job_id}")
            coin_burst()                     # v2.4 — pháo giấy ký tự ✦✧◈
        rew = Table.grid(padding=(0, 2))
        rew.add_column(style="dim")
        rew.add_column()
        if got is not None:
            rew.add_row("Nhận được",
                Text(f"+{got} coin", style="bold bright_green"))
        if d.get("current_balance") is not None:
            rew.add_row("Số dư",
                Text(f"{d['current_balance']} coin", style="bold bright_green"))
        if d.get("balance_pending") is not None:
            rew.add_row("Pending", f"{d['balance_pending']} coin")
        if d.get("coin_pending_today") is not None:
            rew.add_row("Hôm nay (pending)", f"{d['coin_pending_today']} coin")
        if d.get("job_today") is not None:
            rew.add_row("Jobs hôm nay", str(d["job_today"]))
        u = d.get("account_daily_usage") or d.get("daily_usage") or d.get("usage")
        if isinstance(u, dict) and (u.get("limit") is not None
                                    or u.get("done") is not None):
            rew.add_row("Hạn mức hôm nay", _usage_text(u))
            if sess is not None:
                sess["du"] = u       # đồng bộ progress bar header phiên ngay
        if rew.rows:
            console.print(Panel(rew, title="Số dư", border_style="green",
                                box=box.ROUNDED, padding=(0, 2)))

    # Move to next job on success — note giữ lại ở header phiên (fix clear quá nhanh)
    if not is_error:
        note = f"✔ {label} job #{job_id}"
        if msg:
            note += f" · {str(msg)[:60]}"
        if action == "c" and isinstance(data, dict) and isinstance(data.get("data"), dict):
            d = data["data"]
            if d.get("current_balance") is not None:
                note = f"✔ +{reward or 0} coin (job #{job_id}) · dư {d['current_balance']}" \
                    if reward else note + f" · dư {d['current_balance']}"
            u = d.get("account_daily_usage") or d.get("daily_usage") or d.get("usage")
            if isinstance(u, dict) and u.get("limit") is not None:
                note += f" · hạn mức {u.get('done')}/{u.get('limit')}"
        return True, note
    # On error → mặc định Ở LẠI job (Enter) để thử lại sau khi đã follow thật
    m = str(msg or "lỗi không rõ")[:70]
    # v2.6 — "Không tìm thấy dữ liệu" = job đã bị xoá/hết trên server: thử lại
    # vô ích. Web cũng gạch job khỏi danh sách ngay khi gặp lỗi → TỰ sang job
    # kế, không hỏi.
    if "không tìm thấy dữ liệu" in m.lower():
        return True, f"✘ {m} — job đã hết/bị xoá, tự sang job kế"
    skip_job = Confirm.ask("Bỏ qua job này và qua job kế? (Enter = ở lại thử lại)",
                           default=False)
    return skip_job, f"✘ {m}"

def do_earn_coin(c: Client) -> None:
    """Interactive earn-coin job runner.

    v2.8 — "Vẫn còn lỗi không tìm thấy dữ liệu khi đổi acc" (5/11):
    chẩn đoán trực tiếp chứng minh request của tool GIỐNG HỆT web (x-sign khớp
    bit) → lỗi là phía server/phiên. Thêm: thang đổi acc _switch_force có
    trace từng bước; fetch lỗi/accountActive=null → tự chạy thang trước khi
    bỏ cuộc; TỰ ĐĂNG NHẬP LẠI khi 401 nếu user có ghi nhớ mật khẩu (opt-in ở
    màn login — server cho phiên ngắn, token chết sau vài giờ).

    v2.7 — FIX "Vẫn lỗi / server không cho đổi acc" (sau v2.6, 4/11 tối).
    Đọc trực tiếp BUILD WEB MỚI NHẤT (follow4follow.net/_nuxt/: chunk
    BxxhXs6I.js QuickSwitch + Da1G0J4j.js EarnCoinsTiktokView):
    • Web đã BỎ HẲN POST đổi acc — QuickSwitch chọn acc → localStorage
      ['quickswitch_active_<type>'] → fetch earn-coin refresh=1 &
      uid=<name||uid>; SERVER đổi accountActive THEO uid param.
      → POST quick-switch cũ bị server trả "Không tìm thấy dữ liệu"
      (lỗi đổi acc user báo); earn-coin TRẦN uid cũng bị lỗi đó.
    • Tool v2.7: mở workflow → GET quick-switch list (data.accounts,
      fallback account/list) → acc đã nhớ (state file) / auto 1 acc /
      hỏi → LUÔN fetch kèm uid. Đổi acc (A) = chọn acc KHÔNG POST →
      fetch refresh=1+uid. Server trả acc khác acc vừa chọn → thử POST
      cũ 1 lần (quick-switch → active-acc) rồi theo server-truth.

    v2.6 — FIX "Lỗi không tìm thấy dữ liệu" (báo cáo 4/11 tối, kèm ảnh DevTools
    18:25 chứng minh WEB vẫn nhận đủ jobs cho acc hoaibao.2009):
    • Đọc lại BUILD WEB MỚI (site vừa deploy — bundle mới CEACzOZS.js /
      Da1G0J4j.js, chunk EarnCoinsTiktokView) — trang web TRUYỀN `uid` VÀO MỖI
      LẦN gọi earn-coin khi biết tài khoản active:
        getEarnCoinJobs(platform, {type, refresh, uid}) →
          params = {platform, type}; refresh → +refresh=1; uid → +uid
        uid truyền = l.name || l.uid  (vd: uid=hoaibao.2009 — TÊN tài khoản)
      Ảnh DevTools 4/11 18:25: ?platform=tiktok&type=follow&refresh=1&uid=
      hoaibao.2009 → 200 + accountActive(id 5599) + 3 jobs.
      → v2.5 bỏ hẳn uid là SAI với build mới: server không tự đoán được acc
        cần lấy jobs → trả lỗi "Không tìm thấy dữ liệu".
    • fetch_jobs mirror 100% web: uid = acc.name || acc.uid (TÊN trước),
      refresh=1 chỉ khi refetch — GIỐNG HỆT request web đã chụp.
    • Thang dự phòng khi lấy jobs (get_jobs_robust):
        V1 web-chuẩn (refresh? + uid nếu có acc) → V2 plain → V3 refresh=1
        plain. V1 trả rỗng/lỗi thì tự thử phương án kế; 429 → đếm ngược
        retry_after rồi thử lại đúng phương án.
    • Đổi acc / kích hoạt acc xong → fetch lại refresh=1 + uid=<acc mới>
      (web: sl() → Ra(true, e, true) — y hệt ảnh DevTools).
    • Job card đọc cả `remain` (schema build mới 4/11) lẫn `remains` (schema cũ).
    • Complete/Skip trả "Không tìm thấy dữ liệu" (job đã xoá/hết) → TỰ sang
      job kế, không hỏi lại (web cũng gạch job khỏi danh sách ngay khi lỗi).
    • Giữ nguyên các fix v2.5: server-truth accountActive (đổi acc xong danh
      sách job thay TOÀN BỘ), 429 tự đếm ngược ở mọi bước, menu A/F/T/Q khi
      hết jobs, fix crash tuple _do_job_action."""
    if not c.token:
        console.print("[yellow]Cần đăng nhập trước (mục 1).[/yellow]")
        return
    console.print(Rule("🎯 Earn-coin Workflow", style="bright_magenta"))
    type_out("Chạy job như bản web — mọi request tự gắn x-ts · x-sign · Bearer",
             style="dim italic", delay=0.004)

    # 1-2. Pick platform + job type
    platform = PLATFORMS[Prompt.ask("Chọn nền tảng", choices=["1", "2"],
                                    show_choices=True, default="1")]
    jtype = JOB_TYPES[Prompt.ask("Chọn loại nhiệm vụ", choices=["1", "2"],
                                 show_choices=True, default="1")]

    def fetch_jobs(acc: dict | None = None, refresh: bool = False):
        """v2.6 — mirror 100% build web MỚI (chunk CEACzOZS.js 4/11):
        GET /user/earn-coin?platform&type[&refresh=1][&uid=<name||uid>].
        uid = acc.name || acc.uid — web truyền MỖI LẦN khi biết acc active
        (ảnh DevTools 4/11 18:25: uid=hoaibao.2009 → 200 + jobs)."""
        params: dict = {"platform": platform, "type": jtype}
        if refresh:
            params["refresh"] = 1
        if isinstance(acc, dict):
            u = acc.get("name") or acc.get("uid")
            if u:
                params["uid"] = u
        try:
            with console.status(
                f"[bright_cyan]Đang lấy jobs ({platform}/{jtype})…[/bright_cyan]",
                spinner="dots"):
                return c.request("GET", "/user/earn-coin", params=params)
        except requests.RequestException as e:
            console.print(Panel(str(e), title="Lỗi request", border_style="red"))
            return None

    def get_jobs_robust(refresh: bool = False, label: str = "Lấy jobs",
                        acc: dict | None = None) -> dict | None:
        """v2.6 — lấy jobs theo THANG PHƯƠNG ÁN (mỗi phương án thử lại 1 lần
        khi 429):
          V1 web-chuẩn : refresh? + uid=<acc.name||uid>  (như web, ảnh DevTools)
          V2 plain     : không refresh/uid               (phòng uid lệch/cache)
          V3 refresh=1 : chỉ thêm khi caller muốn refresh
        V1 trả lỗi/accountActive-null → tự thử V2, rồi V3. Payload hợp lệ
        (có accountActive) nhưng jobs rỗng vẫn được nhớ lại — nếu mọi phương
        án sau còn tệ hơn thì trả payload đó (trạng thái "hết jobs" thật).
        Trả payload `data` (dict) hoặc None nếu lỗi hoàn toàn."""
        variants: list[tuple[str, dict | None, bool]] = [("web-chuẩn", acc, refresh)]
        if isinstance(acc, dict) and (acc.get("name") or acc.get("uid")):
            variants.append(("plain", None, False))
        if refresh:
            variants.append(("refresh=1 plain", None, True))

        payload: dict = {}
        best: dict | None = None          # payload hợp lệ nhất gặp được
        last_code, last_data, last_ms = 0, None, 0
        for vi, (tag, vacc, vrefresh) in enumerate(variants):
            for attempt in (1, 2):
                r = fetch_jobs(vacc, vrefresh)
                if r is None:
                    return None
                code, data, ms = r
                api = data.get("status_code") if isinstance(data, dict) else None
                if code == 429 or (isinstance(api, int) and api == 429):
                    if attempt == 1:
                        retry = _retry_after(data)
                        console.print(f"[yellow]⏳ 429 thao tác hơi nhanh — chờ "
                                      f"{retry}s rồi thử lại…[/yellow]")
                        countdown(retry, "Rate-limit")
                        continue
                    last_code, last_data, last_ms = code, data, ms
                    break
                last_code, last_data, last_ms = code, data, ms
                break
            if code >= 400 or not isinstance(data, dict) \
                    or (isinstance(api, int) and api >= 400):
                continue                      # lỗi → thử phương án kế
            pl = data.get("data") if isinstance(data.get("data"), dict) else {}
            na = pl.get("accountActive")
            have_acc = isinstance(na, dict) and bool(na.get("id"))
            jobs_now = pl.get("jobs")
            jobs_now = jobs_now if isinstance(jobs_now, list) else []
            if have_acc:
                best = pl                     # payload hợp lệ (dù jobs rỗng)
            if have_acc and jobs_now:
                return pl                     # có acc + có jobs — xong
            if vi + 1 < len(variants):
                if jobs_now or not have_acc:
                    console.print(f"[dim]phương án [{tag}] chưa có jobs hợp lệ — "
                                  "thử phương án kế…[/dim]")
                else:
                    console.print(f"[dim]phương án [{tag}] trả jobs rỗng — thử "
                                  "phương án kế…[/dim]")
                continue
        # Hết thang: ưu tiên payload hợp lệ (acc + jobs rỗng) > response 200
        # cuối cùng (kể cả accountActive=null — đó là TRẠNG THÁI THẬT của
        # server, Phase 1 sẽ tự mở flow kích hoạt) > báo lỗi.
        if best is not None:
            return best
        if isinstance(last_data, dict) and last_code < 400 \
                and not (isinstance(last_data.get("status_code"), int)
                         and last_data["status_code"] >= 400):
            return last_data.get("data") \
                if isinstance(last_data.get("data"), dict) else {}
        show_result(f"Lỗi {label}", last_code or 0, last_data, last_ms,
                    path="/user/earn-coin", method="GET")
        return None

    # sess["du"] — hạn mức ngày dùng chung cho header phiên; complete-job trả
    # account_daily_usage mới (DevTools 29/10) → _do_job_action ghi đè trực tiếp,
    # progress bar cập nhật NGAY không cần refetch jobs (v2.3).
    sess: dict = {"du": None}
    opened: set = set()
    last_note = ""
    idx = 0
    jobs: list = []
    acc_active: dict | None = None
    pending_acc: dict | None = None    # acc cần fetch kèm uid (sau đổi/chọn acc)
    expect_acc: dict | None = None     # v2.7 — acc vừa chọn (đối chiếu server-truth)
    legacy_tried = False               # v2.7 — đã thử POST đổi acc cũ cho lần chọn này?

    def session_header() -> None:
        acc = acc_active or {}
        acc_name = acc.get("name") or acc.get("uid") or "?"
        line = Text()
        line.append(f" {platform}/{jtype} ", style="bold black on bright_magenta")
        line.append(f"  {acc_name} ", style="bold white")
        du = sess.get("du")
        if isinstance(du, dict) and du.get("limit"):
            # v2.3 — bar ▓▓▓░░ 3/50 + (còn N) từ _usage_text; tự cập nhật
            # sau mỗi Complete (response trả account_daily_usage mới).
            line.append("  hôm nay ", style="dim")
            line.append(_usage_text(du))
        line.append(f"  ·  job ", style="dim")
        # v2.4 — bar tiến trình danh sách job: ▓▓░░ 3/10
        try:
            cur, tot = min(idx + 1, len(jobs)), max(1, len(jobs))
            filled = max(0, min(10, round(cur * 10 / tot)))
            line.append("▓" * filled + "░" * (10 - filled), style="bright_cyan")
            line.append(f" {cur}/{tot}", style="bold bright_cyan")
        except (TypeError, ValueError):
            line.append(f"{min(idx + 1, len(jobs))}/{len(jobs)}",
                        style="bold bright_cyan")
        console.print(line)
        if last_note:
            console.print(Text(f"↩ {last_note}", style="dim"))
        console.print()

    # ══ VÒNG PHIÊN — Phase 1 đảm bảo (tài khoản active + jobs) theo
    # SERVER-TRUTH, Phase 2 chạy vòng lặp job. Đổi acc / hết jobs đều quay
    # về Phase 1 fetch lại ĐÚNG KIỂU TRANG WEB: refresh=1 + uid=<acc>. ══
    refresh_next = False
    expect_acc_id: int | None = None
    while True:
        # ── Phase 1: lấy jobs + đồng bộ tài khoản active THEO SERVER ──
        # v2.7 — build web mới KHÔNG BAO GIỜ gọi earn-coin khi chưa biết acc
        # (view Da1G0J4j.js: if(acc){fetch…uid} else jobs=[]) — acc đến từ
        # QuickSwitch (GET quick-switch + nhớ lựa chọn). Mirror web: resolve
        # acc TRƯỚC khi fetch → earn-coin LUÔN kèm uid (tránh lỗi
        # "Không tìm thấy dữ liệu" khi fetch trần).
        if acc_active is None and pending_acc is None:
            boot = _resolve_session_account(c, platform)
            if boot is not None:
                pending_acc = boot
                last_note = (f"tài khoản làm việc: "
                             f"{boot.get('name') or boot.get('uid') or '?'}")
        fetch_acc = pending_acc or acc_active
        payload = get_jobs_robust(refresh=refresh_next, acc=fetch_acc)
        pending_acc = None
        refresh_next = False
        if payload is None:
            # v2.8 — fetch LỖI (vd "Không tìm thấy dữ liệu" ngay khi vừa đổi
            # acc) → KHÔNG bỏ cuộc: chạy THANG ĐỔI ACC với acc đang nhắm
            # (active-acc → uid số → quick-switch cũ → uid lại → trần). Hết
            # thang vẫn fail mới dừng + panel chỉ 3 nguyên nhân.
            cand = expect_acc or fetch_acc
            if isinstance(cand, dict) and cand.get("id"):
                pl2 = _switch_force(c, platform, jtype, cand)
                if isinstance(pl2, dict):
                    na2 = pl2.get("accountActive")
                    if isinstance(na2, dict) and na2.get("id"):
                        payload = pl2
                        if na2.get("id") == (cand.get("id")):
                            last_note = (f"✓ đổi acc thành công (thang v2.8): "
                                         f"{na2.get('name') or na2.get('uid') or '?'}")
                            expect_acc_id = None
                            expect_acc = None
            if payload is None:
                console.print(Panel(
                    "Mọi bước đổi acc đều bị server từ chối (xem trace phía trên).\n\n"
                    "[bold]1.[/bold] Có dòng 'Phiên đăng nhập không hợp lệ' → phiên "
                    "đã chết: thoát, chọn [bold]mục 1 đăng nhập lại[/bold] (có ghi "
                    "nhớ mật khẩu thì tool tự làm).\n"
                    "[bold]2.[/bold] Trace OK nhưng active=acc khác → server đang giữ "
                    "acc khác: thoát (Q) rồi mở lại workflow.\n"
                    "[bold]3.[/bold] 'Không tìm thấy dữ liệu' trên CẢ các bước → acc "
                    "có thể CHƯA liên kết với đúng user f4f mà tool đang đăng nhập, "
                    "hoặc dữ liệu TikTok của acc hỏng phía server: mở WEB → Account "
                    "→ xóa + thêm lại acc này.",
                    title="Không đổi được acc", border_style="red",
                    box=box.ROUNDED))
                return

        na = payload.get("accountActive")
        if not (isinstance(na, dict) and na.get("id")):
            # v2.8 — có acc vừa chọn/đang nhắm → TỰ chạy thang đổi acc TRƯỚC
            # khi hỏi lại user (thường chỉ cần 1 POST active-acc là server
            # kích hoạt được acc).
            cand = expect_acc
            if isinstance(cand, dict) and cand.get("id"):
                console.print(f"[dim]accountActive rỗng — chạy thang đổi acc "
                              f"v2.8 với {cand.get('name') or cand.get('uid') or '?'}…[/dim]")
                pl2 = _switch_force(c, platform, jtype, cand)
                if isinstance(pl2, dict) and isinstance(pl2.get("accountActive"), dict) \
                        and pl2["accountActive"].get("id"):
                    na = pl2["accountActive"]
                    du2 = pl2.get("account_daily_usage") or pl2.get("daily_usage") \
                        or pl2.get("usage")
                    if isinstance(du2, dict) and du2:
                        sess["du"] = du2
                    nj2 = pl2.get("jobs")
                    jobs = nj2 if isinstance(nj2, list) else []
                    idx = 0
                    opened.clear()
                    acc_active = na
                    expect_acc = None
                    expect_acc_id = None
                    last_note = (f"✓ đã kích hoạt {na.get('name') or na.get('uid') or '?'} "
                                 "(thang v2.8)")
                    continue
            console.print(Panel(
                "Server trả về [bold]accountActive = null[/bold] — "
                "tài khoản hiện tại chưa được kích hoạt cho nền tảng này.",
                title="Cần kích hoạt tài khoản", border_style="yellow", box=box.ROUNDED))
            # v2.7 — chọn acc kiểu web mới (KHÔNG POST); server nhận acc qua uid
            picked = _choose_account(c, platform, None,
                                     title=f"Chọn tài khoản {platform}")
            if not picked:
                console.print("[yellow]Không thể tiếp tục — hủy workflow.[/yellow]")
                return
            # Fetch lại ĐÚNG KIỂU TRANG WEB sau kích hoạt: refresh=1 + uid acc
            # mới (v2.6 — web sl(): Ra(true, e, true))
            pending_acc = picked
            payload = get_jobs_robust(refresh=True, acc=picked)
            if payload is None:
                return
            na = payload.get("accountActive")
            # GUARD CỨNG (v1.5): web cũng chặn chạy job khi accountActive=null
            # (complete/skip với account_id=null → 500 "Có lỗi hệ thống").
            if not (isinstance(na, dict) and na.get("id")):
                console.print(Panel(
                    "Sau khi kích hoạt, server VẪN trả [bold]accountActive = null[/bold].\n"
                    "Web cũng chặn chạy job ở trạng thái này — dừng để tránh lỗi 500.\n"
                    "Hãy đợi vài phút rồi chạy lại, hoặc kiểm tra tài khoản ở "
                    "mục 2 → Accounts → 'Kích hoạt tài khoản'.",
                    title="Không lấy được tài khoản active", border_style="red",
                    box=box.ROUNDED))
                return
            pending_acc = None               # đã dùng xong — tránh stale

        du = payload.get("account_daily_usage") or payload.get("daily_usage") \
             or payload.get("usage")
        if isinstance(du, dict) and du:
            sess["du"] = du
        new_jobs = payload.get("jobs")
        new_jobs = new_jobs if isinstance(new_jobs, list) else []

        if new_jobs:
            # Server-truth: acc active khác acc đang dùng (vừa đổi acc,
            # hoặc bị đổi trên web) → ghi nhận + thay TOÀN BỘ danh sách job.
            prev_id = acc_active.get("id") if isinstance(acc_active, dict) else None
            if expect_acc_id is not None and na.get("id") != expect_acc_id:
                # v2.8 — fetch uid không đủ ép server → chạy TOÀN thang đổi acc
                # (active-acc → uid số → quick-switch cũ → uid lại → trần);
                # khớp thì dùng ngay payload mới, không cần fetch vòng sau.
                if expect_acc is not None and not legacy_tried:
                    legacy_tried = True
                    pl2 = _switch_force(c, platform, jtype, expect_acc)
                    if isinstance(pl2, dict):
                        na2 = pl2.get("accountActive")
                        if isinstance(na2, dict) and na2.get("id"):
                            payload = pl2
                            na = na2
                            du2 = pl2.get("account_daily_usage") \
                                or pl2.get("daily_usage") or pl2.get("usage")
                            if isinstance(du2, dict) and du2:
                                sess["du"] = du2
                            nj2 = pl2.get("jobs")
                            new_jobs = nj2 if isinstance(nj2, list) else []
                if isinstance(na, dict) and na.get("id") == expect_acc_id:
                    last_note = (f"✓ đổi acc thành công (thang v2.8): "
                                 f"{na.get('name') or na.get('uid') or '?'}")
                elif isinstance(na, dict):
                    last_note = (f"⚠ server vẫn trả tài khoản "
                                 f"{na.get('name') or na.get('uid') or '?'} — đã "
                                 "hết thang đổi acc, tool theo server-truth")
            elif prev_id is not None and na.get("id") != prev_id:
                last_note = (f"tài khoản active theo server: "
                             f"{na.get('name') or na.get('uid') or '?'} — đã làm mới jobs")
            expect_acc_id = None
            expect_acc = None
            acc_active = na
            jobs = new_jobs
            idx = 0
            opened.clear()
        else:
            # ── Hết jobs / chạm hạn mức → menu TẠI CHỖ (v2.5, không thoát) ──
            du = sess.get("du") or {}
            done, limit = du.get("done"), du.get("limit")
            acc_name = na.get("name") or na.get("uid") or "?"
            reached = du.get("reached") is True or (
                isinstance(done, int) and isinstance(limit, int)
                and limit > 0 and done >= limit)
            why = (f"đã chạm hạn mức hôm nay [bold]{done}/{limit}[/bold]"
                   if reached else "chưa có jobs khả dụng lúc này")
            console.print(Panel(
                f"Tài khoản [bold]{acc_name}[/bold] {why}.\n\n"
                f"[bold]A[/bold] = đổi tài khoản    [bold]F[/bold] = thử lấy lại\n"
                f"[bold]T[/bold] = đổi loại nhiệm vụ (đang [bold]{jtype}[/bold])    "
                f"[bold]Q[/bold] = thoát",
                title="Hết jobs", border_style="yellow", box=box.ROUNDED,
                padding=(1, 2)))
            ans = Prompt.ask("Chọn", choices=["a", "f", "t", "q"],
                             default="a" if reached else "f").lower()
            if ans == "q":
                console.print("[dim]Thoát workflow.[/dim]")
                return
            if ans == "t":
                jtype = "like" if jtype == "follow" else "follow"
                last_note = f"đổi loại nhiệm vụ → {jtype}"
                refresh_next = True          # v2.6 — fetch kèm uid acc active
                continue
            if ans == "f":
                last_note = "thử lấy jobs lại…"
                refresh_next = True
                continue
            # ans == "a" — v2.7: chọn acc KHÔNG POST (web mới bỏ hẳn POST đổi
            # acc) → fetch lại refresh=1 + uid acc mới; server đổi accountActive
            # theo uid (đúng nhịp web: QuickSwitch onChange → sl() → Ra(true,e,true))
            picked = _choose_account(c, platform, na)
            if picked:
                pending_acc = picked
                expect_acc_id = picked.get("id")
                expect_acc = picked
                legacy_tried = False
                refresh_next = True
                last_note = (f"đã chuyển sang "
                             f"{picked.get('name') or picked.get('uid') or '?'}"
                             " — đang lấy jobs của tài khoản mới…")
            else:
                last_note = "giữ nguyên tài khoản"
            continue

        # ── Phase 2: vòng lặp job (clear màn hình + header phiên mỗi vòng) ──
        switched = False
        while idx < len(jobs):
            clear_screen()
            console.print(banner(c))
            session_header()
            job = jobs[idx]
            console.print(_render_job_card(job, idx + 1, len(jobs)))
            # v2.0 — action bar dạng viên màu nền, không phải chữ thường [O]
            # v2.4 — thêm A: đổi tài khoản TikTok làm jobs giữa phiên
            acts = Text(" Hành động: ", style="dim")
            for key, desc, sty in [
                ("O", "Mở link", "bold black on bright_cyan"),
                ("C", "Hoàn thành", "bold black on bright_green"),
                ("S", "Bỏ qua", "bold black on bright_yellow"),
                ("R", "Báo lỗi", "bold black on bright_red"),
                ("A", "Đổi acc", "bold black on cyan"),
                ("N", "Next", "bold black on white"),
                ("F", "Jobs mới", "bold black on bright_blue"),
                ("Q", "Thoát", "bold black on bright_magenta"),
            ]:
                acts.append(f" {key} ", style=sty)
                acts.append(f" {desc}  ", style="bold")
            console.print(acts)
            action = Prompt.ask("Chọn",
                                choices=["o", "c", "s", "r", "a", "n", "f", "q"],
                                default="o").lower()
            if action == "q":
                console.print("[dim]Thoát workflow.[/dim]")
                return
            if action == "n":
                idx += 1
                last_note = "next — không gọi API"
                continue
            if action == "a":
                # v2.7 — đổi acc KIỂU WEB MỚI: chọn acc KHÔNG POST → RỜI VÒNG
                # JOB → fetch lại refresh=1 + uid=<acc mới>; server đổi
                # accountActive theo uid, danh sách job thay TOÀN BỘ.
                picked = _choose_account(c, platform, acc_active)
                if picked:
                    switched = True
                    pending_acc = picked
                    expect_acc_id = picked.get("id")
                    expect_acc = picked
                    legacy_tried = False
                    refresh_next = True
                    last_note = (f"đã chuyển sang "
                                 f"{picked.get('name') or picked.get('uid') or '?'}"
                                 " — đang lấy jobs của tài khoản mới…")
                    break
                last_note = "giữ nguyên tài khoản"
                continue
            if action == "f":
                # v2.6 — jobs mới: refresh=1 + uid (web Ya(): Ra(true) với acc
                # active); rỗng thì thang dự phòng tự fallback plain.
                fp2 = get_jobs_robust(refresh=True, acc=acc_active,
                                      label="Lấy jobs mới")
                if fp2 is None:
                    last_note = "lỗi khi lấy jobs mới — thử F lại"
                    continue
                na2 = fp2.get("accountActive")
                du2 = fp2.get("account_daily_usage") or fp2.get("daily_usage") \
                    or fp2.get("usage")
                if isinstance(du2, dict) and du2:
                    sess["du"] = du2
                fjobs2 = fp2.get("jobs")
                fjobs2 = fjobs2 if isinstance(fjobs2, list) else []
                if (isinstance(na2, dict) and na2.get("id")
                        and isinstance(acc_active, dict)
                        and na2.get("id") != acc_active.get("id")):
                    acc_active = na2
                    switched = True
                    last_note = (f"server đang dùng acc "
                                 f"{na2.get('name') or na2.get('uid') or '?'} "
                                 "— làm mới danh sách job")
                    break
                if fjobs2:
                    jobs = fjobs2
                    idx = 0
                    opened.clear()
                    last_note = f"đã lấy {len(jobs)} jobs mới"
                else:
                    last_note = "chưa có jobs mới — tiếp tục danh sách hiện tại"
                continue
            jid = job.get("id", "?")
            should_advance, note = _do_job_action(c, job, action, platform,
                                                  acc_active, opened, sess)
            last_note = note or f"{action.upper()} job #{jid}"
            if should_advance:
                idx += 1
                if FAST:
                    _sleep(0.4)          # F4F_FAST=1 → chạy liên tục không hỏi
                else:
                    ans = Prompt.ask("\nEnter = job kế · q = dừng",
                                     default="").strip().lower()
                    if ans == "q":
                        console.print("[dim]Đã dừng workflow.[/dim]")
                        return
            else:
                Prompt.ask("\nEnter để vẽ lại job", default="")
                continue

        # Hết danh sách (hoặc vừa đổi acc) → quay lại Phase 1 lấy jobs
        if not switched:
            console.print(Panel(
                "[green]Đã duyệt hết danh sách jobs.[/green] Tự lấy jobs mới…",
                title="Hoàn tất", border_style="green", box=box.ROUNDED))
            refresh_next = True
        # switched=True → Phase 1 fetch refresh=1 + uid=<acc mới> (chuẩn web v2.7)


# ───────────────────────── Main ─────────────────────────
def main() -> None:
    if os.name == "nt":
        os.system("")   # bật ANSI escape trên Windows legacy console (cho \033[3J)
    if "--version" in sys.argv or "-V" in sys.argv:
        console.print(f"f4f CLI v{VERSION} — tự ký x-ts · x-sign · Bearer trên mọi request")
        return
    c = Client()
    if c.token:
        with console.status("[bright_cyan]Đang tải hồ sơ…[/bright_cyan]", spinner="dots"):
            c.refresh_profile()
        if c.profile is None:
            # v2.8 — token cũ đã chết (server cho phiên ngắn): tự đăng nhập lại
            # nếu có mật khẩu đã nhớ, không thì hướng dẫn rõ để user không tưởng lỗi tool.
            if c.has_cred():
                with console.status("[bright_cyan]Phiên hết hạn — tự đăng nhập "
                                    "lại…[/bright_cyan]", spinner="dots"):
                    ok = c.relogin(silent=True)
                if ok and c.profile:
                    flash(True, f"Đã tự đăng nhập lại: "
                          f"{c.profile.get('username', '')} (phiên server ngắn — "
                          "sau này tool tự xử lý khi hết phiên)")
                else:
                    console.print("[yellow]Tự đăng nhập lại thất bại — chọn mục 1 "
                                  "để đăng nhập tay.[/yellow]")
            else:
                console.print("[yellow]Phiên cũ đã hết hạn — chọn [bold]1[/bold] đăng "
                              "nhập lại (khi đăng nhập có thể chọn ghi nhớ mật khẩu "
                              "để tool tự xử lý từ lần sau).[/yellow]")
    first = True
    while True:
        home(c, animated=first)
        if first:
            type_out("Sẵn sàng — mọi request đều tự động ký & gửi kèm Bearer.",
                     style="dim italic", delay=0.004)
            first = False
        ch = Prompt.ask("Chọn", choices=["0", "1", "2", "3", "4", "r", "R"],
                         default="1").lower()
        if ch == "0":
            console.print("[dim]Tạm biệt.[/dim]")
            return
        if ch == "r":
            with console.status("Đang làm mới…", spinner="dots"):
                c.refresh_profile()
            flash(True, "Đã làm mới hồ sơ.")
            continue
        if ch == "4":
            if (c.token or c.has_cred()) and Confirm.ask(
                    "Đăng xuất — xóa token + mật khẩu đã nhớ (nếu có)?", default=True):
                c.logout()               # v2.8 — logout cũng quên mật khẩu đã nhớ
                flash(True, "Đã đăng xuất.")
            elif not c.token and not c.has_cred():
                console.print("[dim]Không có token.[/dim]")
        else:
            {"1": lambda: do_login(c),
             "2": lambda: explorer(c),
             "3": lambda: do_earn_coin(c)}[ch]()
            # explorer (2) tự có nhịp dừng “Enter · j” sau mỗi kết quả — không hỏi thêm
            if ch != "2":
                Prompt.ask("\nEnter để về menu", default="")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        console.print("\n[dim]Đã thoát.[/dim]")

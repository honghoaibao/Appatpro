package com.atpro.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.automation.ServiceMode
import com.atpro.data.CookiePlatform
import com.atpro.data.CookieSaveResult
import com.atpro.data.CookieSummary
import com.atpro.data.CookieVault
import com.atpro.data.CookieVaultCodec
import com.atpro.ui.theme.AtBadge
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtSegmented
import com.atpro.ui.theme.AtTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
//  v1.12.5 — "Cách nuôi" cho Facebook và Instagram: Bằng app (chạy thật) · Bằng cookie.
//  v1.12.7 — Bằng cookie: cookie ĐỦ KHOÁ được lưu MÃ HOÁ trên máy (CookieVault, Android Keystore AES-GCM), còn sau khi
//  mở lại app. Vẫn CHƯA có bộ chạy thật (đăng nhập / gọi mạng). Giao diện chỉ thấy ID tài khoản — không bao giờ hiện lại
//  giá trị cookie; cookie không đồng bộ Supabase, không gửi đi đâu.
// ─────────────────────────────────────────────────────────────────────────────

/** Dịch vụ nào có lựa chọn "Bằng cookie"; null = không có (TikTok, X, Threads, Snapchat…). */
internal fun cookiePlatformOf(mode: ServiceMode): CookiePlatform? = when (mode) {
    ServiceMode.FACEBOOK_NURTURE  -> CookiePlatform.FACEBOOK
    ServiceMode.INSTAGRAM_NURTURE -> CookiePlatform.INSTAGRAM
    else                          -> null
}

/** Chọn cách nuôi: 0 = Bằng app, 1 = Bằng cookie. */
@Composable
internal fun FarmMethodSelector(
    platform: CookiePlatform,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "Cách nuôi ${platform.displayName}",
            color = AtColors.TextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        AtSegmented(options = listOf("Bằng app", "Bằng cookie"), selectedIndex = selected, onSelect = onSelect)
        Text(
            if (selected == 0) "Điều khiển ứng dụng ${platform.displayName} trên máy qua Trợ năng."
            else "Dùng cookie đăng nhập, không mở ứng dụng. Đang hoàn thiện, chưa chạy thật.",
            color = AtColors.TextMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** "Nuôi bằng cookie": dán cookie → kiểm tra đủ khoá → lưu mã hoá → danh sách tài khoản đã lưu. */
@Composable
internal fun CookiePanel(platform: CookiePlatform, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vault   = remember { CookieVault.getInstance(context) }
    val scope   = rememberCoroutineScope()

    var input   by remember(platform) { mutableStateOf("") }
    var reveal  by remember(platform) { mutableStateOf(false) }
    var message by remember(platform) { mutableStateOf<String?>(null) }
    var busy    by remember(platform) { mutableStateOf(false) }
    var items   by remember(platform) { mutableStateOf<List<CookieSummary>>(emptyList()) }

    // Kho dùng Keystore + đọc đĩa → luôn ở luồng IO.
    suspend fun reload() { items = withContext(Dispatchers.IO) { vault.list(platform) } }
    LaunchedEffect(platform) { reload() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AtColors.Warning.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AtBadge("CHƯA CHẠY", AtColors.Warning)
            Spacer(Modifier.size(8.dp))
            Text(
                "Cookie được mã hoá trên máy, không gửi đi, không đồng bộ. Bộ chạy đang hoàn thiện.",
                color = AtColors.TextSec, fontSize = 11.sp, lineHeight = 15.sp,
            )
        }

        AtTextField(
            value         = input,
            onValueChange = { input = it; message = null },
            label         = "Cookie ${platform.displayName}",
            placeholder   = "${platform.requiredKeys[0]}=…; ${platform.requiredKeys[1]}=…",
            singleLine    = false,
            obscure       = !reveal,
            error         = message,
            trailing      = {
                TextButton(onClick = { reveal = !reveal }) {
                    Text(if (reveal) "Ẩn" else "Hiện", color = AtColors.Accent, fontSize = 12.sp)
                }
            },
        )
        AtButton(
            text    = if (busy) "Đang lưu…" else "Lưu cookie",
            onClick = {
                if (busy) return@AtButton
                busy = true
                val raw = input
                scope.launch {
                    val result = withContext(Dispatchers.IO) { vault.save(platform, raw) }
                    when (result) {
                        is CookieSaveResult.Saved -> {
                            input = ""; reveal = false
                            message = null
                            reload()
                        }
                        is CookieSaveResult.Incomplete ->
                            message = if (result.missing.isNotEmpty()) "Thiếu khoá: ${result.missing.joinToString(", ")}"
                                      else "Không xác định được ID tài khoản từ cookie"
                        CookieSaveResult.Invalid ->
                            message = "Không đọc được cookie. Dán dạng tên=giá trị; tên=giá trị"
                        CookieSaveResult.Full ->
                            message = "Tối đa ${CookieVaultCodec.MAX_ENTRIES} cookie. Xoá bớt rồi thử lại"
                        CookieSaveResult.StorageError ->
                            message = "Không lưu được vào kho mã hoá của máy. Thử lại"
                    }
                    busy = false
                }
            },
            enabled = input.isNotBlank() && !busy,
            style   = AtButtonStyle.Tonal,
            icon    = Icons.Rounded.Add,
            height  = 44.dp,
        )

        if (items.isEmpty()) {
            Text("Chưa có cookie đã lưu.", color = AtColors.TextMuted, fontSize = 12.sp)
        } else {
            Text(
                "${items.size} tài khoản đã lưu",
                color = AtColors.TextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
            items.forEach { c ->
                CookieRow(c, onRemove = {
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { vault.remove(platform, c.accountId) }
                        if (!removed) message = "Không xoá được cookie. Thử lại"
                        reload()
                    }
                })
            }
        }
    }
}

private val SAVED_AT_FORMAT get() = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

/** Một dòng gọn: ID tài khoản · thời điểm lưu · nút xoá (không có giá trị cookie). */
@Composable
private fun CookieRow(c: CookieSummary, onRemove: () -> Unit) {
    AtCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, top = 2.dp, end = 2.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${c.platform.idKey} ${c.accountId}",
                color = AtColors.TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Lưu ${SAVED_AT_FORMAT.format(Date(c.addedAt))}",
                color = AtColors.TextMuted, fontSize = 11.sp, maxLines = 1,
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "Xoá cookie", tint = AtColors.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

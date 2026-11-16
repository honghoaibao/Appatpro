package com.atpro.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.automation.ServiceMode
import com.atpro.data.CookieCheck
import com.atpro.data.CookieDemo
import com.atpro.data.CookiePlatform
import com.atpro.ui.theme.AtBadge
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtSegmented
import com.atpro.ui.theme.AtTextField

// ─────────────────────────────────────────────────────────────────────────────
//  v1.12.5 — "Cách nuôi" cho Facebook và Instagram: Bằng app (chạy thật) · Bằng cookie (DEMO).
//
//  Bằng cookie hiện CHỈ là giao diện: dán cookie → đọc cục bộ (tên khoá + ID tài khoản, xem CookieDemo) → danh sách.
//  Chưa có đăng nhập / gọi mạng / chạy thật. Cookie là thông tin đăng nhập nên KHÔNG được lưu: chỉ nằm trong bộ nhớ của
//  màn hình này (mất khi rời màn hình) và chỉ giữ tên khoá + ID, không giữ giá trị xs / sessionid.
// ─────────────────────────────────────────────────────────────────────────────

/** Dịch vụ nào có lựa chọn "Bằng cookie"; null = không có (TikTok, X, Threads, Snapchat…). */
internal fun cookiePlatformOf(mode: ServiceMode): CookiePlatform? = when (mode) {
    ServiceMode.FACEBOOK_NURTURE  -> CookiePlatform.FACEBOOK
    ServiceMode.INSTAGRAM_NURTURE -> CookiePlatform.INSTAGRAM
    else                          -> null
}

/** Chọn cách nuôi: 0 = Bằng app, 1 = Bằng cookie (demo). */
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
        AtSegmented(options = listOf("Bằng app", "Bằng cookie · demo"), selectedIndex = selected, onSelect = onSelect)
        Text(
            if (selected == 0) "Điều khiển ứng dụng ${platform.displayName} trên máy qua Trợ năng."
            else "Dùng cookie đăng nhập, không mở ứng dụng. Bản demo, chưa chạy thật.",
            color = AtColors.TextMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Giao diện demo "nuôi bằng cookie": dán cookie → danh sách các cookie đã đọc. */
@Composable
internal fun CookieDemoPanel(platform: CookiePlatform, modifier: Modifier = Modifier) {
    var input   by remember(platform) { mutableStateOf("") }
    var reveal  by remember(platform) { mutableStateOf(false) }
    var message by remember(platform) { mutableStateOf<String?>(null) }
    val items = remember(platform) { mutableStateListOf<CookieCheck>() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Thông báo demo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AtColors.Warning.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AtBadge("DEMO", AtColors.Warning)
            Spacer(Modifier.size(10.dp))
            Text(
                "Chưa chạy thật. Cookie chỉ giữ tạm trên màn hình này, không lưu, không gửi đi.",
                color = AtColors.TextSec, fontSize = 12.sp,
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
            text    = "Thêm cookie",
            onClick = {
                val check = CookieDemo.parse(input, platform)
                when {
                    check == null ->
                        message = "Không đọc được cookie. Dán dạng tên=giá trị; tên=giá trị"
                    items.size >= CookieDemo.MAX_ITEMS ->
                        message = "Tối đa ${CookieDemo.MAX_ITEMS} cookie trong danh sách"
                    check.accountId != null && items.any { it.accountId == check.accountId } ->
                        message = "Tài khoản này đã có trong danh sách"
                    else -> { items.add(check); input = ""; message = null }
                }
            },
            enabled = input.isNotBlank(),
            style   = AtButtonStyle.Tonal,
            icon    = Icons.Rounded.Add,
            height  = 44.dp,
        )

        if (items.isEmpty()) {
            Text("Chưa có cookie.", color = AtColors.TextMuted, fontSize = 12.sp)
        } else {
            Text(
                "${items.size} cookie · ${items.count { it.isComplete }} đủ khoá",
                color = AtColors.TextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
            items.forEach { c -> CookieRow(c, onRemove = { items.remove(c) }) }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun CookieRow(c: CookieCheck, onRemove: () -> Unit) {
    AtCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    c.accountId?.let { "${c.platform.idKey} $it" } ?: "Chưa rõ tài khoản",
                    color = AtColors.TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    c.platform.requiredKeys.forEach { k ->
                        val ok = k in c.present
                        AtBadge(if (ok) "$k ✓" else "$k thiếu", if (ok) AtColors.Success else AtColors.Danger)
                    }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "Xoá cookie", tint = AtColors.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

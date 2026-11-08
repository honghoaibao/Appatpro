package com.atpro.ui.f4f

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtSkeleton
import com.atpro.ui.theme.AtColors
import com.atpro.tasks.f4f.F4fDashboard
import com.atpro.tasks.f4f.F4fEarnStats
import com.atpro.tasks.f4f.parseCoinText
import java.text.NumberFormat
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
//  Thành phần dùng chung cho các tab của Ví Follow4Follow
// ─────────────────────────────────────────────────────────────────────────────

/** 1250 → "1.250" (dấu chấm ngăn cách hàng nghìn kiểu Việt Nam). */
internal fun Long.fmt(): String = NumberFormat.getIntegerInstance(Locale("vi", "VN")).format(this)

/** Chuỗi số tiền do server trả ("1250", "1.250 coin", "12,5K"…) → số nguyên; null nếu không đọc được. */
internal fun String.digitsToLong(): Long? = parseCoinText(this)

/** Các con số ví đã gộp từ dashboard + thống kê thu nhập (mỗi nguồn có thể thiếu → lấy giá trị lớn hơn). */
internal data class WalletNumbers(
    val pending: Long, val todayCoins: Long, val todayJobs: Int, val totalCoins: Long, val totalJobs: Int,
)

internal fun walletNumbers(d: F4fDashboard, s: F4fEarnStats?): WalletNumbers {
    val pending = maxOf(parseCoinText(d.balancePending) ?: 0L, parseCoinText(s?.totalPending) ?: 0L)
    val todayStat = (parseCoinText(s?.todayApproved) ?: 0L) + (parseCoinText(s?.todayPending) ?: 0L)
    val totalStat = (parseCoinText(s?.totalApproved) ?: 0L) + (parseCoinText(s?.totalPending) ?: 0L)
    return WalletNumbers(
        pending    = pending,
        todayCoins = maxOf(parseCoinText(d.todayEarned) ?: 0L, todayStat),
        todayJobs  = maxOf(d.tasksDoneToday, s?.todayJobs ?: 0),
        totalCoins = d.totalCoin?.takeIf { it > 0L } ?: totalStat,
        totalJobs  = s?.totalJobs ?: 0,
    )
}

/** Màu theo trạng thái yêu cầu rút/duyệt coin (server trả chữ tự do nên khớp theo từ khoá). */
@Composable
internal fun statusTone(status: String): Color {
    val s = status.lowercase()
    return when {
        listOf("reject", "cancel", "fail", "từ chối", "huỷ", "hủy", "lỗi").any { it in s } -> AtColors.Danger
        listOf("success", "complete", "done", "approved", "hoàn", "thành công", "đã duyệt").any { it in s } -> AtColors.Success
        listOf("pend", "chờ", "process", "đang").any { it in s } -> AtColors.Warning
        else -> AtColors.TextMuted
    }
}

/** Hiển thị một khối dữ liệu theo trạng thái tải: đang tải / lỗi (có nút thử lại) / có dữ liệu. */
@Composable
internal fun <T> LoadBlock(
    load: F4fLoad<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (load) {
        is F4fLoad.Ok -> content(load.data)
        is F4fLoad.Fail -> AtCard(modifier.fillMaxWidth()) {
            Text(load.message, color = AtColors.Danger, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            AtButton("Thử lại", onRetry, style = AtButtonStyle.Tonal, height = 42.dp)
        }
        // v1.10.0: khối xương thay vòng xoay — giữ nguyên bố cục khi dữ liệu về, đỡ giật.
        else -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AtSkeleton(Modifier.fillMaxWidth().height(96.dp))
            AtSkeleton(Modifier.fillMaxWidth(0.6f).height(16.dp))
        }
    }
}

/** Tiêu đề nhóm nội dung trong một tab. */
@Composable
internal fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = AtColors.TextPrim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/** Cột nội dung chuẩn của một tab (khoảng cách đều giữa các khối). */
@Composable
internal fun PanelColumn(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

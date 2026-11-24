package com.atpro.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
//  v1.12.7 — Thành phần nhận diện dùng chung cho các màn đăng nhập (Supabase, Follow4Follow).
//  Chỉ vẽ: không có logic, không phụ thuộc màn hình nào. Màu lấy từ AtColors nên tự theo Sáng/Tối.
// ─────────────────────────────────────────────────────────────────────────────

/** Quầng sáng xanh mờ ở phía trên màn hình — tạo chiều sâu mà không cần ảnh nền. */
fun Modifier.atGlow(): Modifier = drawBehind {
    val radius = size.width * 1.05f
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(AtColors.Blue.copy(alpha = 0.24f), AtColors.Sky.copy(alpha = 0.08f), Color.Transparent),
            center = Offset(size.width * 0.5f, 0f),
            radius = radius,
        ),
    )
}

/** Biểu tượng trong ô gradient thương hiệu (chữ/biểu tượng trắng đọc rõ trên cả dải). */
@Composable
fun AtIconBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(AtBrush.Hero),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = AtColors.OnBlue, modifier = Modifier.size(size * 0.5f))
    }
}

/** Một ý lợi ích nhỏ: biểu tượng tròn nền nhạt + nhãn ngắn (dùng thành hàng 3 ô dưới logo). */
@Composable
fun AtFeaturePill(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(AtColors.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AtColors.Accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = text,
            color = AtColors.TextSec,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

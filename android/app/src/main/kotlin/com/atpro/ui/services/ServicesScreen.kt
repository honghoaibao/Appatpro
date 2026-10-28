package com.atpro.ui.services

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.R
import com.atpro.security.AppConstants
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtSectionLabel
import com.atpro.ui.theme.AtType
import com.atpro.ui.theme.staggerIn

// ─────────────────────────────────────────────────────────────────────────────
//  ServicesScreen
//
//  v1.4.0: thiết kế lại. 7 thẻ riêng (mỗi thẻ gradient + viền phát sáng + chip màu thương hiệu)
//  → 1 kiểu hàng duy nhất: logo · tên · mô tả 1 dòng · mũi tên. Màu thương hiệu chỉ còn ở logo.
//  Callback giữ nguyên để MainScreen không phải đổi.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ServicesScreen(
    onOpenFarmService:      () -> Unit = {},
    onOpenTaskService:      () -> Unit = {},
    onOpenFacebookService:  () -> Unit = {},
    onOpenXService:         () -> Unit = {},
    onOpenInstagramService: () -> Unit = {},
    onOpenThreadsService:   () -> Unit = {},
    onOpenSnapchatService:  () -> Unit = {},
) {
    val tasksEnabled = AppConstants.TASKS_ENABLED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text("Dịch vụ", style = AtType.Title, color = AtColors.TextPrim, modifier = Modifier.staggerIn(0))
        Spacer(Modifier.height(28.dp))

        AtSectionLabel("Chính", Modifier.staggerIn(1))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceRow(
                title    = "TikTok",
                subtitle = "Nuôi tài khoản: xem, thích, theo dõi, bình luận",
                modifier = Modifier.staggerIn(2),
                onClick  = onOpenFarmService,
            ) { PlatformLogo(R.drawable.ic_logo_tiktok, "TikTok") }

            ServiceRow(
                title    = "Nhiệm vụ kiếm tiền",
                subtitle = if (tasksEnabled) "Like / Follow theo nhiệm vụ" else "Đang chờ tích hợp nhà cung cấp",
                modifier = Modifier.staggerIn(3),
                tag      = if (tasksEnabled) null else "Sắp có",
                enabled  = tasksEnabled,
                onClick  = onOpenTaskService,
            ) {
                Icon(
                    Icons.Rounded.MonetizationOn,
                    contentDescription = null,
                    tint     = AtColors.TextSec,
                    modifier = Modifier.size(22.dp),
                )
            }

            ServiceRow(
                title    = "Facebook",
                subtitle = "Lướt feed và thích bài viết",
                modifier = Modifier.staggerIn(4),
                onClick  = onOpenFacebookService,
            ) { PlatformLogo(R.drawable.ic_logo_facebook, "Facebook") }
        }

        Spacer(Modifier.height(28.dp))

        AtSectionLabel("Thử nghiệm", Modifier.staggerIn(5))
        Spacer(Modifier.height(4.dp))
        Text(
            "Chỉ mở app, lướt feed và tương tác cơ bản. Cần cài app tương ứng và bật Trợ năng.",
            color      = AtColors.TextMuted,
            fontSize   = 12.sp,
            lineHeight = 17.sp,
            modifier   = Modifier.staggerIn(5),
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceRow(
                title    = "X",
                subtitle = "Lướt timeline, thích tweet",
                modifier = Modifier.staggerIn(6),
                tag      = "Demo",
                onClick  = onOpenXService,
            ) { PlatformLogo(R.drawable.ic_logo_x, "X") }

            ServiceRow(
                title    = "Instagram",
                subtitle = "Lướt Reels, thích bài đăng",
                modifier = Modifier.staggerIn(7),
                tag      = "Demo",
                onClick  = onOpenInstagramService,
            ) { PlatformLogo(R.drawable.ic_logo_instagram, "Instagram") }

            ServiceRow(
                title    = "Threads",
                subtitle = "Lướt feed, thích bài viết",
                modifier = Modifier.staggerIn(8),
                tag      = "Demo",
                onClick  = onOpenThreadsService,
            ) {
                Text("@", color = AtColors.TextPrim, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            ServiceRow(
                title    = "Snapchat",
                subtitle = "Xem Spotlight và Stories",
                modifier = Modifier.staggerIn(9),
                tag      = "Demo",
                onClick  = onOpenSnapchatService,
            ) { PlatformLogo(R.drawable.ic_logo_snapchat, "Snapchat") }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Thành phần
// ─────────────────────────────────────────────────────────────────────────────

/** Một dịch vụ = một hàng. [enabled] = false → mờ, không bấm được, không có mũi tên. */
@Composable
private fun ServiceRow(
    title:    String,
    subtitle: String,
    modifier: Modifier = Modifier,
    tag:      String?  = null,
    enabled:  Boolean  = true,
    onClick:  () -> Unit,
    leading:  @Composable () -> Unit,
) {
    AtCard(
        modifier       = modifier.fillMaxWidth(),
        onClick        = if (enabled) onClick else null,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AtColors.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) { leading() }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color      = if (enabled) AtColors.TextPrim else AtColors.TextSec,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color    = AtColors.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (tag != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    tag,
                    color    = AtColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, AtColors.Hairline, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (enabled) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint     = AtColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PlatformLogo(logoRes: Int, contentDesc: String) {
    Image(
        painter            = painterResource(logoRes),
        contentDescription = contentDesc,
        contentScale       = ContentScale.Fit,
        modifier           = Modifier.size(28.dp),
    )
}

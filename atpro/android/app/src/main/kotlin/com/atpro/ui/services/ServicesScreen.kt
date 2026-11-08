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
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.R
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import com.atpro.security.AppConstants
import com.atpro.tasks.f4f.F4fAuth
import com.atpro.tasks.f4f.F4fAuthState
import com.atpro.ui.tasks.F4fLoginDialog
import com.atpro.ui.f4f.F4fHubTab
import com.atpro.ui.theme.AtBadge
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtHeroCard
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtSectionLabel
import com.atpro.ui.theme.AtType
import com.atpro.ui.theme.staggerIn

// ─────────────────────────────────────────────────────────────────────────────
//  ServicesScreen
//
//  v1.4.0: một kiểu hàng duy nhất: logo · tên · mô tả 1 dòng · mũi tên. Màu thương hiệu chỉ còn ở logo.
//  v1.10.0: chữ mô tả rút gọn; "Thử nghiệm" → "Dịch vụ khác" (bỏ đoạn giải thích); bỏ lối tắt Giới thiệu.
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
    /** v1.7.0: mở màn hình Ví Follow4Follow tại tab chỉ định (null = chưa đăng nhập → hộp thoại đăng nhập). */
    onOpenF4fHub:           (F4fHubTab) -> Unit = {},
) {
    val tasksEnabled = AppConstants.TASKS_ENABLED

    // v1.6.0: dịch vụ nhiệm vụ cần đăng nhập Follow4Follow — chưa đăng nhập thì mở hộp thoại thay vì vào Dashboard.
    val context = LocalContext.current
    val f4fState by F4fAuth.manager(context).state.collectAsStateWithLifecycle()
    // 0 = đóng, 1 = đăng nhập để vào chế độ nhiệm vụ, 2 = đăng nhập để mở Ví
    var loginIntent by remember { mutableStateOf(0) }
    val f4fUser = (f4fState as? F4fAuthState.LoggedIn)
    val taskSubtitle = when {
        !tasksEnabled -> "Sắp có"
        f4fUser == null -> "Follow4Follow"
        else -> "Follow4Follow · ${f4fUser.username.ifBlank { "đã đăng nhập" }}" +
                (f4fUser.balance?.let { " · $it" } ?: "")
    }

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
        Spacer(Modifier.height(18.dp))

        // v1.7.0: thẻ Ví Follow4Follow — số dư + lối tắt tới Xếp hạng / Rút tiền.
        if (tasksEnabled) {
            AtHeroCard(
                modifier = Modifier.fillMaxWidth().staggerIn(1),
                onClick  = { if (f4fUser == null) loginIntent = 2 else onOpenF4fHub(F4fHubTab.OVERVIEW) },
            ) {
                Text("Ví Follow4Follow", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (f4fUser == null) "Đăng nhập" else (f4fUser.balance ?: "—"),
                    style = AtType.Title.copy(fontSize = 28.sp), color = Color.White,
                )
                if (f4fUser != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "@${f4fUser.username.ifBlank { "tài khoản" }}" +
                            (f4fUser.balancePending?.let { " · chờ $it" } ?: ""),
                        color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WalletShortcut("Xếp hạng", Icons.Rounded.EmojiEvents) { onOpenF4fHub(F4fHubTab.LEADERBOARD) }
                        WalletShortcut("Rút tiền", Icons.Rounded.AccountBalanceWallet) { onOpenF4fHub(F4fHubTab.WITHDRAW) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        AtSectionLabel("Chính", Modifier.staggerIn(1))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceRow(
                title    = "TikTok",
                subtitle = "Nuôi tài khoản",
                modifier = Modifier.staggerIn(2),
                onClick  = onOpenFarmService,
            ) { PlatformLogo(R.drawable.ic_logo_tiktok, "TikTok") }

            ServiceRow(
                title    = "Nhiệm vụ kiếm tiền",
                subtitle = taskSubtitle,
                modifier = Modifier.staggerIn(3),
                tag      = when {
                    !tasksEnabled   -> "Sắp có"
                    f4fUser == null -> "Đăng nhập"
                    else            -> null
                },
                enabled  = tasksEnabled,
                onClick  = { if (f4fUser == null) loginIntent = 1 else onOpenTaskService() },
            ) {
                Icon(
                    Icons.Rounded.MonetizationOn,
                    contentDescription = null,
                    tint     = AtColors.Accent,
                    modifier = Modifier.size(22.dp),
                )
            }

            ServiceRow(
                title    = "Facebook",
                subtitle = "Nuôi tài khoản",
                modifier = Modifier.staggerIn(4),
                onClick  = onOpenFacebookService,
            ) { PlatformLogo(R.drawable.ic_logo_facebook, "Facebook") }
        }

        Spacer(Modifier.height(28.dp))

        AtSectionLabel("Dịch vụ khác", Modifier.staggerIn(5))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceRow(
                title    = "X",
                subtitle = "Lướt và thích",
                modifier = Modifier.staggerIn(6),
                onClick  = onOpenXService,
            ) { PlatformLogo(R.drawable.ic_logo_x, "X") }

            ServiceRow(
                title    = "Instagram",
                subtitle = "Lướt và thích",
                modifier = Modifier.staggerIn(7),
                onClick  = onOpenInstagramService,
            ) { PlatformLogo(R.drawable.ic_logo_instagram, "Instagram") }

            ServiceRow(
                title    = "Threads",
                subtitle = "Lướt và thích",
                modifier = Modifier.staggerIn(8),
                onClick  = onOpenThreadsService,
            ) {
                Text("@", color = AtColors.TextPrim, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            ServiceRow(
                title    = "Snapchat",
                subtitle = "Xem Spotlight",
                modifier = Modifier.staggerIn(9),
                onClick  = onOpenSnapchatService,
            ) { PlatformLogo(R.drawable.ic_logo_snapchat, "Snapchat") }
        }

        Spacer(Modifier.height(32.dp))
    }

    if (loginIntent != 0) {
        F4fLoginDialog(
            onDismiss  = { loginIntent = 0 },
            onLoggedIn = { if (loginIntent == 2) onOpenF4fHub(F4fHubTab.OVERVIEW) else onOpenTaskService() },
        )
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
                AtBadge(tag, if (tag == "Đăng nhập") AtColors.Accent else AtColors.TextMuted)
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

/** Nút lối tắt nhỏ nằm trong thẻ ví (nền trắng mờ trên nền gradient). */
@Composable
private fun WalletShortcut(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

package com.atpro.ui.services

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Palette ──────────────────────────────────────────────────────────────────
private val BgDark      = Color(0xFF0D0D14)
private val BgCard      = Color(0xFF141420)
private val BorderDark  = Color(0xFF252538)
private val Purple      = Color(0xFF6C63FF)
private val TextPrim    = Color(0xFFEEEEF5)
private val TextSec     = Color(0xFF9CA3AF)
private val TextMuted   = Color(0xFF5C5C78)
private val TikTokBlack = Color(0xFF010101)
private val TikTokRed   = Color(0xFFFE2C55)
private val TikTokCyan  = Color(0xFF69C9D0)
private val GolikeGold  = Color(0xFFF5A623)
private val GolikeAmber = Color(0xFFFF6B35)
private val GolikeDark  = Color(0xFF1A1205)
private val FacebookBlue = Color(0xFF1877F2)   // v1.2.3
private val XBlack       = Color(0xFF14171A)   // v1.2.4 X (Twitter)
private val XGray        = Color(0xFF536471)
private val InstaGradA   = Color(0xFF833AB4)   // v1.2.4 Instagram purple
private val InstaGradB   = Color(0xFFFD1D1D)   // Instagram red
private val InstaGradC   = Color(0xFFF77737)   // Instagram orange
private val ThreadsBlack = Color(0xFF101010)   // v1.2.4 Threads
private val SnapYellow   = Color(0xFFFFFC00)   // v1.2.4 Snapchat

// ─────────────────────────────────────────────────────────────────────────────
//  ServicesScreen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * v1.2.1: Thêm callbacks để điều hướng tới Dashboard với chế độ tương ứng.
 * v1.2.2: Thêm onGolikeLogout; block task service khi chưa đăng nhập.
 * v1.2.4: Thêm callbacks cho X, Instagram, Threads, Snapchat demo.
 */
@Composable
fun ServicesScreen(
    onOpenFarmService:     () -> Unit  = {},
    onOpenTaskService:     () -> Unit  = {},
    onOpenFacebookService: () -> Unit  = {},
    onOpenXService:        () -> Unit  = {},
    onOpenInstagramService:() -> Unit  = {},
    onOpenThreadsService:  () -> Unit  = {},
    onOpenSnapchatService: () -> Unit  = {},
    onOpenGolikeLogin:     () -> Unit  = {},
    onGolikeLogout:        () -> Unit  = {},
    isGolikeLoggedIn:      Boolean     = false,
    golikeDisplayName:     String      = "",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ServicesHeader()

        // ── Dịch vụ TikTok ──────────────────────────────────────────────────
        ServiceGroup(
            title  = "Dịch vụ TikTok",
            icon   = Icons.Rounded.AccountCircle,
            accent = TikTokCyan,
        ) {
            // Card 1: Nuôi acc
            TikTokFarmCard(onClick = onOpenFarmService)
            // Card 2: Làm nhiệm vụ (v1.2.1 mới) — v1.2.2: block khi chưa đăng nhập
            TikTokTaskCard(
                onClick          = if (isGolikeLoggedIn) onOpenTaskService else onOpenGolikeLogin,
                isGolikeLoggedIn = isGolikeLoggedIn,
            )
        }

        // ── Demo nuôi acc khác [v1.2.3/v1.2.4] ──────────────────────────────────────
        ServiceGroup(
            title  = "Demo nuôi tài khoản khác",
            icon   = Icons.Rounded.ThumbUp,
            accent = FacebookBlue,
        ) {
            DemoNoticeBanner()
            FacebookNurtureCard(onClick = onOpenFacebookService)
            XNurtureCard(onClick = onOpenXService)
            InstagramNurtureCard(onClick = onOpenInstagramService)
            ThreadsNurtureCard(onClick = onOpenThreadsService)
            SnapchatNurtureCard(onClick = onOpenSnapchatService)
        }

        // ── Dịch vụ kiếm tiền ───────────────────────────────────────────────
        ServiceGroup(
            title  = "Tài khoản Golike",
            icon   = Icons.Rounded.AttachMoney,
            accent = GolikeGold,
        ) {
            GolikeAccountCard(
                isLoggedIn    = isGolikeLoggedIn,
                displayName   = golikeDisplayName,
                onLoginClick  = onOpenGolikeLogin,
                onLogoutClick = onGolikeLogout,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ServicesHeader() {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text          = "Dịch vụ",
            color         = TextPrim,
            fontSize      = 26.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 0.3.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text     = "Chọn dịch vụ bạn muốn sử dụng",
            color    = TextMuted,
            fontSize = 13.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Service group wrapper
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ServiceGroup(
    title:   String,
    icon:    ImageVector,
    accent:  Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent),
            )
            Text(
                text          = title,
                color         = accent,
                fontSize      = 11.sp,
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Card: Nuôi tài khoản TikTok
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TikTokFarmCard(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label         = "farm_card_scale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to TikTokBlack,
                        0.55f to Color(0xFF0D0D0D),
                        0.85f to TikTokCyan.copy(alpha = 0.18f),
                        1.0f to TikTokRed.copy(alpha = 0.22f),
                    ),
                )
            )
            .border(
                width  = 1.dp,
                brush  = Brush.horizontalGradient(
                    listOf(TikTokCyan.copy(alpha = 0.35f), TikTokRed.copy(alpha = 0.25f))
                ),
                shape  = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick, onClickLabel = "Mở nuôi tài khoản TikTok")
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TikTokIconBox()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text          = "Nuôi tài khoản TikTok",
                        color         = Color.White,
                        fontSize      = 16.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 0.2.sp,
                    )
                    Text(
                        text     = "Tự động tương tác & tăng trưởng",
                        color    = TextSec,
                        fontSize = 12.sp,
                    )
                }
                ActiveBadge()
            }

            HorizontalDivider(TikTokCyan)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServiceChip("Xem video", TikTokCyan)
                ServiceChip("Like", TikTokRed)
                ServiceChip("Follow", Color(0xFF9D92F5))
            }

            OpenServiceRow(TikTokCyan)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Card: Làm nhiệm vụ TikTok (v1.2.1 mới)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TikTokTaskCard(
    onClick:          () -> Unit,
    isGolikeLoggedIn: Boolean,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label         = "task_card_scale",
    )

    val AccentTask = Color(0xFF7C3AED)  // violet
    val AccentTask2 = Color(0xFF4F46E5) // indigo

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF0A0A18),
                        0.6f to Color(0xFF0D0D1C),
                        1.0f to AccentTask.copy(alpha = 0.15f),
                    ),
                )
            )
            .border(
                width  = 1.dp,
                brush  = Brush.horizontalGradient(
                    listOf(AccentTask.copy(alpha = 0.4f), AccentTask2.copy(alpha = 0.2f))
                ),
                shape  = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick, onClickLabel = "Mở làm nhiệm vụ TikTok")
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Task icon
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(AccentTask.copy(alpha = 0.8f), AccentTask2.copy(alpha = 0.6f))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AssignmentTurnedIn,
                        contentDescription = null,
                        tint     = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text          = "Làm nhiệm vụ TikTok",
                        color         = Color(0xFFD4BCFC),
                        fontSize      = 16.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 0.2.sp,
                    )
                    Text(
                        text     = "Kiếm coin Golike qua tim & follow",
                        color    = TextSec,
                        fontSize = 12.sp,
                    )
                }

                // Login required badge if not logged in
                if (!isGolikeLoggedIn) {
                    Surface(
                        shape  = RoundedCornerShape(20.dp),
                        color  = Color(0xFFF59E0B).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text       = "Cần đăng nhập",
                            color      = Color(0xFFF59E0B),
                            fontSize   = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier   = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                } else {
                    ActiveBadge()
                }
            }

            HorizontalDivider(AccentTask)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServiceChip("Tim video", TikTokRed)
                ServiceChip("Follow", Color(0xFF9D92F5))
                ServiceChip("Golike", GolikeGold)
            }

            // v1.2.2: Khi chưa login → nút chỉ tới trang đăng nhập Golike
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier              = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text       = if (isGolikeLoggedIn) "Mở dịch vụ" else "Đăng nhập Golike",
                    color      = if (isGolikeLoggedIn) AccentTask else Color(0xFFF5A623),
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector        = Icons.Rounded.ArrowForwardIos,
                    contentDescription = null,
                    tint               = if (isGolikeLoggedIn) AccentTask else Color(0xFFF5A623),
                    modifier           = Modifier.size(12.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Banner lưu ý demo [v1.2.4]
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DemoNoticeBanner() {
    val Amber = Color(0xFFF59E0B)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Amber.copy(alpha = 0.08f))
            .border(0.5.dp, Amber.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Rounded.InfoOutlined,
            contentDescription = null,
            tint     = Amber,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Chế độ Demo",
                color      = Amber,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Các nền tảng dưới đây ở chế độ Demo — mở app, lướt feed và tương tác cơ bản. " +
                "Cần cài đặt app tương ứng trên thiết bị. Accessibility Service phải đang bật.",
                color      = Amber.copy(alpha = 0.8f),
                fontSize   = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Card: Demo nuôi X (Twitter) [v1.2.4]
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun XNurtureCard(onClick: () -> Unit) {
    val AccentX = Color(0xFFE7E9EA)  // X text color (light on dark)
    PlatformDemoCard(
        onClick     = onClick,
        bgFrom      = XBlack,
        bgTo        = Color(0xFF1D9BF0).copy(alpha = 0.10f),
        borderColor = Color(0xFF536471),
        iconContent = {
            // X logo — simple X mark
            Text("𝕏", color = AccentX, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        title       = "Nuôi tài khoản X",
        subtitle    = "Lướt timeline & like tweet (demo)",
        titleColor  = AccentX,
        accent      = Color(0xFF1D9BF0),
        chips       = listOf("Like tweet" to Color(0xFF1D9BF0), "Lướt timeline" to XGray, "Repost" to Color(0xFF00BA7C)),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Card: Demo nuôi Instagram [v1.2.4]
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InstagramNurtureCard(onClick: () -> Unit) {
    val igAccent = InstaGradA
    PlatformDemoCard(
        onClick     = onClick,
        bgFrom      = Color(0xFF0D0818),
        bgTo        = InstaGradA.copy(alpha = 0.12f),
        borderColor = InstaGradA.copy(alpha = 0.35f),
        iconContent = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.linearGradient(listOf(InstaGradA, InstaGradB, InstaGradC))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.CameraAlt, null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
        },
        title       = "Nuôi tài khoản Instagram",
        subtitle    = "Lướt Reels & like bài đăng (demo)",
        titleColor  = Color(0xFFF9C8FF),
        accent      = igAccent,
        chips       = listOf("Reels" to InstaGradA, "Like" to InstaGradB, "Follow" to InstaGradC),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Card: Demo nuôi Threads [v1.2.4]
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ThreadsNurtureCard(onClick: () -> Unit) {
    val tAccent = Color(0xFFCCCCCC)
    PlatformDemoCard(
        onClick     = onClick,
        bgFrom      = ThreadsBlack,
        bgTo        = Color(0xFF333333).copy(alpha = 0.20f),
        borderColor = Color(0xFF444444),
        iconContent = {
            Text("@", color = tAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        },
        title       = "Nuôi tài khoản Threads",
        subtitle    = "Lướt feed & like bài viết (demo)",
        titleColor  = tAccent,
        accent      = Color(0xFFAAAAAA),
        chips       = listOf("Lướt feed" to Color(0xFF888888), "Like" to Color(0xFFCC4444), "Threads" to Color(0xFFAAAAAA)),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Card: Demo nuôi Snapchat [v1.2.4]
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SnapchatNurtureCard(onClick: () -> Unit) {
    PlatformDemoCard(
        onClick     = onClick,
        bgFrom      = Color(0xFF141200),
        bgTo        = SnapYellow.copy(alpha = 0.12f),
        borderColor = SnapYellow.copy(alpha = 0.40f),
        iconContent = {
            Icon(Icons.Rounded.CameraAlt, null, tint = SnapYellow, modifier = Modifier.size(22.dp))
        },
        title       = "Nuôi tài khoản Snapchat",
        subtitle    = "Xem Spotlight & Stories (demo)",
        titleColor  = SnapYellow,
        accent      = SnapYellow,
        chips       = listOf("Spotlight" to SnapYellow, "Stories" to SnapYellow.copy(alpha = 0.7f), "Xem story" to Color(0xFFFFA500)),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared demo card template [v1.2.4]
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlatformDemoCard(
    onClick:     () -> Unit,
    bgFrom:      Color,
    bgTo:        Color,
    borderColor: Color,
    iconContent: @Composable () -> Unit,
    title:       String,
    subtitle:    String,
    titleColor:  Color,
    accent:      Color,
    chips:       List<Pair<String, Color>>,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label         = "demo_card_scale_$title",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(bgFrom, bgTo)))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) { iconContent() }

                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = titleColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = TextSec, fontSize = 12.sp)
                }

                // DEMO badge
                Surface(
                    shape  = RoundedCornerShape(20.dp),
                    color  = accent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
                ) {
                    Text(
                        "DEMO",
                        color      = accent,
                        fontSize   = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier   = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }

            HorizontalDivider(accent)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { (label, color) -> ServiceChip(label, color) }
            }

            OpenServiceRow(accent)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Card: Demo nuôi tài khoản Facebook (v1.2.3)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FacebookNurtureCard(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label         = "fb_card_scale",
    )

    val AccentFb2 = Color(0xFF42A5F5) // lighter blue

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF0A0E18),
                        0.6f to Color(0xFF0D131C),
                        1.0f to FacebookBlue.copy(alpha = 0.18f),
                    ),
                )
            )
            .border(
                width  = 1.dp,
                brush  = Brush.horizontalGradient(
                    listOf(FacebookBlue.copy(alpha = 0.4f), AccentFb2.copy(alpha = 0.2f))
                ),
                shape  = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick, onClickLabel = "Mở demo nuôi tài khoản Facebook")
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(FacebookBlue.copy(alpha = 0.85f), AccentFb2.copy(alpha = 0.6f))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ThumbUp,
                        contentDescription = null,
                        tint     = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text          = "Nuôi tài khoản Facebook",
                        color         = Color(0xFFBFDBFE),
                        fontSize      = 16.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 0.2.sp,
                    )
                    Text(
                        text     = "Lướt feed & thích bài viết tự động (demo)",
                        color    = TextSec,
                        fontSize = 12.sp,
                    )
                }
                ActiveBadge()
            }

            HorizontalDivider(FacebookBlue)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServiceChip("Mở Facebook", FacebookBlue)
                ServiceChip("Lướt feed", AccentFb2)
                ServiceChip("Like", TikTokRed)
            }

            OpenServiceRow(FacebookBlue)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Card: Tài khoản Golike (v1.2.1 — thay thế "Đang phát triển")
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GolikeAccountCard(
    isLoggedIn:    Boolean,
    displayName:   String,
    onLoginClick:  () -> Unit,
    onLogoutClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to GolikeDark,
                        0.5f to Color(0xFF1A1008),
                        0.85f to GolikeGold.copy(alpha = 0.10f),
                        1.0f to GolikeAmber.copy(alpha = 0.06f),
                    ),
                )
            )
            .border(
                width  = 1.dp,
                brush  = Brush.horizontalGradient(
                    listOf(GolikeGold.copy(alpha = 0.25f), GolikeAmber.copy(alpha = 0.12f))
                ),
                shape  = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(GolikeGold.copy(alpha = 0.85f), GolikeAmber.copy(alpha = 0.5f))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CurrencyExchange,
                        contentDescription = null,
                        tint     = Color.Black,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = "Golike",
                        color      = Color(0xFFF5D78A),
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text     = if (isLoggedIn) "Đã đăng nhập — $displayName"
                                   else "Chưa đăng nhập",
                        color    = if (isLoggedIn) Color(0xFF10B981) else TextMuted,
                        fontSize = 12.sp,
                    )
                }

                // Status badge
                if (isLoggedIn) {
                    Surface(
                        shape  = RoundedCornerShape(20.dp),
                        color  = Color(0xFF10B981).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text       = "Kết nối",
                            color      = Color(0xFF10B981),
                            fontSize   = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier   = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            HorizontalDivider(GolikeGold)

            // Description
            Text(
                text       = if (isLoggedIn)
                    "Tài khoản Golike đã kết nối. Bạn có thể làm nhiệm vụ TikTok để kiếm coin."
                else
                    "Đăng nhập Golike để làm nhiệm vụ TikTok tự động và nhận coin thưởng.",
                color      = TextSec,
                fontSize   = 12.sp,
                lineHeight = 18.sp,
            )

            if (isLoggedIn) {
                // Khi đã đăng nhập: nút đăng nhập lại + nút đăng xuất
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick  = onLoginClick,
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = GolikeGold.copy(alpha = 0.15f),
                            contentColor   = GolikeGold,
                        ),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Đăng nhập lại", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Button(
                        onClick  = onLogoutClick,
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444).copy(alpha = 0.12f),
                            contentColor   = Color(0xFFEF4444),
                        ),
                    ) {
                        Icon(Icons.Rounded.Logout, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Đăng xuất", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            } else {
                // Chưa đăng nhập: nút đăng nhập
                Button(
                    onClick  = onLoginClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = GolikeGold.copy(alpha = 0.9f),
                        contentColor   = Color.Black,
                    ),
                ) {
                    Icon(Icons.Rounded.Login, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Đăng nhập Golike", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TikTokIconBox() {
    Box(modifier = Modifier.size(38.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .offset(x = 4.dp, y = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TikTokRed.copy(alpha = 0.6f)),
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TikTokCyan.copy(alpha = 0.7f)),
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .offset(x = 2.dp, y = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint     = TikTokBlack,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ActiveBadge() {
    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = Color(0xFF10B981).copy(alpha = 0.18f),
        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
    ) {
        Text(
            text       = "Hoạt động",
            color      = Color(0xFF10B981),
            fontSize   = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 1,
            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun HorizontalDivider(accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.3f), Color.Transparent)
                )
            ),
    )
}

@Composable
private fun OpenServiceRow(accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text       = "Mở dịch vụ",
            color      = accent,
            fontSize   = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector        = Icons.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint               = accent,
            modifier           = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun ServiceChip(label: String, accent: Color) {
    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = accent.copy(alpha = 0.10f),
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.3f)),
    ) {
        Text(
            text     = label,
            color    = accent.copy(alpha = 0.9f),
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

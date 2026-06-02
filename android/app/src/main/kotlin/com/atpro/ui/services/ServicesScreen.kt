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

// ── Palette (đồng bộ với MainScreen / DashboardScreen) ─────────────────────
private val BgDark      = Color(0xFF0D0D14)
private val BgCard      = Color(0xFF141420)
private val BorderDark  = Color(0xFF252538)
private val Purple      = Color(0xFF6C63FF)
private val TextPrim    = Color(0xFFEEEEF5)
private val TextSec     = Color(0xFF9CA3AF)
private val TextMuted   = Color(0xFF5C5C78)

// ── TikTok brand colours ────────────────────────────────────────────────────
private val TikTokBlack = Color(0xFF010101)
private val TikTokRed   = Color(0xFFFE2C55)
private val TikTokCyan  = Color(0xFF69C9D0)

// ── Golike colours ──────────────────────────────────────────────────────────
private val GolikeGold  = Color(0xFFF5A623)
private val GolikeAmber = Color(0xFFFF6B35)
private val GolikeDark  = Color(0xFF1A1205)

// ─────────────────────────────────────────────────────────────────────────────
//  ServicesScreen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ServicesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        ServicesHeader()

        // ── Dịch vụ nuôi tài khoản ──────────────────────────────────────────
        ServiceGroup(
            title = "Dịch vụ nuôi tài khoản",
            icon  = Icons.Rounded.AccountCircle,
            accent = TikTokCyan,
        ) {
            TikTokFarmCard()
        }

        // ── Dịch vụ kiếm tiền ───────────────────────────────────────────────
        ServiceGroup(
            title  = "Dịch vụ kiếm tiền",
            icon   = Icons.Rounded.AttachMoney,
            accent = GolikeGold,
        ) {
            GolikeEarnCard()
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
            text       = "Dịch vụ",
            color      = TextPrim,
            fontSize   = 26.sp,
            fontWeight = FontWeight.Bold,
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
        // Group label
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
                text       = title,
                color      = accent,
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
        // Cards inside group
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
private fun TikTokFarmCard() {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label         = "tiktok_card_scale",
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
                    listOf(
                        TikTokCyan.copy(alpha = 0.35f),
                        TikTokRed.copy(alpha = 0.25f),
                    )
                ),
                shape  = RoundedCornerShape(16.dp),
            )
            .clickable(
                onClick = { /* TODO: navigate to TikTok farm */ },
                onClickLabel = "Mở nuôi tài khoản TikTok",
            )
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Logo row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // TikTok icon placeholder (stacked cyan + red)
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

                // [v1.1.7] weight(1f) → Column takes available space, badge stays natural width
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = "Nuôi tài khoản TikTok",
                        color      = Color.White,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp,
                    )
                    Text(
                        text     = "Tự động tương tác & tăng trưởng",
                        color    = TextSec,
                        fontSize = 12.sp,
                    )
                }

                // Active badge
                Surface(
                    shape  = RoundedCornerShape(20.dp),
                    color  = Color(0xFF10B981).copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
                ) {
                    Text(
                        text     = "Hoạt động",
                        color    = Color(0xFF10B981),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                TikTokCyan.copy(alpha = 0.3f),
                                TikTokRed.copy(alpha = 0.15f),
                                Color.Transparent,
                            )
                        )
                    ),
            )

            // Feature chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ServiceChip("Xem video", TikTokCyan)
                ServiceChip("Like", TikTokRed)
                ServiceChip("Follow", Color(0xFF9D92F5))
            }

            // CTA
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text     = "Mở dịch vụ",
                    color    = TikTokCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector        = Icons.Rounded.ArrowForwardIos,
                    contentDescription = null,
                    tint               = TikTokCyan,
                    modifier           = Modifier.size(12.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Card: Kiếm tiền Golike - TikTok
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GolikeEarnCard() {
    // [v1.1.7] Press scale animation — đồng bộ với TikTokFarmCard
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label         = "golike_card_scale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to GolikeDark,
                        0.5f to Color(0xFF1A1008),
                        0.85f to GolikeGold.copy(alpha = 0.12f),
                        1.0f to GolikeAmber.copy(alpha = 0.08f),
                    ),
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        GolikeGold.copy(alpha = 0.25f),
                        GolikeAmber.copy(alpha = 0.15f),
                    )
                ),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(
                onClick = { /* TODO: navigate to Golike */ },
                onClickLabel = "Mở kiếm tiền Golike",
            )
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Logo row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Golike icon
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(GolikeGold.copy(alpha = 0.85f), GolikeAmber.copy(alpha = 0.6f))
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

                // [v1.1.7] weight(1f) → Column takes available space, badge stays natural width
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text       = "Kiếm tiền Golike",
                            color      = Color(0xFFF5D78A),
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        // "TikTok" label nhỏ
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = TikTokRed.copy(alpha = 0.2f),
                        ) {
                            Text(
                                text     = "TikTok",
                                color    = TikTokRed,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text     = "Hoàn thành nhiệm vụ, nhận thưởng",
                        color    = TextSec,
                        fontSize = 12.sp,
                    )
                }

                // "Đang phát triển" badge
                Surface(
                    shape  = RoundedCornerShape(20.dp),
                    color  = GolikeGold.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, GolikeGold.copy(alpha = 0.35f)),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Build,
                            contentDescription = null,
                            tint     = GolikeGold,
                            modifier = Modifier.size(9.dp),
                        )
                        Text(
                            text       = "Phát triển",
                            color      = GolikeGold,
                            fontSize   = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                        )
                    }
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                GolikeGold.copy(alpha = 0.25f),
                                Color.Transparent,
                            )
                        )
                    ),
            )

            // Description
            Text(
                text     = "Tính năng đang được phát triển và sẽ sớm ra mắt trong phiên bản tiếp theo.",
                color    = TextMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ServiceChip(label: String, accent: Color) {
    // [v1.1.7] Alpha pulse on press — chips are display-only, scale would look odd at small size
    var pressed by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue   = if (pressed) 0.6f else 1f,
        animationSpec = tween(100),
        label         = "chip_alpha",
    )

    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = accent.copy(alpha = 0.10f),
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.3f)),
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .clickable(
                onClick = {},
                onClickLabel = label,
            ),
    ) {
        Text(
            text     = label,
            color    = accent.copy(alpha = 0.9f),
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

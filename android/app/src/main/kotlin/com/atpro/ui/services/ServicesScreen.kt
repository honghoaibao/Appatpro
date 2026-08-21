package com.atpro.ui.services

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.R
import kotlinx.coroutines.launch

// ── Palette ──────────────────────────────────────────────────────────────────
private val BgDark      = Color(0xFF0D0D14)
private val BgCard      = Color(0xFF141420)
private val BorderDark  = Color(0xFF252538)
private val Purple      = Color(0xFF7C6FE0)
private val TextPrim    = Color(0xFFEEEEF5)
private val TextSec     = Color(0xFF9CA3AF)
private val TextMuted   = Color(0xFF5C5C78)
private val TikTokBlack = Color(0xFF010101)
private val TikTokRed   = Color(0xFFFE2C55)
private val TikTokCyan  = Color(0xFF69C9D0)
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
 * v1.2.7: Xoá Golike UI (backend giữ nguyên).
 */
@Composable
fun ServicesScreen(
    // v1.2.9: Golike state + callbacks
    isGolikeLoggedIn:     Boolean    = false,
    golikeCoin:           Double     = 0.0,
    golikeHoldCoin:       Double     = 0.0,
    onSaveGolikeToken:    (String) -> Unit = {},
    onClearGolikeToken:   () -> Unit = {},
    onOpenGolikeWebLogin: () -> Unit = {},
    onOpenFarmService:    () -> Unit = {},
    onOpenTaskService:    () -> Unit = {},
    onOpenFacebookService:() -> Unit = {},
    onOpenXService:       () -> Unit = {},
    onOpenInstagramService:() -> Unit = {},
    onOpenThreadsService: () -> Unit = {},
    onOpenSnapchatService:() -> Unit = {},
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
        }

        // ── Dịch vụ Facebook — v1.2.9: chính thức, tách khỏi nhóm Demo ───────
        ServiceGroup(
            title  = "Dịch vụ Facebook",
            icon   = Icons.Rounded.ThumbUp,
            accent = FacebookBlue,
        ) {
            FacebookNurtureCard(onClick = onOpenFacebookService)
        }

        // ── Golike TikTok — ẩn khi GOLIKE_ENABLED = false ────────────────────
        if (com.atpro.security.AppConstants.GOLIKE_ENABLED) {
            ServiceGroup(
                title  = "Golike TikTok",
                icon   = Icons.Rounded.MonetizationOn,
                accent = Purple,
            ) {
                GolikeTikTokCard(
                    isLoggedIn     = isGolikeLoggedIn,
                    coin           = golikeCoin,
                    holdCoin       = golikeHoldCoin,
                    onSaveToken    = onSaveGolikeToken,
                    onClearToken   = onClearGolikeToken,
                    onOpenWebLogin = onOpenGolikeWebLogin,
                    onStartTask    = onOpenTaskService,
                )
            }
        }

        // ── Demo nuôi acc khác [v1.2.3/v1.2.4] ──────────────────────────────────────
        ServiceGroup(
            title  = "Demo nuôi tài khoản khác",
            icon   = Icons.Rounded.Apps,
            accent = Color(0xFF9CA3AF),
        ) {
            DemoNoticeBanner()
            XNurtureCard(onClick = onOpenXService)
            InstagramNurtureCard(onClick = onOpenInstagramService)
            ThreadsNurtureCard(onClick = onOpenThreadsService)
            SnapchatNurtureCard(onClick = onOpenSnapchatService)
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
//  v1.2.9: Hiệu ứng "được chọn" — mỗi dịch vụ có glow/pop riêng theo màu accent
//  của nó khi người dùng bấm mở. Không chặn điều hướng (onClick gọi ngay),
//  glow chạy song song trong lúc chuyển màn hình.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun rememberSelectionGlow(): Pair<Float, () -> Unit> {
    val glow  = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val trigger: () -> Unit = {
        scope.launch {
            glow.snapTo(1f)
            glow.animateTo(0f, animationSpec = tween(420, easing = FastOutSlowInEasing))
        }
    }
    return glow.value to trigger
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
    val (glowAlpha, triggerGlow) = rememberSelectionGlow()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f  to Color(0xFF0A0A0F),
                        0.40f to TikTokBlack,
                        0.75f to TikTokCyan.copy(alpha = 0.12f),
                        1.0f  to TikTokRed.copy(alpha = 0.18f),
                    ),
                )
            )
            // v1.2.9: glow overlay khi vừa được chọn — màu TikTok cyan
            .background(TikTokCyan.copy(alpha = glowAlpha * 0.22f))
            .border(
                width = (0.8f + glowAlpha * 1.4f).dp,
                brush = Brush.linearGradient(
                    listOf(
                        TikTokCyan.copy(alpha = 0.50f + glowAlpha * 0.5f),
                        TikTokRed.copy(alpha = 0.30f + glowAlpha * 0.4f),
                    )
                ),
                shape = RoundedCornerShape(20.dp),
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { triggerGlow(); onClick() },
                )
            }
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        // Glow subtle ở góc phải
        Box(
            modifier = Modifier
                .size(130.dp)
                .offset(x = 60.dp, y = (-30).dp)
                .align(Alignment.TopEnd)
                .background(
                    Brush.radialGradient(
                        listOf(TikTokCyan.copy(alpha = 0.08f), Color.Transparent)
                    )
                )
        )

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Row 1: Icon + Title + Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // v1.2.7: TikTok icon box — logo fill toàn bộ, bg đen
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TikTokBlack)
                        .border(
                            0.5.dp,
                            Brush.linearGradient(listOf(TikTokCyan.copy(alpha = 0.5f), TikTokRed.copy(alpha = 0.4f))),
                            RoundedCornerShape(14.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    TikTokIconBox()
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text          = "Nuôi tài khoản TikTok",
                        color         = Color.White,
                        fontSize      = 16.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 0.3.sp,
                    )
                    Text(
                        text     = "Tự động tương tác · tăng trưởng tự nhiên",
                        color    = TextSec,
                        fontSize = 11.sp,
                    )
                }
                ActiveBadge()
            }

            // Divider gradient
            Box(
                Modifier.fillMaxWidth().height(0.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(TikTokCyan.copy(alpha = 0.5f), TikTokRed.copy(alpha = 0.3f), Color.Transparent)
                        )
                    )
            )

            // Row 2: Feature chips
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ServiceChip("Xem video", TikTokCyan)
                ServiceChip("Like", TikTokRed)
                ServiceChip("Follow", Color(0xFF9D92F5))
                ServiceChip("Comment", Color(0xFF34D399))
            }

            // Row 3: CTA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text       = "Mở dịch vụ",
                    color      = TikTokCyan,
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.ArrowForwardIos,
                    contentDescription = null,
                    tint     = TikTokCyan,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Banner lưu ý demo [v1.2.4]
// ─────────────────────────────────────────────────────────────────────────────
//  v1.2.9: Golike TikTok card — nhập athu, xem số dư, bắt đầu nhiệm vụ
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GolikeTikTokCard(
    isLoggedIn:   Boolean,
    coin:         Double,
    holdCoin:     Double,
    onSaveToken:  (String) -> Unit,
    onClearToken: () -> Unit,
    onOpenWebLogin: () -> Unit,
    onStartTask:  () -> Unit,
) {
    val Green      = Color(0xFF10B981)
    val Amber      = Color(0xFFF59E0B)
    var tokenInput by remember { mutableStateOf("") }
    // v1.3.4: mặc định KHÔNG hiện ô nhập token thủ công nữa — đăng nhập qua
    // WebView là đường chính, ô nhập tay chỉ hiện khi người dùng chủ động chọn.
    var showInput  by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 5.dp, shape = RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.4f))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF181828), Color(0xFF141420))))
            .border(1.dp, if (isLoggedIn) Green.copy(0.35f) else Purple.copy(0.25f), RoundedCornerShape(18.dp)),
    ) {
      // v1.3.4: viền sáng mảnh ở mép trên — cùng hiệu ứng "ánh phản chiếu
      // kính" dùng ở SettingCard (ConfigScreen) để đồng bộ ngôn ngữ hình ảnh
      // giữa card Golike và toàn bộ phần Cài đặt.
      Box(
          modifier = Modifier
              .fillMaxWidth()
              .height(1.dp)
              .align(Alignment.TopCenter)
              .background(
                  Brush.horizontalGradient(
                      listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                  ),
              ),
      )
      Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v1.3.4: icon trong khối gradient bo góc thay vì icon trần —
            // đồng bộ với SectionTitle bên Cài đặt.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(Purple.copy(alpha = 0.26f), Purple.copy(alpha = 0.09f))))
                    .border(1.dp, Purple.copy(alpha = 0.28f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MonetizationOn,
                    contentDescription = null,
                    tint     = Purple,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(9.dp))
            Text("Nhiệm vụ TikTok Golike", color = TextPrim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // Trạng thái đăng nhập
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isLoggedIn) Green.copy(0.15f) else Color(0xFF252535))
                    .border(
                        0.5.dp,
                        if (isLoggedIn) Green.copy(0.35f) else Color(0xFF34344A),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    if (isLoggedIn) "● Đã kết nối" else "○ Chưa kết nối",
                    color    = if (isLoggedIn) Green else TextMuted,
                    fontSize = 10.sp,
                )
            }
        }

        // Số dư (chỉ hiện khi đã đăng nhập)
        if (isLoggedIn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A1A2A))
                    .border(0.5.dp, Color(0xFF2A2A40), RoundedCornerShape(12.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MonetizationOn, null, tint = Green.copy(0.8f), modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Số dư", color = TextMuted, fontSize = 10.sp)
                    }
                    Text(
                        "%.0f xu".format(coin),
                        color = Green, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .padding(vertical = 10.dp)
                        .background(Color(0xFF2A2A40)),
                )
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, tint = Amber.copy(0.8f), modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Chờ duyệt", color = TextMuted, fontSize = 10.sp)
                    }
                    Text(
                        "%.0f xu".format(holdCoin),
                        color = Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // v1.3.4: Đăng nhập qua WebView — CTA chính khi CHƯA đăng nhập,
        // thay cho việc bắt người dùng tự đi tìm & dán token JWT.
        if (!isLoggedIn && !showInput) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick  = onOpenWebLogin,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Purple),
                    shape    = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.AccountCircle, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Đăng nhập Golike", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Mở trang đăng nhập Golike ngay trong ứng dụng — không cần tự đi tìm token.",
                    color = TextMuted, fontSize = 10.sp,
                )
                TextButton(
                    onClick        = { showInput = true },
                    modifier       = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("Dán token thủ công thay vào đó", color = TextSec, fontSize = 11.sp)
                }
            }
        }

        // Ô nhập token thủ công — fallback khi WebView không tự lấy được token,
        // hoặc khi đổi token của tài khoản đã đăng nhập (nút bút chì bên dưới).
        if (showInput) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Token xác thực (JWT Bearer)", color = TextSec, fontSize = 11.sp)
                OutlinedTextField(
                    value         = tokenInput,
                    onValueChange = { tokenInput = it },
                    placeholder   = { Text("Dán token từ app.golike.net ...", color = TextMuted, fontSize = 11.sp) },
                    singleLine    = false,
                    maxLines      = 3,
                    modifier      = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Purple,
                        unfocusedBorderColor = Color(0xFF2A2A40),
                        focusedTextColor     = TextPrim,
                        unfocusedTextColor   = TextPrim,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (tokenInput.isNotBlank()) {
                                onSaveToken(tokenInput.trim())
                                showInput = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(containerColor = Purple),
                        shape    = RoundedCornerShape(8.dp),
                    ) {
                        Text("Lưu token", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { showInput = false },
                        modifier = Modifier.wrapContentWidth(),
                        shape    = RoundedCornerShape(8.dp),
                        border   = BorderStroke(1.dp, TextMuted.copy(0.4f)),
                    ) {
                        // v1.3.4: chưa đăng nhập → quay lại nút WebView; đã đăng nhập → chỉ Hủy
                        Text(if (isLoggedIn) "Hủy" else "← WebView", fontSize = 12.sp, color = TextMuted)
                    }
                }
                if (!isLoggedIn) {
                    TextButton(
                        onClick        = onOpenWebLogin,
                        modifier       = Modifier.align(Alignment.End),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("Đăng nhập qua WebView thay vào đó", color = TextSec, fontSize = 11.sp)
                    }
                }
            }
        }

        // Nút đổi token khi đã kết nối
        if (isLoggedIn && !showInput) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick  = onStartTask,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(containerColor = Purple),
                    shape    = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Bắt đầu nhiệm vụ", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick  = { showInput = true; tokenInput = "" },
                    modifier = Modifier.wrapContentWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    border   = BorderStroke(1.dp, Purple.copy(0.35f)),
                ) {
                    Icon(Icons.Rounded.Edit, null, tint = Purple, modifier = Modifier.size(14.dp))
                }
                OutlinedButton(
                    onClick  = onClearToken,
                    modifier = Modifier.wrapContentWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    border   = BorderStroke(1.dp, Color(0xFFEF4444).copy(0.4f)),
                ) {
                    Icon(Icons.Rounded.Logout, null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                }
            }
        }
      }
    }
}

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
            Icons.Rounded.Info,
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
            // v1.2.7: X logo fill toàn bộ ô (fillMaxSize → fit trong PlatformDemoCard 40dp)
            Box(
                modifier = Modifier.fillMaxSize().background(XBlack),
                contentAlignment = Alignment.Center,
            ) {
                PlatformLogo(R.drawable.ic_logo_x, "X", Modifier.fillMaxSize())
            }
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
            // v1.2.7: Instagram logo fill toàn bộ ô với gradient background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(InstaGradA, InstaGradB, InstaGradC))),
                contentAlignment = Alignment.Center,
            ) {
                PlatformLogo(R.drawable.ic_logo_instagram, "Instagram", Modifier.fillMaxSize())
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
            // v1.2.7: Threads — fill toàn bộ ô
            Box(
                modifier = Modifier.fillMaxSize().background(ThreadsBlack),
                contentAlignment = Alignment.Center,
            ) {
                Text("@", color = tAccent, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
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
            // v1.2.7: Snapchat logo fill toàn bộ ô với SnapYellow background
            Box(
                modifier = Modifier.fillMaxSize().background(SnapYellow),
                contentAlignment = Alignment.Center,
            ) {
                PlatformLogo(R.drawable.ic_logo_snapchat, "Snapchat", Modifier.fillMaxSize())
            }
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
    val (glowAlpha, triggerGlow) = rememberSelectionGlow()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            // v1.3.1: shadow thật cho card demo — trước đây chỉ có glow/border, phẳng hoàn toàn.
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.4f))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(bgFrom, bgTo)))
            // v1.2.9: glow overlay theo màu accent riêng của từng nền tảng
            .background(accent.copy(alpha = glowAlpha * 0.20f))
            .border((1f + glowAlpha * 1.2f).dp, borderColor.copy(alpha = 1f), RoundedCornerShape(18.dp))
            .clickable(onClick = { triggerGlow(); onClick() })
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // v1.2.7: Icon box — clip đủ để logo fillMaxSize bị trim góc
                // Background được set bởi iconContent (platform-specific color)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            0.5.dp,
                            borderColor.copy(alpha = 0.35f),
                            RoundedCornerShape(12.dp),
                        ),
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
//  Card: Nuôi tài khoản Facebook — dịch vụ chính thức (v1.2.9, trước là demo v1.2.3)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FacebookNurtureCard(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label         = "fb_card_scale",
    )
    val (glowAlpha, triggerGlow) = rememberSelectionGlow()

    val AccentFb2 = Color(0xFF42A5F5) // lighter blue

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(elevation = 7.dp, shape = RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF0A0E18),
                        0.6f to Color(0xFF0D131C),
                        1.0f to FacebookBlue.copy(alpha = 0.18f),
                    ),
                )
            )
            // v1.2.9: glow overlay khi vừa được chọn — màu Facebook blue
            .background(FacebookBlue.copy(alpha = glowAlpha * 0.22f))
            .border(
                width  = (1f + glowAlpha * 1.4f).dp,
                brush  = Brush.horizontalGradient(
                    listOf(
                        FacebookBlue.copy(alpha = 0.4f + glowAlpha * 0.5f),
                        AccentFb2.copy(alpha = 0.2f + glowAlpha * 0.5f),
                    )
                ),
                shape  = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = { triggerGlow(); onClick() }, onClickLabel = "Mở dịch vụ nuôi tài khoản Facebook")
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // v1.2.7: Facebook logo fill toàn bộ ô
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(FacebookBlue),
                ) {
                    PlatformLogo(
                        logoRes     = R.drawable.ic_logo_facebook,
                        contentDesc = "Facebook",
                        modifier    = Modifier.fillMaxSize(),
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
                        text     = "Lướt feed & thích bài viết tự động",
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
//  Shared composables
// ─────────────────────────────────────────────────────────────────────────────

// v1.2.7: Platform logo helpers — dùng ảnh thật thay cho icon generic
@Composable
private fun PlatformLogo(logoRes: Int, contentDesc: String, modifier: Modifier = Modifier) {
    Image(
        painter            = painterResource(logoRes),
        contentDescription = contentDesc,
        contentScale       = ContentScale.Fit,
        modifier           = modifier,
    )
}

// v1.2.7: TikTokIconBox — logo fill toàn bộ ô, không có inner wrapper
@Composable
private fun TikTokIconBox() {
    PlatformLogo(
        logoRes     = R.drawable.ic_logo_tiktok,
        contentDesc = "TikTok",
        modifier    = Modifier.fillMaxSize(),
    )
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

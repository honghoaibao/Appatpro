package com.atpro.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * v1.4.0 — Hệ thiết kế dùng chung của AT PRO.
 *
 * TRƯỚC: mỗi màn hình tự khai báo bảng màu riêng (Purple có 2 giá trị khác nhau, Green 3 giá trị…),
 * thẻ nào cũng gradient + bóng + viền phát sáng → rối và thiếu nhất quán.
 * SAU: MỘT nguồn màu ([AtColors]), bề mặt phẳng + viền mảnh, một màu nhấn duy nhất, nút chính màu trắng,
 * chuyển động ngắn và có chủ đích (nhấn nút co nhẹ, vào màn lần lượt, chỉ báo trượt, số đếm lên).
 *
 * Muốn đổi diện mạo cả app: chỉ sửa [AtColors].
 */
object AtColors {
    // Nền & bề mặt — xám trung tính gần đen, không ngả tím
    val Bg          = Color(0xFF09090B)
    val Surface     = Color(0xFF111114)
    val SurfaceHigh = Color(0xFF18181C)
    val Hairline    = Color(0xFF26262B)
    val HairlineHi  = Color(0xFF3A3A42)

    // Chữ
    val TextPrim  = Color(0xFFF4F4F5)
    val TextSec   = Color(0xFFA1A1AA)
    val TextMuted = Color(0xFF71717A)

    // Màu nhấn duy nhất (chỉ dùng cho trạng thái đang chọn / tiến độ)
    val Accent   = Color(0xFF7B83FF)
    val AccentHi = Color(0xFF9EA4FF)

    // Màu trạng thái
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Danger  = Color(0xFFF87171)
    val Info    = Color(0xFF38BDF8)
    val Rose    = Color(0xFFF472B6)
}

/** Kiểu chữ dùng chung (số lớn dùng letterSpacing âm cho gọn, cao cấp hơn). */
object AtType {
    val Display = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp, lineHeight = 64.sp)
    val Title   = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    val Number  = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium,   letterSpacing = (-0.5).sp)
}

/** Theme Material gắn bảng màu [AtColors] — dùng ở mọi Activity thay cho MaterialTheme(darkColorScheme(...)). */
@Composable
fun AtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary          = AtColors.Accent,
            onPrimary        = Color.White,
            background       = AtColors.Bg,
            onBackground     = AtColors.TextPrim,
            surface          = AtColors.Surface,
            onSurface        = AtColors.TextPrim,
            surfaceVariant   = AtColors.SurfaceHigh,
            onSurfaceVariant = AtColors.TextSec,
            outline          = AtColors.HairlineHi,
            outlineVariant   = AtColors.Hairline,
            error            = AtColors.Danger,
        ),
        content = content,
    )
}

// ─────────────────────────────────────────────────────────────
//  Chuyển động
// ─────────────────────────────────────────────────────────────

/** Click có phản hồi co nhẹ khi nhấn (lò xo), không dùng ripple. Đặt TRƯỚC clip/background để cả khối co lại. */
fun Modifier.pressClickable(
    enabled:       Boolean = true,
    pressedScale:  Float   = 0.97f,
    onClick:       () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue   = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label         = "press_scale",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = interaction,
            indication        = null,
            enabled           = enabled,
            onClick           = onClick,
        )
}

/** Hiện dần + trượt lên nhẹ, lần lượt theo [index] (mỗi phần tử trễ [stepMs] ms). */
fun Modifier.staggerIn(index: Int, stepMs: Int = 50): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * stepMs.toLong())
        shown = true
    }
    val p by animateFloatAsState(
        targetValue   = if (shown) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label         = "stagger_in",
    )
    this.graphicsLayer {
        alpha        = p
        translationY = (1f - p) * 18.dp.toPx()
    }
}

// ─────────────────────────────────────────────────────────────
//  Thành phần
// ─────────────────────────────────────────────────────────────

/** Thẻ phẳng: nền [AtColors.Surface] + viền mảnh. Không bóng, không gradient. */
@Composable
fun AtCard(
    modifier:       Modifier = Modifier,
    onClick:        (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content:        @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val base  = if (onClick != null) modifier.pressClickable(pressedScale = 0.985f, onClick = onClick) else modifier
    Column(
        modifier = base
            .clip(shape)
            .background(AtColors.Surface)
            .border(1.dp, AtColors.Hairline, shape)
            .padding(contentPadding),
        content = content,
    )
}

enum class AtButtonStyle { Primary, Secondary, Danger }

/** Nút: Primary = nền trắng chữ tối (nút hành động chính duy nhất trên màn hình). */
@Composable
fun AtButton(
    text:     String,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    enabled:  Boolean = true,
    icon:     ImageVector? = null,
    style:    AtButtonStyle = AtButtonStyle.Primary,
    height:   androidx.compose.ui.unit.Dp = 52.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    val bg by animateColorAsState(
        targetValue = when {
            !enabled                         -> AtColors.SurfaceHigh
            style == AtButtonStyle.Primary   -> AtColors.TextPrim
            else                             -> AtColors.Surface
        },
        animationSpec = tween(200),
        label = "btn_bg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            !enabled                         -> AtColors.TextMuted
            style == AtButtonStyle.Primary   -> AtColors.Bg
            style == AtButtonStyle.Danger    -> AtColors.Danger
            else                             -> AtColors.TextPrim
        },
        animationSpec = tween(200),
        label = "btn_fg",
    )
    val borderColor = if (style == AtButtonStyle.Primary || !enabled) Color.Transparent else AtColors.HairlineHi
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pressClickable(enabled = enabled, onClick = onClick)
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Chấm trạng thái: lõi đặc + vòng lan toả khi [pulsing]. */
@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier, pulsing: Boolean = true) {
    val inf = rememberInfiniteTransition(label = "live_dot")
    val t by inf.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1600, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "live_dot_t",
    )
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        val s = 1f + t * 1.6f
                        scaleX = s; scaleY = s
                        alpha  = 0.5f * (1f - t)
                    }
                    .background(color, CircleShape)
            )
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/** Nhãn trạng thái gọn: chấm + chữ, không nền, không viền. */
@Composable
fun AtStatus(text: String, color: Color, pulsing: Boolean = true, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LiveDot(color = color, pulsing = pulsing)
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Nhãn mục: chữ hoa nhỏ, giãn chữ. */
@Composable
fun AtSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text          = text.uppercase(),
        color         = AtColors.TextMuted,
        fontSize      = 11.sp,
        fontWeight    = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier      = modifier,
    )
}

/** Đường kẻ mảnh ngang. */
@Composable
fun AtDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(AtColors.Hairline))
}

/** Tab chọn kiểu phân đoạn với thanh chọn TRƯỢT mượt giữa các lựa chọn. */
@Composable
fun AtSegmented(
    options:       List<String>,
    selectedIndex: Int,
    onSelect:      (Int) -> Unit,
    modifier:      Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(AtColors.Surface)
            .border(1.dp, AtColors.Hairline, shape)
            .padding(3.dp)
    ) {
        val itemW = maxWidth / options.size
        val x by animateDpAsState(
            targetValue   = itemW * selectedIndex,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            label         = "seg_x",
        )
        val thumb = RoundedCornerShape(9.dp)
        Box(
            Modifier
                .offset(x = x)
                .width(itemW)
                .fillMaxHeight()
                .clip(thumb)
                .background(AtColors.SurfaceHigh)
                .border(1.dp, AtColors.HairlineHi, thumb)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val c by animateColorAsState(
                    targetValue   = if (i == selectedIndex) AtColors.TextPrim else AtColors.TextMuted,
                    animationSpec = tween(180),
                    label         = "seg_c",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null,
                            onClick           = { onSelect(i) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = c, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Số tự đếm lên/xuống tới [value] khi giá trị đổi. */
@Composable
fun AnimatedNumber(
    value:    Int,
    style:    TextStyle,
    modifier: Modifier = Modifier,
    color:    Color    = AtColors.TextPrim,
) {
    val shown by animateIntAsState(
        targetValue   = value,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label         = "animated_number",
    )
    Text("$shown", style = style, color = color, modifier = modifier)
}

/** Thẻ hộp thoại: phẳng, viền mảnh, hiện lên bằng phóng nhẹ + mờ dần. Đặt trong Dialog có scrim. */
@Composable
fun AtDialogCard(
    modifier:      Modifier = Modifier,
    widthFraction: Float    = 0.86f,
    content:       @Composable ColumnScope.() -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val p by animateFloatAsState(
        targetValue   = if (shown) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label         = "dialog_in",
    )
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .graphicsLayer {
                alpha  = p
                val sc = 0.94f + 0.06f * p
                scaleX = sc; scaleY = sc
            }
            .clip(shape)
            .background(AtColors.Surface)
            .border(1.dp, AtColors.HairlineHi, shape)
            .padding(22.dp),
        content = content,
    )
}

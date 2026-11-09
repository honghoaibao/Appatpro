package com.atpro.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

/**
 * v1.7.0 — Hệ thiết kế "Sky" của AT PRO: xanh da trời · trắng · xanh dương · đen.
 *
 * Hai chế độ cùng một bảng màu gốc:
 *   • SÁNG (mặc định): nền trắng ngả xanh da trời nhạt, thẻ trắng, chữ đen, nhấn xanh dương, điểm xuyết xanh da trời.
 *   • TỐI: nền đen ngả xanh, thẻ xanh đen, chữ trắng, nhấn xanh dương sáng, điểm xuyết xanh da trời.
 *
 * MỘT nguồn màu duy nhất ([AtColors]) — mọi màn hình đọc màu qua đây nên đổi chế độ là đổi cả app ngay lập tức
 * (các bí danh `private val X get() = AtColors.Y` trong từng file đọc lại mỗi lần vẽ).
 * Màu thương hiệu mạng xã hội (TikTok/Facebook/…) giữ nguyên, không thuộc bảng này.
 */
enum class AtThemeMode(val label: String) { LIGHT("Sáng"), DARK("Tối") }

@Immutable
class AtPalette(
    val dark:        Boolean,
    // Nền & bề mặt
    val bg:          Color,
    val surface:     Color,
    val surfaceHigh: Color,
    val hairline:    Color,
    val hairlineHi:  Color,
    // Chữ
    val textPrim:    Color,
    val textSec:     Color,
    val textMuted:   Color,
    // Nhấn
    val blue:        Color,   // nền nút/chip chọn — chữ trắng đọc rõ trên màu này ở cả hai chế độ
    val accent:      Color,   // chữ/biểu tượng nhấn trên nền bề mặt
    val sky:         Color,   // xanh da trời — điểm xuyết, biểu đồ, trạng thái thông tin
    val accentSoft:  Color,   // nền nhạt của trạng thái đang chọn
    val shadow:      Color,
    // Trạng thái
    val success:     Color,
    val warning:     Color,
    val danger:      Color,
    val rose:        Color,
)

object AtPalettes {
    val Light = AtPalette(
        dark = false,
        bg = Color(0xFFF2F7FD), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFE8F1FC),
        hairline = Color(0xFFDAE6F5), hairlineHi = Color(0xFFBFD2EC),
        textPrim = Color(0xFF0A1020), textSec = Color(0xFF3E4E68), textMuted = Color(0xFF5C6F94),
        blue = Color(0xFF1D5FEA), accent = Color(0xFF1D5FEA), sky = Color(0xFF0B8FE0),
        accentSoft = Color(0xFFE0ECFF), shadow = Color(0xFF1D5FEA),
        success = Color(0xFF047857), warning = Color(0xFFB45309), danger = Color(0xFFD92D3F), rose = Color(0xFFE11D48),
    )
    val Dark = AtPalette(
        dark = true,
        bg = Color(0xFF05080F), surface = Color(0xFF0C121C), surfaceHigh = Color(0xFF131B29),
        hairline = Color(0xFF1C2638), hairlineHi = Color(0xFF2A3850),
        textPrim = Color(0xFFF5F9FF), textSec = Color(0xFFA9B8CE), textMuted = Color(0xFF7285A3),
        blue = Color(0xFF2C66F0), accent = Color(0xFF5B93FF), sky = Color(0xFF4FC3FF),
        accentSoft = Color(0xFF10233F), shadow = Color(0xFF000000),
        success = Color(0xFF34D399), warning = Color(0xFFFBBF24), danger = Color(0xFFF87171), rose = Color(0xFFFB7185),
    )
}

/** Chế độ giao diện hiện tại (lưu SharedPreferences, nạp ở `AtProApplication.onCreate`). Mặc định: Sáng. */
object AtThemeState {
    private const val PREFS = "at_ui"
    private const val KEY   = "theme_mode"

    var mode by mutableStateOf(AtThemeMode.LIGHT)
        private set

    val palette: AtPalette get() = if (mode == AtThemeMode.DARK) AtPalettes.Dark else AtPalettes.Light
    val isDark: Boolean get() = mode == AtThemeMode.DARK

    fun load(context: Context) {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        mode = AtThemeMode.values().firstOrNull { it.name == saved } ?: AtThemeMode.LIGHT
    }

    fun set(context: Context, newMode: AtThemeMode) {
        persist(context, newMode)
        com.atpro.backup.BackupStateStore.markDirty(context.applicationContext)
    }

    /** Apply a cloud-restored theme without creating a new local dirty generation. */
    fun restore(context: Context, newMode: AtThemeMode) {
        persist(context, newMode)
    }

    private fun persist(context: Context, newMode: AtThemeMode) {
        mode = newMode
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, newMode.name).apply()
    }
}

/**
 * Nguồn màu duy nhất cho mọi màn hình. Mỗi thuộc tính là getter đọc palette hiện hành (state của Compose) →
 * đổi chế độ Sáng/Tối làm vẽ lại toàn bộ giao diện. KHÔNG sao chép vào `val X = AtColors.Y` ở cấp file
 * (sẽ đóng băng màu) — dùng `val X get() = AtColors.Y`.
 */
object AtColors {
    // Nền & bề mặt
    val Bg:          Color get() = AtThemeState.palette.bg
    val Surface:     Color get() = AtThemeState.palette.surface
    val SurfaceHigh: Color get() = AtThemeState.palette.surfaceHigh
    val Hairline:    Color get() = AtThemeState.palette.hairline
    val HairlineHi:  Color get() = AtThemeState.palette.hairlineHi

    // Chữ
    val TextPrim:  Color get() = AtThemeState.palette.textPrim
    val TextSec:   Color get() = AtThemeState.palette.textSec
    val TextMuted: Color get() = AtThemeState.palette.textMuted

    // Nhấn
    /** Xanh dương — nền nút chính, chip đang chọn (chữ trắng trên đó luôn đủ tương phản). */
    val Blue:       Color get() = AtThemeState.palette.blue
    /** Xanh dương dùng làm CHỮ/biểu tượng nhấn trên bề mặt (sáng hơn ở chế độ tối). */
    val Accent:     Color get() = AtThemeState.palette.accent
    val AccentHi:   Color get() = AtThemeState.palette.sky
    /** Xanh da trời. */
    val Sky:        Color get() = AtThemeState.palette.sky
    /** Nền nhạt cho trạng thái đang chọn / nhãn thông tin. */
    val AccentSoft: Color get() = AtThemeState.palette.accentSoft
    val Shadow:     Color get() = AtThemeState.palette.shadow
    val OnBlue:     Color get() = Color.White

    // Trạng thái
    val Success: Color get() = AtThemeState.palette.success
    val Warning: Color get() = AtThemeState.palette.warning
    val Danger:  Color get() = AtThemeState.palette.danger
    val Info:    Color get() = AtThemeState.palette.sky
    val Rose:    Color get() = AtThemeState.palette.rose

    /** Màn che phía sau hộp thoại. */
    val Scrim: Color get() = if (AtThemeState.isDark) Color(0xB8000000) else Color(0x730A1020)
}

/** Gradient thương hiệu (xanh dương → xanh da trời) dùng cho thẻ nổi bật; chữ trắng đọc rõ trên cả dải. */
object AtBrush {
    val Hero: Brush
        get() = Brush.linearGradient(
            colors = listOf(Color(0xFF1342D1), Color(0xFF1D5FEA), Color(0xFF1E88F0)),
            start  = Offset(0f, 0f),
            end    = Offset(900f, 700f),
        )
}

/** Kiểu chữ dùng chung (số lớn dùng letterSpacing âm cho gọn, cao cấp hơn). */
object AtType {
    val Display = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp, lineHeight = 64.sp)
    val Title   = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    val Number  = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium,   letterSpacing = (-0.5).sp)
    val Heading = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val Body    = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal,   lineHeight = 20.sp)
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal,   lineHeight = 17.sp)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Theme Material gắn palette [AtColors] — dùng ở mọi Activity. Đồng bộ luôn màu nền cửa sổ + biểu tượng thanh hệ thống. */
@Composable
fun AtTheme(content: @Composable () -> Unit) {
    val p = AtThemeState.palette
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.blue, onPrimary = Color.White,
            background = p.bg, onBackground = p.textPrim,
            surface = p.surface, onSurface = p.textPrim,
            surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textSec,
            outline = p.hairlineHi, outlineVariant = p.hairline, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.blue, onPrimary = Color.White,
            background = p.bg, onBackground = p.textPrim,
            surface = p.surface, onSurface = p.textPrim,
            surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textSec,
            outline = p.hairlineHi, outlineVariant = p.hairline, error = p.danger,
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars     = !p.dark
            controller.isAppearanceLightNavigationBars = !p.dark
            window.setBackgroundDrawable(ColorDrawable(p.bg.toArgb()))
        }
    }
    // v1.10.0: chặn cỡ chữ hệ thống quá lớn/nhỏ (0,85–1,15×). Cỡ chữ "Rất lớn" của một số máy làm chữ có dấu
    // tiếng Việt bị cắt trong ô cố định chiều cao và đẩy nút ra ngoài thẻ (màn hình Quyền vỡ giao diện).
    val density = LocalDensity.current
    val limited = Density(density.density, fontScale = density.fontScale.coerceIn(0.85f, 1.15f))
    CompositionLocalProvider(LocalDensity provides limited) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ─────────────────────────────────────────────────────────────
//  Chuyển động
// ─────────────────────────────────────────────────────────────

/** Click có phản hồi co nhẹ khi nhấn (lò xo), không dùng ripple. Đặt TRƯỚC clip/background để cả khối co lại. */
fun Modifier.pressClickable(
    enabled:       Boolean = true,
    pressedScale:  Float   = 0.97f,
    haptic:        Boolean = true,
    onClick:       () -> Unit,
): Modifier = composed {
    val feedback = LocalHapticFeedback.current
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
            onClick           = {
                // v1.10.0: rung rất nhẹ khi chạm (cảm giác "bấm thật"); tắt được bằng haptic = false.
                if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
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

// ─────────────────────────────────────────────────────────────
//  Thành phần
// ─────────────────────────────────────────────────────────────

/**
 * Thẻ: bề mặt + viền mảnh; chế độ Sáng có thêm bóng đổ xanh rất nhẹ cho cảm giác nổi, chế độ Tối phẳng.
 */
@Composable
fun AtCard(
    modifier:       Modifier = Modifier,
    onClick:        (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content:        @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val base  = if (onClick != null) modifier.pressClickable(pressedScale = 0.985f, onClick = onClick) else modifier
    Column(
        modifier = base
            .shadow(
                elevation    = if (AtThemeState.isDark) 0.dp else 6.dp,
                shape        = shape,
                ambientColor = AtColors.Shadow.copy(alpha = 0.10f),
                spotColor    = AtColors.Shadow.copy(alpha = 0.18f),
            )
            .clip(shape)
            .background(AtColors.Surface)
            .border(1.dp, AtColors.Hairline, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Thẻ nổi bật: nền gradient xanh dương → xanh da trời, chữ trắng, hai vòng sáng trang trí. */
@Composable
fun AtHeroCard(
    modifier:       Modifier = Modifier,
    onClick:        (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content:        @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val base  = if (onClick != null) modifier.pressClickable(pressedScale = 0.985f, onClick = onClick) else modifier
    Column(
        modifier = base
            .shadow(
                elevation    = 10.dp,
                shape        = shape,
                ambientColor = Color(0xFF1D5FEA).copy(alpha = 0.25f),
                spotColor    = Color(0xFF1D5FEA).copy(alpha = 0.40f),
            )
            .clip(shape)
            .background(AtBrush.Hero)
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.09f), radius = size.minDimension * 0.55f, center = Offset(size.width * 0.95f, size.height * 0.05f))
                drawCircle(Color.White.copy(alpha = 0.06f), radius = size.minDimension * 0.38f, center = Offset(size.width * 0.78f, size.height * 1.02f))
            }
            .padding(contentPadding),
        content = content,
    )
}

enum class AtButtonStyle { Primary, Secondary, Danger, Tonal }

/**
 * Nút. Primary = nền xanh dương gradient nhẹ, chữ trắng (hành động chính của màn hình);
 * Tonal = nền xanh da trời nhạt, chữ xanh; Secondary = viền; Danger = đỏ nhạt.
 */
@Composable
fun AtButton(
    text:     String,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    enabled:  Boolean = true,
    icon:     ImageVector? = null,
    style:    AtButtonStyle = AtButtonStyle.Primary,
    height:   Dp = 52.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    val solid by animateColorAsState(
        targetValue = when {
            !enabled                        -> AtColors.SurfaceHigh
            style == AtButtonStyle.Primary  -> AtColors.Blue
            style == AtButtonStyle.Tonal    -> AtColors.AccentSoft
            style == AtButtonStyle.Danger   -> AtColors.Danger.copy(alpha = 0.12f)
            else                            -> AtColors.Surface
        },
        animationSpec = tween(200),
        label = "btn_bg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            !enabled                        -> AtColors.TextMuted
            style == AtButtonStyle.Primary  -> AtColors.OnBlue
            style == AtButtonStyle.Tonal    -> AtColors.Accent
            style == AtButtonStyle.Danger   -> AtColors.Danger
            else                            -> AtColors.TextPrim
        },
        animationSpec = tween(200),
        label = "btn_fg",
    )
    val borderColor = when {
        !enabled                       -> Color.Transparent
        style == AtButtonStyle.Primary -> Color.Transparent
        style == AtButtonStyle.Tonal   -> Color.Transparent
        style == AtButtonStyle.Danger  -> AtColors.Danger.copy(alpha = 0.35f)
        else                           -> AtColors.HairlineHi
    }
    val fill: Modifier =
        if (enabled && style == AtButtonStyle.Primary) {
            Modifier.background(Brush.horizontalGradient(listOf(AtColors.Blue, lerp(AtColors.Blue, AtColors.Sky, 0.22f))))
        } else {
            Modifier.background(solid)
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pressClickable(enabled = enabled, onClick = onClick)
            .clip(shape)
            .then(fill)
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
    val shape = RoundedCornerShape(13.dp)
    val feedback = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(AtColors.SurfaceHigh)
            .padding(3.dp)
    ) {
        val itemW = maxWidth / options.size
        val x by animateDpAsState(
            targetValue   = itemW * selectedIndex,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            label         = "seg_x",
        )
        val thumb = RoundedCornerShape(10.dp)
        Box(
            Modifier
                .offset(x = x)
                .width(itemW)
                .fillMaxHeight()
                .shadow(
                    elevation    = if (AtThemeState.isDark) 0.dp else 3.dp,
                    shape        = thumb,
                    ambientColor = AtColors.Shadow.copy(alpha = 0.10f),
                    spotColor    = AtColors.Shadow.copy(alpha = 0.18f),
                )
                .clip(thumb)
                .background(if (AtThemeState.isDark) AtColors.HairlineHi else AtColors.Surface)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val c by animateColorAsState(
                    targetValue   = if (i == selectedIndex) AtColors.Accent else AtColors.TextMuted,
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
                            onClick           = {
                                if (i != selectedIndex) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Khối xương khi đang tải dữ liệu (v1.10.0): nền [AtColors.SurfaceHigh] nhấp nháy nhẹ.
 * Đọc giá trị animation trong `graphicsLayer` → chỉ vẽ lại, không recompose.
 */
@Composable
fun AtSkeleton(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp)) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val a by transition.animateFloat(
        initialValue  = 0.45f,
        targetValue   = 0.95f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "skeleton_alpha",
    )
    Box(modifier.graphicsLayer { alpha = a }.clip(shape).background(AtColors.SurfaceHigh))
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

/** Thẻ hộp thoại: bề mặt, viền mảnh, bóng; hiện lên bằng phóng nhẹ + mờ dần. Đặt trong Dialog có scrim. */
@Composable
fun AtDialogCard(
    modifier:      Modifier = Modifier,
    widthFraction: Float    = 0.88f,
    content:       @Composable ColumnScope.() -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val p by animateFloatAsState(
        targetValue   = if (shown) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label         = "dialog_in",
    )
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .graphicsLayer {
                alpha  = p
                val sc = 0.94f + 0.06f * p
                scaleX = sc; scaleY = sc
            }
            .shadow(
                elevation    = 16.dp,
                shape        = shape,
                ambientColor = AtColors.Shadow.copy(alpha = 0.20f),
                spotColor    = AtColors.Shadow.copy(alpha = 0.30f),
            )
            .clip(shape)
            .background(AtColors.Surface)
            .border(1.dp, AtColors.Hairline, shape)
            .padding(22.dp),
        content = content,
    )
}

/**
 * Hộp thoại chuẩn của app (thay `AlertDialog` Material): tiêu đề, nội dung, ô tuỳ ý, hai nút.
 * [danger] = hành động phá huỷ (nút xác nhận đỏ). [dismissible] = false khóa khi đang xử lý.
 */
@Composable
fun AtDialog(
    onDismiss:    () -> Unit,
    title:        String,
    confirmText:  String,
    onConfirm:    () -> Unit,
    modifier:     Modifier = Modifier,
    message:      String? = null,
    dismissText:  String? = "Huỷ",
    danger:       Boolean = false,
    confirmEnabled: Boolean = true,
    dismissible:  Boolean = true,
    content:      (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress      = dismissible,
            dismissOnClickOutside   = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AtColors.Scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = { if (dismissible) onDismiss() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AtDialogCard(
                modifier = modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = { /* chặn — chạm vào thẻ không đóng hộp thoại */ },
                ),
            ) {
                Text(title, style = AtType.Heading, color = AtColors.TextPrim)
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = AtType.Body, color = AtColors.TextSec)
                }
                if (content != null) {
                    Spacer(Modifier.height(16.dp))
                    content()
                }
                Spacer(Modifier.height(20.dp))
                AtButton(
                    text    = confirmText,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    style   = if (danger) AtButtonStyle.Danger else AtButtonStyle.Primary,
                    height  = 48.dp,
                )
                if (dismissText != null) {
                    Spacer(Modifier.height(8.dp))
                    AtButton(
                        text    = dismissText,
                        onClick = onDismiss,
                        enabled = dismissible,
                        style   = AtButtonStyle.Secondary,
                        height  = 44.dp,
                    )
                }
            }
        }
    }
}

/** Ô nhập thống nhất: nhãn phía trên, viền xanh khi focus, hỗ trợ ẩn mật khẩu/biểu tượng/lỗi. */
@Composable
fun AtTextField(
    value:         String,
    onValueChange: (String) -> Unit,
    modifier:      Modifier = Modifier,
    label:         String? = null,
    placeholder:   String? = null,
    enabled:       Boolean = true,
    singleLine:    Boolean = true,
    obscure:       Boolean = false,
    keyboardType:  KeyboardType = KeyboardType.Text,
    imeAction:     ImeAction = ImeAction.Next,
    onDone:        (() -> Unit)? = null,
    error:         String? = null,
    leading:       (@Composable () -> Unit)? = null,
    trailing:      (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        if (label != null) {
            Text(label, color = AtColors.TextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        OutlinedTextField(
            value         = value,
            onValueChange = onValueChange,
            modifier      = Modifier.fillMaxWidth(),
            enabled       = enabled,
            singleLine    = singleLine,
            isError       = error != null,
            placeholder   = if (placeholder != null) { { Text(placeholder, color = AtColors.TextMuted, fontSize = 14.sp) } } else null,
            visualTransformation = if (obscure) PasswordVisualTransformation() else VisualTransformation.None,
            leadingIcon   = leading,
            trailingIcon  = trailing,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            shape  = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor   = AtColors.Surface,
                unfocusedContainerColor = AtColors.Surface,
                disabledContainerColor  = AtColors.SurfaceHigh,
                errorContainerColor     = AtColors.Surface,
                focusedBorderColor      = AtColors.Blue,
                unfocusedBorderColor    = AtColors.HairlineHi,
                disabledBorderColor     = AtColors.Hairline,
                errorBorderColor        = AtColors.Danger,
                focusedTextColor        = AtColors.TextPrim,
                unfocusedTextColor      = AtColors.TextPrim,
                disabledTextColor       = AtColors.TextMuted,
                errorTextColor          = AtColors.TextPrim,
                cursorColor             = AtColors.Blue,
            ),
        )
        if (error != null) {
            Text(error, color = AtColors.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Công tắc thống nhất (thumb trắng, track xanh dương khi bật). */
@Composable
fun AtSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val feedback = LocalHapticFeedback.current
    Switch(
        checked         = checked,
        onCheckedChange = { v ->
            feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCheckedChange(v)
        },
        modifier        = modifier,
        enabled         = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor       = Color.White,
            checkedTrackColor       = AtColors.Blue,
            checkedBorderColor      = Color.Transparent,
            uncheckedThumbColor     = Color.White,
            uncheckedTrackColor     = AtColors.HairlineHi,
            uncheckedBorderColor    = Color.Transparent,
            disabledCheckedThumbColor    = Color.White.copy(alpha = 0.7f),
            disabledCheckedTrackColor    = AtColors.Blue.copy(alpha = 0.4f),
            disabledUncheckedThumbColor  = Color.White.copy(alpha = 0.7f),
            disabledUncheckedTrackColor  = AtColors.Hairline,
            disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Viên thuốc chọn/bỏ chọn: chọn = nền xanh nhạt + chữ xanh, chưa chọn = bề mặt + viền. */
@Composable
fun AtChip(
    text:     String,
    selected: Boolean,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    icon:     ImageVector? = null,
) {
    val shape = RoundedCornerShape(50)
    val bg by animateColorAsState(if (selected) AtColors.AccentSoft else AtColors.Surface, tween(160), label = "chip_bg")
    val fg by animateColorAsState(if (selected) AtColors.Accent else AtColors.TextSec, tween(160), label = "chip_fg")
    val bd by animateColorAsState(if (selected) AtColors.Accent.copy(alpha = 0.55f) else AtColors.HairlineHi, tween(160), label = "chip_bd")
    Row(
        modifier = modifier
            .pressClickable(pressedScale = 0.95f, onClick = onClick)
            .clip(shape)
            .background(bg)
            .border(1.dp, bd, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Nhãn nhỏ (huy hiệu) nền nhạt cùng tông [color]. */
@Composable
fun AtBadge(text: String, color: Color = AtColors.Accent, modifier: Modifier = Modifier) {
    Text(
        text       = text,
        color      = color,
        fontSize   = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier   = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Ô vuông bo góc chứa biểu tượng, nền nhạt cùng tông [tint]. */
@Composable
fun AtIconBadge(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = AtColors.Accent, size: Dp = 40.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Hàng danh sách: biểu tượng + tiêu đề/phụ đề + nội dung bên phải, bấm được. */
@Composable
fun AtListRow(
    title:    String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon:     ImageVector? = null,
    tint:     Color = AtColors.Accent,
    onClick:  (() -> Unit)? = null,
    /** Mũi tên ">" ở cuối hàng — mặc định bật khi hàng bấm được (điều hướng sang màn hình khác). */
    chevron:  Boolean = onClick != null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val base = if (onClick != null) modifier.pressClickable(pressedScale = 0.985f, onClick = onClick) else modifier
    Row(
        modifier = base.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AtIconBadge(icon, tint = tint)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = AtColors.TextPrim, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = AtColors.TextMuted, fontSize = 12.sp, maxLines = 2)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AtColors.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Thanh tiêu đề thống nhất cho MỌI màn hình (v1.8.0): [title] 22sp + [subtitle] nhỏ + nút quay lại tuỳ chọn
 * + các hành động bên phải. Màn hình tự thêm `statusBarsPadding()` ở gốc. Khi [onBack] = null (màn hình nằm trong tab)
 * thì không hiện mũi tên quay lại.
 */
@Composable
fun AtTopBar(
    title:    String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack:   (() -> Unit)? = null,
    actions:  @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 6.dp else 16.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Quay lại", tint = AtColors.TextPrim, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = AtType.Title.copy(fontSize = 22.sp), color = AtColors.TextPrim, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, color = AtColors.TextMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
        actions()
    }
}

/** Trạng thái trống: biểu tượng lớn nhạt + tiêu đề + mô tả. */
@Composable
fun AtEmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AtIconBadge(icon, size = 64.dp, tint = AtColors.Sky)
        Spacer(Modifier.height(14.dp))
        Text(title, color = AtColors.TextPrim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = AtColors.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
        }
    }
}

/** Ô số liệu gọn: nhãn nhỏ + giá trị lớn, nền [AtColors.SurfaceHigh]. */
@Composable
fun AtStatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = AtColors.TextPrim) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AtColors.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = AtColors.TextMuted, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Đặt màu nền cửa sổ theo chế độ Sáng/Tối ngay khi Activity được tạo — tránh nháy nền tối `bg_dark`
 * (theme XML) trước khung hình Compose đầu tiên khi đang ở chế độ Sáng. Cài 1 lần ở `AtProApplication`.
 */
object AtWindowBackground : android.app.Application.ActivityLifecycleCallbacks {
    fun install(app: android.app.Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
        activity.window.setBackgroundDrawable(ColorDrawable(AtColors.Bg.toArgb()))
    }
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

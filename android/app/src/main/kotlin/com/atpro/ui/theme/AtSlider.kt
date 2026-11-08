package com.atpro.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AtSlider — v1.11.0. Thanh kéo thống nhất của ứng dụng (thay `Slider` của Material ở mọi màn Cài đặt).
 *
 *  • Đường kéo bo tròn, phần đã kéo tô gradient xanh da trời → xanh dương, vạch chia nhẹ khi có nấc.
 *  • Núm trắng viền xanh, phình ra + toả quầng sáng khi chạm (lò xo), rung nhẹ mỗi khi qua một nấc.
 *  • Ô giá trị ở đầu hàng đổi sang nền xanh, chữ trắng và nảy nhẹ khi đang kéo.
 *  • Chạm vào đường kéo để nhảy tới vị trí đó; chỉ kéo NGANG mới bị bắt — cuộn dọc của trang vẫn mượt.
 *  • Có mô tả trợ năng (TalkBack đọc giá trị).
 */

private val THUMB_R        = 10.dp
private val THUMB_R_ACTIVE = 13.dp
private val TRACK_H        = 6.dp
private val SIDE_INSET     = 16.dp
private val AREA_H         = 36.dp

// ─────────────────────────────────────────────────────────────
//  Một giá trị
// ─────────────────────────────────────────────────────────────

/**
 * Thanh kéo một giá trị kèm nhãn + ô giá trị + nhãn đầu/cuối.
 *
 * @param steps  số nấc Ở GIỮA hai đầu (giống Material); 0 = kéo liên tục. Khoảng rộng nên dùng 0 rồi tự làm tròn.
 * @param display chữ hiện ở ô giá trị (đã gồm đơn vị), ví dụ `"30%"`, `"3 giây"`.
 * @param endLabels chữ ở hai đầu thanh; `null` = không hiện.
 */
@Composable
fun AtSliderField(
    label:     String,
    display:   String,
    value:     Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier:  Modifier = Modifier,
    steps:     Int = 0,
    enabled:   Boolean = true,
    hint:      String? = null,
    endLabels: Pair<String, String>? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    var active by remember { mutableStateOf(false) }
    Column(modifier.alpha(if (enabled) 1f else 0.4f)) {
        SliderHeader(label, display, active)
        if (!hint.isNullOrBlank()) {
            Text(hint, color = AtColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
        }
        AtSlider(
            value = value, onValueChange = onValueChange, valueRange = valueRange,
            steps = steps, enabled = enabled, onActiveChange = { active = it },
            onValueChangeFinished = onValueChangeFinished,
            semanticsLabel = "$label $display",
        )
        EndLabels(endLabels)
    }
}

@Composable
fun AtSlider(
    value:     Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier:  Modifier = Modifier,
    steps:     Int = 0,
    enabled:   Boolean = true,
    onActiveChange: (Boolean) -> Unit = {},
    onValueChangeFinished: (() -> Unit)? = null,
    semanticsLabel: String = "",
) {
    val feedback = LocalHapticFeedback.current
    val currentOnChange   by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val currentOnActive   by rememberUpdatedState(onActiveChange)
    val span = valueRange.endInclusive - valueRange.start
    val safeValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val targetFrac = if (span <= 0f) 0f else (safeValue - valueRange.start) / span
    val currentValue by rememberUpdatedState(safeValue)

    var dragging by remember { mutableStateOf(false) }
    var tapHeld  by remember { mutableStateOf(false) }
    val pressed = dragging || tapHeld
    LaunchedEffectOnPressed(pressed) { currentOnActive(it) }

    val frac by animateFloatAsState(
        targetValue   = targetFrac,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label         = "at_slider_frac",
    )
    val thumbR by animateDpAsState(
        targetValue   = if (pressed) THUMB_R_ACTIVE else THUMB_R,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
        label         = "at_slider_thumb",
    )
    val glow by animateFloatAsState(
        targetValue   = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label         = "at_slider_glow",
    )

    val inactive = AtColors.HairlineHi
    val sky      = AtColors.Sky
    val blue     = AtColors.Blue
    val tickCol  = AtColors.TextMuted.copy(alpha = 0.45f)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(AREA_H)
            .semantics {
                if (semanticsLabel.isNotBlank()) contentDescription = semanticsLabel
                progressBarRangeInfo = ProgressBarRangeInfo(safeValue, valueRange.start..valueRange.endInclusive, max(steps, 0))
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { SIDE_INSET.toPx() }
        val trackW  = max(1f, widthPx - 2 * insetPx)

        // Vị trí chạm (px) → giá trị đã bám nấc
        fun valueAt(x: Float): Float {
            var f = ((x - insetPx) / trackW).coerceIn(0f, 1f)
            if (steps > 0) f = (f * (steps + 1)).roundToInt() / (steps + 1f)
            return valueRange.start + f * span
        }
        fun emit(x: Float) {
            val v = valueAt(x)
            if (abs(v - currentValue) > span * 1e-4f) {
                if (steps in 1..40) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnChange(v)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, steps, valueRange.start, valueRange.endInclusive, trackW) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = { offset ->
                            tapHeld = true
                            emit(offset.x)
                            tryAwaitRelease()
                            tapHeld = false
                            currentOnFinished?.invoke()
                        },
                    )
                }
                .pointerInput(enabled, steps, valueRange.start, valueRange.endInclusive, trackW) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart  = { offset -> dragging = true; emit(offset.x) },
                        onDragEnd    = { dragging = false; currentOnFinished?.invoke() },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ -> change.consume(); emit(change.position.x) },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val th = TRACK_H.toPx()
                val left  = insetPx
                val right = size.width - insetPx
                val x     = left + (right - left) * frac
                val radius = CornerRadius(th / 2f, th / 2f)

                // đường nền
                drawRoundRect(inactive, Offset(left, cy - th / 2f), Size(right - left, th), radius)

                // vạch chia (chỉ khi có nấc vừa phải)
                if (steps in 1..24) {
                    val n = steps + 1
                    for (i in 1 until n) {
                        val tx = left + (right - left) * i / n
                        drawCircle(tickCol, radius = 1.6.dp.toPx(), center = Offset(tx, cy))
                    }
                }

                // phần đã kéo: gradient trời → xanh
                if (x > left + 0.5f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(sky, blue), startX = left, endX = max(x, left + 1f)),
                        topLeft = Offset(left, cy - th / 2f),
                        size = Size(x - left, th),
                        cornerRadius = radius,
                    )
                }

                // quầng sáng + núm
                val r = thumbR.toPx()
                if (glow > 0.01f) {
                    drawCircle(blue.copy(alpha = 0.16f * glow), radius = r + 9.dp.toPx() * glow, center = Offset(x, cy))
                }
                drawCircle(Color.Black.copy(alpha = 0.10f), radius = r + 1.5.dp.toPx(), center = Offset(x, cy + 1.dp.toPx()))
                drawCircle(Color.White, radius = r, center = Offset(x, cy))
                drawCircle(blue, radius = r - 1.5.dp.toPx(), center = Offset(x, cy), style = Stroke(width = 3.dp.toPx()))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Hai giá trị (khoảng)
// ─────────────────────────────────────────────────────────────

/**
 * Thanh kéo KHOẢNG (2 núm) — thay cho cặp 2 thanh "tối thiểu/tối đa" riêng lẻ.
 * Luôn giữ `start + minGap <= end`; chạm gần núm nào thì kéo núm đó.
 */
@Composable
fun AtRangeSliderField(
    label:     String,
    display:   String,
    start:     Float,
    end:       Float,
    onRangeChange: (Float, Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier:  Modifier = Modifier,
    steps:     Int = 0,
    minGap:    Float = 0f,
    enabled:   Boolean = true,
    hint:      String? = null,
    endLabels: Pair<String, String>? = null,
) {
    var active by remember { mutableStateOf(false) }
    Column(modifier.alpha(if (enabled) 1f else 0.4f)) {
        SliderHeader(label, display, active)
        if (!hint.isNullOrBlank()) {
            Text(hint, color = AtColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
        }
        AtRangeSlider(
            start = start, end = end, onRangeChange = onRangeChange, valueRange = valueRange,
            steps = steps, minGap = minGap, enabled = enabled, onActiveChange = { active = it },
            semanticsLabel = "$label $display",
        )
        EndLabels(endLabels)
    }
}

@Composable
fun AtRangeSlider(
    start:     Float,
    end:       Float,
    onRangeChange: (Float, Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier:  Modifier = Modifier,
    steps:     Int = 0,
    minGap:    Float = 0f,
    enabled:   Boolean = true,
    onActiveChange: (Boolean) -> Unit = {},
    semanticsLabel: String = "",
) {
    val feedback = LocalHapticFeedback.current
    val currentOnChange by rememberUpdatedState(onRangeChange)
    val currentOnActive by rememberUpdatedState(onActiveChange)
    val currentStart    by rememberUpdatedState(start)
    val currentEnd      by rememberUpdatedState(end)
    val span = valueRange.endInclusive - valueRange.start
    val s = start.coerceIn(valueRange.start, valueRange.endInclusive)
    val e = end.coerceIn(s, valueRange.endInclusive)
    val fracS = if (span <= 0f) 0f else (s - valueRange.start) / span
    val fracE = if (span <= 0f) 1f else (e - valueRange.start) / span

    var dragging by remember { mutableStateOf(false) }
    var tapHeld  by remember { mutableStateOf(false) }
    var which    by remember { mutableStateOf(0) }   // 0 = núm đầu, 1 = núm cuối
    val pressed = dragging || tapHeld
    LaunchedEffectOnPressed(pressed) { currentOnActive(it) }

    val spec = if (dragging) snap<Float>() else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
    val fS by animateFloatAsState(fracS, spec, label = "at_range_s")
    val fE by animateFloatAsState(fracE, spec, label = "at_range_e")
    val glow by animateFloatAsState(
        if (pressed) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 400f), label = "at_range_glow",
    )
    val rS by animateDpAsState(if (pressed && which == 0) THUMB_R_ACTIVE else THUMB_R, spring(dampingRatio = 0.55f, stiffness = 600f), label = "at_range_rs")
    val rE by animateDpAsState(if (pressed && which == 1) THUMB_R_ACTIVE else THUMB_R, spring(dampingRatio = 0.55f, stiffness = 600f), label = "at_range_re")

    val inactive = AtColors.HairlineHi
    val sky      = AtColors.Sky
    val blue     = AtColors.Blue
    val tickCol  = AtColors.TextMuted.copy(alpha = 0.45f)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(AREA_H)
            .semantics {
                if (semanticsLabel.isNotBlank()) contentDescription = semanticsLabel
                progressBarRangeInfo = ProgressBarRangeInfo(s, valueRange.start..valueRange.endInclusive, max(steps, 0))
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { SIDE_INSET.toPx() }
        val trackW  = max(1f, widthPx - 2 * insetPx)

        fun snapFrac(x: Float): Float {
            var f = ((x - insetPx) / trackW).coerceIn(0f, 1f)
            if (steps > 0) f = (f * (steps + 1)).roundToInt() / (steps + 1f)
            return f
        }
        fun pickThumb(x: Float) {
            val xs = insetPx + trackW * ((currentStart - valueRange.start) / max(span, 1e-6f)).coerceIn(0f, 1f)
            val xe = insetPx + trackW * ((currentEnd   - valueRange.start) / max(span, 1e-6f)).coerceIn(0f, 1f)
            which = when {
                abs(x - xs) < abs(x - xe) -> 0
                abs(x - xs) > abs(x - xe) -> 1
                else -> if (x < xs) 0 else 1      // trùng nhau: kéo sang trái → núm đầu, sang phải → núm cuối
            }
        }
        fun emit(x: Float) {
            val v = valueRange.start + snapFrac(x) * span
            val cs = currentStart; val ce = currentEnd
            val (ns, ne) = if (which == 0) min(v, ce - minGap) to ce else cs to max(v, cs + minGap)
            val newS = ns.coerceIn(valueRange.start, valueRange.endInclusive)
            val newE = ne.coerceIn(valueRange.start, valueRange.endInclusive)
            if (newS != cs || newE != ce) {
                if (steps in 1..40) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnChange(newS, newE)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, steps, valueRange.start, valueRange.endInclusive, minGap, trackW) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = { offset ->
                            tapHeld = true
                            pickThumb(offset.x); emit(offset.x)
                            tryAwaitRelease()
                            tapHeld = false
                        },
                    )
                }
                .pointerInput(enabled, steps, valueRange.start, valueRange.endInclusive, minGap, trackW) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart  = { offset -> dragging = true; pickThumb(offset.x); emit(offset.x) },
                        onDragEnd    = { dragging = false },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ -> change.consume(); emit(change.position.x) },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val th = TRACK_H.toPx()
                val left  = insetPx
                val right = size.width - insetPx
                val xs = left + (right - left) * fS
                val xe = left + (right - left) * fE
                val radius = CornerRadius(th / 2f, th / 2f)

                drawRoundRect(inactive, Offset(left, cy - th / 2f), Size(right - left, th), radius)

                if (steps in 1..24) {
                    val n = steps + 1
                    for (i in 1 until n) {
                        drawCircle(tickCol, radius = 1.6.dp.toPx(), center = Offset(left + (right - left) * i / n, cy))
                    }
                }

                if (xe - xs > 0.5f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(sky, blue), startX = xs, endX = max(xe, xs + 1f)),
                        topLeft = Offset(xs, cy - th / 2f),
                        size = Size(xe - xs, th),
                        cornerRadius = radius,
                    )
                }

                fun thumb(x: Float, r: Float, isActive: Boolean) {
                    if (isActive && glow > 0.01f) {
                        drawCircle(blue.copy(alpha = 0.16f * glow), radius = r + 9.dp.toPx() * glow, center = Offset(x, cy))
                    }
                    drawCircle(Color.Black.copy(alpha = 0.10f), radius = r + 1.5.dp.toPx(), center = Offset(x, cy + 1.dp.toPx()))
                    drawCircle(Color.White, radius = r, center = Offset(x, cy))
                    drawCircle(blue, radius = r - 1.5.dp.toPx(), center = Offset(x, cy), style = Stroke(width = 3.dp.toPx()))
                }
                // Núm đang kéo vẽ SAU để nằm trên núm kia khi hai núm trùng nhau
                if (which == 0) { thumb(xe, rE.toPx(), false); thumb(xs, rS.toPx(), pressed) }
                else            { thumb(xs, rS.toPx(), false); thumb(xe, rE.toPx(), pressed) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Phần dùng chung
// ─────────────────────────────────────────────────────────────

@Composable
private fun SliderHeader(label: String, display: String, active: Boolean) {
    val bg by animateColorAsState(
        targetValue   = if (active) AtColors.Blue else AtColors.AccentSoft,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label         = "at_slider_pill_bg",
    )
    val fg by animateColorAsState(
        targetValue   = if (active) Color.White else AtColors.Accent,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label         = "at_slider_pill_fg",
    )
    val pop by animateFloatAsState(
        targetValue   = if (active) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label         = "at_slider_pill_pop",
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        if (label.isNotBlank()) {
            Text(label, color = AtColors.TextSec, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Box(
            Modifier
                .graphicsLayer { scaleX = pop; scaleY = pop }
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(display, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EndLabels(labels: Pair<String, String>?) {
    if (labels == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(labels.first,  color = AtColors.TextMuted, fontSize = 10.sp)
        Text(labels.second, color = AtColors.TextMuted, fontSize = 10.sp)
    }
}

/** Gọi [onChange] mỗi khi trạng thái "đang chạm" đổi (để ô giá trị ở đầu hàng đổi màu). */
@Composable
private fun LaunchedEffectOnPressed(pressed: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(pressed) { onChange(pressed) }
}

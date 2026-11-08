package com.atpro.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.ui.theme.AtColors

// ─────────────────────────────────────────────────────────────────────────────
//  Biểu đồ tự vẽ bằng Canvas (v1.10.0) — không thêm thư viện. Mọi biểu đồ "vẽ dần" khi hiện / khi dữ liệu đổi;
//  giá trị animation chỉ đọc trong phase vẽ (draw scope) → không recompose mỗi khung hình.
// ─────────────────────────────────────────────────────────────────────────────

/** Tiến độ vẽ 0→1, chạy lại mỗi khi [key] đổi. */
@Composable
private fun rememberDrawProgress(key: Any?, durationMs: Int = 700): Animatable<Float, *> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return progress
}

/**
 * Biểu đồ cột. [lower] (tuỳ chọn) = phần dưới của cột xếp chồng, vẽ bằng [lowerColor]; phần còn lại của [values] vẽ bằng [color].
 * Chạm một cột để xem giá trị; chạm lại để bỏ chọn. Nhãn trục chỉ hiện đầu / giữa / cuối.
 */
@Composable
fun AtBarChart(
    values:      List<Float>,
    labels:      List<String>,
    modifier:    Modifier = Modifier,
    color:       Color = AtColors.Blue,
    lower:       List<Float>? = null,
    lowerColor:  Color = AtColors.Sky,
    height:      Dp = 140.dp,
    format:      (Float) -> String = { "%.0f".format(it) },
) {
    var selected by remember(values) { mutableStateOf(-1) }
    val progress = rememberDrawProgress(values)
    val maxV = (values.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)
    val gridColor = AtColors.Hairline

    Column(modifier) {
        Text(
            text = if (selected in values.indices) "${labels.getOrElse(selected) { "" }}  ·  ${format(values[selected])}"
                   else "Chạm cột để xem chi tiết",
            color = if (selected in values.indices) AtColors.TextPrim else AtColors.TextMuted,
            fontSize = 12.sp, fontWeight = if (selected in values.indices) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(values) {
                    detectTapGestures { off ->
                        val n = values.size
                        if (n > 0) {
                            val idx = (off.x / (size.width / n)).toInt().coerceIn(0, n - 1)
                            selected = if (selected == idx) -1 else idx
                        }
                    }
                },
        ) {
            val n = values.size
            if (n == 0) return@Canvas
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val slot = size.width / n
            val barW = (slot * 0.62f).coerceAtMost(26.dp.toPx())
            val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            for (i in 0 until n) {
                val v = values[i].coerceAtLeast(0f)
                val h = size.height * (v / maxV) * progress.value
                if (h <= 0.5f) continue
                val x = slot * i + (slot - barW) / 2f
                val active = selected == -1 || selected == i
                val topColor = color.copy(alpha = if (active) 1f else 0.35f)
                drawRoundRect(topColor, Offset(x, size.height - h), Size(barW, h), radius)
                val low = lower?.getOrNull(i)?.coerceIn(0f, v) ?: 0f
                if (low > 0f) {
                    val lh = size.height * (low / maxV) * progress.value
                    drawRoundRect(
                        lowerColor.copy(alpha = if (active) 1f else 0.35f),
                        Offset(x, size.height - lh), Size(barW, lh), radius,
                    )
                }
            }
        }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(labels.first(), color = AtColors.TextMuted, fontSize = 10.sp)
                if (labels.size > 2) Text(labels[labels.size / 2], color = AtColors.TextMuted, fontSize = 10.sp)
                if (labels.size > 1) Text(labels.last(), color = AtColors.TextMuted, fontSize = 10.sp)
            }
        }
    }
}

/** Biểu đồ vùng (đường + tô gradient), vẽ dần từ trái sang phải. */
@Composable
fun AtAreaChart(
    values:   List<Float>,
    labels:   List<String>,
    modifier: Modifier = Modifier,
    color:    Color = AtColors.Blue,
    height:   Dp = 110.dp,
) {
    val progress = rememberDrawProgress(values, 900)
    val maxV = (values.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size
            if (n == 0) return@Canvas
            val pad = 4.dp.toPx()
            fun px(i: Int) = if (n == 1) size.width / 2f else pad + (size.width - 2 * pad) * i / (n - 1)
            fun py(v: Float) = size.height - pad - (size.height - 2 * pad) * (v / maxV)

            val line = Path().apply {
                moveTo(px(0), py(values[0]))
                for (i in 1 until n) {
                    // đường cong mượt: điểm điều khiển ở giữa theo trục X
                    val cx = (px(i - 1) + px(i)) / 2f
                    cubicTo(cx, py(values[i - 1]), cx, py(values[i]), px(i), py(values[i]))
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(px(n - 1), size.height); lineTo(px(0), size.height); close()
            }
            clipRect(right = size.width * progress.value) {
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
                drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (progress.value >= 0.99f) {
                drawCircle(color, 4.dp.toPx(), Offset(px(n - 1), py(values[n - 1])))
                drawCircle(Color.White, 1.8.dp.toPx(), Offset(px(n - 1), py(values[n - 1])))
            }
        }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(labels.first(), color = AtColors.TextMuted, fontSize = 10.sp)
                if (labels.size > 1) Text(labels.last(), color = AtColors.TextMuted, fontSize = 10.sp)
            }
        }
    }
}

/** Biểu đồ tròn rỗng giữa: [parts] = (giá trị, màu); chữ [centerTop]/[centerBottom] ở giữa. */
@Composable
fun AtDonutChart(
    parts:        List<Pair<Float, Color>>,
    centerTop:    String,
    centerBottom: String,
    modifier:     Modifier = Modifier,
    size:         Dp = 116.dp,
) {
    val progress = rememberDrawProgress(parts)
    val total = parts.sumOf { it.first.toDouble() }.toFloat()
    val track = AtColors.SurfaceHigh
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (total > 0f) {
                var start = -90f
                for ((v, c) in parts) {
                    if (v <= 0f) continue
                    val sweep = 360f * (v / total) * progress.value
                    val gap = if (parts.count { it.first > 0f } > 1) 2f else 0f
                    drawArc(c, start, (sweep - gap).coerceAtLeast(0.5f), false, Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, color = AtColors.TextPrim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(centerBottom, color = AtColors.TextMuted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Danh sách thanh ngang xếp hạng: mỗi hàng = nhãn · thanh · giá trị; thanh chạy dần, so le theo thứ tự. */
@Composable
fun AtRankBars(
    items:    List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    color:    Color = AtColors.Blue,
    format:   (Float) -> String = { "%.0f".format(it) },
) {
    val maxV = (items.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(0.0001f)
    var shown by remember(items) { mutableStateOf(false) }
    LaunchedEffect(items) { shown = true }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, (label, v) ->
            val frac by animateFloatAsState(
                targetValue   = if (shown) (v / maxV).coerceIn(0f, 1f) else 0f,
                animationSpec = tween(600, delayMillis = i * 70, easing = FastOutSlowInEasing),
                label         = "rank_bar",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, color = AtColors.TextSec, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(92.dp),
                )
                Box(
                    Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(AtColors.SurfaceHigh),
                ) {
                    Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                }
                Text(
                    format(v), color = AtColors.TextPrim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(56.dp).padding(start = 8.dp),
                )
            }
        }
    }
}

/** Dòng chú giải: chấm màu · nhãn · giá trị. */
@Composable
fun AtLegendRow(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = AtColors.TextSec, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = AtColors.TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

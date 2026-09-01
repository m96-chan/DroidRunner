package dev.devenus.droidrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.devenus.droidrunner.ui.theme.BtopColors
import java.util.Locale

/** btop-style bordered box with its title inset into the top border line. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color = BtopColors.Cyan,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.padding(top = 7.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, BtopColors.Border, RoundedCornerShape(6.dp))
                .background(BtopColors.Panel, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            content = content,
        )
        Text(
            "┤ $title ├",
            color = titleColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .offset(x = 10.dp, y = (-7).dp)
                .background(BtopColors.Background)
                .padding(horizontal = 2.dp),
        )
    }
}

/** Horizontal load meter: `label ▐███░░░▌ 42%`. */
@Composable
fun Meter(
    label: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    detail: String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
    color: Color = BtopColors.forLoad(fraction.coerceIn(0f, 1f)),
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) {
            Text(
                label,
                color = BtopColors.Dim,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.width(44.dp),
            )
        }
        Canvas(
            Modifier
                .weight(1f)
                .height(10.dp),
        ) {
            val radius = CornerRadius(3.dp.toPx())
            drawRoundRect(color = BtopColors.Border.copy(alpha = 0.45f), cornerRadius = radius)
            if (clamped > 0.01f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        0f to BtopColors.Green.copy(alpha = 0.85f),
                        1f to color,
                        endX = size.width * clamped,
                    ),
                    size = Size(size.width * clamped, size.height),
                    cornerRadius = radius,
                )
            }
        }
        Text(
            detail,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Filled area chart of 0..1 samples, newest on the right. */
@Composable
fun HistoryGraph(
    history: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    color: Color = BtopColors.Cyan,
    capacity: Int = 120,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        // Faint horizontal grid at 25/50/75%.
        for (line in 1..3) {
            val y = size.height * line / 4f
            drawLine(BtopColors.Border.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (history.size < 2) return@Canvas
        val step = size.width / (capacity - 1).coerceAtLeast(1)
        val startX = size.width - step * (history.size - 1)
        fun x(i: Int) = startX + step * i
        fun y(v: Float) = size.height * (1f - v.coerceIn(0f, 1f))

        val line = Path().apply {
            moveTo(x(0), y(history[0]))
            for (i in 1 until history.size) lineTo(x(i), y(history[i]))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(history.size - 1), size.height)
            lineTo(x(0), size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f))),
        )
        drawPath(line, color, style = Stroke(width = 2f))
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val units = arrayOf("K", "M", "G", "T")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, if (value >= 10) "%.0f%s" else "%.1f%s", value, units[unit])
}

fun formatUptime(sinceMillis: Long): String {
    val seconds = (System.currentTimeMillis() - sinceMillis) / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

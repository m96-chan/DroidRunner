package dev.devenus.droidrunner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Terminal palette loosely modeled on btop's default theme. */
object BtopColors {
    val Background = Color(0xFF0B0E14)
    val Panel = Color(0xFF11151F)
    val Border = Color(0xFF2B3245)
    val Text = Color(0xFFCDD3E0)
    val Dim = Color(0xFF6B7385)
    val Cyan = Color(0xFF52D0DB)
    val Green = Color(0xFF9ECE6A)
    val Yellow = Color(0xFFE0AF68)
    val Red = Color(0xFFF7768E)
    val Magenta = Color(0xFFBB9AF7)
    val Blue = Color(0xFF7AA2F7)

    /** Load color: green below 60%, yellow to 85%, red above. */
    fun forLoad(fraction: Float): Color = when {
        fraction < 0.6f -> lerp(Green, Yellow, (fraction / 0.6f) * 0.5f)
        fraction < 0.85f -> lerp(Yellow, Red, (fraction - 0.6f) / 0.25f * 0.6f)
        else -> Red
    }
}

private val monoTextStyle = TextStyle(fontFamily = FontFamily.Monospace)

private val btopTypography = Typography(
    headlineMedium = monoTextStyle.copy(fontSize = 22.sp),
    titleMedium = monoTextStyle.copy(fontSize = 15.sp),
    bodyLarge = monoTextStyle.copy(fontSize = 14.sp),
    bodyMedium = monoTextStyle.copy(fontSize = 13.sp),
    bodySmall = monoTextStyle.copy(fontSize = 11.sp),
    labelLarge = monoTextStyle.copy(fontSize = 13.sp),
    labelMedium = monoTextStyle.copy(fontSize = 11.sp),
    labelSmall = monoTextStyle.copy(fontSize = 10.sp),
)

@Composable
fun BtopTheme(content: @Composable () -> Unit) {
    // The dashboard is a terminal aesthetic: always dark, regardless of system theme.
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BtopColors.Cyan,
            secondary = BtopColors.Magenta,
            background = BtopColors.Background,
            surface = BtopColors.Panel,
            surfaceVariant = BtopColors.Panel,
            onPrimary = BtopColors.Background,
            onBackground = BtopColors.Text,
            onSurface = BtopColors.Text,
            onSurfaceVariant = BtopColors.Dim,
            outline = BtopColors.Border,
            error = BtopColors.Red,
        ),
        typography = btopTypography,
        content = content,
    )
}

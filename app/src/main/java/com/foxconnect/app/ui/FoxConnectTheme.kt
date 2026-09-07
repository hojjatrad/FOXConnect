package com.foxconnect.app.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val CanvasColor = Color(0xFF101411)
val SurfaceColor = Color(0xFF171C18)
val SurfaceVariantColor = Color(0xFF222A24)
val InkColor = Color(0xFFE8F1EA)
val MutedColor = Color(0xFFB9C6BB)
val DividerColor = Color(0xFF3B473E)
val PrimaryIdleColor = Color(0xFFA4D8BB)
val ConnectedColor = Color(0xFF67D89C)
val ErrorColor = Color(0xFFFFB4AB)

private val FoxDarkColors = darkColorScheme(
    primary = PrimaryIdleColor,
    onPrimary = CanvasColor,
    secondary = ConnectedColor,
    onSecondary = CanvasColor,
    error = ErrorColor,
    onError = CanvasColor,
    background = CanvasColor,
    onBackground = InkColor,
    surface = SurfaceColor,
    onSurface = InkColor,
    surfaceVariant = SurfaceVariantColor,
    onSurfaceVariant = MutedColor,
    outline = DividerColor,
)

private val FoxTypography = Typography(
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
    ),
    bodySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
)

@Composable
fun FoxConnectTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = FoxDarkColors, typography = FoxTypography, content = content)
}

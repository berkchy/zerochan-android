package net.berkchy.zerochan.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val ZerochanPink = Color(0xFFE6007E)
private val ZerochanPinkDark = Color(0xFFB0005C)
private val Ink = Color(0xFF14121A)
private val Surface = Color(0xFFFDF7FA)
private val SurfaceDark = Color(0xFF14121A)

private val LightColors = lightColorScheme(
    primary = ZerochanPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E7),
    onPrimaryContainer = Color(0xFF3E0021),
    secondary = Color(0xFF7D5260),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E7),
    onSecondaryContainer = Color(0xFF31111D),
    tertiary = Color(0xFF7A5733),
    onTertiary = Color.White,
    background = Surface,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1DDE4),
    onSurfaceVariant = Color(0xFF504349),
    surfaceContainerHighest = Color(0xFFEAD5DD),
    outline = Color(0xFF82737A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1D2),
    onPrimary = Color(0xFF64103F),
    primaryContainer = ZerochanPinkDark,
    onPrimaryContainer = Color(0xFFFFD9E7),
    secondary = Color(0xFFEFB8C8),
    onSecondary = Color(0xFF492532),
    secondaryContainer = Color(0xFF633B48),
    onSecondaryContainer = Color(0xFFFFD9E7),
    tertiary = Color(0xFFEDBE8E),
    onTertiary = Color(0xFF462A0B),
    background = SurfaceDark,
    onBackground = Color(0xFFEAE0E4),
    surface = SurfaceDark,
    onSurface = Color(0xFFEAE0E4),
    surfaceVariant = Color(0xFF504349),
    onSurfaceVariant = Color(0xFFD4C2C9),
    surfaceContainerHighest = Color(0xFF3A3338),
    outline = Color(0xFF9E8D94),
)

@Composable
fun ZerochanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZerochanTypography,
        content = content,
    )
}
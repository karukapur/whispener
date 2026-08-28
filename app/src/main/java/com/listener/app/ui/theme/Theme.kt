package com.listener.app.ui.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ListenerLightColors = lightColorScheme(
    primary = Color(0xFF315F8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF0C355A),
    inversePrimary = Color(0xFFA2C9F5),
    primaryFixed = Color(0xFFD3E4FF),
    primaryFixedDim = Color(0xFFA2C9F5),
    onPrimaryFixed = Color(0xFF001D35),
    onPrimaryFixedVariant = Color(0xFF174B73),
    secondary = Color(0xFF4E616F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E5F5),
    onSecondaryContainer = Color(0xFF273B48),
    secondaryFixed = Color(0xFFD1E5F5),
    secondaryFixedDim = Color(0xFFB5C9D8),
    onSecondaryFixed = Color(0xFF0A1E29),
    onSecondaryFixedVariant = Color(0xFF374A56),
    tertiary = Color(0xFF66587B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECDBFF),
    onTertiaryContainer = Color(0xFF493E5D),
    tertiaryFixed = Color(0xFFECDBFF),
    tertiaryFixedDim = Color(0xFFD0BFE8),
    onTertiaryFixed = Color(0xFF21182F),
    onTertiaryFixedVariant = Color(0xFF4E4461),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF18212A),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF18212A),
    surfaceTint = Color(0xFF315F8C),
    inverseSurface = Color(0xFF2D3135),
    inverseOnSurface = Color(0xFFEFF1F5),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484D),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF7F9FC),
    surfaceDim = Color(0xFFD7DADE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F3F7),
    surfaceContainer = Color(0xFFEBEEF2),
    surfaceContainerHigh = Color(0xFFE5E8EC),
    surfaceContainerHighest = Color(0xFFDFE2E6),
)

private val ListenerDarkColors = darkColorScheme(
    primary = Color(0xFFA2C9F5),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF174B73),
    onPrimaryContainer = Color(0xFFD3E4FF),
    inversePrimary = Color(0xFF315F8C),
    primaryFixed = Color(0xFFD3E4FF),
    primaryFixedDim = Color(0xFFA2C9F5),
    onPrimaryFixed = Color(0xFF001D35),
    onPrimaryFixedVariant = Color(0xFF174B73),
    secondary = Color(0xFFB5C9D8),
    onSecondary = Color(0xFF20333F),
    secondaryContainer = Color(0xFF374A56),
    onSecondaryContainer = Color(0xFFD1E5F5),
    secondaryFixed = Color(0xFFD1E5F5),
    secondaryFixedDim = Color(0xFFB5C9D8),
    onSecondaryFixed = Color(0xFF0A1E29),
    onSecondaryFixedVariant = Color(0xFF374A56),
    tertiary = Color(0xFFD0BFE8),
    onTertiary = Color(0xFF372D49),
    tertiaryContainer = Color(0xFF4E4461),
    onTertiaryContainer = Color(0xFFECDBFF),
    tertiaryFixed = Color(0xFFECDBFF),
    tertiaryFixedDim = Color(0xFFD0BFE8),
    onTertiaryFixed = Color(0xFF21182F),
    onTertiaryFixedVariant = Color(0xFF4E4461),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E3E8),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE0E3E8),
    surfaceTint = Color(0xFFA2C9F5),
    inverseSurface = Color(0xFFE0E3E8),
    inverseOnSurface = Color(0xFF2D3135),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    outline = Color(0xFF8B9298),
    outlineVariant = Color(0xFF41484D),
    scrim = Color.Black,
    surfaceBright = Color(0xFF36393D),
    surfaceDim = Color(0xFF101418),
    surfaceContainerLowest = Color(0xFF0B0F12),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
)

private val BaseTypography = Typography()

private val ListenerTypography = Typography(
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(lineHeight = 24.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val ListenerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

object ListenerSpacing {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp
}

object ListenerMotion {
    const val FastDurationMillis = 180
    const val DefaultDurationMillis = 320
    val EmphasisEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

@Composable
fun ListenerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(LocalContext.current)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(LocalContext.current)
        darkTheme -> ListenerDarkColors
        else -> ListenerLightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = ListenerTypography,
        shapes = ListenerShapes,
        content = content,
    )
}

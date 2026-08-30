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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.listener.app.R

private val ListenerLightColors = lightColorScheme(
    primary = Color(0xFF246EAF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FF),
    onPrimaryContainer = Color(0xFF061A35),
    inversePrimary = Color(0xFF9EC8F2),
    primaryFixed = Color(0xFFEAF2FF),
    primaryFixedDim = Color(0xFFCFE4FF),
    onPrimaryFixed = Color(0xFF061A35),
    onPrimaryFixedVariant = Color(0xFF184E83),
    secondary = Color(0xFF6B4CA0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFE7FF),
    onSecondaryContainer = Color(0xFF2C174D),
    secondaryFixed = Color(0xFFEFE7FF),
    secondaryFixedDim = Color(0xFFD9C8FF),
    onSecondaryFixed = Color(0xFF201139),
    onSecondaryFixedVariant = Color(0xFF563781),
    tertiary = Color(0xFF7851A8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E9FF),
    onTertiaryContainer = Color(0xFF2A1645),
    tertiaryFixed = Color(0xFFF3E9FF),
    tertiaryFixedDim = Color(0xFFDCC6FF),
    onTertiaryFixed = Color(0xFF201139),
    onTertiaryFixedVariant = Color(0xFF5B3A86),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFEEE2D8),
    onBackground = Color(0xFF061A35),
    surface = Color(0xFFEEE2D8),
    onSurface = Color(0xFF061A35),
    surfaceTint = Color(0xFF246EAF),
    inverseSurface = Color(0xFF1F2937),
    inverseOnSurface = Color(0xFFF4F7FB),
    surfaceVariant = Color(0xFFE6E8F4),
    onSurfaceVariant = Color(0xFF575D74),
    outline = Color(0xFF7D8498),
    outlineVariant = Color(0xFFDCE2F2),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFF9F4),
    surfaceDim = Color(0xFFE0D4CB),
    surfaceContainerLowest = Color(0xFFFFF9F4),
    surfaceContainerLow = Color(0xFFF7EDE5),
    surfaceContainer = Color(0xFFEEE2D8),
    surfaceContainerHigh = Color(0xFFE6D8CE),
    surfaceContainerHighest = Color(0xFFDCCEC4),
)

private val ListenerDarkColors = darkColorScheme(
    primary = Color(0xFF9EC8F2),
    onPrimary = Color(0xFF003358),
    primaryContainer = Color(0xFF16486F),
    onPrimaryContainer = Color(0xFFEAF2FF),
    inversePrimary = Color(0xFF246EAF),
    primaryFixed = Color(0xFFEAF2FF),
    primaryFixedDim = Color(0xFFCFE4FF),
    onPrimaryFixed = Color(0xFF001D35),
    onPrimaryFixedVariant = Color(0xFF184E83),
    secondary = Color(0xFFD9C8FF),
    onSecondary = Color(0xFF3B245B),
    secondaryContainer = Color(0xFF4B3371),
    onSecondaryContainer = Color(0xFFEFE7FF),
    secondaryFixed = Color(0xFFEFE7FF),
    secondaryFixedDim = Color(0xFFD9C8FF),
    onSecondaryFixed = Color(0xFF201139),
    onSecondaryFixedVariant = Color(0xFF563781),
    tertiary = Color(0xFFDCC6FF),
    onTertiary = Color(0xFF432567),
    tertiaryContainer = Color(0xFF58377F),
    onTertiaryContainer = Color(0xFFF3E9FF),
    tertiaryFixed = Color(0xFFF3E9FF),
    tertiaryFixedDim = Color(0xFFDCC6FF),
    onTertiaryFixed = Color(0xFF201139),
    onTertiaryFixedVariant = Color(0xFF5B3A86),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111827),
    onBackground = Color(0xFFEAF2FF),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFEAF2FF),
    surfaceTint = Color(0xFF9EC8F2),
    inverseSurface = Color(0xFFEAF2FF),
    inverseOnSurface = Color(0xFF1F2937),
    surfaceVariant = Color(0xFF3C4256),
    onSurfaceVariant = Color(0xFFC9D1E8),
    outline = Color(0xFF9AA3B8),
    outlineVariant = Color(0xFF40485E),
    scrim = Color.Black,
    surfaceBright = Color(0xFF333B4F),
    surfaceDim = Color(0xFF111827),
    surfaceContainerLowest = Color(0xFF0B1020),
    surfaceContainerLow = Color(0xFF172033),
    surfaceContainer = Color(0xFF1E2940),
    surfaceContainerHigh = Color(0xFF26334C),
    surfaceContainerHighest = Color(0xFF31405B),
)

private val BaseTypography = Typography()

private val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_light, FontWeight.Light),
    Font(R.font.chakra_petch_regular, FontWeight.Normal),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
)

private val AppTextFont = FontFamily.SansSerif

private val ListenerTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = ChakraPetch),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.Normal, fontSize = 26.sp),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = ChakraPetch),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = ChakraPetch),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.Normal, fontSize = 26.sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = AppTextFont, lineHeight = 24.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = AppTextFont),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = AppTextFont),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = AppTextFont, fontWeight = FontWeight.Medium),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = AppTextFont, fontWeight = FontWeight.Normal),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = AppTextFont, fontWeight = FontWeight.Normal),
)

private val ListenerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
    dynamicColor: Boolean = false,
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

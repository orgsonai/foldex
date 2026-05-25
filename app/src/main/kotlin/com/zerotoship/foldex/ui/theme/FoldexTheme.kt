package com.zerotoship.foldex.ui.theme

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

// Forest Green — Foldex 独自カラー (動的カラー非対応端末 / 動的カラー OFF 時のフォールバック)。
//
// 以前は primary だけを緑に差し替え、残りの色 (secondary/tertiary/各 container/surface 等) が
// Material 既定の紫系のままだったため、緑×紫がちぐはぐだった。ここで緑シード (#2E7D32) から
// 起こした一貫したトーナルパレットを light / dark 両方ぶん定義する。
private val FoldexGreen = Color(0xFF2E7D32)

private val LightColors = lightColorScheme(
    primary = FoldexGreen,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2F0A9),
    onPrimaryContainer = Color(0xFF00210A),
    inversePrimary = Color(0xFF88D982),
    secondary = Color(0xFF52634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8CD),
    onSecondaryContainer = Color(0xFF101F0F),
    tertiary = Color(0xFF38656A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF1),
    onTertiaryContainer = Color(0xFF002023),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FBF0),
    onBackground = Color(0xFF191D16),
    surface = Color(0xFFF7FBF0),
    onSurface = Color(0xFF191D16),
    surfaceVariant = Color(0xFFDDE5D8),
    onSurfaceVariant = Color(0xFF424940),
    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC1C9BC),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEEF2E9),
    surfaceTint = FoldexGreen,
    surfaceBright = Color(0xFFF7FBF0),
    surfaceDim = Color(0xFFD7DBD0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EA),
    surfaceContainer = Color(0xFFEBEFE5),
    surfaceContainerHigh = Color(0xFFE5E9DF),
    surfaceContainerHighest = Color(0xFFDFE4D9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF97D788),
    onPrimary = Color(0xFF033910),
    primaryContainer = Color(0xFF1C5121),
    onPrimaryContainer = Color(0xFFB2F0A9),
    inversePrimary = FoldexGreen,
    secondary = Color(0xFFB9CCB1),
    onSecondary = Color(0xFF243423),
    secondaryContainer = Color(0xFF3A4B38),
    onSecondaryContainer = Color(0xFFD5E8CD),
    tertiary = Color(0xFFA0CFD5),
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF1E4D52),
    onTertiaryContainer = Color(0xFFBCEBF1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF10140F),
    onBackground = Color(0xFFE0E4DA),
    surface = Color(0xFF10140F),
    onSurface = Color(0xFFE0E4DA),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC1C9BC),
    outline = Color(0xFF8B9387),
    outlineVariant = Color(0xFF424940),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE0E4DA),
    inverseOnSurface = Color(0xFF2D322C),
    surfaceTint = Color(0xFF97D788),
    surfaceBright = Color(0xFF363A33),
    surfaceDim = Color(0xFF10140F),
    surfaceContainerLowest = Color(0xFF0B0F0A),
    surfaceContainerLow = Color(0xFF191D16),
    surfaceContainer = Color(0xFF1D211A),
    surfaceContainerHigh = Color(0xFF272B24),
    surfaceContainerHighest = Color(0xFF32362E),
)

@Composable
fun FoldexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

package com.zerotoship.foldex.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Forest Green — Foldex 独自カラー (動的カラー非対応端末 / 動的カラー OFF 時のフォールバック)。
//
// 方針: 地の面 (background / surface / surfaceContainer 群) は **ニュートラルなグレー** にし、
// 緑は primary とアクセントの container (HOME のタイル色など) にだけ使う。
// 以前は surface まで緑に色付けして「全体的に緑っぽい」状態になっていたのでニュートラルへ戻した。
private val FoldexGreen = Color(0xFF2E7D32)

private val LightColors = lightColorScheme(
    // --- 緑アクセント ---
    primary = FoldexGreen,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2F0A9),
    onPrimaryContainer = Color(0xFF00210A),
    inversePrimary = Color(0xFF88D982),
    secondary = Color(0xFF4F6352),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E8D4),
    onSecondaryContainer = Color(0xFF0E1F12),
    tertiary = Color(0xFF38656A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF1),
    onTertiaryContainer = Color(0xFF002023),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    // --- ニュートラルな地の面 (グレー、緑の色被りなし) ---
    background = Color(0xFFFCFCFC),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE3E3E3),
    onSurfaceVariant = Color(0xFF47474A),
    outline = Color(0xFF787779),
    outlineVariant = Color(0xFFC7C7C7),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF3F2F2),
    surfaceTint = FoldexGreen,
    surfaceBright = Color(0xFFFCFCFC),
    surfaceDim = Color(0xFFDDDCDC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF1F1F1),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE6E6E6),
)

private val DarkColors = darkColorScheme(
    // --- 緑アクセント ---
    primary = Color(0xFF97D788),
    onPrimary = Color(0xFF033910),
    primaryContainer = Color(0xFF1C5121),
    onPrimaryContainer = Color(0xFFB2F0A9),
    inversePrimary = FoldexGreen,
    secondary = Color(0xFFB6CCB6),
    onSecondary = Color(0xFF243423),
    secondaryContainer = Color(0xFF3A4B38),
    onSecondaryContainer = Color(0xFFD2E8D4),
    tertiary = Color(0xFFA0CFD5),
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF1E4D52),
    onTertiaryContainer = Color(0xFFBCEBF1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    // --- ニュートラルな地の面 (グレー、緑の色被りなし) ---
    background = Color(0xFF131313),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131313),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF46464A),
    onSurfaceVariant = Color(0xFFC7C7C7),
    outline = Color(0xFF919191),
    outlineVariant = Color(0xFF464646),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE3E3E3),
    inverseOnSurface = Color(0xFF303030),
    surfaceTint = Color(0xFF97D788),
    surfaceBright = Color(0xFF393939),
    surfaceDim = Color(0xFF131313),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF353535),
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

    // ステータスバー / ナビバーのアイコン明暗を「アプリの実テーマ」に合わせる。
    // enableEdgeToEdge() はシステムの dark/light を基準にするため、手動でライト/ダークを
    // 選んでいると、ライトなのに白アイコン (= 背景と同化して時計が見えない) になっていた。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            // ライト時は暗いアイコン、ダーク時は明るいアイコン。
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

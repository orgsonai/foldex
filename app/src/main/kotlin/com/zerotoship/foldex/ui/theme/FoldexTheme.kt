// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import com.zerotoship.foldex.core.model.AppColorTheme

// Foldex のフォールバック配色 (動的カラー非対応端末 / Material You OFF 時)。
//
// 方針: 地の面 (background / surface / surfaceContainer 群) は **どの配色でも共通のニュートラルなグレー**
// にし、色味は primary とアクセントの container (HOME のタイル色など) にだけ乗せる。
// こうすることで「全体が○色っぽい」状態を避けつつ、ユーザーが好みのアクセント色を選べる。
//
// 各配色は [AccentColors] (primary/secondary/tertiary 群) だけを持ち、地の面は
// [LightNeutral] / [DarkNeutral] を共有する。

/** アクセント配色 1 モード分 (ライト or ダーク)。地の面は含まない。 */
private data class AccentColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

// --- ニュートラルな地の面 (全配色共通) ---

private fun lightSchemeWith(a: AccentColors): ColorScheme = lightColorScheme(
    primary = a.primary,
    onPrimary = a.onPrimary,
    primaryContainer = a.primaryContainer,
    onPrimaryContainer = a.onPrimaryContainer,
    inversePrimary = a.inversePrimary,
    secondary = a.secondary,
    onSecondary = a.onSecondary,
    secondaryContainer = a.secondaryContainer,
    onSecondaryContainer = a.onSecondaryContainer,
    tertiary = a.tertiary,
    onTertiary = a.onTertiary,
    tertiaryContainer = a.tertiaryContainer,
    onTertiaryContainer = a.onTertiaryContainer,
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    // --- ニュートラルな地の面 (グレー、色被りなし) ---
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
    surfaceTint = a.primary,
    surfaceBright = Color(0xFFFCFCFC),
    surfaceDim = Color(0xFFDDDCDC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF1F1F1),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE6E6E6),
)

private fun darkSchemeWith(a: AccentColors): ColorScheme = darkColorScheme(
    primary = a.primary,
    onPrimary = a.onPrimary,
    primaryContainer = a.primaryContainer,
    onPrimaryContainer = a.onPrimaryContainer,
    inversePrimary = a.inversePrimary,
    secondary = a.secondary,
    onSecondary = a.onSecondary,
    secondaryContainer = a.secondaryContainer,
    onSecondaryContainer = a.onSecondaryContainer,
    tertiary = a.tertiary,
    onTertiary = a.onTertiary,
    tertiaryContainer = a.tertiaryContainer,
    onTertiaryContainer = a.onTertiaryContainer,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    // --- ニュートラルな地の面 (グレー、色被りなし) ---
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
    surfaceTint = a.primary,
    surfaceBright = Color(0xFF393939),
    surfaceDim = Color(0xFF131313),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF353535),
)

// --- 各配色のアクセント定義 (ライト / ダーク) ---

private val GreenLight = AccentColors(
    primary = Color(0xFF2E7D32), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2F0A9), onPrimaryContainer = Color(0xFF00210A),
    inversePrimary = Color(0xFF88D982),
    secondary = Color(0xFF4F6352), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E8D4), onSecondaryContainer = Color(0xFF0E1F12),
    tertiary = Color(0xFF38656A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF1), onTertiaryContainer = Color(0xFF002023),
)
private val GreenDark = AccentColors(
    primary = Color(0xFF97D788), onPrimary = Color(0xFF033910),
    primaryContainer = Color(0xFF1C5121), onPrimaryContainer = Color(0xFFB2F0A9),
    inversePrimary = Color(0xFF2E7D32),
    secondary = Color(0xFFB6CCB6), onSecondary = Color(0xFF243423),
    secondaryContainer = Color(0xFF3A4B38), onSecondaryContainer = Color(0xFFD2E8D4),
    tertiary = Color(0xFFA0CFD5), onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF1E4D52), onTertiaryContainer = Color(0xFFBCEBF1),
)

private val BlueLight = AccentColors(
    primary = Color(0xFF1565C0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF), onPrimaryContainer = Color(0xFF001B3D),
    inversePrimary = Color(0xFFA6C8FF),
    secondary = Color(0xFF545F71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E3F8), onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF6D5676), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6D9FF), onTertiaryContainer = Color(0xFF271430),
)
private val BlueDark = AccentColors(
    primary = Color(0xFFA6C8FF), onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF00468C), onPrimaryContainer = Color(0xFFD5E3FF),
    inversePrimary = Color(0xFF1565C0),
    secondary = Color(0xFFBCC7DC), onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3D4758), onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFFDABDE3), onTertiary = Color(0xFF3D2946),
    tertiaryContainer = Color(0xFF553F5E), onTertiaryContainer = Color(0xFFF6D9FF),
)

private val PurpleLight = AccentColors(
    primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFD0BCFF),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
)
private val PurpleDark = AccentColors(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    inversePrimary = Color(0xFF6750A4),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
)

private val TealLight = AccentColors(
    primary = Color(0xFF00696E), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6FF6FE), onPrimaryContainer = Color(0xFF002022),
    inversePrimary = Color(0xFF4DD9E1),
    secondary = Color(0xFF4A6363), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7), onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E4FF), onTertiaryContainer = Color(0xFF041C35),
)
private val TealDark = AccentColors(
    primary = Color(0xFF4DD9E1), onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF004F53), onPrimaryContainer = Color(0xFF6FF6FE),
    inversePrimary = Color(0xFF00696E),
    secondary = Color(0xFFB0CCCB), onSecondary = Color(0xFF1B3435),
    secondaryContainer = Color(0xFF324B4B), onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFB3C8E8), onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF334863), onTertiaryContainer = Color(0xFFD3E4FF),
)

private val OrangeLight = AccentColors(
    primary = Color(0xFF9A4600), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC9), onPrimaryContainer = Color(0xFF331200),
    inversePrimary = Color(0xFFFFB68F),
    secondary = Color(0xFF765848), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBC9), onSecondaryContainer = Color(0xFF2B1709),
    tertiary = Color(0xFF5F6135), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5E6AE), onTertiaryContainer = Color(0xFF1C1D00),
)
private val OrangeDark = AccentColors(
    primary = Color(0xFFFFB68F), onPrimary = Color(0xFF522300),
    primaryContainer = Color(0xFF743500), onPrimaryContainer = Color(0xFFFFDBC9),
    inversePrimary = Color(0xFF9A4600),
    secondary = Color(0xFFE6BEAB), onSecondary = Color(0xFF442A1B),
    secondaryContainer = Color(0xFF5D4030), onSecondaryContainer = Color(0xFFFFDBC9),
    tertiary = Color(0xFFC9C983), onTertiary = Color(0xFF313300),
    tertiaryContainer = Color(0xFF474A1E), onTertiaryContainer = Color(0xFFE5E6AE),
)

private val RoseLight = AccentColors(
    primary = Color(0xFFB02F4E), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9DE), onPrimaryContainer = Color(0xFF40000F),
    inversePrimary = Color(0xFFFFB2BC),
    secondary = Color(0xFF75565A), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9DE), onSecondaryContainer = Color(0xFF2C151A),
    tertiary = Color(0xFF785831), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB6), onTertiaryContainer = Color(0xFF2A1700),
)
private val RoseDark = AccentColors(
    primary = Color(0xFFFFB2BC), onPrimary = Color(0xFF65041F),
    primaryContainer = Color(0xFF8E1636), onPrimaryContainer = Color(0xFFFFD9DE),
    inversePrimary = Color(0xFFB02F4E),
    secondary = Color(0xFFE5BDC2), onSecondary = Color(0xFF43292D),
    secondaryContainer = Color(0xFF5C3F43), onSecondaryContainer = Color(0xFFFFD9DE),
    tertiary = Color(0xFFEABE90), onTertiary = Color(0xFF452A08),
    tertiaryContainer = Color(0xFF5F421D), onTertiaryContainer = Color(0xFFFFDDB6),
)

private fun accentFor(theme: AppColorTheme, dark: Boolean): AccentColors = when (theme) {
    AppColorTheme.GREEN -> if (dark) GreenDark else GreenLight
    AppColorTheme.BLUE -> if (dark) BlueDark else BlueLight
    AppColorTheme.PURPLE -> if (dark) PurpleDark else PurpleLight
    AppColorTheme.TEAL -> if (dark) TealDark else TealLight
    AppColorTheme.ORANGE -> if (dark) OrangeDark else OrangeLight
    AppColorTheme.ROSE -> if (dark) RoseDark else RoseLight
}

/** 設定画面のスウォッチ表示などで使う「その配色の代表色」(ライトモードの primary)。 */
fun appColorThemeSwatch(theme: AppColorTheme): Color = accentFor(theme, dark = false).primary

@Composable
fun FoldexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    colorTheme: AppColorTheme = AppColorTheme.GREEN,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkSchemeWith(accentFor(colorTheme, dark = true))
        else -> lightSchemeWith(accentFor(colorTheme, dark = false))
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

package com.zhixue.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import com.zhixue.assistant.core.ThemeMode

val LocalZhixuePalette = staticCompositionLocalOf { LightPalette }

val LocalIsDark = staticCompositionLocalOf { false }

@Composable
fun rememberZhixuePalette(): ZhixuePalette = LocalZhixuePalette.current

/** Web 端正文 13-14px；手机上整体上调一档，标题层级保持「页面标题/卡片标题/正文/次要」。 */
private fun zhixueTypography() = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall,
        titleLarge = base.titleLarge,
        titleMedium = base.titleMedium,
        titleSmall = base.titleSmall,
        bodyLarge = base.bodyLarge,
        bodyMedium = base.bodyMedium,
        labelLarge = base.labelLarge,
    )
}

@Composable
fun ZhixueTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val palette = if (dark) DarkPalette else LightPalette

    val colorScheme = if (dark) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.accentSubtle,
            onPrimaryContainer = palette.text,
            secondary = palette.success,
            onSecondary = Color(0xFF0D1117),
            error = palette.danger,
            onError = Color(0xFFFFFFFF),
            background = palette.background,
            onBackground = palette.text,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.soft,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            outlineVariant = palette.border,
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.accentSubtle,
            onPrimaryContainer = palette.text,
            secondary = palette.success,
            onSecondary = Color(0xFFFFFFFF),
            error = palette.danger,
            onError = Color(0xFFFFFFFF),
            background = palette.canvas,
            onBackground = palette.text,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.soft,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            outlineVariant = palette.border,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (LocalContext.current as? android.app.Activity)?.window
        SideEffect {
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalZhixuePalette provides palette,
        LocalIsDark provides dark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = zhixueTypography(),
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

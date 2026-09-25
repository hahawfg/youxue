package com.youxue.app.ui.theme

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

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = BlueOnContainer,

    secondary = OrangeAccent,
    onSecondary = Color.White,
    secondaryContainer = OrangeContainer,
    onSecondaryContainer = OrangeOnContainer,

    tertiary = BluePrimaryDark,
    onTertiary = Color.White,

    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = Color(0xFFEDEFF5),

    error = ErrorColor,
    onError = Color.White,
    errorContainer = SuccessContainer,
    onErrorContainer = Color(0xFF7A1010),
)

private val DarkColors = darkColorScheme(
    primary = BluePrimaryLight,
    onPrimary = Color(0xFF08215C),
    primaryContainer = BlueContainerDark,
    onPrimaryContainer = Color(0xFFD6E0FF),

    secondary = OrangeAccentLight,
    onSecondary = Color(0xFF4A2000),
    secondaryContainer = Color(0xFF5C2E00),
    onSecondaryContainer = Color(0xFFFFDDC2),

    tertiary = BluePrimaryLight,
    onTertiary = Color(0xFF08215C),

    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = Color(0xFF2E333F),

    error = Color(0xFFFF8A8A),
    onError = Color(0xFF5C0000),
    errorContainer = Color(0xFF7A1F1F),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * 应用主题。
 *
 * 默认关闭动态取色（Dynamic Color）：学生用这个 App 是为了看清楚、听得清，
 * 而不是为了桌面跟壁纸同色。固定一套经过对比度校验的配色，比跟随壁纸更可靠。
 */
@Composable
fun YouxueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

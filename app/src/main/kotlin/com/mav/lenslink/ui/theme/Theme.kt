/*
 * Theme.kt — Material 3 Expressive theming with dynamic color.
 *
 * material3 1.5.0-beta02 is pinned in gradle/libs.versions.toml to make
 * MaterialShapes / MaterialExpressiveTheme available.
 */
package com.mav.lenslink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FD8CC),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF0E4B44),
    onPrimaryContainer = Color(0xFF97E5DA),
    secondary = Color(0xFFB4CCC6),
    onSecondary = Color(0xFF213530),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1413),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF121A19),
    onSurface = Color(0xFFDDE4E1),
    surfaceContainer = Color(0xFF171F1E),
    surfaceContainerHigh = Color(0xFF1D2524),
    surfaceContainerHighest = Color(0xFF232B2A),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0A6B60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1E5),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635D),
    onSecondary = Color(0xFFFFFFFF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9FAF8),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF9FAF8),
    onSurface = Color(0xFF191C1B),
    surfaceContainer = Color(0xFFEFF0EE),
    surfaceContainerHigh = Color(0xFFE9EAE8),
    surfaceContainerHighest = Color(0xFFE4E4E2),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBEC9C5),
)

/** Rounded containers straight from the expressive `MaterialShapes`. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun heroShape(): Shape = MaterialShapes.Cookie9Sided.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun badgeShape(): Shape = MaterialShapes.Sunny.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun panelShape(): Shape = MaterialShapes.ClamShell.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LensLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = Shapes(),
        content = content,
    )
}

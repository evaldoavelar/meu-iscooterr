package br.dev.evaldo.meuscooter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Cores além do `ColorScheme` do Material 3; valores de `design/lib.py` COLORS. */
@Immutable
data class ExtendedColors(
    val success: Color, val pending: Color, val error: Color,
    val disabled: Color, val onDisabled: Color, val onColor: Color,
    val track: Color, val textSecondary: Color,
)

class AppPalette(val scheme: ColorScheme, val extended: ExtendedColors)

/** Fonte única dos tokens (claro/escuro). */
object AppColors {
    val light = AppPalette(
        lightColorScheme(
            background = Color(0xFFF4F7FB), onBackground = Color(0xFF0E1726),
            surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0E1726),
            surfaceVariant = Color(0xFFE3EAF3), onSurfaceVariant = Color(0xFF52627A),
            outline = Color(0xFFD3DCE8), primary = Color(0xFF00A9C0), onPrimary = Color(0xFF00262B),
            error = Color(0xFFD32F2F),
        ),
        ExtendedColors(
            success = Color(0xFF0E9F63), pending = Color(0xFFE67E00), error = Color(0xFFD32F2F),
            disabled = Color(0xFFC9D3E0), onDisabled = Color(0xFF6B7B92), onColor = Color(0xFF101820),
            track = Color(0xFFE3EAF3), textSecondary = Color(0xFF52627A),
        ),
    )
    val dark = AppPalette(
        darkColorScheme(
            background = Color(0xFF0B1220), onBackground = Color(0xFFF2F6FA),
            surface = Color(0xFF131C2B), onSurface = Color(0xFFF2F6FA),
            surfaceVariant = Color(0xFF1E2A3D), onSurfaceVariant = Color(0xFF9AA8BC),
            outline = Color(0xFF22304A), primary = Color(0xFF00E5FF), onPrimary = Color(0xFF00262B),
            error = Color(0xFFFF5252),
        ),
        ExtendedColors(
            success = Color(0xFF19D68A), pending = Color(0xFFFF9800), error = Color(0xFFFF5252),
            disabled = Color(0xFF2A3547), onDisabled = Color(0xFF7C8BA3), onColor = Color(0xFF101820),
            track = Color(0xFF1E2A3D), textSecondary = Color(0xFF9AA8BC),
        ),
    )
}

private val LocalExtendedColors = staticCompositionLocalOf { AppColors.light.extended }

object AppTheme {
    val extended: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
}

@Composable
fun MeuPatineteTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) AppColors.dark else AppColors.light
    CompositionLocalProvider(LocalExtendedColors provides palette.extended) {
        MaterialTheme(colorScheme = palette.scheme, content = content)
    }
}

/** Tema escuro efetivo para a preferência do usuário. */
fun br.dev.evaldo.meuscooter.domain.ThemeMode.resolveDark(systemDark: Boolean): Boolean = when (this) {
    br.dev.evaldo.meuscooter.domain.ThemeMode.System -> systemDark
    br.dev.evaldo.meuscooter.domain.ThemeMode.Light -> false
    br.dev.evaldo.meuscooter.domain.ThemeMode.Dark -> true
}

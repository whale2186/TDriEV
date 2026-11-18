package dev.nemesis.drive.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.nemesis.drive.model.AccentColor
import dev.nemesis.drive.model.ThemeChoice

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83B5FF), onPrimary = Color(0xFF08284F),
    primaryContainer = Color(0xFF203650), onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFFBCC8D6), onSecondary = Color(0xFF18232F),
    secondaryContainer = Color(0xFF29333E), onSecondaryContainer = Color(0xFFE0E7F0),
    background = Color(0xFF101215), onBackground = Color(0xFFF3F1EC),
    surface = Color(0xFF191D22), onSurface = Color(0xFFF3F1EC),
    surfaceVariant = Color(0xFF262C34), onSurfaceVariant = Color(0xFFB3BBC7),
    outline = Color(0xFF717B89), outlineVariant = Color(0xFF343C47),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF512724), onErrorContainer = Color(0xFFFFDAD6)
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF205DA9), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEAFF), onPrimaryContainer = Color(0xFF12365F),
    secondary = Color(0xFF4D6074), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EAF1), onSecondaryContainer = Color(0xFF253444),
    background = Color(0xFFF5F5F2), onBackground = Color(0xFF191D23),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF191D23),
    surfaceVariant = Color(0xFFE8ECF0), onSurfaceVariant = Color(0xFF52606E),
    outline = Color(0xFF727C88), outlineVariant = Color(0xFFD7DDE4),
    error = Color(0xFFAD302B), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410003)
)

@Composable
internal fun DriveTheme(
    choice: ThemeChoice,
    dynamicColor: Boolean = true,
    amoledMode: Boolean = true,
    accentColor: AccentColor = AccentColor.TEAL,
    customAccentColor: Long = 0xFF00E5FFL,
    content: @Composable () -> Unit
) {
    val dark = when (choice) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (dark && amoledMode) {
                dynamic.copy(
                    background = Color(0xFF000000),
                    surface = Color(0xFF0D0F12),
                    surfaceVariant = Color(0xFF181C22)
                )
            } else {
                dynamic
            }
        }
        dark -> {
            val primaryColor = Color(customAccentColor)
            val bg = if (amoledMode) Color(0xFF000000) else Color(0xFF101215)
            val surf = if (amoledMode) Color(0xFF0E1014) else Color(0xFF191D22)
            val surfVar = if (amoledMode) Color(0xFF1C2028) else Color(0xFF262C34)
            DarkColors.copy(
                primary = primaryColor,
                primaryContainer = primaryColor.copy(alpha = 0.25f),
                onPrimaryContainer = Color.White,
                background = bg,
                surface = surf,
                surfaceVariant = surfVar
            )
        }
        else -> {
            val primaryColor = Color(customAccentColor)
            LightColors.copy(
                primary = primaryColor,
                primaryContainer = primaryColor.copy(alpha = 0.15f),
                onPrimaryContainer = primaryColor
            )
        }
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.let { activity ->
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
        ),
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}

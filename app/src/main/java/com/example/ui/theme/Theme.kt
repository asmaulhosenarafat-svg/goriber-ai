package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

fun accentColorFor(key: String): Color = when (key) {
    "indigo" -> IndigoAccent
    "emerald" -> EmeraldAccent
    "amber" -> AmberAccent
    "rose" -> RoseAccent
    "gold" -> BrightGold
    else -> CyanAccent
}

@Composable
fun AIControllerTheme(
    darkTheme: Boolean = true,
    amoled: Boolean = false,
    goldRoyal: Boolean = false,
    accentKey: String = "cyan",
    content: @Composable () -> Unit,
) {
    val accent = if (goldRoyal) BrightGold else accentColorFor(accentKey)
    val colorScheme = if (goldRoyal) {
        darkColorScheme(
            primary = BrightGold,
            onPrimary = Color(0xFF140F02),
            secondary = RoyalGold,
            onSecondary = Color(0xFF140F02),
            tertiary = EmeraldAccent,
            background = ObsidianGoldBg,
            onBackground = Color(0xFFFAF6E8),
            surface = ObsidianGoldSurface,
            onSurface = Color(0xFFFAF6E8),
            surfaceVariant = ObsidianGoldVariant,
            onSurfaceVariant = Color(0xFFD1C29B),
            outline = ObsidianGoldOutline,
            error = RoseAccent
        )
    } else if (darkTheme) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF041018),
            secondary = IndigoAccent,
            onSecondary = Color.White,
            tertiary = EmeraldAccent,
            background = if (amoled) Color.Black else ObsidianBg,
            onBackground = TextPrimaryDark,
            surface = if (amoled) Color(0xFF0A0D14) else ObsidianSurface,
            onSurface = TextPrimaryDark,
            surfaceVariant = ObsidianSurfaceVariant,
            onSurfaceVariant = TextSecondaryDark,
            outline = ObsidianOutline,
            error = RoseAccent
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = IndigoAccent,
            onSecondary = Color.White,
            tertiary = EmeraldAccent,
            background = LightBg,
            onBackground = TextPrimaryLight,
            surface = LightSurface,
            onSurface = TextPrimaryLight,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = TextSecondaryLight,
            outline = Color(0xFFCBD5E1),
            error = RoseAccent
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

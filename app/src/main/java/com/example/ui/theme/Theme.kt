package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.ai.AssistantPersona

@Composable
fun Aether3DTheme(
    persona: AssistantPersona = AssistantPersona.AETHER,
    content: @Composable () -> Unit
) {
    val accentColor = when (persona) {
        AssistantPersona.AETHER -> ArcCyan
        AssistantPersona.ARIA -> NeuralViolet
    }

    val colorScheme = darkColorScheme(
        primary = accentColor,
        onPrimary = VoidObsidian,
        primaryContainer = DeepCockpit,
        onPrimaryContainer = HudTextPrimary,
        secondary = SolarGold,
        onSecondary = VoidObsidian,
        tertiary = StealthEmerald,
        onTertiary = VoidObsidian,
        background = VoidObsidian,
        onBackground = HudTextPrimary,
        surface = DeepCockpit,
        onSurface = HudTextPrimary,
        surfaceVariant = Color(0xFF131D33),
        onSurfaceVariant = HudTextSecondary,
        outline = accentColor.copy(alpha = 0.35f),
        error = AlertCoral,
        onError = VoidObsidian
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AetherTypography,
        content = content
    )
}

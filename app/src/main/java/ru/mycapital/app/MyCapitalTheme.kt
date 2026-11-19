package ru.mycapital.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun MyCapitalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF2588FF),
            secondary = Color(0xFF55C99A),
            background = Color(0xFF080F1B),
            surface = Color(0xFF101C30),
            surfaceVariant = Color(0xFF14243B),
            secondaryContainer = Color(0xFF1B3555),
            onSecondaryContainer = Color(0xFFB8D8FF),
            tertiary = Color(0xFF4B9BFF),
            tertiaryContainer = Color(0xFF1B3555),
            onTertiaryContainer = Color(0xFFB8D8FF),
            onBackground = Color(0xFFF4F5F8),
            onSurface = Color(0xFFF4F5F8),
            onSurfaceVariant = Color(0xFFB3B7C4)
        ),
        content = content
    )
}

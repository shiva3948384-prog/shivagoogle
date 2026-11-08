package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ============================================================================
// Smarter AI 1.0 — Gemini-Inspired Original Visual Identity Palette
// ============================================================================

// Dark Theme Palette (Obsidian Cosmos & Luminous Sapphire-Violet Accents)
val DarkBackground = Color(0xFF090C12)
val DarkSurface = Color(0xFF101520)
val DarkSurfaceElevated = Color(0xFF171E2E)
val DarkSurfaceHigh = Color(0xFF1F293D)
val DarkComposerSurface = Color(0xFF141A29)
val DarkPrimary = Color(0xFF6889FF)
val DarkPrimaryContainer = Color(0xFF1D2B53)
val DarkSecondary = Color(0xFF9F7AEA)
val DarkTertiary = Color(0xFF38BDF8)
val DarkTextPrimary = Color(0xFFF3F6FC)
val DarkTextSecondary = Color(0xFF9AA5B8)
val DarkOutline = Color(0xFF232D42)
val DarkUserBubble = Color(0xFF1C2742)
val DarkUserBubbleText = Color(0xFFF8FAFC)

// Light Theme Palette (Daylight Porcelain & Royal Sapphire-Indigo Accents)
val LightBackground = Color(0xFFF7F9FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFEEF3FA)
val LightSurfaceHigh = Color(0xFFE3EAF4)
val LightComposerSurface = Color(0xFFFFFFFF)
val LightPrimary = Color(0xFF2563EB)
val LightPrimaryContainer = Color(0xFFDBEAFE)
val LightSecondary = Color(0xFF6D28D9)
val LightTertiary = Color(0xFF0284C7)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)
val LightOutline = Color(0xFFDCE4F0)
val LightUserBubble = Color(0xFFE8F0FE)
val LightUserBubbleText = Color(0xFF0F172A)

// Smarter AI 1.0 Signature Aurora Gradients (Original Identity)
val SmarterGradientStartDark = Color(0xFF60A5FA)
val SmarterGradientMidDark = Color(0xFF818CF8)
val SmarterGradientEndDark = Color(0xFFC084FC)

val SmarterGradientStartLight = Color(0xFF1D4ED8)
val SmarterGradientMidLight = Color(0xFF4F46E5)
val SmarterGradientEndLight = Color(0xFF9333EA)

fun smarterAiSignatureGradient(isDark: Boolean): Brush {
    return if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                SmarterGradientStartDark,
                SmarterGradientMidDark,
                SmarterGradientEndDark
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                SmarterGradientStartLight,
                SmarterGradientMidLight,
                SmarterGradientEndLight
            )
        )
    }
}

fun smarterAiAmbientHeaderBrush(isDark: Boolean): Brush {
    return if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF162038).copy(alpha = 0.55f),
                DarkBackground.copy(alpha = 0.0f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFDBEAFE).copy(alpha = 0.50f),
                LightBackground.copy(alpha = 0.0f)
            )
        )
    }
}

// Semantic Status & Capability Badge Colors
val StatusAvailableGreen = Color(0xFF10B981)
val StatusDegradedAmber = Color(0xFFF59E0B)
val StatusWarningAmber = Color(0xFFF59E0B)
val StatusErrorRed = Color(0xFFEF4444)
val CodeBlockBackgroundDark = Color(0xFF06090F)
val CodeBlockBackgroundLight = Color(0xFFF1F5F9)

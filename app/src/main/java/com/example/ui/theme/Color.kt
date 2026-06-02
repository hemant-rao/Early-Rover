package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Base static definitions for Dark Mode
val SleekDarkBackground = Color(0xFF0D0F14)
val SleekDarkCardBg = Color(0xFF1C1F26)
val SleekDarkBorder = Color(0xFF2D313D)
val SleekDarkPrimary = Color(0xFF3287FE)
val SleekDarkSecondary = Color(0xFF1EC9E8)
val SleekDarkMutedText = Color(0xFF9BA1B0)
val SleekDarkActiveText = Color(0xFFE2E2E6)

// Base static definitions for Light Mode
val SleekLightBackground = Color(0xFFF8FAFC)
val SleekLightCardBg = Color(0xFFFFFFFF)
val SleekLightBorder = Color(0xFFE2E8F0)
val SleekLightPrimary = Color(0xFF3287FE)
val SleekLightSecondary = Color(0xFF0EA5E9)
val SleekLightMutedText = Color(0xFF64748B)
val SleekLightActiveText = Color(0xFF0F172A)

// Custom dynamic components using @Composable package properties
val SleekBackground: Color
    @Composable
    get() = MaterialTheme.colorScheme.background

val SleekCardBg: Color
    @Composable
    get() = MaterialTheme.colorScheme.surface

val SleekBorder: Color
    @Composable
    get() = MaterialTheme.colorScheme.outline

val SleekPrimary: Color
    @Composable
    get() = MaterialTheme.colorScheme.primary

val SleekSecondary: Color
    @Composable
    get() = MaterialTheme.colorScheme.secondary

val SleekMutedText: Color
    @Composable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

val SleekActiveText: Color
    @Composable
    get() = MaterialTheme.colorScheme.onSurface

val SleekSolarAccent = Color(0xFFFFB347)

// Fallback legacy colors
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

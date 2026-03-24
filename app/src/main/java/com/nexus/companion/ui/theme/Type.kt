package com.nexus.companion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val NexusTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Light,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
        color = NexusTextPrimary
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Light,
        fontSize = 22.sp,
        color = NexusTextPrimary
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = NexusTextPrimary
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = NexusTextSecondary
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = NexusTextDim
    )
)

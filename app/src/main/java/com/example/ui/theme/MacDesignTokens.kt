package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// macOS Sequoia-inspired semantic colors (dark mode)
val MacWindowBg = Color(0xFF000000)
val MacContentBg = Color(0xFF1C1C1E)
val MacSecondaryBg = Color(0xFF2C2C2E)
val MacTertiaryBg = Color(0xFF3A3A3C)
val MacSeparator = Color(0xA6545458)
val MacLabelPrimary = Color(0xFFFFFFFF)
val MacLabelSecondary = Color(0x99EBEBF5)
val MacSystemBlue = Color(0xFF007AFF)
val MacSystemGreen = Color(0xFF30D158)
val MacSystemRed = Color(0xFFFF453A)
val MacSystemOrange = Color(0xFFFF9F0A)
val MacGlassFill = Color(0xB82C2C2E)
val MacGlassBorder = Color(0x14FFFFFF)
val MacOnBlue = Color(0xFFFFFFFF)
val MacBlueContainer = Color(0xFF0A3D7A)
val MacRedContainer = Color(0x33FF453A)
val MacGreenContainer = Color(0x3330D158)

val MacRadiusSmall = 8.dp
val MacRadiusMedium = 12.dp
val MacRadiusLarge = 16.dp
val MacRadiusButton = 10.dp

val MacSpace1 = 8.dp
val MacSpace2 = 16.dp
val MacSpace3 = 24.dp
val MacSpace4 = 32.dp

val MacShapeSmall = RoundedCornerShape(MacRadiusSmall)
val MacShapeMedium = RoundedCornerShape(MacRadiusMedium)
val MacShapeLarge = RoundedCornerShape(MacRadiusLarge)
val MacShapeButton = RoundedCornerShape(MacRadiusButton)

// Deprecated aliases for migration
val ImmersiveBg = MacContentBg
val ImmersiveSurface = MacSecondaryBg
val ImmersiveSurfaceVariant = MacTertiaryBg
val ImmersiveText = MacLabelPrimary
val ImmersiveMuted = MacLabelSecondary
val ImmersiveOutline = MacSeparator
val ImmersiveAccent = MacSystemBlue
val ImmersiveOnAccent = MacOnBlue
val ImmersiveHighlightContainer = MacBlueContainer
val ImmersiveOnHighlightContainer = MacLabelPrimary
val ImmersiveNavBarBg = MacSecondaryBg

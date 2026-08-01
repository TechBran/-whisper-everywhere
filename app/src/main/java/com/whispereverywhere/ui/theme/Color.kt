package com.whispereverywhere.ui.theme

import androidx.compose.ui.graphics.Color

// Brand Colors - Red to Blue Gradient (matching logo)
val GradientRed = Color(0xFFEF4444)      // Left side of gradient
val GradientPink = Color(0xFFEC4899)     // Middle pink
val GradientPurple = Color(0xFF8B5CF6)   // Middle purple
val GradientBlue = Color(0xFF3B82F6)     // Right side of gradient

// Primary Colors - the RED end of the gradient (owner restyle 2026-08-01: blue -> red)
val Primary = Color(0xFFEF4444)
val PrimaryVariant = Color(0xFFDC2626)
val PrimaryLight = Color(0xFFF87171)
val OnPrimary = Color.White

// Secondary Colors - deeper reds (primary took the bright red)
val Secondary = Color(0xFFB91C1C)
val SecondaryVariant = Color(0xFF991B1B)
val OnSecondary = Color.White

// Accent - Purple from gradient
val Accent = Color(0xFF8B5CF6)

// Background Colors - pure black (owner restyle 2026-08-01: "black dark background" everywhere;
// buttons/cards share it and stay visible via outline + chevron, not via a lighter surface)
val Background = Color(0xFF000000)
val Surface = Color(0xFF000000)
val OnBackground = Color(0xFFF8FAFC)
val OnSurface = Color(0xFFF8FAFC)
val SurfaceVariant = Color(0xFF1A1A1A)

// Light Theme alternatives
val BackgroundLight = Color(0xFFF8FAFC)
val SurfaceLight = Color.White
val OnBackgroundLight = Color(0xFF1E293B)
val OnSurfaceLight = Color(0xFF1E293B)
val SurfaceVariantLight = Color(0xFFF1F5F9)

// Status Colors
val Error = Color(0xFFEF4444)
val OnError = Color.White
val Success = Color(0xFF22C55E)
val Warning = Color(0xFFF59E0B)

// Recording States. Idle is a dark ember red, NOT Primary: with primary now red, idle == active
// would erase the state signal the two colors exist to carry.
val RecordingIdle = Color(0xFF7F1D1D)
val RecordingActive = GradientRed
val RecordingProcessing = GradientPurple  // Purple when processing

// Bubble Colors (in-app preview accents; the overlay bubble owns its own colors in the service)
val BubbleIdle = RecordingIdle
val BubbleRecording = GradientRed
val BubbleProcessing = GradientPurple

// Text Colors (neutral grays — the old values carried a blue-slate tint)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF9CA3AF)
val TextTertiary = Color(0xFF6B7280)

// Card Colors: black like everything else; the BORDER is what makes a card read as a button
// on the all-black ground (plus the chevron the nav cards already carry).
val CardBackground = Color(0xFF000000)
val CardBorder = Color(0xFF2E2E2E)

// Gradient for waveform — red into pink; the blue end of the logo gradient is retired app-wide.
val WaveformGradientStart = GradientRed
val WaveformGradientEnd = GradientPink

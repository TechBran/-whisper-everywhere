package com.whispereverywhere.ui.theme

import androidx.compose.ui.graphics.Color

// Brand Colors - Red to Blue Gradient (matching logo)
val GradientRed = Color(0xFFEF4444)      // Left side of gradient
val GradientPink = Color(0xFFEC4899)     // Middle pink
val GradientPurple = Color(0xFF8B5CF6)   // Middle purple
val GradientBlue = Color(0xFF3B82F6)     // Right side of gradient

// Primary Colors - Using the blue from the gradient
val Primary = Color(0xFF3B82F6)
val PrimaryVariant = Color(0xFF2563EB)
val PrimaryLight = Color(0xFF60A5FA)
val OnPrimary = Color.White

// Secondary Colors - Using the red/coral from gradient
val Secondary = Color(0xFFEF4444)
val SecondaryVariant = Color(0xFFDC2626)
val OnSecondary = Color.White

// Accent - Purple from gradient
val Accent = Color(0xFF8B5CF6)

// Background Colors - Dark theme matching logo background
val Background = Color(0xFF0F172A)        // Dark navy from logo
val Surface = Color(0xFF1E293B)           // Slightly lighter
val OnBackground = Color(0xFFF8FAFC)
val OnSurface = Color(0xFFF8FAFC)
val SurfaceVariant = Color(0xFF334155)

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

// Recording States - Using brand gradient
val RecordingIdle = Primary               // Blue when idle
val RecordingActive = GradientRed         // Red when recording
val RecordingProcessing = GradientPurple  // Purple when processing

// Bubble Colors
val BubbleIdle = Primary
val BubbleRecording = GradientRed
val BubbleProcessing = GradientPurple

// Text Colors
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextTertiary = Color(0xFF64748B)

// Card Colors
val CardBackground = Color(0xFF1E293B)
val CardBorder = Color(0xFF334155)

// Gradient for waveform (matches logo exactly)
val WaveformGradientStart = GradientRed
val WaveformGradientEnd = GradientBlue

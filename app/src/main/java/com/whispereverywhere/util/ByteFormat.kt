package com.whispereverywhere.util

import java.util.Locale

/**
 * Human-readable, base-1000 (SI) size string, e.g. 57_000_000 -> "57 MB",
 * 190_000_000 -> "190 MB", 574_000_000 -> "574 MB", 1_500_000_000 -> "1.5 GB".
 * Bytes/KB render as whole numbers; MB/GB/TB keep one decimal only when it is
 * non-zero. Negative inputs are clamped to 0 B. Pure — safe for JVM unit tests.
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1000.0 && unitIndex < units.size - 1) {
        value /= 1000.0
        unitIndex++
    }
    // B and KB: no decimals. MB and up: one decimal, trimmed when it's .0
    val text = if (unitIndex <= 1) {
        String.format(Locale.US, "%.0f", value)
    } else {
        val oneDp = String.format(Locale.US, "%.1f", value)
        if (oneDp.endsWith(".0")) oneDp.dropLast(2) else oneDp
    }
    return "$text ${units[unitIndex]}"
}

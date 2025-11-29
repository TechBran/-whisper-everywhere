package com.whispereverywhere.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks usage statistics for the app.
 * Note: This app uses a BYOK (Bring Your Own Key) model, so there are no usage limits.
 * Users are billed directly by OpenAI for their API usage.
 */
class UsageTracker(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "whisper_usage_prefs",
        Context.MODE_PRIVATE
    )

    // Track today's usage for display purposes only
    private val _usedSecondsToday = MutableStateFlow(0)
    val usedSecondsToday: StateFlow<Int> = _usedSecondsToday.asStateFlow()

    init {
        try {
            checkAndResetIfNewDay()
            _usedSecondsToday.value = prefs.getInt(KEY_USED_SECONDS, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getTodayDateString(): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        } catch (e: Exception) {
            "unknown_date"
        }
    }

    private fun checkAndResetIfNewDay() {
        val lastUsageDate = prefs.getString(KEY_LAST_USAGE_DATE, null)
        val today = getTodayDateString()

        if (lastUsageDate != today) {
            // New day, reset daily usage counter
            prefs.edit()
                .putInt(KEY_USED_SECONDS, 0)
                .putString(KEY_LAST_USAGE_DATE, today)
                .apply()
            _usedSecondsToday.value = 0
        }
    }

    /**
     * Add usage time in seconds
     */
    fun addUsage(seconds: Int) {
        checkAndResetIfNewDay()
        val newTotal = _usedSecondsToday.value + seconds
        prefs.edit().putInt(KEY_USED_SECONDS, newTotal).apply()
        _usedSecondsToday.value = newTotal
    }

    /**
     * Check if user can record - always returns true since there are no limits.
     * Users pay OpenAI directly for their API usage.
     */
    fun canRecord(): Boolean = true

    /**
     * Get used time today
     */
    fun getUsedSeconds(): Int {
        checkAndResetIfNewDay()
        return _usedSecondsToday.value
    }

    /**
     * Get total usage all time (for stats)
     */
    fun getTotalUsageAllTime(): Long {
        return prefs.getLong(KEY_TOTAL_USAGE_ALL_TIME, 0L)
    }

    /**
     * Add to total usage counter
     */
    fun addToTotalUsage(seconds: Int) {
        val current = getTotalUsageAllTime()
        prefs.edit().putLong(KEY_TOTAL_USAGE_ALL_TIME, current + seconds).apply()
    }

    /**
     * Get total transcriptions count
     */
    fun getTotalTranscriptionCount(): Int {
        return prefs.getInt(KEY_TOTAL_TRANSCRIPTION_COUNT, 0)
    }

    /**
     * Increment transcription count
     */
    fun incrementTranscriptionCount() {
        val current = getTotalTranscriptionCount()
        prefs.edit().putInt(KEY_TOTAL_TRANSCRIPTION_COUNT, current + 1).apply()
    }

    /**
     * Format seconds as MM:SS string
     */
    fun formatTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", mins, secs)
    }

    /**
     * Format seconds as "X min Y sec" string
     */
    fun formatTimeVerbose(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return when {
            mins == 0 -> "$secs sec"
            secs == 0 -> "$mins min"
            else -> "$mins min $secs sec"
        }
    }

    companion object {
        private const val KEY_USED_SECONDS = "used_seconds_today"
        private const val KEY_LAST_USAGE_DATE = "last_usage_date"
        private const val KEY_TOTAL_USAGE_ALL_TIME = "total_usage_all_time"
        private const val KEY_TOTAL_TRANSCRIPTION_COUNT = "total_transcription_count"
    }
}
